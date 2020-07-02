(ns jt.core
  (:gen-class)
  (:require [chime.core :as chime-core]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :refer [stringify-keys keywordize-keys]]
            [compojure.core :refer [defroutes POST]]
            [io.aviso.logging.setup]
            [mailgun.mail :as mail]
            [mailgun.util :refer [to-file]]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :refer [response]])
  (:import (com.google.auth.oauth2 ServiceAccountCredentials)
           (java.io PushbackReader)
           (java.time LocalTime ZonedDateTime ZoneId Period Instant)
           (java.time.format DateTimeFormatter)
           (com.google.firebase FirebaseApp FirebaseOptions$Builder)
           (com.google.firebase.database FirebaseDatabase ValueEventListener)))


;; ->clj
;; converts nested java objects from firebase into
;; clojure persistant colls
;; from https://groups.google.com/forum/#!topic/clojure/1NzLnWUtj0Q

(defprotocol ConvertibleToClojure
  (->clj [o]))

(extend-protocol ConvertibleToClojure
  java.util.Map
  (->clj [o] (let [entries (.entrySet o)]
               (reduce (fn [m [^String k v]]
                         (assoc m (keyword k) (->clj v)))
                       {} entries)))

  java.util.List
  (->clj [o] (vec (map ->clj o)))

  java.lang.Object
  (->clj [o] o)

  nil
  (->clj [_] nil))

;; Misc Helpers

(defn read-edn-resource [path]
  (-> path
      io/resource
      io/reader
      PushbackReader.
      edn/read))

(defn fmt-with-pattern [pattern zoned-date]
  (-> (DateTimeFormatter/ofPattern pattern)
      (.format zoned-date)))

;; i.e Wed Jul 1
(def friendly-date-pattern "E LLL d")
;; i.e Wednesday
(def day-of-week-pattern "EEEE")
;; i.e 2020-07-01
(def numeric-date-pattern "yyyy-MM-dd")

;; Secrets

(def secrets (read-edn-resource "secrets.edn"))

;; Config

(def config
  {:port 8080
   :mailgun {:domain "mg.journaltogether.com"}
   :firebase {:db-url "https://journaltogether.firebaseio.com"}})

;; Mail

(def mailgun-creds {:key (-> secrets :mailgun :api-key)
                    :domain (-> config :mailgun :domain)})

(defn send-mail [content]
  (mail/send-mail
    mailgun-creds
    content))

;; DB

(defn firebase-init []
  (let [{:keys [db-url]} (:firebase config)
        {:keys
         [client-id
          client-email
          private-key
          private-key-id]} (:firebase secrets)
        creds (ServiceAccountCredentials/fromPkcs8
                client-id
                client-email
                private-key
                private-key-id
                [])
        options (-> (FirebaseOptions$Builder.)
                    (.setCredentials creds)
                    (.setDatabaseUrl db-url)
                    .build)]
    (FirebaseApp/initializeApp options)))

(defn firebase-save [path v]
  (-> (FirebaseDatabase/getInstance)
      (.getReference path)
      (.setValueAsync (stringify-keys v))))

(defn firebase-fetch [path]
  (let [p (promise)]
    (-> (FirebaseDatabase/getInstance)
        (.getReference path)
        (.addListenerForSingleValueEvent
          (reify ValueEventListener
            (onDataChange [_ s]
              (deliver p (->> s
                              .getValue
                              ->clj)))
            (onCancelled [_ err]
              (throw err)))))
    p))

(comment
  @(firebase-fetch "/journals/stepan-p_gmail-com"))

(defn email->id [email]
  (-> email
      (str/replace #"\." "-")
      (str/replace #"@" "_")))

(defn journal-path [email zoned-date]
  (str "/journals/"
       (email->id email)
       "/"
       (fmt-with-pattern numeric-date-pattern zoned-date)))

;; Schedule

(defn pst-now []
  (ZonedDateTime/now
    (ZoneId/of "America/Los_Angeles")))

(defn pst-instant [h m s]
  (-> (LocalTime/of h m s)
      (.adjustInto (pst-now))
      .toInstant))

(defn daily-period [inst]
  (chime-core/periodic-seq inst (Period/ofDays 1)))

(defn after-now [period]
  (let [now (Instant/now)]
    (filter #(.isAfter % now) period)))

(defn reminder-period []
  (-> (pst-instant 16 0 0)
      daily-period
      after-now))

(defn summary-period []
  (-> (pst-instant 9 0 0)
      daily-period
      after-now))

;; Email Content

(def friends (:friends secrets))

(def hows-your-day-email "journal-buddy@mg.journaltogether.com")

(defn content-hows-your-day? [day]
  {:from hows-your-day-email
   :to friends
   :subject (str
              (fmt-with-pattern friendly-date-pattern day)
              " — 👋 How was your day?")
   :html
   (str "<p>"
        "Howdy, Happy " (fmt-with-pattern day-of-week-pattern day)
        "</p>"
        "<p>"
        "How was your day today? What's been on your mind? 😊 📝"
        "</p>")})

(def summary-email "Journal Summary <journal-summary@mg.journaltogether.com>")

(defn journal-entry->html [{:keys [sender stripped-html]}]
  (str
    "<p><b>"
    sender
    "</b></p>"
    "<br>"
    stripped-html
    "<p></p>"))

(defn content-summary [day entries]
  (let [friendly-date-str (fmt-with-pattern friendly-date-pattern day)]
    {:from summary-email
     :to friends
     :subject (str "☀️ Here's how things went " friendly-date-str)
     :html
     (str "<p>"
          "Howdy, Here's how things have went on " friendly-date-str
          "</p>"
          (->> entries
               (map journal-entry->html)
               str/join))}))

(defn content-ack-receive [email, subject]
  {:from hows-your-day-email
   :to [email]
   :subject subject
   :html "Oky doke, received this 👌"})

(defn content-already-received [email, subject]
  {:from hows-your-day-email
   :to [email]
   :subject subject
   :html (str
           "<p>Oi, I already logged a journal entry for you.</p>"
           "<p>I can't do much with this. Ping Stopa sry 🙈</p>")})

;; Schedule Handlers

(defn handle-reminder []
  (send-mail (content-hows-your-day? (pst-now))))

(defn handle-summary []
  (let [day (.minusDays (pst-now) 10)
        entries (->> friends
                     (pmap (fn [email]
                             @(firebase-fetch
                                (journal-path email day))))
                     (filter seq))]

    (if-not (seq entries)
      (log/infof "skipping for %s because there are not entries" day)
      (send-mail (content-summary day entries)))))

;; HTTP Server

(def mailgun-date-formatter
  (DateTimeFormatter/ofPattern "EEE, d LLL yyyy HH:mm:ss ZZ"))

(defn parse-email-params [params]
  (let [date (-> params
                 :Date
                 (ZonedDateTime/parse mailgun-date-formatter))]
    (-> params
        (select-keys
          #{:sender :subject :stripped-text :stripped-html :recipient})
        (assoc :date date))))

(defn emails-handler [{:keys [params]}]
  (future
    (let [{:keys
           [sender recipient date subject] :as data} (parse-email-params params)
          journal-path (journal-path sender date)]
      (cond
        (not= recipient hows-your-day-email)
        (log/infof "skipping for recipient %s data %s" recipient data)

        (seq @(firebase-fetch journal-path))
        (do
          (log/infof "already have a journal recorded for %s on " sender date)
          (send-mail (content-already-received sender subject)))

        :else
        (do
          (firebase-save
            journal-path
            (select-keys
              data
              #{:sender :subject :stripped-text :stripped-html}))
          (send-mail (content-ack-receive sender subject))))))
  (response {:receive true}))

(comment
  (do
    (def _req (read-edn-resource "api-emails-req.edn"))
    (def _res (emails-handler _req))
    _res))

(defroutes
  routes
  (POST "/api/emails" [] emails-handler))

(defn -main []
  (firebase-init)
  (future (chime-core/chime-at
            (reminder-period)
            handle-reminder))
  (future (chime-core/chime-at
            (summary-period)
            handle-summary))
  (let [{:keys [port]} config
        app (-> routes
                wrap-keyword-params
                ring.middleware.params/wrap-params
                (wrap-json-body {:keywords? true})
                wrap-json-response)]
    (jetty/run-jetty app {:port port :join false}))
  (log/info "kicked off!"))

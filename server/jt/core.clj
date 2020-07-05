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
           (java.time LocalTime ZonedDateTime ZoneId Period Instant)
           (java.time.format DateTimeFormatter)
           (com.google.firebase FirebaseApp FirebaseOptions$Builder)
           (com.google.firebase.database FirebaseDatabase ValueEventListener)))

;; ------------------------------------------------------------------------------
;; Macros

(defmacro fut-bg
  "Futures only throw when de-referenced. fut-bg writes a future
  with a top-level try-catch, so you can run code asynchronously,
  without _ever_ de-referencing them"
  [& forms]
  `(future
     (try
       ~@forms
       (catch Exception e#
         (log/errorf "uh-oh, failed to run async function %s %s" '~form e#)
         (throw e#)))))

;; ------------------------------------------------------------------------------
;; Protocols

(defprotocol ConvertibleToClojure
  "Converts nested java objects to clojure objects.
  This is useful when we fetch data from firebase.
  Instead of working on mutable objects, we transform them
  to keywordized immutable clojure ones"
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

;; ------------------------------------------------------------------------------
;; Misc Helpers

(defn read-edn-resource
  "Transforms a resource in our classpath to edn"
  [path]
  (-> path
      io/resource
      slurp
      edn/read-string))


;; ------------------------------------------------------------------------------
;; Date Helpers

(def friendly-date-pattern
  "i.e Wed Jul 1"
  "E LLL d")

(def day-of-week-pattern
  "i.e Wednesday"
  "EEEE")

(def numeric-date-pattern
  "i.e 2020-07-01"
  "yyyy-MM-dd")

(defn fmt-with-pattern
  [str-pattern zoned-date]
  (-> (DateTimeFormatter/ofPattern str-pattern)
      (.format zoned-date)))


;; ------------------------------------------------------------------------------
;; Config

(def secrets (read-edn-resource "secrets.edn"))

(def config
  {:port 8080
   :mailgun {:domain "mg.journaltogether.com"}
   :firebase {:db-url "https://journaltogether.firebaseio.com"}})


(def friends
  (:friends secrets))

;; ------------------------------------------------------------------------------
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

(defn ->numeric-date-str [zoned-date]
  (fmt-with-pattern numeric-date-pattern zoned-date))

(defn journal-path [email zoned-date]
  (str "/journals/"
       (email->id email)
       "/"
       (->numeric-date-str zoned-date)))

;; ------------------------------------------------------------------------------
;; Mail

(def mailgun-creds {:key (-> secrets :mailgun :api-key)
                    :domain (-> config :mailgun :domain)})

;; TODO: consider adding spec here
(defn send-mail [content]
  (mail/send-mail
    mailgun-creds
    content))

;; ------------------------------------------------------------------------------
;; Schedule

(def pst-zone (ZoneId/of "America/Los_Angeles"))
(defn pst-now []
  (ZonedDateTime/now pst-zone))

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

;; ------------------------------------------------------------------------------
;; Content

(defn email-with-name [email name]
  (str name " <" email ">"))


(def hows-your-day-email "journal-buddy@mg.journaltogether.com")
(def hows-your-day-email-with-name
  (email-with-name hows-your-day-email "Journal Buddy"))

(def summary-email "journal-summary@mg.journaltogether.com")
(def summary-email-with-name
  (email-with-name summary-email "Journal Summary"))

(defn content-hows-your-day? [day]
  {:from hows-your-day-email-with-name
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

(defn poor-mans-parse [body-html]
  (->> (str/split body-html #"<br>")
       (take-while #(not (str/includes? % "gmail_quote")))
       (str/join "<br>")))

(defn journal-entry->html [{:keys [sender body-html]}]
  (str
    "<p><b>"
    sender
    "</b></p>"
    "<br>"
    "<div style=\"white-space:pre\">"
    (poor-mans-parse body-html)
    "</div>"))

(defn content-summary [day entries]
  (let [friendly-date-str (fmt-with-pattern friendly-date-pattern day)]
    {:from summary-email-with-name
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
  {:from hows-your-day-email-with-name
   :to [email]
   :subject subject
   :html "Oky doke, received this 👌"})

(defn content-already-received [email, subject]
  {:from hows-your-day-email-with-name
   :to [email]
   :subject subject
   :html (str
           "<p>Oi, I already logged a journal entry for you.</p>"
           "<p>I can't do much with this. Ping Stopa sry 🙈</p>")})

;; ------------------------------------------------------------------------------
;; Outgoing Mail

(defn handle-reminder [_]
  (send-mail (content-hows-your-day? (pst-now))))

(defn handle-summary [_]
  (let [day (.minusDays (pst-now) 1)
        entries (->> friends
                     (pmap (fn [email]
                             @(firebase-fetch
                                (journal-path email day))))
                     (filter seq))]


    (if-not (seq entries)
      (log/infof "skipping for %s because there are no entries" day)
      (send-mail (content-summary day entries)))))

;; ------------------------------------------------------------------------------
;; Incoming Mail

(def mailgun-date-formatter
  (-> "EEE, d LLL yyyy HH:mm:ss ZZ"
      DateTimeFormatter/ofPattern
      (.withZone pst-zone)))

(defn parse-email-date [params]
  (-> params
      :Date
      (ZonedDateTime/parse mailgun-date-formatter)))

(def email-keys
  #{:sender :subject :stripped-text :stripped-html
    :recipient :body-html :body-plain})

(defn emails-handler [{:keys [params] :as req}]
  (fut-bg
    (let [{:keys [sender recipient subject date] :as data}
          (-> params
              (assoc :date (parse-email-date params)))
          journal-path (journal-path sender date)]
      (log/infof "[api/emails] received data=%s" data)
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
            (-> data
                (update :date ->numeric-date-str)
                (select-keys email-keys)))
          (send-mail (content-ack-receive sender subject))))))
  (response {:receive true}))

(comment
  (do
    (def _req (read-edn-resource "api-emails-req.edn"))
    (def _res (emails-handler _req))
    _res))

;; ------------------------------------------------------------------------------
;; Server

(defroutes
  routes
  (POST "/api/emails" [] emails-handler))

(defn -main []
  (firebase-init)
  (fut-bg (chime-core/chime-at
               (reminder-period)
               handle-reminder))
  (fut-bg (chime-core/chime-at
               (summary-period)
               handle-summary))
  (let [{:keys [port]} config
        app (-> routes
                wrap-keyword-params
                wrap-params
                (wrap-json-body {:keywords? true})
                wrap-json-response)]
    (jetty/run-jetty app {:port port :join false}))
  (log/info "kicked off!"))

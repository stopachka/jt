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

;; Misc Helpers

(defn read-edn-resource [path]
  (-> path
      io/resource
      io/reader
      PushbackReader.
      edn/read))

;; i.e Wed Jul 1
(defn pretty-date [zoned-date]
  (-> (DateTimeFormatter/ofPattern "E LLL d")
      (.format zoned-date)))

;; i.e Wednesday
(defn pretty-day-of-week [zoned-date]
  (-> (DateTimeFormatter/ofPattern "EEEE")
      (.format zoned-date)))

;; i.e 2020-07-01
(defn year-month-day [zoned-date]
  (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd")
      (.format zoned-date)))

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
                              (into {})
                              keywordize-keys)))
            (onCancelled [_ err]
              (throw err)))))
    p))

(defn email->id [email]
  (-> email
      (str/replace #"\." "-")
      (str/replace #"@" "_")))

(defn journal-path [email zoned-date]
  (str "/journals/" (email->id email) "/" (year-month-day zoned-date)))

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

;; Reminders & Summaries

(defn content-hows-your-day? [day]
  {:from "Journal Buddy <journal-buddy@mg.journaltogether.com>"
   :to ["stepan.p@gmail.com"
        "markshlick@gmail.com"
        "joeaverbukh@gmail.com"
        "reichertjalex@gmail.com"]
   :subject (str (pretty-date day) " — 👋 How was your day?")
   :html
   (str "<p>"
        "Howdy, Happy " (pretty-day-of-week day)
        "</p>"
        "<p>"
        "How was your day today? What's been on your mind? 😊 📝"
        "</p>")})

(defn content-summary [day]
  {:from "Journal Buddy <journal-buddy@mg.journaltogether.com>"
   :to ["stepan.p@gmail.com"
        "markshlick@gmail.com"
        "joeaverbukh@gmail.com"
        "reichertjalex@gmail.com"]
   :subject (str "☀️ Here's how things went " (pretty-date day))
   :html
   (str "<p>"
        "Howdy, Here's how things have went:"
        "</p>"
        "<p>"
        "Coming soon : }"
        "</p>")})

(defn content-ack-receive [email, subject]
  {:from "Journal Buddy <journal-buddy@mg.journaltogether.com>"
   :to [email]
   :subject subject
   :html "Oky doke, received this 👌"})

(defn send-reminders [_]
  (send-mail (content-hows-your-day? (pst-now))))

(defn send-summaries [_]
  (send-mail (content-summary (.minusDays (pst-now) 1))))

(defn send-ack [email subject]
  (send-mail (content-ack-receive email subject)))

;; HTTP Server

(defn mailgun-date-formatter []
  (DateTimeFormatter/ofPattern "EEE, d LLL yyyy HH:mm:ss ZZ"))

(defn emails-handler [req]
  (let [{:keys [params]} req
        {:keys [sender
                subject
                stripped-text
                stripped-html]} params
        date (-> params
                 :Date
                 (ZonedDateTime/parse (mailgun-date-formatter)))]
    (firebase-save
      (journal-path sender date)
      {:email sender
       :subject subject
       :stripped-text stripped-text
       :stripped-html stripped-html})
    (send-ack sender subject)
    (response {:ok "true"})))

(comment
  (do
    (def _req (read-edn-resource "api-emails-req.edn"))
    (def _res (emails-handler _req))
    _res))

(defroutes routes
           ;; ---
           ;; api
           (POST "/api/emails" [] emails-handler))

(defn -main []
  (firebase-init)
  (future (chime-core/chime-at
            (reminder-period)
            send-reminders))
  (future (chime-core/chime-at
            (summary-period)
            send-summaries))
  (future
    (let [{:keys [port]} config
          app (-> routes
                  wrap-keyword-params
                  ring.middleware.params/wrap-params
                  (wrap-json-body {:keywords? true})
                  wrap-json-response)]
      (jetty/run-jetty app {:port port})))
  (log/info "kicked off!"))
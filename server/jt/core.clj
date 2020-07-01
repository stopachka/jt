(ns jt.core
  (:gen-class)
  (:require [io.aviso.logging.setup]
            [clojure.tools.logging :as log]
            [mailgun.mail :as mail]
            [mailgun.util :refer [to-file]]
            [chime.core :as chime-core]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import (com.google.firebase FirebaseApp FirebaseOptions$Builder)
           (com.google.auth.oauth2 ServiceAccountCredentials)
           (java.time LocalTime ZonedDateTime ZoneId Period Instant)
           (java.io PushbackReader)))

;; Secrets

(def secrets (-> "secrets.edn"
                 io/resource
                 io/reader
                 PushbackReader.
                 edn/read))
;; Config

(def config
  {:root-domain "journaltogether.com"
   :mailgun {:domain "mg.journaltogether.com"}
   :firebase {:db-url "https://journaltogether.firebaseio.com"}})

;; Mail

(def mailgun-creds {:key (-> secrets :mailgun :api-key)
                    :domain (-> config :mailgun :domain)})

(defn send-mail [content]
  (mail/send-mail
    mailgun-creds
    content))

;; Firebase

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

;; Schedule

(defn pst-instant [h m s]
  (-> (LocalTime/of h m s)
      (.adjustInto (ZonedDateTime/now
                     (ZoneId/of "America/Los_Angeles")))
      .toInstant))

(defn daily-period [inst]
  (chime-core/periodic-seq inst (Period/ofDays 1)))

(defn after-now [period]
  (let [now (Instant/now)]
    (filter #(.isAfter % now) period)))

(defn reminder-period []
  (-> (pst-instant 13 15 0)
      daily-period
      after-now))

(defn summary-period []
  (-> (pst-instant 9 0 0)
      daily-period
      after-now))

;; Reminders & Summaries

(defn test-email [subject]
  {:from "journal-buddy@journaltogether.com"
   :to ["stepan.p@gmail.com"]
   :subject subject
   :html "will do nothing with a reply 4 now"})

(defn send-reminders [_]
  (log/infof (pr-str (test-email "how was your day? (this is a reminder)"))))

(defn send-summaries [_]
  (log/infof (pr-str (test-email "this is a summary email"))))

(defn -main []
  (firebase-init)
  (future (chime-core/chime-at
            (reminder-period)
            send-reminders))
  (future (chime-core/chime-at
            (summary-period)
            send-summaries))
  (log/info "started!"))
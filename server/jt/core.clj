(ns jt.core
  (:gen-class)
  (:require [mailgun.mail :as mail]
            [mailgun.util :refer [to-file]]
            [chime.core :as chime-core]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import (com.google.firebase FirebaseApp FirebaseOptions$Builder)
           (com.google.auth.oauth2 ServiceAccountCredentials)
           (java.time LocalTime ZonedDateTime ZoneId Period)
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

(defn reminder-period []
  (chime-core/periodic-seq (pst-instant 16 0 0) (Period/ofDays 1)))

(defn summary-period []
  (chime-core/periodic-seq
    (pst-instant 9 0 0)
    (Period/ofDays 1)))

;; Reminders & Summaries

(defn test-email [subject]
  {:from "journal-buddy@journaltogether.com"
   :to ["stepan.p@gmail.com"]
   :subject subject
   :html "will do nothing with a reply 4 now"})

(defn send-reminders []
  (send-mail (test-email "how was your day? (this is a reminder)")))

(defn send-summaries []
  (send-mail (test-email "this is a summary email")))

(defn -main []
  (future (chime-core/chime-at
            (reminder-period)
            send-reminders))
  (future (chime-core/chime-at
            (summary-period)
            send-summaries))
  (println "started!"))
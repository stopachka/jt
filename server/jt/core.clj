(ns jt.core
  (:gen-class)
  (:require [mailgun.mail :as mail]
            [mailgun.util :refer [to-file]]
            [chime.core :as chime-core])
  (:import (com.google.firebase FirebaseApp FirebaseOptions$Builder)
           (com.google.auth.oauth2 ServiceAccountCredentials)
           (java.time LocalTime ZonedDateTime ZoneId Period)))

;; Mail

(def mailgun-domain "mg.journaltogether.com")
(def mailgun-creds {:key (System/getenv "JT_MAILGUN_API_KEY")
                    :domain mailgun-domain})

(defn send-mail [content]
  (mail/send-mail
    mailgun-creds
    content))

;; Firebase

(defn firebase-init []
  (let [db-url (System/getenv "JT_FIREBASE_DB_URL")
        creds (ServiceAccountCredentials/fromPkcs8
                (System/getenv "JT_FIREBASE_CLIENT_ID")
                (System/getenv "JT_FIREBASE_CLIENT_EMAIL")
                (System/getenv "JT_FIREBASE_PRIVATE_KEY")
                (System/getenv "JT_FIREBASE_PRIVATE_KEY_ID")
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
   :to ["joeaverbukh@gmail.com"
        "markshlick@gmail.com"
        "stepan.p@gmail.com"
        "reichertjalex@gmail.com"]
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
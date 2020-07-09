(ns jt.core
  (:gen-class)
  (:require [chime.core :as chime-core]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jt.db :as db]
            [compojure.core :refer [defroutes GET POST]]
            [io.aviso.logging.setup]
            [mailgun.mail :as mail]
            [markdown.core :as markdown-core]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :refer [response]])
  (:import (java.time LocalTime ZonedDateTime ZoneId Period Instant)
           (java.time.format DateTimeFormatter)
           (com.google.firebase.database DatabaseException)
           (com.google.firebase.auth FirebaseAuth UserRecord$CreateRequest)))

;; ------------------------------------------------------------------------------
;; Helpers

(defmacro fut-bg
  "Futures only throw when de-referenced. fut-bg writes a future
  with a top-level try-catch, so you can run code asynchronously,
  without _ever_ de-referencing them"
  [& forms]
  `(future
     (try
       ~@forms
       (catch Exception e#
         (log/errorf "uh-oh, failed to run async function %s %s" '~forms e#)
         (throw e#)))))


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

(defn ->numeric-date-str [zoned-date]
  (fmt-with-pattern numeric-date-pattern zoned-date))

(defn ->epoch-milli [zoned-date]
  (-> zoned-date
      .toInstant
      .toEpochMilli))

;; ------------------------------------------------------------------------------
;; Config

(def secrets (read-edn-resource "secrets.edn"))

(def config
  {:port 8080
   :mailgun {:domain "mg.journaltogether.com"}
   :firebase
   {:auth {:uid "jt-sv"}
    :project-id "journaltogether"
    :db-url "https://journaltogether.firebaseio.com"}})

(def friends
  (:friends secrets))

;; ------------------------------------------------------------------------------
;; DB

(defn email->id [email]
  (-> email
      (str/replace #"\." "-")
      (str/replace #"@" "_")))

(defn journal-path [email zoned-date]
  (str "/journals/"
       (email->id email)
       "/"
       (->numeric-date-str zoned-date)))

(defn task-path [task-id]
  (str "/tasks/" task-id))

;; ------------------------------------------------------------------------------
;; Mail

(def mailgun-creds {:key (-> secrets :mailgun :api-key)
                    :domain (-> config :mailgun :domain)})

(defn send-mail [content]
  (mail/send-mail mailgun-creds content))

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

(def signup-email "sign-up@mg.journaltogether.com")

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

(defn journal-entry->html [{:keys [sender stripped-text]}]
  (str
    "<p><b>"
    sender
    "</b></p>"
    "<br>"
    (markdown-core/md-to-html-string stripped-text)))

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

(defn try-grab-task!
  "given a task id, tries to reserve the task. Our database rules
  do not allow writes to existing tasks. This ensures only one can
  succeed, so only one worker can grab a task"
  [task-id]
  (try
    @(db/firebase-save (task-path task-id) true)
    (log/infof "[task] grabbed %s" task-id)
    :grabbed
    (catch DatabaseException _e
      (log/infof "[task] skipping %s" task-id)
      nil)))

(defn handle-reminder [_]
  (let [day (pst-now)
        task-id (str "reminder-" (->numeric-date-str day))]
    (when (try-grab-task! task-id)
      (send-mail (content-hows-your-day? (pst-now))))))

(defn handle-summary [_]
  (let [day (.minusDays (pst-now) 1)
        task-id (str "summary-" (->numeric-date-str day))]
    (when (try-grab-task! task-id)
      (let [entries (->> friends
                         (pmap (fn [email]
                                 @(db/firebase-fetch
                                    (journal-path email day))))
                         (filter seq))]
        (if-not (seq entries)
          (log/infof "skipping for %s because there are no entries" day)
          (send-mail (content-summary day entries)))))))

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
    :recipient :body-html :body-plain :at})

;; ------------------------------------------------------------------------------
;; handle-hows-your-day-response

(defn data->journal [data]
  (-> data
      (assoc :at (->epoch-milli (:date data)))
      (select-keys email-keys)))

(defn handle-hows-your-day-response! [data]
  (let [{:keys [sender subject date]} data
        email (data->journal data)]
    (cond
      (seq @(db/firebase-fetch (journal-path sender date)))
      (do
        (log/infof "already have a journal recorded for %s on " sender date)
        (send-mail (content-already-received sender subject)))

      :else
      (do
        (db/firebase-save (journal-path sender date) email)
        (send-mail (content-ack-receive sender subject))))))

;; ------------------------------------------------------------------------------
;; handle-signup

(defn handle-signup-response [data]
  (let [{:keys [sender]} data]
    (-> (FirebaseAuth/getInstance)
        (.createUser (-> (UserRecord$CreateRequest.)
                         (.setEmail sender)
                         (.setEmailVerified true))))))
(comment
  (handle-signup-response {:sender "stepan.p@gmail.com"}))

;; ------------------------------------------------------------------------------
;; emails-handler

(defn emails-handler [{:keys [params] :as req}]
  (fut-bg
    (let [{:keys [recipient sender] :as data}
          (-> params
              (assoc :date (parse-email-date params)))]
      (condp = recipient
        hows-your-day-email
        (handle-hows-your-day-response! data)

        signup-email
        (handle-signup-response data)

        :else
        (log/infof "skipping for recipient=%s sender=%s" recipient sender))))
  (response {:receive true}))

(comment
  (do
    (def _req (read-edn-resource "api-emails-req.edn"))
    (def _res (emails-handler _req))
    _res))

;; ------------------------------------------------------------------------------
;; Server

(defn fallback-handler [_]
  (response
    "<html>
      <style>
        body {
          max-width: 500px;
          padding: 10px;
          margin: 0 auto;
          font-family: Helvetica Neue;
        }
        h1, h2, h3 {
          font-weight: 500;
        }
        li {
          line-height: 1.6
        }
      </style>
      <body>
       <h1>journaltogether</h1>
       <h3>Keep track of your days and connect with your friends</h3>
       <ol>
         <li>Choose a few friends</li>
         <li>Every evening, each of you will receive an email, asking about your day</li>
         <li>Each of you write a reflection</li>
         <li>The next morning, you'll all receive an email of all reflections</li>
       </ol>
       <h4>Interested? <a href='mailto:stepan.p@gmail.com' target='_blank'>send a ping : )</a></h4>
      </body>
     </html>"))

(defroutes
  routes
  (POST "/api/emails" [] emails-handler)
  (GET "*" [] fallback-handler))

(defn -main []
  (db/firebase-init config secrets)
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

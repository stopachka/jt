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
           (com.google.firebase.database FirebaseDatabase ValueEventListener DatabaseReference$CompletionListener DatabaseException)
           (clojure.lang IDeref)
           (java.util UUID)))


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

(defn throwable-promise
  "clojure promises do not have a concept of reject.
  this mimics the idea: you can pass a function, which receives
  a resolve, and reject function

  If you reject a promise, it will throw when de-referenced"
  [f]
  (let [p (promise)
        resolve #(deliver p [nil %])
        reject #(deliver p [% nil])
        throwable-p (reify IDeref
                      (deref [this]
                        (let [[err res] @p]
                          (if err (throw err) res))))]
    (f resolve reject)
    throwable-p))

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

;; ------------------------------------------------------------------------------
;; Config

(def secrets (read-edn-resource "secrets.edn"))

(def config
  {:port 8080
   :mailgun {:domain "mg.journaltogether.com"}
   :firebase
   {:auth {:uid "jt-sv"}
    :db-url "https://journaltogether.firebaseio.com"}})


(def friends
  (:friends secrets))

;; ------------------------------------------------------------------------------
;; DB

(defn firebase-init []
  (let [{:keys [db-url auth]} (:firebase config)
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
                    (.setDatabaseAuthVariableOverride
                      (stringify-keys auth))
                    .build)]
    (FirebaseApp/initializeApp options)))

(defn firebase-save [path v]
  (throwable-promise
    (fn [resolve reject]
      (-> (FirebaseDatabase/getInstance)
          (.getReference path)
          (.setValue
            (stringify-keys v)
            (reify DatabaseReference$CompletionListener
              (onComplete [_this err ref]
                (if err (reject (.toException err))
                        (resolve ref)))))))))

(defn firebase-fetch [path]
  (throwable-promise
    (fn [resolve reject]
      (-> (FirebaseDatabase/getInstance)
          (.getReference path)
          (.addListenerForSingleValueEvent
            (reify ValueEventListener
              (onDataChange [_this s]
                (resolve (->> s .getValue ->clj)))
              (onCancelled [_this err]
                (reject (.toException err)))))))))

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

(defn content-hows-your-day? [day]
  {:from hows-your-day-email-with-name
   :to ["stepan.p@gmail.com"]
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

(defn ONLY-GMAIL-poor-mans-parse-last-response
  "Mailgun has :stripped-text, but the html they provide is
  borked. Emojis don't work : (. This is our work around for now.
  We rely on the fact that all of our friends are on gmail, and
  gmail provides a special `gmail_quote` tag."
  [body-html]
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
    (ONLY-GMAIL-poor-mans-parse-last-response body-html)
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

(defn try-grab-task!
  "given a task id, tries to reserve the task. Our database rules
  do not allow writes to existing tasks. This ensures only one can
  succeed, so only one worker can grab a task"
  [task-id]
  (try
    @(firebase-save (task-path task-id) true)
    (log/infof "[task] grabbed %s" task-id)
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
                                 @(firebase-fetch
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

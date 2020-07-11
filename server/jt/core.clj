(ns jt.core
  (:gen-class)
  (:require [chime.core :as chime-core]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jt.db :as db]
            [jt.concurrency :refer [fut-bg]]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :refer [resources]]
            [io.aviso.logging.setup]
            [mailgun.mail :as mail]
            [markdown.core :as markdown-core]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :as resp :refer [response bad-request]])
  (:import (java.time LocalTime ZonedDateTime ZoneId Period Instant)
           (java.time.format DateTimeFormatter)
           (com.google.firebase.database DatabaseException)
           (com.google.firebase.auth FirebaseAuthException FirebaseAuth)))

;; ------------------------------------------------------------------------------
;; Helpers

(defn read-edn-resource
  "Transforms a resource in our classpath to edn"
  [path]
  (-> path
      io/resource
      slurp
      edn/read-string))

(def email-pattern #"(?i)[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
(defn email?
  [email]
  (and (string? email) (boolean (re-matches email-pattern email))))

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
   :static-root "public"
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

(defn send-email [content]
  (log/infof "[mail] sending content=%s" content)
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
(def signup-email-with-name
  (email-with-name signup-email "Journal Signup"))

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

(defn content-ack-receive [to, subject]
  {:from hows-your-day-email-with-name
   :to to
   :subject subject
   :html "Oky doke, received this 👌"})

(defn content-already-received [to, subject]
  {:from hows-your-day-email-with-name
   :to to
   :subject subject
   :html (str
           "<p>Oi, I already logged a journal entry for you.</p>"
           "<p>I can't do much with this. Ping Stopa sry 🙈</p>")})

(defn url-with-magic-code [magic-code]
  (str "https://www.journaltogether.com/magic/" magic-code))

(defn content-magic-code-response [to magic-code]
  {:from signup-email-with-name
   :to to
   :subject "Here's how to sign into JournalTogether"
   :html
   (str
     "<p>Welcome to Journal Together!</p>
     Visit "
     (url-with-magic-code magic-code)
     " to get started")})

;; ------------------------------------------------------------------------------
;; Outgoing Mail

(defn try-grab-task!
  "given a task id, tries to reserve the task. Our database rules
  do not allow writes to existing tasks. This ensures only one can
  succeed, so only one worker can grab a task"
  [task-id]
  (try
    @(db/firebase-save (db/firebase-ref (task-path task-id)) true)
    (log/infof "[task] grabbed %s" task-id)
    :grabbed
    (catch DatabaseException _e
      (log/infof "[task] skipping %s" task-id)
      nil)))

(defn handle-reminder [_]
  (let [day (pst-now)
        task-id (str "reminder-" (->numeric-date-str day))]
    (when (try-grab-task! task-id)
      (send-email (content-hows-your-day? (pst-now))))))

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
          (send-email (content-summary day entries)))))))

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
      (send-email (content-already-received sender subject))

      :else
      (do
        (db/firebase-save (db/firebase-ref (journal-path sender date)) email)
        (send-email (content-ack-receive sender subject))))))

;; ------------------------------------------------------------------------------
;; emails-handler

(defn emails-handler [{:keys [params] :as req}]
  (fut-bg
    (let [{:keys [recipient sender subject] :as data}
          (-> params
              (assoc :date (parse-email-date params)))]
      (condp = recipient
        hows-your-day-email
        (handle-hows-your-day-response! data)

        :else
        (log/infof "skipping for recipient=%s sender=%s" recipient sender))))
  (response {:receive true}))

(comment
  (do
    (def _req (read-edn-resource "api-emails-req.edn"))
    (def _res (emails-handler _req))
    _res))

;; ------------------------------------------------------------------------------
;; magic codes

(defn magic-request-handler [{:keys [body] :as _req}]
  (let [{:keys [email]} body]
    (cond
      (not (email? email))
      (bad-request {:reason "invalid email"})

      :else
      (do (send-email (content-magic-code-response email (:key (db/create-magic-code! email))))
          (response {:receive true})))))

(defn magic-auth-handler [{:keys [body] :as _req}]
  (let [{:keys [code]} body
        {:keys [email]} @(db/consume-magic-code code)]
    (cond
      (nil? email)
      (bad-request {:reason "could not consume magic code"})

      :else
      (let [{:keys [uid]} (or (db/get-user-by-email! email)
                              (db/create-user! email))]
        (response {:token (db/create-token-for-uid! uid)})))))

;; ------------------------------------------------------------------------------
;; sync

(defn handle-sync [{:keys [body headers] :as _req}]
  (let [{:keys [type data]} body
        token (get headers "token")
        user (-> (FirebaseAuth/getInstance)
                 (.verifyIdToken token)
                 db/user-record->map)
        res (condp = (keyword type)
              :create-group
              (let [{:keys [name]} data]
                @(db/create-group name user)))]
    {:res res}))

;; ------------------------------------------------------------------------------
;; Server

(defn wrap-cors [handler]
  (fn [request]
    (-> (handler request)
        (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
        (assoc-in [:headers "Access-Control-Allow-Headers"] "*"))))

(def static-root (:static-root config))
(defn render-static-file [filename]
  (resp/content-type
    (resp/resource-response filename {:root static-root}) "text/html"))

(defroutes
  routes
  (POST "/api/emails" [] emails-handler)
  (POST "/api/magic/request" [] magic-request-handler)
  (POST "/api/magic/auth" [] magic-auth-handler)

  ;; static assets
  (resources "/" {:root static-root})
  (GET "*" [] (render-static-file "index.html")))

(defn -main []
  (db/init config secrets)
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
                wrap-json-response
                wrap-cors)]
    (jetty/run-jetty app {:port port :join? false}))
  (log/info "kicked off!"))

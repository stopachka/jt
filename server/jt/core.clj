(ns jt.core
  (:gen-class)
  (:require [chime.core :as chime-core]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jt.db :as db]
            [jt.concurrency :refer [fut-bg]]
            [compojure.core :refer [context routes defroutes GET POST]]
            [compojure.route :refer [resources]]
            [io.aviso.logging.setup]
            [mailgun.mail :as mail]
            [markdown.core :as markdown-core]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.util.request :refer [body-string]]
            [ring.util.response :as resp :refer [response bad-request]])
  (:import (java.time LocalTime ZonedDateTime ZoneId Period Instant)
           (java.time.format DateTimeFormatter)
           (com.google.firebase.database DatabaseException)
           (com.stripe.model.checkout Session)
           (com.stripe Stripe)
           (com.stripe.net Webhook)
           (com.stripe.model Event)))

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
   :stripe {:premium-price-id "price_1H4IDVGnGs7xopb5ZhBQ2hnU"}
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

(defn content-group-invitation [sender-email receiver-email magic-code]
  {:from signup-email-with-name
   :to receiver-email
   :subject "You've been invited to join a group on JournalTogether"
   :html
   (str
     "<p>Hello there!</p>"
     "<p>" sender-email " has invited you to join their group on Journal Together</p>"
     "<p> Visit " (url-with-magic-code magic-code) " to join them")})

;; ------------------------------------------------------------------------------
;; Outgoing Mail

(defn try-grab-task
  "given a task id, tries to reserve the task. Our database rules
  do not allow writes to existing tasks. This ensures only one can
  succeed, so only one worker can grab a task"
  [task-id]
  (try
    (db/firebase-save (db/firebase-ref (task-path task-id)) true)
    (log/infof "[task] grabbed %s" task-id)
    :grabbed
    (catch DatabaseException _e
      (log/infof "[task] skipping %s" task-id)
      nil)))

(defn handle-reminder [_]
  (let [day (pst-now)
        task-id (str "reminder-" (->numeric-date-str day))]
    (when (try-grab-task task-id)
      (send-email (content-hows-your-day? (pst-now))))))

(defn handle-summary [_]
  (let [day (.minusDays (pst-now) 1)
        task-id (str "summary-" (->numeric-date-str day))]
    (when (try-grab-task task-id)
      (let [entries (->> friends
                         (pmap (fn [email]
                                 (db/firebase-fetch
                                   (db/firebase-ref (journal-path email day)))))
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

(defn handle-hows-your-day-response [data]
  (let [{:keys [sender subject date]} data
        email (data->journal data)]
    (cond
      (seq (db/firebase-fetch (db/firebase-ref
                                (journal-path sender date))))
      (send-email (content-already-received sender subject))

      :else
      (do
        (db/firebase-save (db/firebase-ref (journal-path sender date)) email)
        (send-email (content-ack-receive sender subject))))))

;; ------------------------------------------------------------------------------
;; emails-handler

(defn emails-handler [{:keys [params] :as req}]
  (fut-bg
    (let [{:keys [recipient sender] :as data}
          (-> params
              (assoc :date (parse-email-date params)))]
      (condp = recipient
        hows-your-day-email
        (handle-hows-your-day-response data)

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
      (do (->> {:email email}
               db/create-magic-code
               :key
               (content-magic-code-response email)
               send-email)
          (response {:receive true})))))

(defn accept-invitation [id]
  (let [{:keys [sender-email group-id receiver-email] :as invitation}
        (db/get-invitation-by-id id)

        _ (assert invitation (format "Expected invitation for id = %s" id))

        sender-user (db/get-user-by-email sender-email)
        receiver-user (db/get-user-by-email receiver-email)
        group (db/get-group-by-id group-id)

        _ (assert
            (and sender-user receiver-user group)
            (format (str "Expected data for invitation = %s "
                         "sender-user = %s receiver-user = %s group = %s")
                    invitation sender-user receiver-user group))
        _ (assert
            (db/user-belongs-to-group? group sender-user)
            (format "Expected sender-user = %s to belong to group = %s"
                    sender-user group))]
    (db/delete-invitation id)
    (db/add-user-to-group group-id receiver-user)))

(defn magic-auth-handler [{:keys [body] :as _req}]
  (let [{:keys [code]} body
        {:keys [email invitations]} (db/consume-magic-code code)
        _ (assert (email? email) (str "Expected a valid email =" email))]
    (let [{:keys [uid]} (or (db/get-user-by-email email)
                            (db/create-user email))]
      (pmap accept-invitation invitations)
      (response {:token (db/create-token-for-uid uid)}))))

;; ------------------------------------------------------------------------------
;; invitations

(defn invite-user-handler [{:keys [body] :as _req}]
  (let [{:keys [invitation-id]} body]
    (let [{:keys [sender-email receiver-email] :as invitation}
          (db/get-invitation-by-id invitation-id)

          _ (assert invitation (format "Invalid invitation id = %s" invitation-id))
          _ (assert (email? receiver-email) (format "Invalid email = %s" receiver-email))]
      (->> {:email receiver-email :invitations [invitation-id]}
           db/create-magic-code
           :key
           (content-group-invitation sender-email receiver-email)
           send-email)
      (response {:sent true}))))

;; ------------------------------------------------------------------------------
;; stripe

(defn create-session-handler [{:keys [headers] :as _req}]
  (let [{:keys [uid] :as _user} (db/user-from-id-token (get headers "token"))
        {:keys [customer-id] :as _payment-info} (db/get-payment-info uid)

        _ (assert customer-id (format "expected a valid customer-id for %s" uid))

        session
        (Session/create
          {"success_url"
           "http://localhost:3000/me/checkout/success?session_id={CHECKOUT_SESSION_ID}"
           "cancel_url"
           "https://localhost:3000/me/account"
           "customer" customer-id
           "mode" "subscription"
           "line_items" [{"price" (-> config :stripe :premium-price-id)
                          "quantity" 1}]
           "payment_method_types" ["card"]})]
    (response {:id (.getId session)})))

(defn webhooks-stripe-handler [{:keys [headers] :as req}]
  (let [sig (get headers "stripe-signature")
        body-str (body-string req)
        ^Event evt (Webhook/constructEvent
                     body-str sig (-> secrets :stripe :webhook-secret))]
    (condp = (.getType evt)
      "checkout.session.completed"
      (let [^Session session (-> evt .getDataObjectDeserializer .getObject)]
        (log/infof "would be completing the purchase here! =%s" session))

      (log/infof "ignoring evt %s " evt))
    (response {:receive true})))

;; ------------------------------------------------------------------------------
;; Server

(def static-root (:static-root config))
(defn render-static-file [filename]
  (resp/content-type
    (resp/resource-response filename {:root static-root}) "text/html"))

(defroutes
  webhook-routes
  (context "/hooks" []
    (POST "/stripe" [] webhooks-stripe-handler)))

(defroutes
  api-routes
  (context "/api" []
    (POST "/emails" [] emails-handler)
    (POST "/magic/request" [] magic-request-handler)
    (POST "/magic/auth" [] magic-auth-handler)

    (POST "/me/invite-user" [] invite-user-handler)
    (POST "/me/checkout/create-session" [] create-session-handler)))

(defroutes
  static-routes
  (resources "/" {:root static-root})
  (GET "*" [] (render-static-file "index.html")))

(defn -main []
  (db/init config secrets)
  (set! (. Stripe -apiKey) (-> secrets :stripe :secret-key))
  (fut-bg (chime-core/chime-at
            (reminder-period)
            handle-reminder))
  (fut-bg (chime-core/chime-at
            (summary-period)
            handle-summary))
  (let [{:keys [port]} config
        app (routes
              (-> webhook-routes
                  wrap-json-response)
              (-> api-routes
                  wrap-keyword-params
                  wrap-params
                  (wrap-json-body {:keywords? true})
                  wrap-json-response
                  (wrap-cors :access-control-allow-origin [#".*"]
                             :access-control-allow-methods [:get :put :post :delete]))
              static-routes)]
    (jetty/run-jetty app {:port port}))
  (log/info "kicked off!"))

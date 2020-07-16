(ns jt.core
  (:gen-class)
  (:require [chime.core :as chime-core]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jt.db :as db]
            [jt.concurrency :refer [fut-bg]]
            [jt.profile :as profile]
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
           (com.stripe.model Event Subscription)))

;; ------------------------------------------------------------------------------
;; Helpers

(def email-pattern #"(?i)[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
(defn email?
  [email]
  (and (string? email) (boolean (re-matches email-pattern email))))

;; ------------------------------------------------------------------------------
;; Date Helpers

(def friendly-date-pattern
  "i.e Wed Jul 1"
  "E LLL d")

(def friend-date-time-pattern
  "Tue 07 14 02:56 PM PDT"
  "E LL d hh:mm a zz")

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
;; DB

(defn task-path [task-id]
  (str "/tasks/" task-id))

;; ------------------------------------------------------------------------------
;; Mail

(defn send-email [content]
  (log/infof "[mail] sending content=%s" content)
  (when (or true (= profile/env :prod))
    (mail/send-mail
      {:key (profile/get-secret :mailgun :api-key)
       :domain (profile/get-config :mailgun :domain)}
      content)))

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

(defn content-hows-your-day? [day email]
  {:from hows-your-day-email-with-name
   :to email
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

(defn ->entry-html [{:keys [date stripped-text]}]
  (str
    "<p><b>"
    (fmt-with-pattern friend-date-time-pattern date)
    "</b></p>"
    (markdown-core/md-to-html-string stripped-text)))

(defn ->user-section-html [[{:keys [email] :as _user} entries]]
  (str
    "<h3>"email"</h3>"
    (->> entries
         (filter seq)
         (map ->entry-html)
         str/join)))

(defn content-summary [day to users-with-entries]
  (let [friendly-date-str (fmt-with-pattern friendly-date-pattern day)]
    {:from summary-email-with-name
     :to to
     :subject (str "☀️ Here's how things went " friendly-date-str)
     :html
     (str "<p>"
          "Howdy, Here's how things have went on " friendly-date-str
          "</p>"
          (->> users-with-entries
               (map ->user-section-html)
               str/join))}))

(defn content-ack-receive [to, subject]
  {:from hows-your-day-email-with-name
   :to to
   :subject subject
   :html (str
           "<p>Oky doke, I logged this entry!</p>"
           "<p>To manage your journals, you can always visit https://www.journaltogether.com/me/journals</p>"
           "<p>See ya</p>")})

(defn url-with-magic-code [magic-code]
  (str "https://www.journaltogether.com/magic/" magic-code))

(defn content-magic-code-response [to magic-code]
  {:from signup-email-with-name
   :to to
   :subject "Magic sign-in link for Journal Together"
   :html
   (str
     "<p>Hey there, you asked us to send you a magic link to sign into Journal Together.</p>"
     "<p>Here it is:</p>"
     "<p><strong>"(url-with-magic-code magic-code) "<strong></p>"
     "<p>Once you open that link, you'll be signed in and ready to go!</p>"
     "<p>Note: your magic link can only be used once.</p>"
     "<p>See ya :)</p>")})

(defn content-group-invitation [sender-email receiver-email magic-code]
  {:from signup-email-with-name
   :to receiver-email
   :subject "You've been invited to join a group on JournalTogether"
   :html
   (str
     "<p>Hello there!</p>"
     "<p>" sender-email " has invited you to join their group on Journal Together</p>"
     "<p> Visit " (url-with-magic-code magic-code) " to join them")})

(defn content-user-does-not-exist [from to subject]
  {:from from
   :to to
   :subject subject
   :html
   (str
     "<p>Oi, you need to sign up to use journaltogether</p>"
     "<p>Visit https://journaltogether.com/me to sign up</p>")})


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
        task-id (str "send-reminder-" (->numeric-date-str day))]
    (when (try-grab-task task-id)
      (->> (db/get-all-users)
           (pmap (fn [{:keys [email] :as user}]
                   (try
                     (send-email (content-hows-your-day? day email))
                     (catch Exception e
                       (log/errorf e "failed to send reminder for user = %s" user)))))))))

(defn send-summary
  [start-date group-id group]
  (when (try-grab-task (str "summary-group-" group-id (->numeric-date-str start-date)))
    (let [end-date (.plusDays start-date 1)
          users (->> group
                     :users
                     keys
                     (map name)
                     (pmap db/get-user-by-uid))
          users-with-entries (->> users
                                  (pmap (fn [{:keys [uid] :as u}]
                                          [u (db/get-entries-between
                                               uid start-date end-date)]))
                                  (filter (comp seq second)))]
      (cond
        (not (seq users-with-entries))
        (log/infof "skipping group, no entries group=%s " group)

        :else
        (send-email
          (content-summary start-date (map :email users) users-with-entries))))))

(defn handle-summary [_]
  (let [start-date (.minusDays (pst-now) 1)
        task-id (str "handle-summary-" (->numeric-date-str start-date))]
    (when (try-grab-task task-id)
      (->> (db/get-all-groups)
           (pmap (fn [[k g]]
                   (try
                     (send-summary start-date k g)
                     (catch Exception e
                       (log/errorf e "failed to send summary to group %s" g)))))
           doall))))

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

;; ------------------------------------------------------------------------------
;; handle-hows-your-day-response

(defn handle-hows-your-day-response [data]
  (let [{:keys [sender recipient subject]} data
        {:keys [uid] :as _user} (db/get-user-by-email sender)]
    (cond
      (not uid)
      (send-email (content-user-does-not-exist recipient sender subject))

      :else
      (do
        (db/save-entry uid data)
        (send-email (content-ack-receive sender subject))))))

;; ------------------------------------------------------------------------------
;; emails-handler

(defn emails-handler [{:keys [params] :as _req}]
  (fut-bg
    (let [{:keys [recipient sender] :as data}
          (-> params
              (assoc :date (parse-email-date params)))]
      (condp = recipient
        hows-your-day-email
        (handle-hows-your-day-response data)

        (log/infof "skipping for recipient=%s sender=%s" recipient sender))))
  (response {:receive true}))

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
    (db/add-user-to-group receiver-user group-id)))

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
;; account

(defn delete-account-handler [{:keys [headers] :as _req}]
  (let [{:keys [uid] :as _user} (db/get-user-from-id-token (get headers "token"))]
    (do
      (db/delete-group-memberships uid)
      (db/delete-payment-info uid)
      (db/delete-entries uid)
      (db/delete-user uid))
    (response {:receive true})))

;; ------------------------------------------------------------------------------
;; schedule

(defn schedule-handler [_req]
  (response
    {:reminder-ms (db/->epoch-milli (first (reminder-period)))
     :summary-ms (db/->epoch-milli (first (summary-email)))}))

;; ------------------------------------------------------------------------------
;; stripe

(defn cancel-subscription-handler [{:keys [headers] :as _req}]
  (let [{:keys [uid] :as user} (db/get-user-from-id-token (get headers "token"))
        {:keys [customer-id subscription-id] :as _payment-info} (db/get-payment-info uid)
        _ (assert (and customer-id subscription-id)
                  (format "expected a valid subscription for user=%s" user))
        sub (Subscription/retrieve subscription-id)]
    (cond
      (= (.getStatus sub) "canceled")
      (bad-request {:code "already_canceled"})

      :else
      (do (.cancel sub)
          (db/save-user-level uid :standard)))
    (response {:receive true})))

(defn create-session-handler [{:keys [headers] :as _req}]
  (let [{:keys [uid] :as _user} (db/get-user-from-id-token (get headers "token"))
        {:keys [customer-id] :as _payment-info} (db/get-payment-info uid)

        _ (assert customer-id (format "expected a valid customer-id for %s" uid))

        session
        (Session/create
          {"success_url" (profile/get-config :stripe :session :success-url)
           "cancel_url" (profile/get-config :stripe :session :cancel-url)
           "customer" customer-id
           "mode" "subscription"
           "line_items" [{"price" (profile/get-config :stripe :premium-price-id)
                          "quantity" 1}]
           "payment_method_types" ["card"]})]
    (response {:id (.getId session)})))

(defn handle-stripe-webhook-event [evt]
  (condp = (.getType evt)
    "checkout.session.completed"
    (let [^Session session (-> evt .getDataObjectDeserializer .getObject .get)
          customer-id (.getCustomer session)
          subscription-id (.getSubscription session)

          {:keys [uid]} (db/get-user-by-customer-id customer-id)]
      (db/save-payment-info uid {:customer-id customer-id
                                 :subscription-id subscription-id})
      (db/save-user-level uid :premium))

    "customer.subscription.deleted"
    (let [^Subscription subscription (-> evt .getDataObjectDeserializer .getObject .get)
          customer-id (.getCustomer subscription)
          {:keys [uid]} (db/get-user-by-customer-id customer-id)]
      (db/save-payment-info uid {:customer-id customer-id})
      (db/save-user-level uid :standard))

    (log/infof "ignoring evt %s " evt)))

(defn webhooks-stripe-handler [{:keys [headers] :as req}]
  (let [sig (get headers "stripe-signature")
        body-str (body-string req)
        ^Event evt (Webhook/constructEvent
                     body-str
                     sig
                     (profile/get-secret :stripe :webhook-secret))]
    (fut-bg (handle-stripe-webhook-event evt))
    (response {:receive true})))

;; ------------------------------------------------------------------------------
;; Server

(defn render-static-file [filename]
  (resp/content-type
    (resp/resource-response filename {:root (profile/get-config :static-root)}) "text/html"))

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

    (GET "/me/schedule" [] schedule-handler)
    (POST "/me/invite-user" [] invite-user-handler)
    (POST "/me/delete-account" [] delete-account-handler)
    (POST "/me/checkout/create-session" [] create-session-handler)
    (POST "/me/checkout/cancel-subscription" [] cancel-subscription-handler)))

(defroutes
  static-routes
  (resources "/" {:root (profile/get-config :static-root)})
  (GET "*" [] (render-static-file "index.html")))

(defn -main []
  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread e]
        (log/error e "Uncaught exception on" (.getName thread)))))
  (db/init)
  (set! (. Stripe -apiKey) (profile/get-secret :stripe :secret-key))
  (fut-bg (chime-core/chime-at (reminder-period) handle-reminder))
  (fut-bg (chime-core/chime-at (summary-period) handle-summary))
  (let [port (profile/get-config :port)
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

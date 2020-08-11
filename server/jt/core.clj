(ns jt.core
  (:gen-class)
  (:require
    [buddy.core.codecs :as codecs]
    [buddy.core.mac :as mac]
    [chime.core :as chime-core]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [compojure.core :refer [context routes defroutes GET POST]]
    [compojure.route :refer [resources]]
    [io.aviso.logging.setup]
    [jt.concurrency :refer [fut-bg]]
    [jt.db :as db]
    [jt.profile :as profile]
    [mailgun.mail :as mail]
    [markdown.core :as markdown-core]
    [ring.adapter.jetty :as jetty]
    [ring.middleware.cors :refer [wrap-cors]]
    [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
    [ring.middleware.keyword-params :refer [wrap-keyword-params]]
    [ring.middleware.params :refer [wrap-params]]
    [ring.middleware.multipart-params :refer [wrap-multipart-params]]
    [ring.util.request :refer [body-string]]
    [ring.util.response :as resp :refer [response bad-request]])
  (:import
    (com.google.firebase.database
      DatabaseException)
    (com.stripe
      Stripe)
    (com.stripe.model
      Event
      Subscription)
    (com.stripe.model.checkout
      Session)
    (com.stripe.net
      Webhook)
    (java.time
      DayOfWeek
      LocalTime
      Period
      ZoneId
      ZonedDateTime)
    (java.time.format
      DateTimeFormatter)
    (java.time.temporal ChronoUnit)))

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


(defn ->numeric-date-str
  [zoned-date]
  (fmt-with-pattern numeric-date-pattern zoned-date))

;; ------------------------------------------------------------------------------
;; DB

(defn task-path
  [task-id]
  (str "/tasks/" task-id))

;; ------------------------------------------------------------------------------
;; Mail

(defn send-email
  [content]
  (log/infof "[mail] sending content=%s" content)
  (mail/send-mail
    {:key (profile/get-secret :mailgun :api-key)
     :domain (profile/get-config :mailgun :domain)}
    content))

;; ------------------------------------------------------------------------------
;; Schedule

(def pst-zone (ZoneId/of "America/Los_Angeles"))

(defn pst-now
  []
  (ZonedDateTime/now pst-zone))

(defn hourly-period []
  (let [now (pst-now)
        daily-period #(chime-core/periodic-seq % (Period/ofDays 1))
        start-of-hour (fn [date h] (-> date
                                       (.withHour h)
                                       (.truncatedTo ChronoUnit/HOURS)))
        ->all-hours (fn [date]
                      (map (partial start-of-hour date)
                           (range 24)))
        after-now? #(.isAfter % now)]
    (->> (daily-period now)
         (mapcat ->all-hours)
         (filter after-now?))))

;; ------------------------------------------------------------------------------
;; Sender

(defn create-sender
  [name email]
  {:raw-name name
   :raw-email email
   :email-with-name (str name " <" email ">")})


(def hows-your-day-sender
  (create-sender
    "Journal Buddy"
    "journal-buddy@mg.journaltogether.com"))


(def summary-sender
  (create-sender
    "Journal Summary"
    "journal-summary@mg.journaltogether.com"))


(def signup-sender
  (create-sender
    "Journal Signup"
    "sign-up@mg.journaltogether.com"))


(def ceo-assistant-sender
  (create-sender
    "CEO Assistant"
    "ceo-assistant@mg.journaltogether.com"))


(defn content-hows-your-day?
  [day email]
  {:from (:email-with-name hows-your-day-sender)
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


(defn ->entry-html
  [{:keys [date stripped-text]}]
  (str
    "<p><b>"
    (fmt-with-pattern friend-date-time-pattern date)
    "</b></p>"
    (markdown-core/md-to-html-string stripped-text)))


(defn ->user-section-html
  [[{:keys [email] :as _user} entries]]
  (str
    "<h3>" email "</h3>"
    (->> entries
         (filter seq)
         (map ->entry-html)
         str/join)))


(defn content-summary
  [day to users-with-entries]
  (let [friendly-date-str (fmt-with-pattern friendly-date-pattern day)]
    {:from (:email-with-name summary-sender)
     :to to
     :subject (str "☀️ Here's how things went " friendly-date-str)
     :html
     (str "<p>"
          "Howdy, Here's how things have went on " friendly-date-str
          "</p>"
          (->> users-with-entries
               (map ->user-section-html)
               str/join)
          "<hr />"
          "<p><em>"
          "Thoughts, comments? You can reply all to this email to start a conversation :)"
          "</em></p>")}))


(defn url-with-magic-code
  [magic-code]
  (str "https://www.journaltogether.com/magic/" magic-code))


(defn content-magic-code-response
  [to magic-code]
  {:from (:email-with-name signup-sender)
   :to to
   :subject "Magic sign-in link for Journal Together"
   :html
   (str
     "<p>Hey there, you asked us to send you a magic link to sign into Journal Together.</p>"
     "<p>Here it is:</p>"
     "<p><strong>" (url-with-magic-code magic-code) "<strong></p>"
     "<p>Once you open that link, you'll be signed in and ready to go!</p>"
     "<p>Note: your magic link can only be used once.</p>"
     "<p>See ya :)</p>")})


(defn content-group-invitation
  [sender-email receiver-email magic-code message]
  {:from (:email-with-name signup-sender)
   :to receiver-email
   :subject "You've been invited to join a group on Journal Together"
   :html
   (str
     "<p>Howdy,</p>"
     "<p>" sender-email " has invited you to join their group on Journal Together.</p>"
     (if (seq message)
       (str "<p>This is what they said:</p>"
            "<p>" message "</p>")
       "<p>If you don't know what this is about, please ask em : )</p>")
     "<p>To get started, open this link:</p>"
     "<p><strong>" (url-with-magic-code magic-code) "</strong></p>")})


(defn content-user-does-not-exist
  [from to subject]
  {:from from
   :to to
   :subject subject
   :html
   (str
     "<p>Oi, you need to sign up to use journaltogether</p>"
     "<p>Visit https://journaltogether.com/me to sign up</p>")})


(defn content-notify-ceo-about-magic
  [{:keys [email] :as magic}]
  {:from (:email-with-name ceo-assistant-sender)
   :to (profile/get-config :ceo-email)
   :subject (str "New magic usage by " (-> email
                                           (str/split #"@")
                                           first))
   :html
   (str
     "<p>" email " just used a magic code. Here's what it says:</p>"
     "<pre><code>" magic "</code></pre>")})

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


(defn send-reminder?
  "Some users do not want to receive a reminder email, asking about
  their day. The use case here, is I think, they simply want to follow
  along with their friend's journals. I am not sure about this UX, but
  supporting it for the few folks who asked for this."
  [email]
  (not (contains? (profile/get-secret :emails :reminder-ignore) email)))


(defn rounded-hour
  "Our hourly periods are envoked by the scheduler.
  Our goal is to understand what hour it is.
  In the case the scheduler invokes us at 14:59:59, for example,
  we would want to get the hour as 15."
  [zoned-date]
  (let [min-frac (/ (.getMinute zoned-date) 60)]
    (cond->
      zoned-date
      (>= min-frac 1/2)
      (.plusHours 1)
      :else
      (.truncatedTo ChronoUnit/HOURS))))

(defn handle-reminder
  [_]
  (let [date (rounded-hour (pst-now))
        h (.getHour date)
        task-id (str "send-reminder-" (->numeric-date-str date) "-" h)]
    (when (try-grab-task task-id)
      (->> (db/get-users-by-reminder-hour h)
           (filter (comp send-reminder? :email))
           (pmap
             (fn [{:keys [email] :as user}]
               (try
                 (log/infof "attempt sender-reminder: %s" user)
                 (send-email (content-hows-your-day? date email))
                 (catch Exception e
                   (log/errorf e "failed send-reminder: %s" user)))))
           doall))))


(defn send-summary
  [start-date group]
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
        (content-summary start-date (map :email users) users-with-entries)))))


(defn handle-summary
  [_]
  (let [date (rounded-hour (pst-now))
        h (.getHour date)
        start-date (.minusDays date 1)
        task-id (str "handle-summary-" (->numeric-date-str start-date) "-" h)]
    (when (try-grab-task task-id)
      (->> (db/get-groups-by-summary-hour h)
           (pmap (fn [[_k g]]
                   (try
                     (send-summary start-date g)
                     (catch Exception e
                       (log/errorf e "failed to send summary to group %s" g)))))
           doall))))

;; ------------------------------------------------------------------------------
;; Incoming Mail

(def mailgun-date-formatter
  (-> "EEE, d LLL yyyy HH:mm:ss ZZ"
      DateTimeFormatter/ofPattern
      (.withZone pst-zone)))


(defn parse-email-date
  [params]
  (-> params
      :Date
      (ZonedDateTime/parse mailgun-date-formatter)))

;; ------------------------------------------------------------------------------
;; handle-hows-your-day-response

(defn handle-hows-your-day-response
  [data]
  (let [{:keys [sender recipient subject]} data
        {:keys [uid] :as _user} (db/get-user-by-email sender)]
    (cond
      (not uid)
      (send-email (content-user-does-not-exist recipient sender subject))

      :else
      (db/save-entry uid data))))

;; ------------------------------------------------------------------------------
;; emails-handler

(defn verify-sender
  [{:keys [token timestamp signature] :as _params}]
  (mac/verify
    (str timestamp token)
    (codecs/hex->bytes signature)
    {:key (profile/get-secret :mailgun :api-key)
     :alg :hmac+sha256}))

(defn parse-email-attachments
  "Mailgun sends flat attachments.
  If there are any, it comes with keys attachment-1, attachment-2, etc
  This fn converts this to a list, for simpler manipulation"
  [params]
  (loop [ret []]
    (if-let [att ((keyword (str "attachment-" (+ 1 (count ret))))
                  params)]
      (recur (conj ret att))
      ret)))

(defn emails-handler
  [{:keys [params] :as _req}]
  (assert (verify-sender params)
          (format "Could not verify message came from mailgun %s" params))
  (fut-bg
    (let [date (parse-email-date params)
          attachments (parse-email-attachments params)
          {:keys [recipient sender] :as data}
          (cond->
            params
            date (assoc :date date)
            (seq attachments) (assoc :attachments attachments))]
      (condp = recipient
        (:raw-email hows-your-day-sender)
        (handle-hows-your-day-response data)

        (log/infof "skipping for recipient=%s sender=%s" recipient sender))))
  (response {:receive true}))

;; ------------------------------------------------------------------------------
;; magic codes

(defn magic-request-handler
  [{:keys [body] :as _req}]
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


(defn accept-invitation
  [id]
  (log/infof "accepting invitation id %s" id)
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
    (log/infof "accepting invitation %s receiver-user %s group %s" invitation receiver-user group)
    (db/delete-invitation id)
    (db/add-user-to-group receiver-user group-id)))


(defn magic-auth-handler
  [{:keys [body] :as _req}]
  (let [{:keys [code]} body
        {:keys [email invitations] :as magic} (db/consume-magic-code code)

        _ (assert (email? email) (format "Expected a valid magic = %s" magic))]
    (fut-bg
      (send-email (content-notify-ceo-about-magic magic)))
    (let [{:keys [uid]} (or (db/get-user-by-email email)
                            (db/create-user email))]
      (pmap accept-invitation invitations)
      (response {:token (db/create-token-for-uid uid)}))))

;; ------------------------------------------------------------------------------
;; invitations

(defn invite-users-handler
  [{:keys [body] :as _req}]
  (let [{:keys [invitation-ids message]} body
        get-and-validate-invitation
        (fn [id]
          (let [{:keys [receiver-email] :as invitation}
                (db/get-invitation-by-id id)
                _ (assert invitation (format "Invalid invitation id = %s" id))
                _ (assert (email? receiver-email) (format "Invalid email = %s" receiver-email))]
            [id invitation]))
        send-invitation
        (fn [[id {:keys [receiver-email sender-email]}]]
          (let [magic-code-key (->> {:email receiver-email :invitations [id]}
                                    db/create-magic-code
                                    :key)]
            (send-email
              (content-group-invitation sender-email receiver-email magic-code-key message))))]
    (->> invitation-ids
         (pmap get-and-validate-invitation)
         (pmap send-invitation)
         doall)
    (response {:sent true})))

;; ------------------------------------------------------------------------------
;; account

(defn delete-account-handler
  [{:keys [headers] :as _req}]
  (let [{:keys [uid] :as _user} (db/get-user-from-id-token (get headers "token"))]
    (db/delete-group-memberships uid)
    (db/delete-payment-info uid)
    (db/delete-entries uid)
    (db/delete-user uid)
    (response {:receive true})))

;; ------------------------------------------------------------------------------
;; stripe

(defn cancel-subscription-handler
  [{:keys [headers] :as _req}]
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
    (response {:success true})))


(defn create-session-handler
  [{:keys [headers] :as _req}]
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


(defn handle-stripe-webhook-event
  [evt]
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


(defn webhooks-stripe-handler
  [{:keys [headers] :as req}]
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

(defn wrap-errors [handler]
  (fn [request]
    (try (handler request)
         (catch Throwable e
           (log/errorf e "uncaught error, request=%s" request)
           {:status 500
            :body "Something went wrong. Please Ping Stopa :<"}))))

(defn render-static-file
  [filename]
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

           (POST "/me/invite-users" [] invite-users-handler)
           (POST "/me/delete-account" [] delete-account-handler)
           (POST "/me/checkout/create-session" [] create-session-handler)
           (POST "/me/checkout/cancel-subscription" [] cancel-subscription-handler)))


(defroutes
  static-routes
  (resources "/" {:root (profile/get-config :static-root)})
  (GET "*" [] (render-static-file "index.html")))

;; ------------------------------------------------------------------------------
;; Misc helpers

(defn notif-emails
  "Quick fn to get all user emails, if I need to send them an update"
  []
  (->> (db/get-all-users)
       (map :email)
       (remove (profile/get-secret :emails :notif-ignore))))

;; ------------------------------------------------------------------------------
;; Off- topic: Stopa's other notifications

(defn community-review-period []
  (let [now (pst-now)
        first-date-of-the-month
        (.adjustInto (LocalTime/of 8 0 0)
                     (.withDayOfMonth now 1))
        period (chime-core/periodic-seq
                 first-date-of-the-month
                 (Period/ofDays 1))
        friday? (comp #{DayOfWeek/FRIDAY}
                      #(.getDayOfWeek %))
        month #(.getMonth %)
        take-every-other (partial take-nth 2)]
    (->> period
         (filter friday?)
         (partition-by month)
         (mapcat take-every-other)
         (filter #(.isAfter % now)))))


(defn content-review-community
  "Doing a small test, where I get a 1 / 3 week notification, asking me to
   review how my community is doing."
  []
  {:from "Community Assistant <community-assistant@mg.journaltogether.com>"
   :to (profile/get-secret :emails :ceo)
   :subject "Hey Stopa, how's the community doing?"
   :html
   (str
     "<p>Howdy :)</p>"
     "<p>How has your community been? Let's engage, help, share in the journey of life -- that's what it's all about at the end of the day.</p>"
     "<p>"
     "1. Open this "
     "<a href=\"" (profile/get-secret :crm :execution-link) "\">" "execution doc" "</a>"
     "</p>"
     "<p>"
     "2. And this "
     "<a href=\"" (profile/get-secret :crm :database-link) "\">" "community sheet" "</a>"
     "</p>"
     "<p>Then get rollin!</p>")})


(defn handle-community-review
  [_]
  (send-email (content-review-community)))

;; ------------------------------------------------------------------------------
;; Main

(defn -main
  []
  (let [chime-error-handler (fn [e]
                              (log/errorf e "[schedule] error running schedule"))]
    (Thread/setDefaultUncaughtExceptionHandler
      (reify Thread$UncaughtExceptionHandler
        (uncaughtException
          [_ thread e]
          (log/error e "Uncaught exception on" (.getName thread)))))
    (db/init)
    (set! (. Stripe -apiKey) (profile/get-secret :stripe :secret-key))
    (chime-core/chime-at
      (hourly-period)
      handle-reminder
      {:error-handler chime-error-handler})
    (chime-core/chime-at
      (hourly-period)
      handle-summary
      {:error-handler chime-error-handler})
    (chime-core/chime-at
      (community-review-period)
      handle-community-review
      {:error-handler chime-error-handler})
    (let [port (profile/get-config :port)
          app (routes
                (-> webhook-routes
                    wrap-json-response
                    wrap-errors)
                (-> api-routes
                    wrap-keyword-params
                    wrap-params
                    wrap-multipart-params
                    (wrap-json-body {:keywords? true})
                    wrap-json-response
                    (wrap-cors :access-control-allow-origin [#".*"]
                               :access-control-allow-methods [:get :put :post :delete])
                    wrap-errors)
                static-routes)]
      (jetty/run-jetty app {:port port}))
    (log/info "kicked off!")))

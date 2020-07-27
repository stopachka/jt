(ns jt.db
  (:require
    [clojure.walk :refer [stringify-keys]]
    [jt.concurrency :refer [fut-bg throwable-promise]]
    [jt.profile :as profile])
  (:import
    (com.google.auth.oauth2
      ServiceAccountCredentials)
    (com.google.firebase
      FirebaseApp
      FirebaseOptions$Builder)
    (com.google.firebase.auth
      FirebaseAuth
      FirebaseAuthException
      UserRecord$CreateRequest)
    (com.google.firebase.database
      DatabaseReference
      DatabaseReference$CompletionListener
      FirebaseDatabase
      Query
      ValueEventListener)
    (com.stripe.model
      Customer)
    (java.time
      ZonedDateTime)
    (java.time.format
      DateTimeFormatter)))


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


(defn firebase-ref
  [path]
  (-> (FirebaseDatabase/getInstance)
      (.getReference path)))


(defn firebase-save
  [^DatabaseReference ref v]
  @(throwable-promise
     (fn [resolve reject]
       (-> ref
           (.setValue
             (stringify-keys v)
             (reify DatabaseReference$CompletionListener
               (onComplete
                 [_this err ref]
                 (if err (reject (.toException err))
                     (resolve ref)))))))))


(defn firebase-fetch
  [^Query ref]
  @(throwable-promise
     (fn [resolve reject]
       (-> ref
           (.addListenerForSingleValueEvent
             (reify ValueEventListener
               (onDataChange
                 [_this s]
                 (resolve (->> s .getValue ->clj)))

               (onCancelled
                 [_this err]
                 (reject (.toException err)))))))))

;; ------------------------------------------------------------------------------
;; payment-info

(defn payment-info-path
  [uid]
  (str "/users/" uid "/payment-info/"))


(defn save-payment-info
  [uid payment-info]
  (firebase-save (firebase-ref (payment-info-path uid)) payment-info))


(defn get-payment-info
  [uid]
  (firebase-fetch (firebase-ref (payment-info-path uid))))


(defn create-payment-info
  [{:keys [uid email] :as _user}]
  (let [cus (Customer/create {"email" email})]
    (save-payment-info uid {:customer-id (.getId cus)})))


(defn delete-payment-info
  [uid]
  (when-let [cus (some-> (get-payment-info uid)
                         :customer-id
                         Customer/retrieve)]
    (.delete cus))
  (save-payment-info uid nil))

;; ------------------------------------------------------------------------------
;; users

(defn- user-record->map
  [x]
  {:uid (.getUid x)
   :email (.getEmail x)})


(defn create-user
  [email]
  (let [user (-> (FirebaseAuth/getInstance)
                 (.createUser (-> (UserRecord$CreateRequest.)
                                  (.setEmail email)
                                  (.setEmailVerified true)))
                 user-record->map)]
    (create-payment-info user)
    user))


(defn get-user-by-email
  [email]
  (try
    (-> (FirebaseAuth/getInstance)
        (.getUserByEmail email)
        user-record->map)
    (catch FirebaseAuthException e
      (if (= (.getErrorCode e) "user-not-found")
        nil
        (throw e)))))


(defn get-user-by-uid
  [uid]
  (-> (FirebaseAuth/getInstance)
      (.getUser uid)
      user-record->map))


(defn create-token-for-uid
  [uid]
  (-> (FirebaseAuth/getInstance)
      (.createCustomToken uid)))


(defn get-user-from-id-token
  [token]
  (-> (FirebaseAuth/getInstance)
      (.verifyIdToken token)
      user-record->map))


(defn get-all-users
  []
  (loop [ret []
         page (-> (FirebaseAuth/getInstance)
                  (.listUsers nil))]
    (if page
      (recur (into
               ret
               (map user-record->map (.getValues page)))
             (.getNextPage page))
      ret)))


(defn get-user-by-customer-id
  [customer-id]
  (let [id-kws (-> (firebase-ref "/users/")
                   (.orderByChild "/payment-info/customer-id")
                   (.equalTo customer-id)
                   firebase-fetch
                   keys)
        _ (assert (= (count id-kws) 1)
                  (format "expected 1 uid, got %s" id-kws))
        user (-> id-kws
                 first
                 name
                 get-user-by-uid)]
    user))


(defn delete-user
  [uid]
  (-> (FirebaseAuth/getInstance)
      (.deleteUser uid)))


(defn save-user-level
  [uid level]
  (firebase-save
    (firebase-ref (str "/users/" uid "/level/"))
    (name level)))

;; ------------------------------------------------------------------------------
;; time-prefs

(def time-pref-root "/time-prefs")

(defn get-user-ids-by-time-pref
  "Gets all the user that have a certain time pref.
  i.e [:send-reminder 8]
  Returns all users who want a reminder at 8am"
  [k h]
  (let [time-prefs (-> (FirebaseDatabase/getInstance)
                       (.getReference "/time-prefs")
                       (.orderByChild (name k))
                       (.equalTo (* 1.0 h))
                       firebase-fetch
                       keys)]
    (map name time-prefs)))

(defn get-users-by-time-pref [k h]
  (pmap
    get-user-by-uid
    (get-user-ids-by-time-pref k h)))

(defn get-groups-by-time-pref [k h]
  (pmap
    (get-group-by-)))
;; ------------------------------------------------------------------------------
;; groups

(def group-root "/groups/")


(defn get-all-groups
  []
  (firebase-fetch (firebase-ref group-root)))


(defn get-group-by-id
  [id]
  (firebase-fetch (firebase-ref (str group-root id))))


(defn add-user-to-group
  [{:keys [uid email]} group-id]
  (firebase-save
    (firebase-ref (str "/groups/" group-id "/users/" uid "/email"))
    email)
  (firebase-save
    (firebase-ref (str "/users/" uid "/groups/" group-id))
    true))


(defn user-belongs-to-group?
  [group user]
  (let [uid-kw (-> user :uid keyword)]
    (-> group :users uid-kw boolean)))


(defn get-user-groups
  [uid]
  (let [group-kws (-> (firebase-ref (str "/users/" uid "/groups/"))
                      firebase-fetch
                      keys)]
    (map name group-kws)))


(defn remove-user-from-group
  [uid group-id]
  (firebase-save
    (firebase-ref (str "/groups/" group-id "/users/" uid))
    nil)
  (firebase-save
    (firebase-ref (str "/users/" uid "/groups/" group-id))
    nil))


(defn delete-group-memberships
  [uid]
  (let [group-ids (get-user-groups uid)]
    (doall
      (pmap (partial remove-user-from-group uid) group-ids))))

;; ------------------------------------------------------------------------------
;; invitations

(def invitation-root "/invitations/")


(defn get-invitation-by-id
  [id]
  (firebase-fetch (firebase-ref (str invitation-root id))))


(defn delete-invitation
  [id]
  (firebase-save (firebase-ref (str invitation-root id)) nil))

;; ------------------------------------------------------------------------------
;; magic codes

(def magic-code-root "/magic-codes/")


(defn- magic-code-path
  [code]
  (str magic-code-root code))


(defn create-magic-code
  [{:keys [email invitations]}]
  (let [key (-> (firebase-ref magic-code-root)
                .push
                (firebase-save {:at (str (System/currentTimeMillis))
                                :email email
                                :invitations invitations})
                .getKey)]
    {:key key}))


(defn- get-magic-code
  [code]
  (firebase-fetch (firebase-ref (magic-code-path code))))


(defn- kill-magic-code
  [code]
  (firebase-save (firebase-ref (magic-code-path code)) nil))


(defn consume-magic-code
  [code]
  (when-let [res (get-magic-code code)]
    (kill-magic-code code)
    res))

;; ------------------------------------------------------------------------------
;; entries

(defn ->epoch-milli
  [zoned-date]
  (-> zoned-date
      .toInstant
      .toEpochMilli))


(def entry-keys
  #{:sender :subject :stripped-text :stripped-html
    :recipient :body-html :body-plain :date})


(def entry-date-formatter
  (-> "EEE, d LLL yyyy HH:mm:ss ZZ"
      DateTimeFormatter/ofPattern))


(defn save-entry
  [uid {:keys [date] :as entry}]
  (firebase-save
    (firebase-ref (str "/entries/" uid "/" (->epoch-milli date)))
    (-> entry
        (select-keys entry-keys)
        (update :date #(.format entry-date-formatter %)))))


(defn get-entries-between
  [uid start-date end-date]
  (let [entries (-> (firebase-ref (str "/entries/" uid "/"))
                    .orderByKey
                    (.startAt (str (->epoch-milli start-date)))
                    (.endAt (str (->epoch-milli end-date)))
                    firebase-fetch)
        ->entry (fn [x]
                  (update x :date #(ZonedDateTime/parse % entry-date-formatter)))]
    (->> entries
         (sort-by first)
         (map (comp ->entry second)))))


(defn delete-entries
  [uid]
  (firebase-save
    (firebase-ref (str "/entries/" uid "/"))
    nil))

;; ------------------------------------------------------------------------------
;; init

(defn init
  []
  (let [{:keys [db-url auth project-id]} (profile/get-config :firebase)
        {:keys
         [client-id
          client-email
          private-key
          private-key-id]} (profile/get-secret :firebase)
        creds (ServiceAccountCredentials/fromPkcs8
                client-id
                client-email
                private-key
                private-key-id
                [])
        options (-> (FirebaseOptions$Builder.)
                    (.setCredentials creds)
                    (.setProjectId project-id)
                    (.setDatabaseUrl db-url)
                    (.setDatabaseAuthVariableOverride
                      (stringify-keys auth))
                    .build)]
    (FirebaseApp/initializeApp options)))

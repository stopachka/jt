(ns jt.db
  (:require [jt.concurrency :refer [fut-bg throwable-promise]]
            [clojure.walk :refer [stringify-keys]]
            [clojure.string :as str])
  (:import (com.google.auth.oauth2 ServiceAccountCredentials)
           (com.google.firebase FirebaseOptions$Builder FirebaseApp)
           (com.google.firebase.database
             FirebaseDatabase
             ValueEventListener
             DatabaseReference$CompletionListener DatabaseReference Query)
           (com.google.firebase.auth
             FirebaseAuth UserRecord$CreateRequest FirebaseAuthException)
           (com.stripe.model Customer)
           (java.time.format DateTimeFormatter)
           (java.time LocalDate ZonedDateTime ZoneId)))

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


(defn firebase-ref [path]
  (-> (FirebaseDatabase/getInstance)
      (.getReference path)))

(defn firebase-save [^DatabaseReference ref v]
  @(throwable-promise
     (fn [resolve reject]
       (-> ref
           (.setValue
             (stringify-keys v)
             (reify DatabaseReference$CompletionListener
               (onComplete [_this err ref]
                 (if err (reject (.toException err))
                         (resolve ref)))))))))

(defn firebase-fetch [^Query ref]
  @(throwable-promise
     (fn [resolve reject]
       (-> ref
           (.addListenerForSingleValueEvent
             (reify ValueEventListener
               (onDataChange [_this s]
                 (resolve (->> s .getValue ->clj)))
               (onCancelled [_this err]
                 (reject (.toException err)))))))))

;; ------------------------------------------------------------------------------
;; payment-info

(defn payment-info-path [uid]
  (str "/users/" uid "/payment-info/"))

(defn save-payment-info [uid payment-info]
  (firebase-save (firebase-ref (payment-info-path uid)) payment-info))

(defn get-payment-info [uid]
  (firebase-fetch (firebase-ref (payment-info-path uid))))

(defn create-payment-info [{:keys [uid email] :as _user}]
  (let [cus (Customer/create {"email" email})]
    (save-payment-info uid {:customer-id (.getId cus)})))

;; ------------------------------------------------------------------------------
;; users

(defn- user-record->map [x]
  {:uid (.getUid x)
   :email (.getEmail x)})

(defn create-user [email]
  (let [user (-> (FirebaseAuth/getInstance)
                          (.createUser (-> (UserRecord$CreateRequest.)
                                           (.setEmail email)
                                           (.setEmailVerified true)))
                          user-record->map)]
    (create-payment-info user)
    user))

(defn get-user-by-email [email]
  (try
    (-> (FirebaseAuth/getInstance)
        (.getUserByEmail email)
        user-record->map)
    (catch FirebaseAuthException e
      (if (= (.getErrorCode e) "user-not-found")
        nil
        (throw e)))))

(defn get-user-by-uid [uid]
  (-> (FirebaseAuth/getInstance)
      (.getUser uid)
      user-record->map))

(defn create-token-for-uid [uid]
  (-> (FirebaseAuth/getInstance)
      (.createCustomToken uid)))

(defn get-user-from-id-token [token]
  (-> (FirebaseAuth/getInstance)
      (.verifyIdToken token)
      user-record->map))

(defn get-user-by-customer-id [customer-id]
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

(defn save-user-level [uid level]
  (firebase-save
    (firebase-ref (str "/users/" uid "/level/"))
    (name level)))

;; ------------------------------------------------------------------------------
;; groups

(def group-root "/groups/")
(defn get-group-by-id [id]
  (firebase-fetch (firebase-ref (str group-root id))))

(defn add-user-to-group [group-id {:keys [uid email]}]
  (let [add-user-to-group-fut (firebase-save
                                (firebase-ref (str "/groups/" group-id "/users/" uid "/email"))
                                email)
        add-group-to-user-fut (firebase-save
                                (firebase-ref (str "/users/" uid "/groups/" group-id))
                                true)]
    @add-user-to-group-fut
    @add-group-to-user-fut))

(defn user-belongs-to-group? [group user]
  (let [uid-kw (-> user :uid keyword)]
    (-> group :users uid-kw boolean)))

;; ------------------------------------------------------------------------------
;; invitations

(def invitation-root "/invitations/")

(defn get-invitation-by-id [id]
  (firebase-fetch (firebase-ref (str invitation-root id))))

(defn delete-invitation [id]
  (firebase-save (firebase-ref (str invitation-root id)) nil))

;; ------------------------------------------------------------------------------
;; magic codes

(def magic-code-root "/magic-codes/")
(defn- magic-code-path [code] (str magic-code-root code))

(defn create-magic-code [{:keys [email invitations]}]
  (let [key (-> (firebase-ref magic-code-root)
                .push
                (firebase-save {:at (str (System/currentTimeMillis))
                                :email email
                                :invitations invitations})
                .getKey)]
    {:key key}))

(defn- get-magic-code [code]
  (firebase-fetch (firebase-ref (magic-code-path code))))

(defn- kill-magic-code [code]
  (firebase-save (firebase-ref (magic-code-path code)) nil))

(defn consume-magic-code [code]
  (when-let [res (get-magic-code code)]
    (kill-magic-code code)
    res))

;; ------------------------------------------------------------------------------
;; migrate-journals

(def journal-keys #{:sender :subject :stripped-text :stripped-html
                    :recipient :body-html :body-plain :at-ms})

(defn save-journal [uid {:keys [at-ms] :as j}]
  (firebase-save
    (firebase-ref (str "/users/" uid "/journals/" at-ms))
    (select-keys j journal-keys)))

(defn email->id [email]
  (-> email
      (str/replace #"\." "-")
      (str/replace #"@" "_")))

(defn migrate-journal [{:keys [uid email] :as _user}]
  (let [old-journals (firebase-fetch (firebase-ref (str "/journals/" (email->id email))))
        ->new-journal (fn [[k j]]
                        (let [at-ms (or (:at j)
                                        (-> (LocalDate/parse (name k))
                                            (.atTime 17 0)
                                            (ZonedDateTime/of (ZoneId/of "America/Los_Angeles"))
                                            .toInstant
                                            .toEpochMilli))]
                          (-> j
                              (assoc :at-ms at-ms))))]
    (->> old-journals
         (pmap (comp (partial save-journal uid) ->new-journal)))))

;; ------------------------------------------------------------------------------
;; init

(defn init [config secrets]
  (let [{:keys [db-url auth project-id]} (:firebase config)
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
                    (.setProjectId project-id)
                    (.setDatabaseUrl db-url)
                    (.setDatabaseAuthVariableOverride
                      (stringify-keys auth))
                    .build)]
    (FirebaseApp/initializeApp options)))

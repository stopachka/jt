(ns jt.db
  (:require [jt.concurrency :refer [fut-bg throwable-promise]]
            [clojure.walk :refer [stringify-keys]])
  (:import (com.google.auth.oauth2 ServiceAccountCredentials)
           (com.google.firebase FirebaseOptions$Builder FirebaseApp)
           (com.google.firebase.database
             FirebaseDatabase
             ValueEventListener
             DatabaseReference$CompletionListener DatabaseReference)
           (com.google.firebase.auth UserRecord FirebaseAuth UserRecord$CreateRequest FirebaseAuthException)))

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
  (throwable-promise
    (fn [resolve reject]
      (-> ref
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

;; ------------------------------------------------------------------------------
;; users

(defn- user-record->map [^UserRecord x]
  {:uid (.getUid x)
   :email (.getEmail x)})

(defn create-user! [email]
  (-> (FirebaseAuth/getInstance)
      (.createUser (-> (UserRecord$CreateRequest.)
                       (.setEmail email)
                       (.setEmailVerified true)))
      user-record->map))

(defn get-user-by-email! [email]
  (try
    (-> (FirebaseAuth/getInstance)
        (.getUserByEmail email)
        user-record->map)
    (catch FirebaseAuthException e
      (if (= (.getErrorCode e) "user-not-found")
        nil
        (throw e)))))

(defn get-user-by-uid! [uid]
  (-> (FirebaseAuth/getInstance)
      (.getUser uid)
      user-record->map))

(defn create-token-for-uid! [uid]
  (-> (FirebaseAuth/getInstance)
      (.createCustomToken uid)))

;; ------------------------------------------------------------------------------
;; magic codes

(def magic-code-root "/magic-codes/")
(defn- magic-code-path [code] (str magic-code-root code))

(defn create-magic-code! [email]
  (let [key (-> (firebase-ref magic-code-root)
                .push
                (firebase-save {:at (str (System/currentTimeMillis))
                                :email email})
                deref
                .getKey)]
    {:key key}))

(defn- get-magic-code [code]
  (firebase-fetch (magic-code-path code)))

(defn- kill-magic-code [code]
  (firebase-save (firebase-ref (magic-code-path code)) nil))

(defn consume-magic-code [code]
  (future
    (when-let [res @(get-magic-code code)]
      (fut-bg @(kill-magic-code code))
      res)))

;; ------------------------------------------------------------------------------
;; groups

(defn create-group [group-name {:keys [uid email] :as _user}]
  (let [group-ref (-> (firebase-ref "/groups")
                      .push)
        group-id (.getKey group-ref)
        save-group-fut (firebase-save
                         group-ref
                         {:name group-name
                          :users {uid {:email email}}})
        update-user-fut (firebase-save
                          (firebase-ref (str "/users/" uid "/groups/" group-id))
                          true)]
    (future
      @save-group-fut
      @update-user-fut)))

(defn remove-member-from-group [group-id uid]
  (let [remove-from-group-fut (firebase-save
                                (firebase-ref (str "/groups/" group-id "/users/" uid))
                                nil)
        remove-from-user-fut (firebase-save
                               (firebase-ref (str "/users/" uid "/groups/" group-id))
                               nil)]
    (future
      @remove-from-group-fut
      @remove-from-user-fut)))

(defn add-member-to-group [group-id {:keys [uid email]}]
  (let [add-user-to-group-fut (firebase-save
                                (firebase-ref (str "/groups/" group-id "/users/" uid))
                                {:email email})
        add-group-to-user-fut (firebase-save
                                (firebase-ref (str "/users/" uid "/groups/" group-id))
                                nil)]
    (future
      @add-user-to-group-fut
      @add-group-to-user-fut)))

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

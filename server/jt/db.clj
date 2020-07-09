(ns jt.db
  (:require [clojure.walk :refer [stringify-keys]])
  (:import (com.google.auth.oauth2 ServiceAccountCredentials)
           (com.google.firebase FirebaseOptions$Builder FirebaseApp)
           (com.google.firebase.database
             FirebaseDatabase
             ValueEventListener
             DatabaseReference$CompletionListener)
           (clojure.lang IDeref)))

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
                      (deref [_this]
                        (let [[err res] @p]
                          (if err (throw err) res))))]
    (f resolve reject)
    throwable-p))

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

(defn firebase-init [config secrets]
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

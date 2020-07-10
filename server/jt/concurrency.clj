(ns jt.concurrency
  (:require [clojure.tools.logging :as log])
  (:import (clojure.lang IDeref)))

(defmacro fut-bg
  "Futures only throw when de-referenced. fut-bg writes a future
  with a top-level try-catch, so you can run code asynchronously,
  without _ever_ de-referencing them"
  [& forms]
  `(future
     (try
       ~@forms
       (catch Exception e#
         (log/errorf e# "uh-oh, failed to run async function %s" '~forms)
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
                      (deref [_this]
                        (let [[err res] @p]
                          (if err (throw err) res))))]
    (f resolve reject)
    throwable-p))

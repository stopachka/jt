(ns jt.profile
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]))

(def env
  (if (= "true" (System/getenv "PRODUCTION"))
    :prod
    :dev))

(def file-sources
  {:secrets {:dev "profile/secrets-development.edn"
             :prod "profile/secrets-production.edn"}
   :config {:dev "profile/config-development.edn"
            :prod "profile/config-production.edn"}})

(defn read-edn-resource
  "Transforms a resource in our classpath to edn"
  [path]
  (-> path
      io/resource
      slurp
      edn/read-string))

(def config (read-edn-resource (-> file-sources :config env)))
(def secrets (read-edn-resource (-> file-sources :config env)))

(defn get-secret [& ks]
  (get-in config ks))

(defn get-config [& ks]
  (get-in secrets ks))

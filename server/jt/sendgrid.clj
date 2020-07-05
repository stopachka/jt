(ns jt.sendgrid
  (:require [clj-http.client :as http-client]
            [clojure.data.json :refer [write-str]]
            [clojure.walk :refer [stringify-keys]]))

(defn api-url [part]
  (str "https://api.sendgrid.com/v3" part))

(defn send-email
  [api-token
   {:keys [from to subject html]}]
  (let [->coll #(if (coll? %) % [%])
        ->email-map (fn [x] {:email x})
        json-body {:personalizations [{:to (map ->email-map (->coll to))}]
                   :from (->email-map from)
                   :subject subject
                   :content [{:type "text/html" :value html}]}
        request {:content-type :json
                 :headers
                 {:authorization (str "Bearer " api-token)}
                 :body
                 (write-str json-body)}]
    (http-client/post (api-url "/mail/send") request)))



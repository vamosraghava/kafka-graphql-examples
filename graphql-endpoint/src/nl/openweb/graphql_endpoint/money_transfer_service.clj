(ns nl.openweb.graphql-endpoint.money-transfer-service
  (:require [com.stuartsierra.component :as component]
            [nl.openweb.topology.clients :as clients]
            [nl.openweb.topology.value-generator :refer [bytes->uuid uuid->bytes]]
            [clojure.tools.logging :as log])
  (:import (org.apache.kafka.clients.consumer ConsumerRecord)
           (nl.openweb.data Uuid MoneyTransferConfirmed MoneyTransferFailed MoneyTransferConfirmed ConfirmMoneyTransfer)
           (java.util UUID)))

(def command-topic (or (System/getenv "KAFKA_COMMAND_TOPIC") "commands"))
(def mtf-topic (or (System/getenv "KAFKA_MTF_TOPIC") "money_transfer_feedback"))
(def client-id (or (System/getenv "KAFKA_CLIENT_ID") "graphql-endpoint-money-transfer"))

(defn v->map
  [v]
  (cond
    (instance? MoneyTransferConfirmed v)
    {:uuid    (str (bytes->uuid (.bytes (.getId v))))
     :success true
     :reason  nil}
    (instance? MoneyTransferFailed v)
    {:uuid    (str (bytes->uuid (.bytes (.getId v))))
     :success false
     :reason  (.getReason v)}))

(defn handle-reply
  [^ConsumerRecord cr subscriptions]
  (let [v-map (v->map (.value cr))]
    (doseq [[s-id source-stream] (vals (:map @subscriptions))]
      (when (= s-id (:uuid v-map))
        (source-stream v-map)))))

(defn add-stream
  [subscriptions id source-stream]
  (let [new-id (inc (:id subscriptions))]
    (-> subscriptions
        (assoc :id new-id)
        (assoc-in [:map new-id] [id source-stream]))))

(defn add-sub
  [subscriptions id source-stream]
  (let [new-subscriptions (swap! subscriptions add-stream id source-stream)]
    (:id new-subscriptions)))

(defn create-money-transfer
  [^UUID uuid args]
  (let [id (Uuid. (uuid->bytes uuid))]
    (ConfirmMoneyTransfer.
      id
      (:token args)
      (long (:amount args))
      (:from args)
      (:to args)
      (:descr args))))

(defn money-transfer
  [db source-stream args]
  (try
    (let [uuid-arg (:uuid args)
          uuid (UUID/fromString uuid-arg)
          sub-id (add-sub (:subscriptions db) uuid-arg source-stream)]
      (clients/produce (get-in db [:kafka-producer :producer]) command-topic (:username args) (create-money-transfer uuid args))
      sub-id)
    (catch IllegalArgumentException e (log/warn (:uuid args) "is not valid" e))))

(defrecord MoneyTransferService []

  component/Lifecycle

  (start [this]
    (let [subscriptions (atom {:id 0 :map {}})
          stop-consume-f (clients/consume client-id client-id mtf-topic #(handle-reply % subscriptions))]
      (-> this
          (assoc :subscriptions subscriptions)
          (assoc :stop-consume stop-consume-f))))

  (stop [this]
    ((:stop-consume this))
    (doseq [[_ source-stream] (vals (:map @(:subscriptions this)))]
      (source-stream nil))
    (-> this
        (assoc :subscriptions nil)
        (assoc :stop-consume nil))))

(defn new-service
  []
  {:money-transfer-service (-> {}
                               map->MoneyTransferService
                               (component/using [:kafka-producer]))})

(defn stop-transaction-subscription
  [db id]
  (let [source-stream (second (get (:map @(:subscriptions db)) id))]
    (when source-stream
      (source-stream nil)
      (swap! (:subscriptions db) #(update % :map dissoc id)))))

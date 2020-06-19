(ns ziggurat.messaging.rabbitmq.consumer-test
  (:require [clojure.test :refer :all]
            [ziggurat.fixtures :as fix]
            [ziggurat.config :refer [config]]
            [ziggurat.messaging.rabbitmq-wrapper :as rmqw]
            [ziggurat.messaging.rabbitmq.consumer :as rmq-cons]
            [ziggurat.messaging.rabbitmq-wrapper :refer [connection]]
            [ziggurat.messaging.rabbitmq.producer :as rmq-producer]
            [langohr.basic :as lb]
            [taoensso.nippy :as nippy]
            [langohr.channel :as lch])
  (:import (com.rabbitmq.client Channel Connection)
           (java.io Closeable)))

(use-fixtures :once (join-fixtures [fix/init-rabbit-mq
                                    fix/silence-logging]))

(defn- create-mock-channel [] (reify Channel
                                (close [_] nil)))

(def message-payload {:foo "bar"})

(deftest ^:integration start-subscriber-test
  (testing "It should start a RabbitMQ subscriber and consume a message from the instant queue"
    (let [queue-name               "instant-queue-test"
          exchange-name            "instant-queue-exchange"
          is-mocked-mpr-fn-called? (atom false)
          mock-mapper-fn           (fn [message]
                                     (when (= message-payload message)
                                       (reset! is-mocked-mpr-fn-called? true)))]
      (rmq-producer/create-and-bind-queue connection queue-name exchange-name false)
      (rmq-producer/publish connection exchange-name message-payload nil)
      (rmqw/start-subscriber 1 mock-mapper-fn queue-name)
      (Thread/sleep 5000)
      (is (true? @is-mocked-mpr-fn-called?)))))

(deftest consume-message-test
  (testing "It should not call the lb/ack function if ack? is false"
    (let [is-ack-called? (atom false)]
      (with-redefs [rmq-cons/ack-message (fn [_ _] (reset! is-ack-called? true))
                    nippy/thaw           (constantly 1)]
        (rmq-cons/consume-message nil {} (byte-array 12345) false))
      (is (false? @is-ack-called?))))

  (testing "It should call the lb/ack function if ack? is true and return the deserialized message"
    (let [is-ack-called?   (atom false)
          is-nippy-called? (atom false)]
      (with-redefs [rmq-cons/ack-message (fn ([^Channel _ ^long _]
                                              (reset! is-ack-called? true)))
                    nippy/thaw           (fn [payload]
                                           (when (= message-payload payload)
                                             (reset! is-nippy-called? true))
                                           1)]
        (let [deserialized-message (rmq-cons/consume-message nil {:delivery-tag 12345} message-payload true)]
          (is (= deserialized-message 1))))
      (is (true? @is-ack-called?))
      (is (true? @is-nippy-called?))))

  (testing "It should call the lb/reject function if ack? is false and nippy throws an error"
    (let [is-reject-called? (atom false)]
      (with-redefs [lb/reject  (fn [^Channel _ ^long _ ^Boolean _] (reset! is-reject-called? true))
                    nippy/thaw (fn [_] (throw (Exception. "Deserializaion error")))]
        (let [deserialized-message (rmq-cons/consume-message nil {:delivery-tag 12345} (byte-array 12345) false)]
          (is (= deserialized-message nil))))
      (is (true? @is-reject-called?))))

  (testing "It should call the lb/reject function if ack? is true and lb/ack function function fails to ack the message"
    (let [is-reject-called? (atom false)]
      (with-redefs [lb/reject            (fn [^Channel _ ^long _ ^Boolean _] (reset! is-reject-called? true))
                    rmq-cons/ack-message (fn [^Channel _ ^long _]
                                           (throw (Exception. "ack error")))
                    nippy/thaw           (constantly 1)]
        (let [deserialized-message (rmq-cons/consume-message nil {:delivery-tag 12345} (byte-array 12345) true)]
          (is (= deserialized-message nil))))
      (is (true? @is-reject-called?)))))

(deftest get-messages-from-queue-test
  (testing "It should return `count` number of messages from the specified queue"
    (let [count    5
          messages (repeat count message-payload)]
      (with-redefs [lb/get                   (fn [^Channel _ ^String _ ^Boolean _]
                                               [1 message-payload])
                    lch/open                 (fn [^Connection _] (create-mock-channel))
                    rmq-cons/consume-message (fn [_ _ ^bytes _ _] message-payload)]
        (let [consumed-messages (rmq-cons/get-messages-from-queue nil "test-queue" true count)]
          (is (= consumed-messages messages))))))

  (testing "It should return `count` number of nils from the specified queue if the payload is empty"
    (let [count    5
          messages (repeat count nil)]
      (with-redefs [lb/get                   (fn [^Channel _ ^String _ ^Boolean _]
                                               [1 nil])
                    lch/open                 (fn [^Connection _] (create-mock-channel))
                    rmq-cons/consume-message (fn [_ _ ^bytes _ _] message-payload)]
        (let [consumed-messages (rmq-cons/get-messages-from-queue nil "test-queue" true count)]
          (is (= consumed-messages messages)))))))

(deftest process-messages-from-queue-test
  (testing "The processing function should be called with the correct message and the message is acked"
    (let [count                      5
          times-processing-fn-called (atom 0)
          times-ack-called           (atom 0)
          processing-fn              (fn [message] (when (= message message-payload)
                                                     (swap! times-processing-fn-called inc)))]
      (with-redefs [lb/get                   (fn [^Channel _ ^String _ ^Boolean _]
                                               [1 message-payload])
                    lch/open                 (fn [^Connection _] (create-mock-channel))
                    rmq-cons/consume-message (fn [_ _ ^bytes payload _] payload)
                    rmq-cons/ack-message     (fn [_ _] (swap! times-ack-called inc))]
        (rmq-cons/process-messages-from-queue nil "test-queue" count processing-fn))
      (is (= @times-processing-fn-called count))
      (is (= @times-ack-called count))))

  (testing "It should call the lb/reject function when the processing function throws an exception"
    (let [count                5
          reject-fn-call-count (atom 0)
          processing-fn        (fn [_] (throw (Exception. "message processing error")))]
      (with-redefs [lb/get                   (fn [^Channel _ ^String _ ^Boolean _]
                                               [1 message-payload])
                    lch/open                 (fn [^Connection _] (create-mock-channel))
                    rmq-cons/reject-message  (fn [_ _ _] (swap! reject-fn-call-count inc))
                    rmq-cons/consume-message (fn [_ _ ^bytes payload _] payload)
                    rmq-cons/ack-message     (fn [_ _] nil)]
        (rmq-cons/process-messages-from-queue nil "test-queue" count processing-fn))
      (is (= @reject-fn-call-count count)))))


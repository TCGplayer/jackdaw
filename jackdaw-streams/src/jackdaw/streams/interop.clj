(ns jackdaw.streams.interop
  "Clojure wrapper to kafka streams."
  (:refer-clojure :exclude [count map reduce group-by merge filter])
  (:require [clojure.string :as str]
            [jackdaw.streams :refer :all]
            [jackdaw.streams.lambdas :refer :all])
  (:import
   (java.util.regex Pattern)
   (org.apache.kafka.common.serialization Serde)
   (org.apache.kafka.streams KafkaStreams)
   (org.apache.kafka.streams.kstream KGroupedStream KGroupedTable KStream ValueJoiner
                                     Initializer Reducer Aggregator KeyValueMapper GlobalKTable
                                     KStreamBuilder KTable Predicate Windows JoinWindows)
   (org.apache.kafka.streams.processor TopologyBuilder)))

(set! *warn-on-reflection* true)

(declare clj-kstream clj-ktable clj-kgroupedtable clj-kgroupedstream clj-global-ktable)

(def ^:private kstream-memo
  "Returns a kstream for the topic, creating a new one if needed."
  (memoize
   (fn [topology-builder
        {:keys [topic.metadata/name jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
     (clj-kstream
      (.stream ^KStreamBuilder topology-builder
               ^Serde key-serde
               ^Serde value-serde
               ^"[Ljava.lang.String;" (into-array String [name]))))))

(def ^:private kstream-memo-patterned
  "Returns a kstream for the topic, creating a new one if needed."
  (memoize
   (fn [topology-builder
        {:keys [jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}
        topic-pattern]
     (clj-kstream
      (.stream ^KStreamBuilder topology-builder
               ^Serde key-serde
               ^Serde value-serde
               ^Pattern topic-pattern)))))

(def ^:private ktable-memo
  "Returns a ktable for the topic, creating a new one if needed."
  (memoize
   (fn [topology-builder
        {:keys [topic.metadata/name jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}
        store-name]
     (clj-ktable
      (.table ^KStreamBuilder topology-builder key-serde value-serde name store-name)))))

(deftype CljKStreamBuilder [^KStreamBuilder topology-builder]
  ITopologyBuilder
  (merge
    [_ kstreams]
    (clj-kstream
     (.merge topology-builder
             (into-array KStream (mapv kstream* kstreams)))))

  (new-name
    [_ prefix]
    (.newName topology-builder prefix))

  (kstream
    [_ topic-config]
    (kstream-memo topology-builder topic-config))

  (kstream
    [_ topic-config topic-pattern]
    (kstream-memo-patterned topology-builder topic-config topic-pattern))

  (kstreams
    [_ topic-configs]
    (clj-kstream
     (let [topic-names (clojure.core/map :topic.metadata/name topic-configs)]
       (.stream topology-builder
                ^"[Ljava.lang.String;" (into-array String topic-names)))))

  (ktable
    [_ {:keys [topic.metadata/name] :as topic-config}]
    (ktable-memo topology-builder topic-config name))

  (ktable
    [_ topic-config store-name]
    (ktable-memo topology-builder topic-config store-name))

  (global-ktable [this {:keys [topic.metadata/name] :as topic-config}]
    (global-ktable this topic-config name))

  (global-ktable [_ {:keys [topic.metadata/name jackdaw.serdes/key-serde jackdaw.serdes/value-serde]} store-name]
    (clj-global-ktable
      (.globalTable ^KStreamBuilder topology-builder key-serde value-serde name store-name)))

  (source-topics
    [_]
    (let [pattern-str (.. topology-builder
                          sourceTopicPattern
                          pattern)]
      (into #{} (str/split pattern-str #"\|"))))

  (topology-builder*
    [_]
    topology-builder))

(defn topology-builder
  "Makes a kstream builder."
  []
  (CljKStreamBuilder. (KStreamBuilder.)))

(deftype CljKStream [^KStream kstream]
  IKStreamBase
  (left-join
    [_ ktable value-joiner-fn]
    (clj-kstream
     (.leftJoin kstream
                (ktable* ktable)
                (value-joiner value-joiner-fn))))

  (left-join
    [_ ktable value-joiner-fn {:keys [jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
    (clj-kstream
     (.leftJoin kstream
                (ktable* ktable)
                (value-joiner value-joiner-fn)
                key-serde
                value-serde)))

  (for-each!
    [_ foreach-fn]
    (.foreach kstream (foreach-action foreach-fn))
    nil)

  (filter
    [_ predicate-fn]
    (clj-kstream
     (.filter kstream (predicate predicate-fn))))

  (filter-not
    [_ predicate-fn]
    (clj-kstream
     (.filterNot kstream (predicate predicate-fn))))

  (group-by
    [_ key-value-mapper-fn]
    (clj-kgroupedstream
     (.groupBy kstream (select-key-value-mapper key-value-mapper-fn))))

  (group-by
    [_ key-value-mapper-fn {:keys [jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
    (clj-kgroupedstream
     (.groupBy kstream
               (select-key-value-mapper key-value-mapper-fn)
               key-serde
               value-serde)))

  (map-values
    [_ value-mapper-fn]
    (clj-kstream
     (.mapValues kstream (value-mapper value-mapper-fn))))

  (print!
    [_]
    (.print kstream)
    nil)

  (print!
    [_ {:keys [jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
    (.print kstream key-serde value-serde)
    nil)

  (through
    [_ {:keys [topic.metadata/name jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
    (clj-kstream
     (.through kstream key-serde value-serde name)))

  (through
    [_ partition-fn {:keys [topic.metadata/name jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
    (clj-kstream
     (.through kstream key-serde value-serde (stream-partitioner partition-fn) name)))

  (to!
    [_ {:keys [topic.metadata/name jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
    (.to kstream key-serde value-serde name)
    nil)

  (to!
    [_ partition-fn {:keys [topic.metadata/name jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
    (.to kstream key-serde value-serde (stream-partitioner partition-fn) name)
    nil)

  (write-as-text!
    [_ file-path]
    (.writeAsText kstream file-path))

  (write-as-text!
    [_ file-path {:keys [jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
    (.writeAsText kstream file-path key-serde value-serde))

  IKStream
  (aggregate-by-key
    [this initializer-fn aggregator-fn topic-config]
    (-> this
        (group-by-key topic-config)
        (aggregate initializer-fn aggregator-fn topic-config)))

  (aggregate-by-key-windowed
    [this initializer-fn aggregator-fn windows topic-config]
    (-> this
        (group-by-key topic-config)
        (aggregate-windowed initializer-fn aggregator-fn windows topic-config)))

  (branch
    [_ predicate-fns]
    (mapv clj-kstream
          (->> (into-array Predicate (mapv predicate predicate-fns))
               (.branch kstream))))

  (count-by-key
    [this topic-config]
    (-> this
        (group-by-key topic-config)
        (count topic-config)))

  (count-by-key-windowed
    [this windows]
    (-> this
        (group-by-key)
        (count windows)))

  (count-by-key-windowed
    [this windows topic-config]
    (-> this
        (group-by-key topic-config)
        (count-windowed windows topic-config)))

  (flat-map
    [_ key-value-mapper-fn]
    (clj-kstream
     (.flatMap kstream (key-value-flatmapper key-value-mapper-fn))))

  (flat-map-values
    [_ value-mapper-fn]
    (clj-kstream
     (.flatMapValues kstream (value-mapper value-mapper-fn))))

  (group-by-key
    [_]
    (clj-kgroupedstream
     (.groupByKey kstream)))

  (group-by-key
    [_ {:keys [jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
    (clj-kgroupedstream
     (.groupByKey ^KStream kstream key-serde value-serde)))

  (join-windowed
    [_ other-kstream value-joiner-fn windows]
    (clj-kstream
     (.join ^KStream kstream
            ^KStream (kstream* other-kstream)
            ^ValueJoiner (value-joiner value-joiner-fn)
            ^JoinWindows windows)))

  (join-windowed
    [_ other-kstream value-joiner-fn windows
     {key-serde :jackdaw.serdes/key-serde this-value-serde :jackdaw.serdes/value-serde}
     {other-value-serde :jackdaw.serdes/value-serde}]
    (clj-kstream
     (.join kstream
            (kstream* other-kstream)
            (value-joiner value-joiner-fn)
            windows
            key-serde
            this-value-serde
            other-value-serde)))

  (left-join-windowed
    [_ other-kstream value-joiner-fn windows]
    (clj-kstream
     (.leftJoin ^KStream kstream
                ^KStream (kstream* other-kstream)
                ^ValueJoiner (value-joiner-fn)
                ^JoinWindows windows)))

  (left-join-windowed
    [_ other-kstream value-joiner-fn windows
     {:keys [jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}
     {other-value-serde :jackdaw.serdes/value-serde}]
    (clj-kstream
     (.leftJoin kstream
                (kstream* other-kstream)
                (value-joiner value-joiner-fn)
                windows
                key-serde
                value-serde
                other-value-serde)))

  (map
    [_ key-value-mapper-fn]
    (clj-kstream
     (.map kstream (key-value-mapper key-value-mapper-fn))))

  (outer-join-windowed
    [_ other-kstream value-joiner-fn windows]
    (clj-kstream
     (.outerJoin kstream
                 (kstream* other-kstream)
                 (value-joiner value-joiner-fn)
                 windows)))

  (outer-join-windowed
    [_ other-kstream value-joiner-fn windows
     {key-serde :jackdaw.serdes/key-serde value-serde :jackdaw.serdes/value-serde}
     {other-value-serde :jackdaw.serdes/value-serde}]
    (clj-kstream
     (.outerJoin kstream
                 (kstream* other-kstream)
                 (value-joiner value-joiner-fn)
                 windows
                 key-serde
                 value-serde
                 other-value-serde)))

  (process!
    [_ processor-supplier-fn state-store-names]
    (.process kstream
              (processor-supplier processor-supplier-fn)
              (into-array String state-store-names)))

  (reduce-by-key
    [this reducer-fn topic-config]
    (-> this
        (group-by-key topic-config)
        (reduce reducer-fn topic-config)))

  (reduce-by-key-windowed
    [this reducer-fn windows topic-config]
    (-> this
        (group-by-key topic-config)
        (reduce-windowed reducer-fn windows topic-config)))

  (select-key
    [_ select-key-value-mapper-fn]
    (clj-kstream
     (.selectKey kstream (select-key-value-mapper select-key-value-mapper-fn))))

  (transform
    [this transformer-supplier-fn]
    (transform this transformer-supplier-fn []))

  (transform
    [_ transformer-supplier-fn state-store-names]
    (clj-kstream
     (.transform kstream
                 (transformer-supplier transformer-supplier-fn)
                 (into-array String state-store-names))))

  (transform-values
    [this value-transformer-supplier-fn]
    (transform-values this value-transformer-supplier-fn []))

  (transform-values
    [_ value-transformer-supplier-fn state-store-names]
    (clj-kstream
     (.transformValues kstream
                       (value-transformer-supplier value-transformer-supplier-fn)
                       (into-array String state-store-names))))

  (join-global
    [_ global-kstream key-value-mapper-fn joiner-fn]
    (clj-kstream
      (.join kstream
             ^GlobalKTable (global-ktable* global-kstream)
             ^KeyValueMapper (select-key-value-mapper key-value-mapper-fn)
             ^ValueJoiner (value-joiner joiner-fn))))

  (left-join-global
    [_ global-kstream key-value-mapper-fn joiner-fn]
    (clj-kstream
      (.leftJoin kstream
                 ^GlobalKTable (global-ktable* global-kstream)
                 ^KeyValueMapper (select-key-value-mapper key-value-mapper-fn)
                 ^ValueJoiner (value-joiner joiner-fn))))

  (kstream* [_]
    kstream))

(defn clj-kstream
  "Makes a CljKStream object."
  [kstream]
  (CljKStream. kstream))

(deftype CljKTable [^KTable ktable]
  IKStreamBase
  (left-join
    [_ other-ktable value-joiner-fn]
    (clj-ktable
     (.leftJoin ktable
                (ktable* other-ktable)
                (value-joiner value-joiner-fn))))

  (for-each!
    [_ foreach-fn]
    (.foreach ktable (foreach-action foreach-fn))
    nil)

  (filter
    [_ predicate-fn]
    (clj-ktable
     (.filter ktable (predicate predicate-fn))))

  (filter-not
    [_ predicate-fn]
    (clj-ktable
     (.filterNot ktable (predicate predicate-fn))))

  (map-values
    [_ value-mapper-fn]
    (clj-ktable
     (.mapValues ktable (value-mapper value-mapper-fn))))

  (print!
    [_]
    (.print ktable)
    nil)

  (print!
    [_ {:keys [jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
    (.print ktable key-serde value-serde)
    nil)

  (through
    [_ {:keys [topic.metadata/name jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
    ;; todo add store name
    (clj-ktable
     (.through ktable key-serde value-serde name name)))

  (through
    [_ partition-fn {:keys [topic.metadata/name jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
    ;; todo add store name
    (clj-ktable
     (.through ktable key-serde value-serde (stream-partitioner partition-fn) name name)))

  (to!
    [_ {:keys [topic.metadata/name jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
    (.to ktable key-serde value-serde name)
    nil)

  (to!
    [_ partition-fn {:keys [topic.metadata/name jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
    (.to ktable key-serde value-serde (stream-partitioner partition-fn) name)
    nil)

  (write-as-text!
    [_ file-path]
    (.writeAsText ktable file-path))

  (write-as-text!
    [_ file-path {:keys [jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
    (.writeAsText ktable file-path key-serde value-serde))

  IKTable
  (group-by
    [_ key-value-mapper-fn]
    (clj-kgroupedtable
     (.groupBy ktable (key-value-mapper key-value-mapper-fn))))

  (group-by
    [_ key-value-mapper-fn {:keys [jackdaw.serdes/key-serde jackdaw.serdes/value-serde]}]
    (clj-kgroupedtable
     (.groupBy ktable
               (key-value-mapper key-value-mapper-fn)
               key-serde
               value-serde)))

  (join
    [_ other-ktable value-joiner-fn]
    (clj-ktable
     (.join ktable
            (ktable* other-ktable)
            (value-joiner value-joiner-fn))))

  (outer-join
    [_ other-ktable value-joiner-fn]
    (clj-ktable
     (.outerJoin ktable
                 (ktable* other-ktable)
                 (value-joiner value-joiner-fn))))

  (to-kstream
    [_]
    (clj-kstream
     (.toStream ktable)))

  (to-kstream
    [_ key-value-mapper-fn]
    (clj-kstream
     (.toStream ktable (key-value-mapper key-value-mapper-fn))))

  (ktable* [_]
    ktable))

(defn clj-ktable
  "Makes a CljKTable object."
  [ktable]
  (CljKTable. ktable))

(deftype CljGlobalKTable [^GlobalKTable global-ktable]
  IGlobalKTable

  (global-ktable* [_]
    global-ktable))

(defn clj-global-ktable
  "Makes a CljKTable object."
  [global-ktable]
  (CljGlobalKTable. global-ktable))

(deftype CljKGroupedTable [^KGroupedTable kgroupedtable]
  IKGroupedBase
  (aggregate
    [_ initializer-fn adder-fn subtractor-fn
     {:keys [topic.metadata/name jackdaw.serdes/value-serde]}]
    (clj-ktable
     (.aggregate kgroupedtable
                 (initializer initializer-fn)
                 (aggregator adder-fn)
                 (aggregator subtractor-fn)
                 value-serde
                 name)))
  (count
    [_ {:keys [topic.metadata/name]}]
    (clj-ktable
     (.count ^KGroupedTable kgroupedtable
             ^String name)))

  (reduce
    [_ adder-fn subtractor-fn {:keys [topic.metadata/name]}]
    (clj-ktable
     (.reduce ^KGroupedTable kgroupedtable
              ^Reducer (reducer adder-fn)
              ^Reducer (reducer subtractor-fn)
              ^String name)))

  IKGroupedTable
  (kgroupedtable*
    [_]
    kgroupedtable))

(defn clj-kgroupedtable
  "Makes a CljKGroupedTable object."
  [kgroupedtable]
  (CljKGroupedTable. kgroupedtable))

(deftype CljKGroupedStream [^KGroupedStream kgroupedstream]
  IKGroupedBase
  (aggregate
    [_ initializer-fn aggregator-fn {:keys [topic.metadata/name jackdaw.serdes/value-serde]}]
    (clj-ktable
     (.aggregate ^KGroupedStream kgroupedstream
                 ^Initializer (initializer initializer-fn)
                 ^Aggregator (aggregator aggregator-fn)
                 ^Serde value-serde
                 ^String name)))
  (count
    [_ {:keys [topic.metadata/name]}]
    (clj-ktable
     (.count ^KGroupedStream kgroupedstream
             ^String name)))

  (reduce
    [_ reducer-fn {:keys [topic.metadata/name]}]
    (clj-ktable
     (.reduce ^KGroupedStream kgroupedstream
              ^Reducer (reducer reducer-fn)
              ^String name)))

  IKGroupedStream
  (aggregate-windowed
    [_ initializer-fn aggregator-fn windows {:keys [topic.metadata/name jackdaw.serdes/value-serde]}]
    (clj-ktable
     (.aggregate ^KGroupedStream kgroupedstream
                 ^Initializer (initializer initializer-fn)
                 ^Aggregator (aggregator aggregator-fn)
                 ^Windows windows
                 ^Serde value-serde
                 ^String name)))

  (count-windowed
    [_ windows {:keys [topic.metadata/name]}]
    (clj-ktable
     (.count ^KGroupedStream kgroupedstream
             ^Windows windows
             ^String name)))

  (reduce-windowed
    [_ reducer-fn windows {:keys [topic.metadata/name]}]
    (clj-ktable
     (.reduce ^KGroupedStream kgroupedstream
              ^Reducer (reducer reducer-fn)
              ^Windows windows
              ^String name)))

  (kgroupedstream*
    [_]
    kgroupedstream))

(defn clj-kgroupedstream
  "Makes a CljKGroupedStream object."
  [kgroupedstream]
  (CljKGroupedStream. kgroupedstream))
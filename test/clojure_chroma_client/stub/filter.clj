(ns clojure-chroma-client.stub.filter
  "Pure matchers for Chroma `where` (metadata) and `where_document`
  filters. Test-only: part of the in-process Chroma stub."
  (:require [clojure.string :as str]))

(defn- same?
  [a b]
  (if (and (number? a) (number? b))
    (== a b)
    (= a b)))

(defn- order
  "Compare two comparable scalars, or nil when they are not comparable."
  [a b]
  (when (or (and (number? a) (number? b))
            (and (string? a) (string? b)))
    (compare a b)))

(defn- op-match?
  [op actual expected]
  (case op
    :$eq (same? actual expected)
    :$ne (not (same? actual expected))
    :$gt (boolean (some-> (order actual expected) pos?))
    :$gte (boolean (some-> (order actual expected) (>= 0)))
    :$lt (boolean (some-> (order actual expected) neg?))
    :$lte (boolean (some-> (order actual expected) (<= 0)))
    :$in (boolean (some #(same? actual %) expected))
    :$nin (not (some #(same? actual %) expected))
    (throw (ex-info (str "Unsupported where operator: " op)
                    {:status 400 :op op}))))

(defn matches-where?
  "True when `metadata` satisfies the Chroma `where` clause."
  [where metadata]
  (if (empty? where)
    true
    (every? (fn [[k v]]
              (case k
                :$and (every? #(matches-where? % metadata) v)
                :$or (boolean (some #(matches-where? % metadata) v))
                (if (map? v)
                  (every? (fn [[op expected]]
                            (op-match? op (get metadata k) expected))
                          v)
                  (same? (get metadata k) v))))
            where)))

(defn- contains-text?
  [document text]
  (and (string? document) (str/includes? document text)))

(defn matches-document?
  "True when `document` satisfies the Chroma `where_document` clause."
  [where-document document]
  (if (empty? where-document)
    true
    (every? (fn [[k v]]
              (case k
                :$and (every? #(matches-document? % document) v)
                :$or (boolean (some #(matches-document? % document) v))
                :$contains (contains-text? document v)
                :$not_contains (not (contains-text? document v))
                (throw (ex-info (str "Unsupported where_document operator: " k)
                                {:status 400 :op k}))))
            where-document)))

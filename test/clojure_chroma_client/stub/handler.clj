(ns clojure-chroma-client.stub.handler
  "Ring handler that speaks the slice of the Chroma v2 HTTP API the client
  calls, backed by the pure model in `clojure-chroma-client.stub.store`.
  Translation only: routing, JSON in/out, error-to-status mapping. Test-only."
  (:require [clj-json.core :as json]
            [clojure.string :as str]
            [clojure-chroma-client.stub.store :as store])
  (:import [java.net URLDecoder]))

;; --- request decoding -------------------------------------------------

(defn- decode
  [s]
  (URLDecoder/decode ^String s "UTF-8"))

(defn- query-params
  [query-string]
  (into {}
        (for [pair (when-not (str/blank? query-string)
                     (str/split query-string #"&"))
              :let [[k v] (str/split pair #"=" 2)]]
          [(keyword (decode k)) (decode (or v ""))])))

(defn- read-body
  [req]
  (let [s (some-> (:body req) slurp)]
    (when-not (str/blank? s)
      (json/parse-string s true))))

(defn- segments
  [uri]
  (mapv decode (remove str/blank? (str/split uri #"/"))))

;; --- routing ----------------------------------------------------------

(defn- collection-route
  "Route within a tenant/database: `more` are the segments after them."
  [method more]
  (let [[head ref action] more]
    (case [(count more) method]
      [1 :get] (case head
                 "collections" [:list-collections]
                 ("count_collections" "collections_count") [:count-collections]
                 nil)
      [1 :post] (when (= "collections" head) [:create-collection])
      [2 :get] (when (= "collections" head) [:get-collection ref])
      [2 :put] (when (= "collections" head) [:modify-collection ref])
      [2 :delete] (when (= "collections" head) [:delete-collection ref])
      [3 :get] (when (and (= "collections" head) (= "count" action))
                 [:count-records ref])
      [3 :post] (when (and (= "collections" head)
                           (#{"add" "upsert" "update" "get" "delete" "query"} action))
                  [(keyword action) ref])
      nil)))

(defn- route
  "Resolve method + path segments to [op & args], or nil."
  [method segs]
  (let [[root version & more] segs]
    (when (and (= "api" root) (= "v2" version))
      (cond
        (= [:get ["heartbeat"]] [method (vec more)]) [:heartbeat]
        (= [:get ["version"]] [method (vec more)]) [:version]
        (= [:post ["reset"]] [method (vec more)]) [:reset]
        (and (= "tenants" (first more)) (= "databases" (nth more 2 nil)))
        (collection-route method (vec (drop 4 more)))))))

;; --- operations -------------------------------------------------------

(def ^:private all-columns [:ids :embeddings :documents :metadatas :distances])

(defn- column
  [k row]
  (case k
    :ids (:id row)
    :embeddings (:embedding row)
    :documents (:document row)
    :metadatas (:metadata row)
    :distances (:distance row)))

(defn- columns
  "Chroma's columnar result: always `ids`; other columns only when named in
  `include`, null otherwise. `rows` is one seq of rows."
  [rows include]
  (let [wanted (conj (set include) "ids")]
    (into {}
          (map (fn [k]
                 [k (when (wanted (name k))
                      (mapv #(column k %) rows))]))
          all-columns)))

(defn- nested-columns
  "Like `columns`, for query results: one seq of rows per query."
  [rows-per-query include]
  (let [wanted (conj (set include) "ids")]
    (into {}
          (map (fn [k]
                 [k (when (wanted (name k))
                      (mapv (fn [rows] (mapv #(column k %) rows)) rows-per-query))]))
          all-columns)))

(defn- selector
  [params body]
  (cond-> body
    (:limit params) (assoc :limit (parse-long (:limit params)))
    (:offset params) (assoc :offset (parse-long (:offset params)))))

(defn- write-op
  [db mode ref body]
  (swap! db store/write-records ref mode body)
  {})

(defn- perform
  "Run one routed operation against the db atom; returns the JSON body."
  [{:keys [db new-id]} [op ref] params body]
  (case op
    :heartbeat {"nanosecond heartbeat" (System/nanoTime)}
    :version "1.0.0-stub"
    :reset (do (reset! db (store/empty-db)) true)
    :list-collections (store/list-collections
                       @db
                       (or (some-> (:limit params) parse-long) Integer/MAX_VALUE)
                       (or (some-> (:offset params) parse-long) 0))
    :count-collections (store/count-collections @db)
    :create-collection (do (swap! db store/create-collection (new-id) body)
                           (store/get-collection @db (:name body)))
    :get-collection (store/get-collection @db ref)
    :modify-collection (do (swap! db store/modify-collection ref body) {})
    :delete-collection (do (swap! db store/delete-collection ref) {})
    :count-records (store/count-records @db ref)
    :add (write-op db :add ref body)
    :upsert (write-op db :upsert ref body)
    :update (write-op db :update ref body)
    :get (columns (store/get-records @db ref (selector params body))
                  (or (:include body) ["metadatas" "documents"]))
    :delete (do (swap! db store/delete-records ref body) [])
    :query (nested-columns (store/query-records @db ref body)
                           (or (:include body) ["metadatas" "documents" "distances"]))))

;; --- ring adapter -----------------------------------------------------

(defn- json-response
  [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

(defn make-handler
  "A Ring handler over `state`, a map of {:db <atom of store db>
  :new-id <fn returning a fresh collection id>}."
  [state]
  (fn [req]
    (try
      (if-let [endpoint (route (:request-method req) (segments (:uri req)))]
        (json-response 200 (perform state endpoint
                                    (query-params (:query-string req))
                                    (read-body req)))
        (json-response 404 {:error (str "no route for " (:request-method req) " " (:uri req))}))
      (catch clojure.lang.ExceptionInfo e
        (json-response (or (:status (ex-data e)) 500) {:error (ex-message e)}))
      (catch Exception e
        (json-response 500 {:error (str e)})))))

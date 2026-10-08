(ns clojure-chroma-client.stub.store
  "Pure, in-memory model of a Chroma database for the test stub: every
  function takes a db value and returns a new db value or a query result.
  No I/O, no clocks, no randomness (ids are passed in). Test-only."
  (:require [clojure-chroma-client.stub.filter :as flt]))

(defn empty-db
  []
  {:collections []})

(defn- not-found
  [what ref]
  (ex-info (str what " " ref " does not exist") {:status 404}))

(defn- find-collection
  [db ref]
  (or (first (filter #(or (= ref (:id %)) (= ref (:name %)))
                     (:collections db)))
      (throw (not-found "Collection" ref))))

(defn- update-collection
  [db ref f]
  (let [id (:id (find-collection db ref))]
    (update db :collections
            (fn [colls] (mapv #(if (= id (:id %)) (f %) %) colls)))))

(defn- public
  [coll]
  (-> coll
      (dissoc :records)
      (assoc :tenant "default_tenant" :database "default_database")))

;; --- collections ------------------------------------------------------

(defn list-collections
  [db limit offset]
  (->> (:collections db) (drop offset) (take limit) (mapv public)))

(defn count-collections
  [db]
  (clojure.core/count (:collections db)))

(defn get-collection
  [db ref]
  (public (find-collection db ref)))

(defn create-collection
  "Create the collection, or, when `get_or_create` is set and the name is
  taken, leave the db untouched. `id` is the id used for a new collection."
  [db id {:keys [name metadata get_or_create]}]
  (if-let [existing (first (filter #(= name (:name %)) (:collections db)))]
    (if get_or_create
      db
      (throw (ex-info (str "Collection " name " already exists")
                      {:status 409 :id (:id existing)})))
    (update db :collections conj {:id id
                                  :name name
                                  :metadata (or metadata {})
                                  :records []})))

(defn modify-collection
  [db ref {:keys [new_name new_metadata]}]
  (update-collection db ref
                     #(cond-> %
                        new_name (assoc :name new_name)
                        new_metadata (assoc :metadata new_metadata))))

(defn delete-collection
  [db ref]
  (let [id (:id (find-collection db ref))]
    (update db :collections (fn [colls] (filterv #(not= id (:id %)) colls)))))

;; --- records ----------------------------------------------------------

(defn- selected?
  [{:keys [ids where where_document]} record]
  (and (or (nil? ids) (contains? (set ids) (:id record)))
       (flt/matches-where? where (:metadata record))
       (flt/matches-document? where_document (:document record))))

(defn- row
  [{:keys [ids metadatas documents embeddings]} i]
  {:id (nth ids i)
   :metadata (get metadatas i)
   :document (get documents i)
   :embedding (get embeddings i)})

(defn- index-of
  [records id]
  (first (keep-indexed (fn [i r] (when (= id (:id r)) i)) records)))

(defn- merge-present
  [old new]
  (into old (filter (comp some? val)) new))

(defn- write-row
  [records mode incoming]
  (let [i (index-of records (:id incoming))]
    (cond
      (nil? i) (if (= mode :update) records (conj records incoming))
      (= mode :add) records
      :else (assoc records i (merge-present (nth records i) incoming)))))

(defn write-records
  "Apply `cols` ({:ids :metadatas :documents :embeddings}) to a collection.
  `mode` is :add (existing ids are ignored), :upsert (existing ids take the
  provided fields) or :update (unknown ids are ignored)."
  [db ref mode {:keys [ids] :as cols}]
  (when (empty? ids)
    (throw (ex-info "ids are required" {:status 400})))
  (update-collection
   db ref
   (fn [coll]
     (update coll :records
             (fn [records]
               (reduce #(write-row %1 mode (row cols %2))
                       records
                       (range (clojure.core/count ids))))))))

(defn count-records
  [db ref]
  (clojure.core/count (:records (find-collection db ref))))

(defn get-records
  [db ref {:keys [limit offset] :or {limit 100 offset 0} :as selector}]
  (->> (:records (find-collection db ref))
       (filter #(selected? selector %))
       (drop offset)
       (take limit)
       vec))

(defn delete-records
  [db ref selector]
  (update-collection db ref
                     (fn [coll]
                       (update coll :records
                               (fn [rs] (filterv #(not (selected? selector %)) rs))))))

(defn- l2
  [a b]
  (reduce + (map (fn [x y] (let [d (- (double x) (double y))] (* d d))) a b)))

(defn query-records
  "For each query embedding, the `n_results` nearest records (squared L2,
  ascending, ties in insertion order) as rows with a :distance."
  [db ref {:keys [query_embeddings n_results] :or {n_results 10} :as selector}]
  (let [candidates (filter #(and (:embedding %) (selected? selector %))
                           (:records (find-collection db ref)))]
    (mapv (fn [q]
            (->> candidates
                 (map #(assoc % :distance (l2 q (:embedding %))))
                 (sort-by :distance)
                 (take n_results)
                 vec))
          query_embeddings)))

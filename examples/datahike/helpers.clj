(ns datahike.helpers
  "Shared helpers for serializing and reconstructing Datahike DBs across processes."
  (:require [datahike.writing :as dw]
            [datahike.datom :as dd]
            [datahike.index.persistent-set :as pset])
  (:import [org.fressian.handlers ReadHandler WriteHandler]
           [me.tonsky.persistent_sorted_set PersistentSortedSet Settings]))

;; Store registry - maps store-config to store/storage
(defonce store-registry (atom {}))

(defn normalize-store-config
  "Normalize store config to only include stable keys for matching.
   The :scope key defines the address space where the store is uniquely known."
  [store-config]
  (select-keys store-config [:backend :path :id :scope]))

(defn register-store!
  "Register a store in the registry by store-config."
  [store-config store]
  (let [normalized (normalize-store-config store-config)]
    (swap! store-registry assoc normalized {:store store
                                             :storage (:storage store)})
    (println "Registered store:" normalized)))

(defn get-storage-from-registry
  "Get storage from registry by store-config."
  [store-config]
  (get-in @store-registry [(normalize-store-config store-config) :storage]))

(defn get-store-from-registry
  "Get store from registry by store-config."
  [store-config]
  (get-in @store-registry [(normalize-store-config store-config) :store]))

(defn reconstruct-pset
  "Reconstruct a PersistentSortedSet from deferred data using storage."
  [deferred-data storage]
  (let [{:keys [meta address count]} (:data deferred-data)
        cmp (dd/index-type->cmp-quick (:index-type meta) false)
        settings (Settings. (int pset/DEFAULT_BRANCHING_FACTOR) nil)]
    (PersistentSortedSet. meta cmp address storage nil count settings 0)))

(defn reconstruct-db-from-deferred
  "Reconstruct full DB from deferred stored-db by reconstructing all indices."
  [deferred-stored storage]
  (let [eavt (reconstruct-pset {:deferred-type :persistent-sorted-set
                                :data (:data (:eavt-key deferred-stored))}
                               storage)
        aevt (reconstruct-pset {:deferred-type :persistent-sorted-set
                                :data (:data (:aevt-key deferred-stored))}
                               storage)
        avet (reconstruct-pset {:deferred-type :persistent-sorted-set
                                :data (:data (:avet-key deferred-stored))}
                               storage)]
    (-> deferred-stored
        (assoc :eavt-key eavt)
        (assoc :aevt-key aevt)
        (assoc :avet-key avet))))

(defn serialize-db
  "Serialize a DB to stored format for transmission.
   Returns [schema-meta-kv stored-db] tuple from db->stored."
  [db]
  (dw/db->stored db true))

(defn deserialize-and-reconstruct-db
  "Deserialize a deferred stored-db and reconstruct it using storage from registry.
   The deferred-stored should have deferred indices that will be reconstructed."
  [deferred-stored]
  (let [store-config (get-in deferred-stored [:config :store])
        storage (get-storage-from-registry store-config)
        store (get-store-from-registry store-config)]
    (when-not storage
      (throw (ex-info "Store not found in registry. Make sure to connect to the store first."
                      {:store-config store-config
                       :available-stores (keys @store-registry)})))
    (let [full-stored (reconstruct-db-from-deferred deferred-stored storage)]
      (dw/stored->db full-stored store))))

;; Fressian read handlers - defined after functions so they can reference them

;; Deferred PersistentSortedSet read handler
(def deferred-pset-read-handler
  "Fressian read handler that defers PersistentSortedSet reconstruction.
   Returns a map with :deferred-type and :data instead of constructing the set."
  (reify ReadHandler
    (read [_ reader _tag _component-count]
      (let [value (.readObject reader)
            data (if (instance? (Class/forName "[Ljava.lang.Object;") value)
                   (first value)
                   value)]
        {:deferred-type :persistent-sorted-set
         :data data}))))

;; DB read handler that reconstructs the DB from stored format
(def db-read-handler
  "Fressian read handler that reconstructs a DB from stored format.
   The stored-db arrives with deferred PersistentSortedSet indices,
   which are reconstructed using the storage from the registry."
  (reify ReadHandler
    (read [_ reader _tag _component-count]
      (let [stored-db (.readObject reader)]
        ;; Reconstruct the DB immediately using the helper function
        ;; This requires that the storage is already registered
        (deserialize-and-reconstruct-db stored-db)))))

;; Custom Fressian read handlers for deferred deserialization
(def custom-read-handlers
  {"datahike.index.PersistentSortedSet" deferred-pset-read-handler
   "datahike.db.DB" db-read-handler})

;; Datahike write handlers for serialization
(def datahike-write-handlers
  "Fressian write handlers for Datahike types (DB, Datom and PersistentSortedSet)."
  {datahike.db.DB
   {"datahike.db.DB"
    (reify WriteHandler
      (write [_ writer db]
        ;; Convert DB to stored format (without flush)
        (let [[_schema-meta-kv stored-db] (dw/db->stored db false)]
          (.writeTag writer "datahike.db.DB" 1)
          ;; Write the stored-db map - its indices will be serialized
          ;; by the PersistentSortedSet write handler
          (.writeObject writer stored-db))))}

   datahike.datom.Datom
   {"datahike.datom.Datom"
    (reify WriteHandler
      (write [_ writer datom]
        (.writeTag writer "datahike.datom.Datom" 1)
        (.writeObject writer (vec (seq datom)))))}

   me.tonsky.persistent_sorted_set.PersistentSortedSet
   {"datahike.index.PersistentSortedSet"
    (reify WriteHandler
      (write [_ writer pset]
        (when (nil? (.-_address pset))
          (throw (ex-info "Must be flushed." {:type :must-be-flushed :pset pset})))
        (.writeTag writer "datahike.index.PersistentSortedSet" 1)
        (.writeObject writer {:meta (meta pset)
                             :address (.-_address pset)
                             :count (count pset)})))}})

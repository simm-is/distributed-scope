# Datahike Sync: Helia vs Tiered Store + Kabel

## Context

We need to sync a Datahike database from backend (file store) to frontend (IndexedDB) with:
- Initial sync of DB roots + missing immutable content
- Streaming updates as backend writes occur
- Content-addressed deduplication (Datahike's B-tree nodes are immutable once written)
- Support for offline access with eventual consistency

## Approach A: Tiered Store + Kabel Streaming

### Architecture

```clojure
;; Backend: File store
;; Frontend: IndexedDB (via tiered store)
;; Communication: Kabel WebSocket + pub/sub

;; 1. Initial Sync (Content-Addressed)
(defn sync-datahike-store [tiered-store opts]
  ;; Custom sync strategy that understands Datahike's layout:
  ;; - DB root keys: [:datahike-db :root]
  ;; - Immutable content: B-tree nodes addressed by hash

  (let [backend-roots (get-db-roots backend-store)
        frontend-roots (get-db-roots frontend-store)

        ;; Only sync missing or changed roots
        roots-to-sync (diff-roots backend-roots frontend-roots)

        ;; For each root, walk the B-tree and find missing nodes
        missing-nodes (find-missing-content backend-store
                                           frontend-store
                                           roots-to-sync)]

    ;; Batch fetch only missing nodes
    (sync-keys-to-frontend frontend-store
                          backend-store
                          (concat roots-to-sync missing-nodes)
                          opts)))

;; 2. Streaming Updates
(defn wrap-konserve-with-pubsub [backend-store kabel-peer]
  ;; Intercept write operations
  (reify PEDNKeyValueStore
    (-assoc-in [this key-vec meta-up-fn val opts]
      (let [result (-assoc-in backend-store key-vec meta-up-fn val opts)]
        ;; Emit change event via kabel
        (publish! kabel-peer {:type :store/assoc
                              :key (first key-vec)
                              :hash (hash-value val)})
        result))))

;; 3. Frontend subscribes and applies
(defn subscribe-to-backend-changes [tiered-store kabel-peer]
  (subscribe! kabel-peer :store/assoc
    (fn [{:keys [key hash]}]
      ;; Check if we already have this content
      (when-not (has-content? frontend-store hash)
        ;; Fetch and apply
        (let [value (fetch-from-backend key)]
          (assoc-in! frontend-store [key] value))))))
```

### Pros
- ✅ **Minimal dependencies** - Uses existing infrastructure (konserve, kabel)
- ✅ **Pure Clojure/ClojureScript** - No JavaScript interop
- ✅ **Tiered store already exists** - Just add streaming layer
- ✅ **Content-aware sync** - Custom strategy understands Datahike structure
- ✅ **Efficient delta sync** - `populate-missing-strategy` already does this
- ✅ **Small payloads** - Only send hashes, fetch missing content on demand
- ✅ **Full control** - Can optimize for Datahike's specific patterns
- ✅ **Offline-first** - Reconnect uses populate-missing automatically

### Cons
- ⚠️ **Centralized** - Backend is single source of truth (but you said single writer)
- ⚠️ **Change detection needs wrapper** - Must intercept konserve operations
- ⚠️ **No global deduplication** - Content only shared between connected clients
- ⚠️ **Manual reconnect logic** - Need to handle disconnects/reconnects explicitly

### Implementation Complexity
**Medium** - Main work:
1. Implement konserve wrapper to emit change events (~50 LOC)
2. Implement Datahike-aware sync strategy (~100 LOC)
3. Wire up kabel pub/sub for streaming (~50 LOC)
4. Handle reconnection logic (~50 LOC)

**Total: ~250 LOC**

## Approach B: Helia (IPFS)

### Architecture

```clojure
;; Backend & Frontend both run Helia nodes
;; Content stored in IPFS blockstore (CID-addressed)
;; Konserve sits on top of Helia blockstore

;; 1. Initial Sync
(defn sync-via-ipfs [frontend-helia backend-helia]
  ;; Backend publishes DB root CID via IPNS or pubsub
  (let [root-cid (get-published-root backend-helia)

        ;; Frontend walks DAG, Helia automatically fetches missing blocks
        db (walk-dag frontend-helia root-cid)]

    ;; Helia's bitswap handles deduplication and fetching
    db))

;; 2. Streaming Updates
(defn subscribe-to-ipfs-updates [helia]
  ;; Subscribe to pubsub topic for root updates
  (subscribe-pubsub helia "datahike-roots"
    (fn [new-root-cid]
      ;; Helia automatically fetches missing blocks via bitswap
      (update-local-root! new-root-cid))))

;; 3. Konserve on Helia Blockstore
(defn konserve-ipfs-backend [helia]
  ;; Custom konserve backend that uses Helia's blockstore
  ;; Maps konserve keys to CIDs
  (reify PEDNKeyValueStore
    (-get-in [this [key] not-found opts]
      (let [cid (key->cid key)]
        (dag-cbor-get helia cid)))

    (-assoc-in [this [key] meta-up-fn val opts]
      (let [cid (dag-cbor-add helia val)]
        (update-key-index! key cid)
        cid))))
```

### Pros
- ✅ **Content-addressed by design** - CIDs provide deduplication automatically
- ✅ **Global deduplication** - Same content = same CID across all nodes
- ✅ **Proven protocol** - IPFS/Helia is battle-tested
- ✅ **Multiple transports** - Bitswap, HTTP gateways, etc.
- ✅ **Automatic fetching** - DAG traversal fetches missing blocks transparently
- ✅ **Could enable P2P** - Browsers could share content directly (future)
- ✅ **Trustless verification** - CIDs are cryptographic hashes
- ✅ **IPNS for mutable refs** - Publish DB roots as mutable pointers

### Cons
- ⚠️ **ClojureScript ↔ JavaScript interop** - Helia is TypeScript/JavaScript
- ⚠️ **Additional complexity** - Running full Helia node in browser
- ⚠️ **Bundle size** - Helia + libp2p adds significant weight to frontend
- ⚠️ **Browser connectivity challenges** - WebRTC still "in sordid state"
- ⚠️ **Need custom konserve backend** - Map konserve operations to IPFS blocks
- ⚠️ **Blockstore impedance mismatch** - Konserve is key-value, IPFS is content-addressed
- ⚠️ **Learning curve** - Team needs to understand IPFS/Helia ecosystem
- ⚠️ **Deployment complexity** - Need IPFS infrastructure or rely on public gateways

### Implementation Complexity
**High** - Main work:
1. ClojureScript wrapper for Helia API (~200 LOC)
2. Custom konserve backend for Helia blockstore (~300 LOC)
3. Key index mapping (konserve keys ↔ CIDs) (~100 LOC)
4. IPNS/pubsub for root updates (~100 LOC)
5. Handle browser-specific networking issues (~100 LOC)

**Total: ~800 LOC + external dependencies**

## Hybrid Approach: Best of Both

### Idea

Use **tiered store + kabel** for the sync mechanism, but adopt **content-addressing patterns from IPFS**:

```clojure
;; 1. Hash-based content keys (IPFS-style)
(defn store-immutable-content [store content]
  (let [content-hash (sha256 content)
        key [:content content-hash]]
    (assoc-in! store key content)
    content-hash))

;; 2. Mutable root pointers (IPNS-style)
(defn update-db-root [store db-id new-root-hash]
  (assoc-in! store [:roots db-id] new-root-hash))

;; 3. Content-addressed sync
(defn sync-content-addressed [frontend backend]
  ;; Frontend: "I have root X with hash H1"
  ;; Backend: "I have root X with hash H2"
  ;; Frontend: "Send me H2 and all its referenced content I don't have"

  (let [backend-root (get-in backend [:roots db-id])
        frontend-root (get-in frontend [:roots db-id])]

    (when (not= backend-root frontend-root)
      ;; Walk backend DAG, check what frontend has
      (let [missing (find-missing-blocks frontend backend backend-root)]
        ;; Batch fetch missing blocks only
        (fetch-blocks! frontend backend missing)))))

;; 4. Stream root updates only
(defn stream-root-updates [kabel-peer]
  ;; Only stream lightweight root updates
  ;; Content fetched on-demand via content-addressing
  (publish! kabel-peer {:type :root/updated
                        :db-id db-id
                        :new-hash new-hash}))
```

### Pros
- ✅ **Content-addressed benefits** - Deduplication, immutability
- ✅ **No IPFS complexity** - Pure Clojure, existing stack
- ✅ **Lightweight protocol** - Only stream root updates
- ✅ **Natural fit for Datahike** - B-trees are already content-addressed
- ✅ **Efficient sync** - Only fetch what's missing
- ✅ **Simple to understand** - Familiar patterns in your stack

### Implementation
**Medium-Low** - Simpler than full Helia integration:
1. Content-addressed key scheme (~50 LOC)
2. DAG walking to find missing blocks (~100 LOC)
3. Root update streaming (~50 LOC)
4. Tiered store integration (~100 LOC)

**Total: ~300 LOC**

## Recommendation

**Start with Hybrid Approach** because:

1. **Datahike is already content-addressed** - B-tree nodes use addresses (hashes), so you're 80% there
2. **Konserve tiered store exists** - Just add content-aware sync strategy
3. **Kabel is proven** - We just demonstrated transparent DB serialization
4. **No new dependencies** - Pure Clojure solution
5. **Incremental path** - Could add IPFS later if P2P becomes important

## Open Questions

1. **How does Datahike currently reference B-tree nodes?**
   - If already using content hashes, we can leverage directly
   - If using sequential IDs, need to add content-addressing layer

2. **What's in a "DB root"?**
   - Schema + indices + transaction metadata?
   - How much data needs to move when DB is updated?

3. **Write frequency?**
   - If writes are rare, streaming every change is fine
   - If writes are frequent, may want batching/debouncing

4. **Multiple DBs per user?**
   - How many Datahike databases per frontend?
   - Sync all or subscribe selectively?

## Next Steps

1. **Investigate Datahike's storage layout**
   - How are B-tree nodes currently addressed?
   - What's in stored-db format vs what's in konserve?

2. **Prototype content-addressed sync strategy**
   - Implement `find-missing-blocks` for Datahike structure
   - Test with realistic data sizes

3. **Add change detection wrapper**
   - Wrap konserve operations to emit events
   - Wire up kabel pub/sub

4. **Test offline/reconnect scenarios**
   - Does populate-missing handle reconnects correctly?
   - Measure sync time for various data sizes

Would you like me to start with step 1 - investigating Datahike's storage layout to see how content-addressed it already is?

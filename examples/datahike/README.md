# Datahike Distributed Example

This example demonstrates transparent serialization and distribution of Datahike databases across processes using distributed-scope.

## Features

- **Transparent DB Serialization**: Pass Datahike DB objects directly through `go-remote` - no manual serialization needed!
- **Deferred Deserialization**: PersistentSortedSet indices are reconstructed on-demand using shared storage
- **Cross-Process Queries**: Query the same database from different processes seamlessly

## Running with Two Processes

### Terminal 1: Start the Server

```bash
clj -M:test -m datahike.run-server
# Or specify a custom port:
clj -M:test -m datahike.run-server 9090
```

The server will:
- Create a test database at `/tmp/datahike-distributed-test`
- Add test data (Alice age 30, Bob age 25)
- Start listening for client connections
- Keep running until you press Ctrl+C

### Terminal 2: Run the Client

```bash
clj -M:test -m datahike.run-client
# Or specify a custom port (must match server):
clj -M:test -m datahike.run-client 9090
```

The client will:
- Connect to the server
- Connect to the shared database
- Run three demo functions showing different DB distribution patterns
- Display results and exit

## Demo Functions

### 1. `simple-demo` - Immutable Snapshot Verification
- Server takes a DB snapshot (Alice, Bob)
- Server adds new data (Charlie) AFTER taking the snapshot
- Sends the snapshot to client
- Client queries and sees only the snapshot data (Alice, Bob)
- **Proves**: The DB is a true immutable snapshot, not reading from the connection!

### 2. `query-on-client`
- Server sends DB snapshot to client
- Client performs complex multi-field query
- Returns results to server

### 3. `bidirectional-query`
- Server queries for names
- Client queries for ages (on the same DB)
- Server combines both results

## How It Works

1. **Shared Storage**: Both processes connect to the same file-based Datahike store at `/tmp/datahike-distributed-test`

2. **Storage Registry**: Each process registers the storage in a local registry by store-config (`:backend`, `:path`, `:scope`)

3. **DB Write Handler**: When a DB is serialized:
   - Calls `db->stored` to get stored format
   - PersistentSortedSet indices are serialized as lightweight pointers (`:address`, `:count`, `:meta`)

4. **DB Read Handler**: When a DB is deserialized:
   - Looks up storage from registry by store-config
   - Reconstructs PersistentSortedSets using the storage atom
   - Calls `stored->db` to get fully functional DB

5. **Transparent Usage**: Just pass `db` in `go-remote` capture lists - serialization happens automatically!

## Code Examples

### Transparent Serialization

```clojure
;; Before: Manual serialization
(let [db @conn
      [_ stored-db] (helpers/serialize-db db)]
  (go-remote server-id [client-id stored-db]
    (let [db (helpers/deserialize-and-reconstruct-db stored-db)]
      (d/q '[:find ?n :where [?e :name ?n]] db))))

;; After: Transparent serialization
(let [db @conn]
  (go-remote server-id [client-id db]
    ;; db just works!
    (d/q '[:find ?n :where [?e :name ?n]] db)))
```

### Immutable Snapshot Behavior

```clojure
;; Take a snapshot
(let [db @conn  ; db = snapshot of current state
      ;; Modify the database AFTER taking snapshot
      _ (d/transact conn [{:name "Charlie" :age 35}])]

  (go-remote server-id [client-id db]
    ;; Send snapshot to client
    (go-remote client-id [db]
      ;; Client sees the ORIGINAL snapshot (before Charlie was added)
      ;; This proves we're passing a true immutable value!
      (d/q '[:find ?n :where [?e :name ?n]] db))))
```

## Files

- `helpers.clj` - Serialization handlers and storage registry
- `server.clj` - Server setup with test database
- `client.clj` - Client setup with shared store connection
- `demo.cljc` - Demo functions showing DB distribution patterns
- `run_server.clj` - Standalone server runner
- `run_client.clj` - Standalone client runner
- `../../../test/is/simm/datahike_distributed_test.clj` - Automated tests

## Testing

Run automated tests:
```bash
clj -X:test
```

**All 8 tests pass with 25 assertions! 🎉**

The tests verify:
- ✅ Transparent DB serialization works across processes
- ✅ Client sees true immutable snapshots (not current DB state)
- ✅ Complex multi-field queries work on distributed DBs
- ✅ Bidirectional querying works correctly
- ✅ DB reconstruction produces functionally equivalent databases

### Key Test: Immutable Snapshot Verification

The `datahike-simple-demo-test` proves that we're passing true immutable snapshots:

```clojure
;; Server takes snapshot: #{["Alice"] ["Bob"]}
;; Server adds Charlie: #{["Alice"] ["Bob"] ["Charlie"]}
;; Client receives snapshot: #{["Alice"] ["Bob"]}  ← Only sees original!
```

This confirms the DB value is truly immutable and not reading from the connection!

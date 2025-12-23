# distributed-scope

<p align="center">
<a href="https://clojurians.slack.com/archives/C09622F337D"><img src="https://badgen.net/badge/-/slack?icon=slack&label"/></a>
<a href="https://clojars.org/is.simm/distributed-scope"><img src="https://img.shields.io/clojars/v/is.simm/distributed-scope.svg"/></a>
<a href="https://circleci.com/gh/simm-is/distributed-scope"><img src="https://circleci.com/gh/simm-is/distributed-scope.svg?style=shield"/></a>
<a href="https://github.com/simm-is/distributed-scope/tree/main"><img src="https://img.shields.io/github/last-commit/simm-is/distributed-scope/main"/></a>
<a href="https://cljdoc.org/d/is.simm/distributed-scope"><img src="https://badgen.net/badge/cljdoc/distributed-scope/blue"/></a>
</p>

Write distributed code that looks like local code. Variables from your scope automatically travel with your computation across peers.

## Quick Example

```clojure
(defn-go-remote search-and-display [client-id server-id]
  (go-remote client-id [server-id]               ; 1. Start on client
    (let [query (get-search-input)
          filters (get-active-filters)]

      (let [results (<? S (go-remote server-id [query filters]  ; 2. Hop to server
                            (-> (db/search query)
                                (apply-filters filters)
                                (take 50))))]

        (<? S (go-remote client-id [results]     ; 3. Back to client
                (render-results! results)
                (count results)))))))
```

**What just happened?**
1. **Client**: Collected `query` and `filters` from the UI
2. **Server**: Ran database search with those values - no manual serialization needed
3. **Client**: Rendered results - `results` traveled back automatically

The explicit `[query filters]` and `[results]` argument lists declare what travels across the wire. The macro validates at compile-time that you're not accidentally forgetting variables.

## Installation

[![Clojars Project](http://clojars.org/is.simm/distributed-scope/latest-version.svg)](http://clojars.org/is.simm/distributed-scope)

```clojure
;; deps.edn
{:deps {is.simm/distributed-scope {:mvn/version "LATEST"}}}
```

## Features

- **Scope Travels With Code**: Variables are explicitly captured and serialized to remote peers
- **Compile-time Safety**: Missing variables = error, unused variables = warning
- **Bidirectional**: Hop between any connected peers (server→client→server→...)
- **Cross-platform**: Clojure + ClojureScript with reader conditional support
- **Two Flavors**: `go-remote` (core.async) or `sp-remote` (Missionary)
- **Auth Ready**: Works with [kabel-auth](https://github.com/replikativ/kabel-auth) for JWT/principal-based authentication

## How It Works

When you write:

```clojure
(let [x 42, y "hello"]
  (go-remote server-id [x]    ; Only 'x' is sent
    (+ x 1)))
```

The macro:

1. **Captures** the values of variables in `[x]` from your local scope
2. **Serializes** them into `{:x 42}` and sends to the remote peer
3. **Executes** `(+ x 1)` on the server with `x` bound to `42`
4. **Returns** the result (`43`) back to the caller

The code block is identified by its source location (line/column), so both peers must share the same codebase. Only the *data* travels over the wire, not the code. This approach handles reader conditionals (`#?(:clj ...)`) correctly since positions are stable even when syntax differs between Clojure and ClojureScript.

```
   Client                          Server
  ┌────────────────┐             ┌────────────────┐
  │ (let [x 42]    │             │                │
  │   (go-remote   │─── {:x 42} ─▶│ (+ x 1)       │
  │     server     │             │   ; x = 42     │
  │     [x]        │◀──── 43 ────│   => 43        │
  │     (+ x 1)))  │             │                │
  └────────────────┘             └────────────────┘
```

### Nested Hops

Each `go-remote` can contain more `go-remote` calls, creating a chain of context switches:

```clojure
(go-remote A []
  (let [from-a (compute-on-a)]
    (<? S (go-remote B [from-a]
            (let [from-b (compute-on-b from-a)]
              (<? S (go-remote C [from-a from-b]
                      (finalize from-a from-b))))))))
```

Execution flows: **A → B → C**, with each hop carrying exactly the variables you specify.

## API

### `go-remote` / `defn-go-remote`

For `core.async` style with `superv.async`:

```clojure
(require '[is.simm.distributed-scope :refer [defn-go-remote go-remote]]
         '[superv.async :refer [S <?]])

(defn-go-remote my-distributed-fn [peer-a peer-b]
  (go-remote peer-a [peer-b]
    (let [a-result (do-something)]
      (<? S (go-remote peer-b [a-result]
              (use-result a-result))))))

;; Call it
(<? S (my-distributed-fn peer-a-id peer-b-id))
```

### `sp-remote` / `defn-sp-remote`

For Missionary sequential processes:

```clojure
(require '[is.simm.distributed-scope :refer [defn-sp-remote sp-remote]]
         '[missionary.core :as m])

(defn-sp-remote my-distributed-task [peer-a peer-b]
  (sp-remote peer-a []
    (let [a-result (do-something)]
      (m/? (sp-remote peer-b [a-result]
             (use-result a-result))))))

;; Call it
(m/? (my-distributed-task peer-a-id peer-b-id))
```

## Running the Example

```clojure
;; Start REPL: clj -A:dev
(require '[simple.server :as server]
         '[simple.client :as client]
         '[simple.demo :as demo]
         '[hasch.core :refer [uuid]]
         '[superv.async :refer [S <??]])

(def server-id (uuid :server))
(def client-id (uuid :client))

;; Start peers
(def started (server/start! "ws://localhost:47291" server-id))
(def _client (client/start! "ws://localhost:47291" client-id))

;; Run distributed computation
(<?? S (demo/demo server-id client-id))
;; => [(42 42 42 ...) 44]

(server/stop! started)
```

## Setting Up Peers

### Server

```clojure
(require '[kabel.peer :as peer]
         '[is.simm.distributed-scope :refer [remote-middleware invoke-on-peer]])

(def server-id #uuid "05a06e85-e7ca-4213-9fe5-04ae511e50a0")
(def server (peer/server-peer S handler server-id remote-middleware identity))

(invoke-on-peer server)
(<?? S (peer/start server))
```

### Client

```clojure
(def client-id #uuid "c14c628b-b151-4967-ae0a-7c83e5622d0f")
(def client (peer/client-peer S client-id remote-middleware identity))

(invoke-on-peer client)
(<?? S (peer/connect S client "ws://localhost:47291"))
```

## Inspiration & Related Work

Inspired by [Electric Clojure](https://github.com/hyperfiddle/electric)'s vision of seamless distributed code. Key differences:

- **À la carte**: No full buy-in to a reactive compiler—integrate where you need it
- **P2P native**: Built on [kabel](https://github.com/replikativ/kabel) with no server/client distinction; peers can broadcast via pub-sub
- **Your choice of async**: Supports both `core.async` (widespread) and Missionary (also used by Electric)

Unlike systems that serialize closures (Spark, Flink), distributed-scope only transmits **immutable Clojure values**. Code blocks are identified by source location (line/column), allowing reader conditionals to work correctly across CLJ/CLJS.

**Related systems**: [Unison](https://www.unison-lang.org/) (content-addressed functions), Termite Scheme (distributed continuations), Links/Hop.js (tierless web programming).

**Coming soon**: First-class [Datahike](https://github.com/replikativ/datahike) database references that can travel with scope.

## Building and Testing

```bash
# Run Clojure tests
clojure -X:test

# Run browser integration tests (requires Chrome)
npm install
./test-browser.sh

# Build JAR
clojure -T:build jar

# Deploy to Clojars
clojure -T:build deploy
```

## License

Copyright 2025 Christian Weilbach. Apache License 2.0.

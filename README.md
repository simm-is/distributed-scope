# distributed-scope

<p align="center">
<a href="https://clojurians.slack.com/archives/C09622F337D"><img src="https://badgen.net/badge/-/slack?icon=slack&label"/></a>
<a href="https://clojars.org/is.simm/distributed-scope"><img src="https://img.shields.io/clojars/v/is.simm/distributed-scope.svg"/></a>
<a href="https://circleci.com/gh/simm-is/distributed-scope"><img src="https://circleci.com/gh/simm-is/distributed-scope.svg?style=shield"/></a>
<a href="https://github.com/simm-is/distributed-scope/tree/main"><img src="https://img.shields.io/github/last-commit/simm-is/distributed-scope/main"/></a>
<a href="https://cljdoc.org/d/is.simm/distributed-scope"><img src="https://badgen.net/badge/cljdoc/distributed-scope/blue"/></a>
</p>

A Clojure/ClojureScript library for distributed computation with lexical scope preservation. Execute code seamlessly across multiple peers while maintaining access to variables from the original scope—making distributed programming feel like local programming.

## Overview

`distributed-scope` allows you to write distributed applications where computations can be transparently moved between different peers (servers/clients) while preserving lexical scope. The library analyzes your code to identify free variables and automatically serializes them, enabling you to write distributed code that looks and feels like regular Clojure code.

Think of it as "computation with scope that follows you wherever you go."

## Why "Distributed Scope"?

The name reflects the library's core innovation: **scope is distributed along with computation**. When you send a computation to a remote peer, all the variables from your local scope automatically travel with it. This is what makes distributed programming feel natural—you don't have to manually pack and unpack data; the scope just works wherever your code runs.

## Key Features

- **Explicit Scope Preservation**: Variables are explicitly listed for transmission to remote peers, making data flow clear
- **Compile-time Validation**: Errors if you use variables not in the arg list, warnings if you list unused variables
- **Bidirectional Communication**: Computations can hop between any connected peers seamlessly
- **Async/Await Style**: Built on `core.async` and `superv.async` for clean asynchronous code
- **Macro-based API**: Simple `go-remote` macro for remote execution and `defn-go-remote` for defining distributed functions; Missionary support with `sp-remote` and `defn-sp-remote` for sequential processes
- **Cross-platform**: Works with both Clojure (JVM) and ClojureScript
- **Works with Reader Conditionals**: Explicit argument lists work correctly with `#?(:clj ...)` blocks

## How It Works

### The `go-remote` and `sp-remote` Macros

The remote execution macros take a peer ID, an explicit argument vector, and a body of code:

```clojure
(go-remote peer-id [arg1 arg2 ...] body...)
(sp-remote peer-id [arg1 arg2 ...] body...)
```

The macro then:

1. **Extracts** the explicit argument list `[arg1 arg2 ...]`
2. **Analyzes** the code using `tools.analyzer.jvm` to identify free variables actually used
3. **Validates** that all free variables are in the arg list (error if missing)
4. **Warns** if any variables in the arg list aren't used in the body
5. **Generates** a uniquely-named function based on the code and its dependencies
6. **Serializes** the explicit arguments into a map `{:arg1 arg1, :arg2 arg2, ...}`
7. **Transmits** the invocation request to the remote peer via channels
8. **Executes** the code on the remote peer with the captured scope
9. **Returns** the result back to the caller

This explicit approach makes data flow clear and works correctly with reader conditionals like `#?(:clj ...)` since the argument list is visible before reader conditional evaluation.

### The `defn-go-remote` Macro

The `defn-go-remote` macro defines distributed functions by:

1. **Walking** the function body to find all `go-remote` invocations
2. **Extracting** each remote code block and creating a named function for it
3. **Defining** all remote functions upfront so they can be resolved by remote peers
4. **Maintaining** the original function structure with remote calls intact

### Architecture

The system consists of several components:

- **Peer**: Each node (client/server) has a unique ID and maintains bidirectional channels
- **Remote Middleware**: Intercepts messages and routes `::invoke` and `::invoke-result` messages
- **Internal Bus**: A pub/sub system for routing messages between local and remote handlers
- **Message Types**:
  - `::register-scope`: Announces a peer's scope to connected peers
  - `::invoke`: Requests remote execution of a function
  - `::invoke-result`: Returns the result (or error) of a remote execution

### Example

```clojure
(defn-go-remote demo [server-id client-id]
  (go-remote server-id [client-id server-id]
    (let [a 42
          b (<? S (go-remote client-id [a]
                    (repeat (inc a) 42)))]  ; 'a' is explicitly captured and sent to client
      (<? S (go-remote server-id [a b]
              (let [c (+ a 2)]   ; 'a' and 'b' are explicitly captured
                [b c]))))))      ; returns [(42 42 ...) 44]
```

In this example:
1. Execution starts on the server with `a = 42`
2. Computation moves to the client with `[a]` - explicitly sending `a`, which creates a list of 43 `42`s
3. Execution returns to the server with `[a b]` - explicitly capturing both variables to compute `[(42 42 ...) 44]`

The **explicit argument lists** `[a]` and `[a b]` make it clear what data is being sent across peers. The macro validates that all variables used in the remote body are listed (compile error if missing) and warns if you list variables that aren't used.

### Missionary sequential processes (`defn-sp-remote`)

If you prefer Missionary for sequential processes, use `defn-sp-remote` together with `sp-remote`. Nested `sp-remote` invocations expand to Missionary tasks, so you can compose them with `m/?` inside your flows.

Example:

```clojure
(ns simple.demo-sp
  (:require [is.simm.distributed-scope :refer [defn-sp-remote sp-remote]]
        [missionary.core :as m]))

(defn-sp-remote demo-sp [server-id client-id]
  (sp-remote
   server-id []
   (let [_ (prn "sp: starting on server")
       a 42
       b (m/? (sp-remote
             client-id [a]
             (prn "sp: continuing on client")
             (inc a)))]
     (m/? (sp-remote
         server-id [a b]
         (let [_ (prn "sp: finishing on server")
             c (+ a 2)]
         [b c]))))))          ; returns [43 44]
```

Notes:
- Each `sp-remote` returns a Missionary task; use `m/?` to await it inside your sequential process.
- The explicit argument vectors `[]`, `[a]`, and `[a b]` make data flow clear and enable compile-time validation.
- The top-level function defined by `defn-sp-remote` can be used similarly to the `go-remote` example in this project's tests (see below) and in `examples/simple/demo_sp.cljc`.

## Try the simple example

There is a minimal, end-to-end example in `examples/simple` with both Clojure and ClojureScript clients.

- Server and Clojure client: `simple.server`, `simple.client`
- ClojureScript client (browser): `simple.client` (CLJS) with `init` entrypoint
- Demo function: `simple.demo/demo` (defined via `defn-go-remote`)
    and `simple.demo-sp/demo-sp` (defined via `defn-sp-remote`)

From a REPL started with the dev alias, you can run a roundtrip:

```clojure
;; Start a dev REPL with extra paths
;; clj -A:dev

(require '[simple.server :as server]
         '[simple.client :as client]
         '[simple.demo :as demo]
         '[hasch.core :refer [uuid]]
         '[superv.async :refer [S <??]])

(def port 47291)
(def url (str "ws://localhost:" port))
(def server-id (uuid :server))
(def client-id (uuid :client))

(def started (server/start! url server-id))
(def _client (client/start! url client-id))

(<?? S (demo/demo server-id client-id))
;; => [(42 42 42 ...) 44]

(server/stop! started)
```

The integration test `test/is/simm/distributed_scope_test.clj` runs the same flow automatically.

To try the Missionary variant, require `simple.demo-sp` and call:

```clojure
(m/? (simple.demo-sp/demo-sp server-id client-id))
;; => [43 44]
```

## Development auto-reload (dev-only)

During development, you can enable a background file watcher that triggers `tools.namespace` refresh on changes. It lives in `is.simm.dev.watch` and starts automatically when required.

Usage (in a REPL started with `-A:dev`):

```clojure
(require 'is.simm.dev.watch)

;; Optionally configure a function to run after every successful refresh
;; The function must be a fully-qualified symbol, e.g., 'user/reset
(is.simm.dev.watch/set-after-refresh! 'user/reset)
```

By default it watches `src` and `resources` for `*.clj`, `*.cljc`, and `*.cljs` files with a short debounce to avoid chattiness. The watcher disables unload/reload for its own namespace so it keeps running across refreshes.

## Usage

### Starting a Server

```clojure
(require '[kabel.peer :as peer]
         '[is.simm.distributed-scope :refer [remote-middleware invoke-on-peer]])

(def server-id #uuid "05a06e85-e7ca-4213-9fe5-04ae511e50a0")
(def server (peer/server-peer S handler server-id remote-middleware identity))

(invoke-on-peer server)
(<?? S (peer/start server))
```

### Connecting a Client

```clojure
(def client-id #uuid "c14c628b-b151-4967-ae0a-7c83e5622d0f")
(def client (peer/client-peer S client-id remote-middleware identity))

(invoke-on-peer client)
(<?? S (peer/connect S client "ws://localhost:47291"))
```

### Running Distributed Functions

```clojure
;; Call the distributed function
(<?? S (demo server-id client-id))
;; => [(42 42 42 ...) 44]
```

## ClojureScript browser demo

The `shadow-cljs` build is configured with `:init-fn` pointing at the example client `simple.client/init`. Start the dev server and open the browser:

```bash
# In one terminal
shadow-cljs watch app

# In another terminal/REPL, start the server at the URL expected by the CLJS client
# default is ws://localhost:47291 (see examples/simple/client.cljs)
``` 

Then open http://localhost:8080 and check the browser console.

## Building and Testing

Run the Clojure tests:

    $ clojure -X:test

Run browser integration tests (requires Chrome/Chromium):

    $ npm install
    $ ./test-browser.sh

Run the project's CI pipeline and build a JAR:

    $ clojure -T:build ci

This will produce an updated `pom.xml` file with synchronized dependencies inside the `META-INF`
directory inside `target/classes` and the JAR in `target`. You can update the version (and SCM tag)
information in generated `pom.xml` by updating `build.clj`.

Install it locally (requires the `ci` task be run first):

    $ clojure -T:build install

Deploy it to Clojars -- needs `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` environment
variables (requires the `ci` task be run first):

    $ clojure -T:build deploy

Your library will be deployed to is.simm/distributed-scope on clojars.org by default.

## License

Copyright © 2025 Christian Weilbach

Distributed under the Apache License version 2.0.

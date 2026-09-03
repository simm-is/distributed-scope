# Change Log

All notable changes to this project will be documented in this file. This
change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Changed
- The runtime — connection middleware, function registry, authorization gate,
  request/response over a kabel connection — is now `kabel.remote` (kabel
  0.3.127). `remote-middleware`, `invoke-on-peer`, `invoke-remote`,
  `register-remote-fn!`, `connect-distributed-scope`, `*principal*`,
  `current-principal` and `require-principal` remain as thin wrappers, so
  existing code keeps working. The wire protocol is specified in kabel's
  `doc/remote-invocation.md`; frames in the old `:is.simm.distributed-scope/*`
  dialect are still accepted, and a peer speaking it is answered in it.
- Remote errors are typed `ex-info`s (`:kabel.remote/unknown-function`,
  `:kabel.remote/not-authorized`, `:kabel.remote/authentication-required`, or
  the thrown exception's own `:type`) instead of a printed string. A hop in
  flight when its connection closes fails with `:kabel.remote/disconnected`.
- `invoke-on-peer` accepts `:authorize` in the `kabel.authorize` map shape;
  the positional `:authorize-fn` is still accepted.
- Registered functions receive the caller's principal under `:kabel/principal`
  in their argument map as well as through `*principal*`.

### Removed
- The published artifact no longer contains the development watcher
  (`is.simm.dev.watch`, now under `dev/` behind the `:dev` alias), shadow-cljs
  output, or the ClojureScript, shadow-cljs, hawk, tools.namespace and riddley
  dependencies. The ClojureScript analyzer used for free-variable analysis is
  resolved at macro-expansion time from the consumer's cljs build. This is what
  made a JVM consumer's uberjar carry the ClojureScript compiler.
- The `connections`, `connection-promises` and `local-peers` atoms are no
  longer public; connection state lives in `kabel.remote`.

## [0.1.9]
- `:authorize-fn` control-plane gate on `invoke-on-peer`; replies ride the
  request's own connection; `:authentication-required` distinguished from
  `:not-authorized`.

## [0.1.1] - 2025-09-30
- Initial public releases.

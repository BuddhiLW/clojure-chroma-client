# clojure-chroma-client

<!-- hive-badges -->

[![Clojars Project](https://img.shields.io/clojars/v/io.github.hive-agi/clojure-chroma-client.svg)](https://clojars.org/io.github.hive-agi/clojure-chroma-client)
[![cljdoc](https://cljdoc.org/badge/io.github.hive-agi/clojure-chroma-client)](https://cljdoc.org/d/io.github.hive-agi/clojure-chroma-client/CURRENT)
[![release](https://github.com/BuddhiLW/clojure-chroma-client/actions/workflows/release.yml/badge.svg)](https://github.com/BuddhiLW/clojure-chroma-client/actions/workflows/release.yml)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

<!-- /hive-badges -->

**A Clojure client for the [Chroma](https://www.trychroma.com) vector
database.** Every API call returns a derefable promise; if the promise holds an
exception, it is thrown on deref.

## Coordinates

```clojure
;; deps.edn
io.github.hive-agi/clojure-chroma-client {:mvn/version "0.1.0"}
```

Dependencies are deliberately thin: `http-kit` for transport and `clj-json` for
encoding.

## Usage

```clojure
(require '[clojure-chroma-client.api :as chroma])

@(chroma/heartbeat)

(def coll @(chroma/create-collection "memories"))

;; An embedding record is a map of :id / :document / :metadata / :embedding
@(chroma/add coll [{:id "a" :document "first"  :metadata {:kind "note"}}
                   {:id "b" :document "second" :metadata {:kind "note"}}]
             :upsert? true)

@(chroma/query coll query-vector :num-results 5 :where {:kind "note"})
```

Options are keyword arguments, and collection-scoped calls take the collection
map returned by `create-collection` / `get-collection` — not its name.

| Area | Functions |
|---|---|
| Server | `version`, `heartbeat`, `reset` |
| Collections | `collections`, `page-seq`, `count-collections`, `create-collection`, `get-collection`, `update-collection`, `delete-collection`, `fork-collection` |
| Records | `add`, `add-batches`, `update`, `get`, `count`, `delete` |
| Search | `query`, `query-batch`, `search` |

`page-seq` continues a paginated listing lazily, following the `::next-page`
metadata on a result. `add-batches` splits a large write into batches
(`:batch-size`, default 32) and can run them with a chosen `:parallel` level.
`search` is hybrid vector + metadata + full-text search, and is Chroma Cloud
only — a local executor answers "Not implemented".

## API versions

Both Chroma v1 and v2 are supported. Bind `*api-version*` to `"v1"` or `"v2"`;
v2 is the default. Connection settings are read by
`clojure-chroma-client.config` from the environment.

## License

Apache-2.0.

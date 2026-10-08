(ns clojure-chroma-client.stub.server
  "Lifecycle of the in-process stub Chroma: an http-kit server on an
  ephemeral loopback port, and the client config bound to point at it.
  Test-only: lets the API tests run with no Chroma service."
  (:require [clojure-chroma-client.config :as config]
            [clojure-chroma-client.stub.handler :as handler]
            [clojure-chroma-client.stub.store :as store]
            [org.httpkit.server :as http]))

(defn start!
  "Start a stub on a free loopback port. Returns {:db :server :port}."
  []
  (let [db (atom (store/empty-db))
        server (http/run-server
                (handler/make-handler {:db db
                                       :new-id #(str (java.util.UUID/randomUUID))})
                {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    {:db db :server server :port (http/server-port server)}))

(defn stop!
  [{:keys [server]}]
  (http/server-stop! server))

(defn reset-db!
  "Drop every collection, as a fresh Chroma would have."
  [{:keys [db]}]
  (reset! db (store/empty-db)))

(defn call-with-stub
  "Run `(f stub)` with a started stub and the client config pointed at it,
  stopping the stub afterwards."
  [f]
  (let [stub (start!)]
    (try
      (binding [config/*protocol* "http"
                config/*host* "127.0.0.1"
                config/*port* (:port stub)
                config/*api-version* "v2"
                config/*api-key* nil
                config/*tenant* "default_tenant"
                config/*database* "default_database"]
        (f stub))
      (finally
        (stop! stub)))))

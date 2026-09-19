(ns clj-manifold3d.journal.server
  "Local document store. Datahike owns persisted state; .clj files are projections."
  (:require [clj-manifold3d.journal.schema :as schema]
            [clj-manifold3d.journal.document :as doc]
            [clj-manifold3d.journal.codex :as codex]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress URI]
           [java.nio.file Files StandardCopyOption]
           [java.nio.charset StandardCharsets]
           [java.util UUID]))

(def root (.getCanonicalFile (io/file ".")))
(def data-root (io/file (or (System/getenv "JOURNAL_DATA") "data/journal")))
(def port (Integer/parseInt (or (System/getenv "JOURNAL_PORT") "8090")))
(defonce connection (atom nil))
(defn db [] (d/db @connection))
(defn transact! [tx] (d/transact @connection tx))

(defn document [namespace]
  (when-let [id (d/q '[:find ?e . :in $ ?ns :where [?e :document/namespace ?ns]] (db) namespace)]
    (doc/from-entity (d/pull (db) doc/pull-pattern id))))

(defn documents []
  (mapv (comp doc/from-entity first)
        (d/q '[:find (pull ?e pattern) :in $ pattern :where [?e :document/id]]
             (db) doc/pull-pattern)))

(defn materialize! [document]
  (let [file (io/file data-root "documents" (doc/namespace-path (:namespace document)))]
    (io/make-parents file)
    (let [tmp (Files/createTempFile (.toPath (.getParentFile file)) ".journal-" ".clj" (make-array java.nio.file.attribute.FileAttribute 0))]
      (spit (.toFile tmp) (doc/source document))
      (Files/move tmp (.toPath file) (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE])))
    document))

(defn validate-document! [{:keys [namespace title blocks applied-requests]}]
  (when-not (and (doc/valid-namespace? namespace) (string? title) (<= (count title) 200)
                 (vector? blocks) (<= 1 (count blocks) 500)
                 (= (count blocks) (count (distinct (map :id blocks))))
                 (every? #(and (string? (:id %)) (re-matches #"[a-zA-Z0-9_-]+" (:id %))
                                (#{"code" "prose" "thinking"} (:kind %)) (string? (:source %))) blocks)
                 (or (nil? applied-requests)
                     (and (vector? applied-requests) (every? #(and (string? %) (re-matches #"[a-f0-9-]{36}" %)) applied-requests))))
    (throw (ex-info "A document needs a valid namespace, title, and uniquely identified code/prose blocks." {:status 400}))))

(defn save-document! [namespace incoming]
  (validate-document! incoming)
  (when-not (= namespace (:namespace incoming))
    (throw (ex-info "Namespace does not match the URL" {:status 400})))
  (let [old (document namespace)]
    (when (and old (not= (:revision old) (:revision incoming)))
      (throw (ex-info "This document changed in another window. Reload before saving." {:status 409})))
    ;; Block IDs cannot transfer ownership from another document.
    (doseq [id (map :id (:blocks incoming))]
      (when-let [owner (d/q '[:find ?ns . :in $ ?id
                              :where [?b :block/id ?id] [?d :document/blocks ?b]
                              [?d :document/namespace ?ns]] (db) id)]
        (when-not (= owner namespace) (throw (ex-info "Block belongs to another document" {:status 400})))))
    (let [next (assoc incoming :revision (inc (or (:revision old) -1)))
          tx (concat (map #(vector :db/retractEntity [:block/id (:id %)]) (:blocks old))
                     (doc/entity-tx next))]
      (transact! (vec tx))
      (materialize! next))))

(defn workspace []
  (let [id (d/q '[:find ?e . :where [?e :workspace/id "default"]] (db))
        w (when id (d/pull (db) '[*] id))
        panes (d/q '[:find [(pull ?e [:pane/id :pane/order :pane/width
                                      {:pane/document [:document/namespace]}]) ...]
                      :where [?e :pane/id]] (db))]
    {:vim (boolean (:workspace/vim? w)) :active-pane (:workspace/active-pane w)
     :panes (mapv (fn [p] {:id (:pane/id p) :document (get-in p [:pane/document :document/namespace])
                           :width (:pane/width p)}) (sort-by :pane/order panes))}))

(defn save-workspace! [{:keys [panes active-pane vim] :as w}]
  (when-not (and (vector? panes) (<= 1 (count panes) 6)
                 (= (count panes) (count (distinct (map :id panes))))
                 (some #(= active-pane (:id %)) panes)
                 (every? #(and (string? (:id %)) (re-matches #"[a-zA-Z0-9_-]+" (:id %))
                                (document (:document %)) (number? (:width %))
                                (<= 0.2 (:width %) 5)) panes))
    (throw (ex-info "Workspace needs 1–6 panes referring to saved documents" {:status 400})))
  (transact!
   (vec (concat (map #(vector :db/retractEntity [:pane/id (:id %)]) (:panes (workspace)))
                (map-indexed (fn [i p] {:pane/id (:id p) :pane/order i :pane/width (double (:width p))
                                        :pane/document [:document/id (:document p)]}) panes)
                [{:workspace/id "default" :workspace/active-pane active-pane :workspace/vim? (boolean vim)}])))
  w)

(defn initialize! []
  (.mkdirs data-root)
  (let [path (.getAbsolutePath (io/file data-root "database"))
        config {:store {:backend :file :path path :id (UUID/nameUUIDFromBytes (.getBytes path StandardCharsets/UTF_8))}
                :schema-flexibility :write :keep-history? true :initial-tx schema/attribute-tx}]
    (when-not (.exists (io/file path)) (d/create-database config))
    (reset! connection (d/connect config)))
  (transact! schema/attribute-tx)
  (codex/recover! @connection)
  (when (empty? (documents))
    (doseq [document (doc/examples)] (save-document! (:namespace document) document)))
  (when (empty? (:panes (workspace)))
    (save-workspace! {:vim false :active-pane "pane-first"
                      :panes [{:id "pane-first" :document "journal.first-shapes" :width 1}]}))
  ;; Repair projections after interruption between database commit and file move.
  (doseq [document (documents)] (materialize! document)))

(defn- respond! [exchange status value mime]
  (let [bytes (if (bytes? value) value (.getBytes (str value) StandardCharsets/UTF_8))]
    (doto (.getResponseHeaders exchange) (.set "Content-Type" mime)
      (.set "Cache-Control" "no-store") (.set "X-Content-Type-Options" "nosniff"))
    (.sendResponseHeaders exchange status (if (= "HEAD" (.getRequestMethod exchange)) -1 (alength bytes)))
    (with-open [out (.getResponseBody exchange)]
      (when-not (= "HEAD" (.getRequestMethod exchange)) (.write out bytes)))))

(defn- json! [exchange status x] (respond! exchange status (json/write-str x) "application/json; charset=utf-8"))
(defn- request-body [exchange]
  (let [bytes (.readNBytes (.getRequestBody exchange) 2000001)]
    (when (> (alength bytes) 2000000) (throw (ex-info "Document is too large" {:status 413})))
    (json/read-str (String. bytes StandardCharsets/UTF_8) :key-fn keyword)))

(defn- static-file [path]
  (let [[base relative] (cond
                          (#{"/" "/journal" "/journal/"} path) [(io/file root "public/journal") "index.html"]
                          (str/starts-with? path "/journal/") [(io/file root "public/journal") (subs path 9)]
                          (str/starts-with? path "/wasm/") [(io/file root "public/wasm") (subs path 6)])]
    (when base
      (let [base (.getCanonicalFile base) file (.getCanonicalFile (io/file base relative))]
        (when (and (.startsWith (.toPath file) (.toPath base)) (.isFile file)) file)))))

(defn- mime [file]
  (case (last (str/split (.getName file) #"\."))
    "html" "text/html; charset=utf-8" "js" "text/javascript" "css" "text/css"
    "wasm" "application/wasm" "application/octet-stream"))

(defn handle! [exchange]
  (try
    (let [path (.getPath (.getRequestURI exchange)) method (.getRequestMethod exchange)
          origin (.getFirst (.getRequestHeaders exchange) "Origin")
          host (.getFirst (.getRequestHeaders exchange) "Host")]
      ;; Local authenticated Codex access must not be reachable by DNS rebinding
      ;; or cross-site HTML form posts. Local scripts may use JSON without Origin.
      (when-not (re-matches #"(?:localhost|127\.0\.0\.1|\[::1\])(?::[0-9]+)?" (or host ""))
        (throw (ex-info "Only loopback hostnames are allowed" {:status 403})))
      (when (and origin (not= (.getAuthority (URI. origin)) host))
        (throw (ex-info "Cross-origin request rejected" {:status 403})))
      (when (and (#{"PUT" "POST"} method)
                 (not (str/starts-with? (or (.getFirst (.getRequestHeaders exchange) "Content-Type") "") "application/json")))
        (throw (ex-info "Expected application/json" {:status 415})))
      (cond
        (and (= method "GET") (= path "/api/state"))
        (json! exchange 200 {:documents (documents) :workspace (workspace)
                            :requests (mapv #(dissoc % :context) (codex/requests @connection))})
        (and (= method "POST") (= path "/api/codex/requests"))
        (json! exchange 202 (dissoc (codex/start! @connection root (request-body exchange)) :context))
        (and (= method "POST") (re-matches #"/api/codex/requests/[a-f0-9-]+/cancel" path))
        (json! exchange 200 (dissoc (codex/cancel! @connection (nth (str/split path #"/") 4)) :context))
        (and (= method "GET") (str/starts-with? path "/api/codex/requests/"))
        (if-let [request (codex/request @connection (subs path 20))]
          (json! exchange 200 (dissoc request :context))
          (json! exchange 404 {:error "Unknown Codex request"}))
        (and (= method "PUT") (= path "/api/workspace"))
        (json! exchange 200 (save-workspace! (request-body exchange)))
        (and (= method "PUT") (str/starts-with? path "/api/documents/"))
        (json! exchange 200 (save-document! (subs path 15) (request-body exchange)))
        (#{"GET" "HEAD"} method)
        (if-let [file (static-file path)]
          (respond! exchange 200 (Files/readAllBytes (.toPath file)) (mime file))
          (json! exchange 404 {:error "Not found"}))
        :else (json! exchange 405 {:error "Method not allowed"})))
    (catch Exception error (json! exchange (or (:status (ex-data error)) 400) {:error (.getMessage error)}))))

(defn -main [& _]
  (initialize!)
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" port) 0)]
    (.createContext server "/" (reify HttpHandler (handle [_ exchange] (handle! exchange))))
    (.start server)
    (println (str "Modeling Journal → http://localhost:" port "/journal/"))
    (.join (Thread/currentThread))))

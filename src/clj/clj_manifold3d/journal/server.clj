(ns clj-manifold3d.journal.server
  "Local document store. Datahike owns persisted state; .clj files are projections."
  (:require [clj-manifold3d.journal.schema :as schema]
            [clj-manifold3d.journal.document :as doc]
            [clj-manifold3d.journal.context :as context]
            [clj-manifold3d.journal.viewer :as viewer]
            [clj-manifold3d.journal.namespace :as ns-form]
            [clj-manifold3d.journal.codex :as codex]
            [clj-manifold3d.journal.generation :as gen]
            [clj-manifold3d.journal.projection :as projection]
            [clj-manifold3d.journal.collaboration :as collaboration]
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
(def bind-host (or (System/getenv "JOURNAL_BIND_HOST") "127.0.0.1"))
(def allowed-hosts
  (set (remove str/blank? (str/split (or (System/getenv "JOURNAL_ALLOWED_HOSTS")
                                         "localhost,127.0.0.1,[::1],johndesktop.tailbe6034.ts.net,johndesktop.tailbe6034.ts.net:8090") #","))))
(defn- allowed-host? [host]
  (or (contains? allowed-hosts host)
      (contains? allowed-hosts (first (str/split host #":" 2)))))
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
  (projection/materialize! data-root document))

(defn validate-document!
  ([incoming] (validate-document! incoming true))
  ([{:keys [namespace ns-source title blocks applied-requests instructions instructions-mode deleted-panels] :as incoming} active?]
  (when-not (and (doc/valid-namespace? namespace) (string? title) (<= (count title) 200)
                 (string? ns-source) (<= 1 (count ns-source) 50000)
                 (vector? blocks) (<= 1 (count blocks) 500)
                 (= (count blocks) (count (distinct (map :id blocks))))
                 (every? #(and (string? (:id %)) (re-matches #"[a-zA-Z0-9_-]+" (:id %))
                                (not (str/starts-with? (:id %) "namespace-"))
                                (#{"code" "prose" "thinking"} (:kind %)) (string? (:source %))
                                (or (nil? (:viewer %)) (and (string? (:viewer %)) (< (count (:viewer %)) 2000)
                                                            (viewer/valid-settings? (:viewer %))))
                                (or (nil? (:context-settings %))
                                    (and (string? (:context-settings %)) (<= (count (:context-settings %)) 50000)
                                         (context/valid-settings? (:context-settings %))))) blocks)
                 (or (nil? instructions) (and (string? instructions) (<= (count instructions) 20000)))
                 (or (nil? instructions-mode) (#{"extend" "replace"} instructions-mode))
                 (or (nil? applied-requests)
                     (and (vector? applied-requests) (every? #(and (string? %) (re-matches #"[a-f0-9-]{36}" %)) applied-requests))))
    (throw (ex-info "A document needs a valid namespace, title, and uniquely identified code/prose blocks." {:status 400})))
  (ns-form/assert-declaration! namespace ns-source)
  (when active? (doseq [b blocks :when (= "code" (:kind b))] (ns-form/assert-body! (:source b))))
  (when deleted-panels
    (when-not (and (string? deleted-panels) (<= (count deleted-panels) 1000000))
      (throw (ex-info "Deleted-panel history exceeds the size limit." {:status 400})))
    (let [history (doc/deletion-history incoming)]
      (when-not (and (vector? history) (<= (count history) 10)
                     (every? #(and (integer? (:index %)) (<= 0 (:index %) 500)) history))
        (throw (ex-info "Invalid deleted-panel history." {:status 400})))
      (doseq [{:keys [panel placeholder]} history p (remove nil? [panel placeholder])]
        (validate-document! (assoc incoming :deleted-panels nil :blocks [p]) false))))))

(defn prepare-document [namespace supplied]
  (let [old (document namespace) base (:base-document supplied)
        incoming (dissoc supplied :base-document)
        locked (codex/locked-ids @connection)
        stale? (and old (not= (:revision old) (:revision incoming)))
        _ (when base (validate-document! base false))
        _ (when (and stale? base)
            (let [before (into {} (map (juxt :id gen/content) (ns-form/panels base)))
                  after (into {} (map (juxt :id gen/content) (ns-form/panels incoming)))]
              (when (some #(and (contains? before %) (not= (get before %) (get after %))) locked)
                (throw (ex-info "Codex owns this code panel. Stop & edit to take over." {:status 423}))))
            (when (collaboration/conflicting-edits? base incoming old locked)
              (throw (ex-info "The same panel changed in another client. Reload to review the conflict." {:status 409}))))
        incoming (if (and stale? base)
                   (collaboration/merge-remote base incoming old locked) incoming)]
  (validate-document! incoming)
  (when-not (= namespace (:namespace incoming))
    (throw (ex-info "Namespace does not match the URL" {:status 400})))
  (let [old (document namespace)]
    (let [locked (codex/locked-ids @connection)
          before (into {} (map (juxt :id gen/content) (ns-form/panels old)))
          after (into {} (map (juxt :id gen/content) (ns-form/panels incoming)))
          before-order (filter locked (map :id (:blocks old)))
          after-order (filter locked (map :id (:blocks incoming)))]
      (when (or (some #(and (contains? before %) (not= (get before %) (get after %))) locked)
                (not= before-order after-order))
        (throw (ex-info "Codex owns this code panel while editing. Stop & edit to take over." {:status 423}))))
    (when (and old (:create-only supplied))
      (throw (ex-info "This namespace already exists. Reload to review it; it was not overwritten." {:status 409})))
    (when (some #(and (not= namespace (:namespace %))
                      (= (doc/namespace-path namespace) (doc/namespace-path (:namespace %)))) (documents))
      (throw (ex-info "This namespace would overwrite another namespace's file." {:status 409})))
    (when (and old (not= (:revision old) (:revision incoming)))
      (throw (ex-info "This document changed in another window. Reload before saving." {:status 409})))
    ;; Block IDs cannot transfer ownership from another document.
    (doseq [id (map :id (:blocks incoming))]
      (when-let [owner (d/q '[:find ?ns . :in $ ?id
                              :where [?b :block/id ?id] [?d :document/blocks ?b]
                              [?d :document/namespace ?ns]] (db) id)]
        (when-not (= owner namespace) (throw (ex-info "Block belongs to another document" {:status 400})))))
    (assoc (dissoc incoming :create-only) :revision (inc (or (:revision old) -1))))))

(defn save-documents!
  "Commit namespace creation and the referring document together. A revision
  conflict anywhere aborts the whole batch. Files remain database projections."
  [incoming]
  (locking @connection
    (when-not (and (vector? incoming) (<= 1 (count incoming) 100))
      (throw (ex-info "Expected one to one hundred documents." {:status 400})))
    (let [next (mapv #(prepare-document (:namespace %) %) incoming)
          paths (map #(doc/namespace-path (:namespace %)) next)
          ids (mapcat #(map :id (:blocks %)) next)]
      (when-not (and (= (count paths) (count (distinct paths))) (= (count ids) (count (distinct ids))))
        (throw (ex-info "Documents must have distinct namespace paths and panel IDs." {:status 409})))
      (transact! (vec (concat (for [d next b (:blocks (document (:namespace d)))]
                               [:db/retractEntity [:block/id (:id b)]])
                             (mapcat doc/entity-tx next))))
      (mapv materialize! next))))

(defn save-document! [namespace incoming]
  (when-not (= namespace (:namespace incoming))
    (throw (ex-info "Namespace does not match the URL" {:status 400})))
  (first (save-documents! [incoming])))

(defn workspace []
  (let [id (d/q '[:find ?e . :where [?e :workspace/id "default"]] (db))
        w (when id (d/pull (db) '[*] id))
        panes (d/q '[:find [(pull ?e [:pane/id :pane/order :pane/width
                                      {:pane/document [:document/namespace]}]) ...]
                      :where [?e :pane/id]] (db))]
    {:vim (boolean (:workspace/vim? w)) :instructions (:workspace/instructions w "") :active-pane (:workspace/active-pane w)
     :codex-model (:workspace/codex-model w "")
     :panes (mapv (fn [p] {:id (:pane/id p) :document (get-in p [:pane/document :document/namespace])
                           :width (:pane/width p)}) (sort-by :pane/order panes))}))

(defn save-workspace! [{:keys [panes active-pane vim instructions codex-model] :as w}]
  (when-not (and (gen/valid-model? codex-model) (vector? panes) (<= 1 (count panes) 6)
                 (= (count panes) (count (distinct (map :id panes))))
                 (some #(= active-pane (:id %)) panes)
                 (or (nil? instructions) (and (string? instructions) (<= (count instructions) 20000)))
                 (every? #(and (string? (:id %)) (re-matches #"[a-zA-Z0-9_-]+" (:id %))
                                (document (:document %)) (number? (:width %))
                                (<= 0.2 (:width %) 5)) panes))
    (throw (ex-info "Workspace needs 1–6 panes referring to saved documents" {:status 400})))
  (transact!
   (vec (concat (map #(vector :db/retractEntity [:pane/id (:id %)]) (:panes (workspace)))
                (map-indexed (fn [i p] {:pane/id (:id p) :pane/order i :pane/width (double (:width p))
                                        :pane/document [:document/id (:document p)]}) panes)
                [{:workspace/id "default" :workspace/active-pane active-pane :workspace/vim? (boolean vim)
                  :workspace/instructions (or instructions "") :workspace/codex-model (or codex-model "")}])))
  w)

(defn initialize! []
  (.mkdirs data-root)
  (let [path (.getAbsolutePath (io/file data-root "database"))
        config {:store {:backend :file :path path :id (UUID/nameUUIDFromBytes (.getBytes path StandardCharsets/UTF_8))}
                :schema-flexibility :write :keep-history? true :initial-tx schema/attribute-tx}]
    (when-not (.exists (io/file path)) (d/create-database config))
    (reset! connection (d/connect config)))
  (transact! schema/attribute-tx)
  ;; Read signatures from the implementation used by the journal, never require
  ;; or evaluate library code. Store the catalog as Datahike application state.
  (transact! [{:library/id "built-in"
               :library/catalog
               (pr-str (into {} (for [name ["core" "texture" "animation" "model" "mesh-io" "math"]
                                      :let [namespace (str "clj-manifold3d." name)
                                            file (if (= name "math") (io/file root "src/cljc/clj_manifold3d/math.cljc")
                                                     (io/file root "src/cljs/clj_manifold3d" (str (str/replace name "-" "_") ".cljs")))
                                            index (context/index-document
                                                   {:namespace namespace :blocks [{:id (str "library-" name) :kind "code" :source (slurp file)}]})]]
                                  [namespace index])))}])
  (codex/recover! @connection)
  ;; One-time migration: make previously implicit imports visible and hoist
  ;; literal standalone requires without reformatting any modeling code.
  (let [pending (filterv #(nil? (:ns-source %)) (documents))]
    (when (seq pending) (save-documents! (mapv ns-form/migrate pending))))
  (when (empty? (documents))
    (doseq [document (doc/examples)] (save-document! (:namespace document) document)))
  ;; Add the new example to existing libraries, never reset an edited example.
  (doseq [example (doc/examples)
          :when (and (#{"journal.flag-uv" "journal.castle-architecture" "journal.castle-night"} (:namespace example))
                     (not (document (:namespace example))))]
    (save-document! (:namespace example) example))
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
      (when-not (allowed-host? (or host ""))
        (throw (ex-info "Only loopback hostnames are allowed" {:status 403})))
      (when (and origin (not= (.getAuthority (URI. origin)) host))
        (throw (ex-info "Cross-origin request rejected" {:status 403})))
      (when (and (#{"PUT" "POST"} method)
                 (not (str/starts-with? (or (.getFirst (.getRequestHeaders exchange) "Content-Type") "") "application/json")))
        (throw (ex-info "Expected application/json" {:status 415})))
      (cond
        (and (= method "GET") (= path "/api/state"))
        (json! exchange 200 {:documents (documents) :workspace (workspace)
                            :library (:library/catalog (d/pull (db) '[:library/catalog] [:library/id "built-in"]))
                            :requests (mapv #(dissoc % :context :input :api-catalog :evaluation-input :turn-base) (codex/requests @connection))})
        (and (= method "GET") (= path "/api/codex/sessions"))
        (let [since (or (some->> (.getQuery (.getRequestURI exchange)) (re-find #"(?:^|&)since=([0-9]+)") second Long/parseLong) 0)
              cursor (codex/now)]
          (json! exchange 200 {:cursor cursor :requests
                               (mapv #(codex/public-request @connection %)
                                     (filter #(and (:collaborative? %) (or (gen/active? %) (>= (:updated % 0) since)))
                                             (codex/requests @connection)))}))
        (and (= method "GET") (= path "/api/codex/models"))
        (json! exchange 200 (codex/models! @connection root))
        (and (= method "POST") (= path "/api/codex/inspect"))
        (json! exchange 200 (codex/inspect-input root (dissoc (request-body exchange) :input)))
        (and (= method "POST") (= path "/api/codex/requests"))
        (json! exchange 202 (codex/public-request @connection (codex/start! @connection root (request-body exchange))))
        (and (= method "POST") (re-matches #"/api/codex/requests/[a-f0-9-]+/cancel" path))
        (json! exchange 200 (codex/public-request @connection (codex/cancel! @connection (nth (str/split path #"/") 4))))
        (and (= method "GET") (re-matches #"/api/codex/requests/[a-f0-9-]+/artifacts/[a-f0-9-]+" path))
        (let [[_ _ _ _ request-id _ artifact-id] (str/split path #"/")
              artifact (d/pull (db) '[*] [:artifact/id artifact-id])]
          (if (and (= request-id (:artifact/request artifact))
                   (= "complete" (:status (codex/request @connection request-id))))
            (respond! exchange 200 (:artifact/bytes artifact) "model/gltf-binary")
            (json! exchange 404 {:error "Unknown verified result"})))
        (and (= method "GET") (re-matches #"/api/codex/requests/[a-f0-9-]+/inspection" path))
        (if-let [request (codex/request @connection (nth (str/split path #"/") 4))]
          (json! exchange 200 (codex/inspect-input root request))
          (json! exchange 404 {:error "Unknown Codex request"}))
        (and (= method "GET") (str/starts-with? path "/api/codex/requests/"))
        (if-let [request (codex/request @connection (subs path 20))]
          (json! exchange 200 (codex/public-request @connection request))
          (json! exchange 404 {:error "Unknown Codex request"}))
        (and (= method "PUT") (= path "/api/workspace"))
        (json! exchange 200 (save-workspace! (request-body exchange)))
        (and (= method "PUT") (= path "/api/documents-batch"))
        (json! exchange 200 (save-documents! (request-body exchange)))
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
  (let [server (HttpServer/create (InetSocketAddress. bind-host port) 0)]
    (.createContext server "/" (reify HttpHandler (handle [_ exchange] (handle! exchange))))
    (.start server)
    (println (str "Modeling Journal → http://" bind-host ":" port "/journal/"))
    (.join (Thread/currentThread))))

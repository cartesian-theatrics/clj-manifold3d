(ns clj-manifold3d.journal.generation
  "Pure request/output transformations, shared by the browser and server."
  (:require [clojure.string :as str]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clj-manifold3d.journal.context :as context]
            [clj-manifold3d.journal.namespace :as ns-form]
            [clj-manifold3d.journal.document :as doc]))

(def active-statuses #{"queued" "running"})
(defn active? [request] (contains? active-statuses (:status request)))
(def fields [:id :namespace :model :resolved-model :prompt-block :prompt :context :input :snapshot :previous-prompt :status :progress :output :preview :error :created :updated :rejected-output :repair-input :repair-attempt :api-catalog :tool-log :evaluation-input :verification :verified-results :collaborative? :working-plan :turn-base])
(defn valid-model? [model]
  (or (nil? model) (= "" model)
      (and (string? model) (<= (count model) 128) (boolean (re-matches #"[A-Za-z0-9][A-Za-z0-9._:/-]*" model)))))
(defn entity [request]
  (into {} (keep (fn [k] (when-some [v (get request k)] [(keyword "request" (name k)) v])) fields)))
(defn from-entity [entity]
  (into {} (keep (fn [k] (when-some [v (get entity (keyword "request" (name k)))] [k v])) fields)))

(defn snapshot [request] (when-let [s (:snapshot request)] (edn/read-string s)))

(defn request-input
  ([document prompt-id request-id] (request-input document prompt-id request-id []))
  ([document prompt-id request-id requests]
   (request-input document prompt-id request-id requests [document] {}))
  ([document prompt-id request-id requests documents workspace]
  (let [prompt (first (filter #(= prompt-id (:id %)) (:blocks document)))
        prior (last (sort-by #(or (:created %) 0)
                            (filter #(and (= (:namespace document) (:namespace %))
                                          (= prompt-id (:prompt-block %)) (= "complete" (:status %))) requests)))
        selection (context/build document prompt-id documents workspace)]
    (when (and (= "prose" (:kind prompt)) (not (str/blank? (:source prompt))))
      {:id request-id :namespace (:namespace document) :prompt-block prompt-id
       :prompt (:source prompt) :previous-prompt (or (:prompt prior) "")
       :model (:codex-model workspace "")
       :snapshot (pr-str selection)
       :context ""}))))

(defn insert-after [blocks id additions]
  (vec (mapcat #(if (= id (:id %)) (cons % additions) [%]) blocks)))

(defn patch-source
  "Replace one uniquely matching literal fragment. Patches are sequential;
  before refers to the text AFTER earlier patches, not to guessed offsets.
  Empty before is only valid for an empty panel. Include a small unchanged
  anchor to insert text into a nonempty panel. Never use fuzzy matching."
  [source {:keys [before after]}]
  (when-not (and (string? source) (string? before) (string? after))
    (throw (ex-info "A patch needs literal before and after strings." {})))
  (let [at (str/index-of source before)]
    (when (or (nil? at)
              (and (empty? before) (seq source))
              (and (seq before) (some? (str/index-of source before (inc at)))))
      (throw (ex-info (if (nil? at) "Patch fragment was not found verbatim in the target panel."
                         "Patch fragment matches more than one location; include a unique surrounding form.")
                      {:type :patch-match :reason (if (nil? at) :missing :ambiguous) :before before})))
    (let [next (str (subs source 0 at) after (subs source (+ at (count before))))]
      (when (> (count next) 50000)
        (throw (ex-info "Patched panel exceeds the 50k character limit." {})))
      next)))

(defn edit-id [request i edit]
  (if (and (= "create" (:action edit)) (= "namespace" (:kind edit)))
    (ns-form/header-id (:target edit)) (str (:id request) "-" i)))

(defn patched-sources [request edits]
  (reduce (fn [sources [i {:keys [action target after kind] :as edit}]]
            (if (= "patch" action)
              (try (update sources target patch-source edit)
                   (catch #?(:clj Exception :cljs :default) e
                     (throw (ex-info (str "Edit " (inc i) ", panel " target ": " (ex-message e))
                                     (assoc (ex-data e) :edit-index i :target target
                                            :source (get sources target))))))
              (cond-> (assoc sources (edit-id request i edit) after)
                (and (= action "create") (not (contains? sources (ns-form/header-id target))) (not= kind "namespace"))
                (assoc (ns-form/header-id target) (ns-form/declaration target)))))
          (into {} (map (juxt :id :source) (:panels (snapshot request)))) (map-indexed vector edits)))

(defn creatable-namespace? [request target]
  (let [s (snapshot request)]
    (and (true? (:allow-create-namespaces? s)) (doc/valid-namespace? target)
         (not (re-find #"^(?:clj-manifold3d|clojure|cljs|sci|goog|js|math)(?:\.|$)" target))
         (not-any? #(= (doc/namespace-path target) (doc/namespace-path %))
                   (conj (vec (:existing-namespaces s)) (:namespace request))))))

(defn output-plan
  "Validate the complete edit plan before any document mutation. Insert IDs
  belong to the host; updates may only address panels in the sent snapshot."
  [request output]
  (let [edits (:edits output)
        ;; Host IDs are stable across the append-only session log, including
        ;; panels created in earlier turns. Later patches can address them.
        known-at (reductions (fn [known [i edit]]
                               (if (#{"insert" "create"} (:action edit))
                                 (cond-> (assoc known (edit-id request i edit)
                                                {:role "target" :kind (:kind edit)
                                                 :namespace (if (= "create" (:action edit)) (:target edit) (:namespace request))})
                                   (= "create" (:action edit))
                                   (assoc (ns-form/header-id (:target edit)) {:role "target" :kind "namespace" :namespace (:target edit)}))
                                 known))
                             (into {} (map (juxt :id identity) (:panels (snapshot request))))
                             (map-indexed vector edits))]
    (when-not (and (map? output) (string? (:summary output)) (<= (count (:summary output)) 2000)
                   (vector? edits) (<= (count edits) 100)
                   (every? (fn [[known {:keys [action target kind before after] :as edit}]]
                             (and (not (contains? edit :source))
                                  (#{"prose" "code" "namespace"} kind) (string? after) (<= (count after) 50000)
                                  (case action
                                    "insert" (and (nil? target) (nil? before) (not= "namespace" kind))
                                    "create" (and (nil? before) (creatable-namespace? request target))
                                    "patch" (and (string? before) (<= (count before) 50000)
                                                  (string? target) (not= target (:prompt-block request))
                                                  (= "target" (:role (get known target)))
                                                  (= kind (:kind (get known target))))
                                    false))) (map vector known-at edits)))
      (throw (ex-info "Codex returned an invalid edit plan or an unknown panel target." {})))
    (let [names (distinct (map :target (filter #(= "create" (:action %)) edits)))]
      (when (or (> (count names) 10) (not= (count names) (count (distinct (map doc/namespace-path names)))))
        (throw (ex-info "Create at most ten namespaces, each with a distinct file path." {}))))
    (let [sources (patched-sources request edits)]
      (doseq [{:keys [action target kind after]} edits
              :let [source (if (= "patch" action) (get sources target) after)]]
        (case kind
          "namespace" (ns-form/assert-declaration! (if (= "create" action) target
                                                       (or (:namespace (get (last known-at) target)) (:namespace request))) source)
          "code" (ns-form/assert-body! source)
          nil))
      (doseq [[_ group] (group-by :target (filter #(= "create" (:action %)) edits))]
        (when (> (count (filter #(= "namespace" (:kind %)) group)) 1)
          (throw (ex-info "A new document has exactly one namespace header." {:type :namespace-declaration})))))
    {:summary (:summary output)
     :edits (mapv (fn [i edit]
                    (cond-> (select-keys edit [:action :target :kind :before :after])
                      (#{"insert" "create"} (:action edit)) (assoc :id (edit-id request i edit)))) (range) edits)}))

(defn created-documents [request output]
  (let [plan (output-plan request output) sources (patched-sources request (:edits plan))]
  (mapv (fn [[namespace edits]]
          {:namespace namespace :title namespace :revision 0
           :ns-source (get sources (ns-form/header-id namespace) (ns-form/declaration namespace))
           :blocks (let [panels (mapv #(assoc (select-keys % [:id :kind]) :source (get sources (:id %)) :hidden false)
                                     (remove #(= "namespace" (:kind %)) edits))]
                     (if (seq panels) panels [{:id (str (:id request) "-empty-" (ns-form/header-id namespace)) :kind "prose" :source "" :hidden false}]))})
        (sort-by key (group-by :target (filter #(= "create" (:action %)) (:edits plan)))))))

(defn content [panel] (select-keys panel [:kind :source]))

(defn apply-output
  ([document request output] (apply-output document request output [document]))
  ([document request output documents]
  (let [id (:id request)]
    (when (and (= "complete" (:status request))
               (some #(and (= id (:id %)) (= "thinking" (:kind %))) (:blocks document))
               (not (some #{id} (:applied-requests document))))
      (let [plan (output-plan request output)
            before (into {} (map (juxt :id identity) (:panels (snapshot request))))
            current (into {} (map (juxt :id identity) (ns-form/panels document)))
            targets (set (filter #(contains? before %) (map :target (filter #(= "patch" (:action %)) (:edits plan)))))
            sources (patched-sources request (:edits plan))
            updates (select-keys sources targets)
            additions (mapv #(assoc (select-keys % [:id :kind]) :source (get sources (:id %))
                                    :hidden false :prompt-id (:prompt-block request))
                            (filter #(= "insert" (:action %)) (:edits plan)))
            conflict (cond
                       (when-let [header (get before (ns-form/header-id (:namespace document)))]
                         (not= (:source header) (:ns-source document)))
                       "The namespace imports changed while Codex was working. No changes applied; retry with the current imports."
                       (some (set (map #(doc/namespace-path (:namespace %)) documents))
                             (map #(doc/namespace-path (:namespace %)) (created-documents request output)))
                       "A requested namespace already exists. No changes applied; retry with the current context."
                       (not= (:prompt request) (:source (get current (:prompt-block request))))
                       "The prompt changed while Codex was working. No changes applied; retry with the current prompt."
                       (some #(not= (content (get before %)) (content (get current %))) (keys updates))
                       "A target panel was edited or deleted while Codex was working. No changes applied; retry to include your edits."
                       (> (+ (count (:blocks document)) (count additions)) 500)
                       "This response would exceed the 500-panel limit. No changes applied.")
            message (or conflict (str "Updated " (count updates) ", added " (count additions)
                                      (when (seq (created-documents request output))
                                        (str ", created namespaces: " (str/join ", " (map :namespace (created-documents request output)))))
                                      ". " (:summary plan)))
            blocks (mapv (fn [b]
                           (cond
                             (= id (:id b)) (assoc b :generation-status (if conflict "conflict" "applied")
                                                     :generation-message message)
                             (and (not conflict) (get updates (:id b)))
                             (assoc b :source (get updates (:id b)) :prompt-id (:prompt-block request))
                             :else b)) (:blocks document))]
        (-> document
            (cond-> (and (not conflict) (get updates (ns-form/header-id (:namespace document))))
              (assoc :ns-source (get updates (ns-form/header-id (:namespace document)))))
            (assoc :blocks (if conflict blocks (insert-after blocks id additions)))
            (update :applied-requests (fnil conj []) id)))))))

(ns clj-manifold3d.journal.api-catalog
  "Pure bounded discovery over a frozen, trusted library catalog. No user
  documents, paths, evaluation or network are accessible through these tools."
  (:require [clojure.string :as str]
            [clj-manifold3d.journal.runtime-api :as runtime]
            [clj-manifold3d.journal.document :as doc]))

(def tool-specs
  [{:type "function" :name "journal_api_search"
    :description "Discover APIs available in this journal by name or topic (e.g. texture, scene, animation). Returns signatures, docstrings and runtime limitations. Read-only; no code execution."
    :inputSchema {:type "object" :properties {:query {:type "string"}} :required ["query"] :additionalProperties false}}
   {:type "function" :name "journal_api_read"
    :description "Read a supported function's documentation and optionally its implementation. Use a fully qualified symbol returned by journal_api_search. Aliases resolve to their defining API. Read-only."
    :inputSchema {:type "object" :properties {:symbol {:type "string"} :include_source {:type "boolean"}}
                  :required ["symbol" "include_source"] :additionalProperties false}}
   {:type "function" :name "journal_api_examples"
    :description "Read runnable built-in examples, including their explicit ns imports. Topics: texture/flag/UV, animation/pivot/scene, boolean/solids, namespaces. Does not read user-edited example documents."
    :inputSchema {:type "object" :properties {:topic {:type "string"}} :required ["topic"] :additionalProperties false}}])
(def tool-names (set (map :name tool-specs)))

(defn- alias-target [indexes entry]
  (when-let [alias (:alias entry)]
    (let [sym (symbol alias) ns (:namespace entry) prefix (namespace sym)]
      (str (if prefix (get-in indexes [ns :imports :aliases prefix] prefix) ns) "/" (name sym)))))

(defn build [indexes]
  (let [definitions (apply merge (map :definitions (vals indexes)))
        resolve-entry (fn [entry]
                        (loop [current entry visited #{}]
                          (let [target (alias-target indexes current)]
                            (if (and target (not (visited target)) (get definitions target))
                              (recur (get definitions target) (conj visited target))
                              (merge (select-keys entry [:symbol :namespace :name])
                                     (select-keys current [:signature :doc :source])
                                     {:defined-by (:symbol current)})))))
        examples (mapv #(select-keys % [:namespace :title :ns-source :blocks]) (doc/examples))]
    {:capabilities runtime/capabilities
     :functions (into (sorted-map)
                      (for [[sym entry] definitions
                            :when (and (not (:private? entry)) (runtime/exposed? (:namespace entry) (:name entry)))]
                        [sym (resolve-entry entry)]))
     :examples examples}))

(defn- tokens [s]
  (map #(if (and (> (count %) 4) (str/ends-with? % "s")) (subs % 0 (dec (count %))) %)
       (remove str/blank? (str/split (str/lower-case s) #"[^a-z0-9_-]+"))))
(defn- score [query text]
  (reduce + (map #(if (str/includes? (str/lower-case text) %) 1 0) (tokens query))))
(defn- ranked [query entries text]
  (->> entries (map #(assoc % ::score (score query (text %))))
       (filter #(pos? (::score %))) (sort-by (juxt (comp - ::score) :symbol :namespace))))

(defn call [catalog name args]
  (when-not (tool-names name) (throw (ex-info "Unknown journal API tool." {})))
  (when-not (and (map? args) (<= (count (pr-str args)) 1000))
    (throw (ex-info "API lookup arguments must be a small object." {})))
  (when-not (= (set (keys args)) (case name "journal_api_search" #{:query}
                                     "journal_api_read" #{:symbol :include_source}
                                     "journal_api_examples" #{:topic}))
    (throw (ex-info "Unexpected API lookup arguments." {})))
  (let [text (get args (case name "journal_api_search" :query "journal_api_read" :symbol "journal_api_examples" :topic nil))]
    (when-not (and (string? text) (<= 1 (count text) 250))
      (throw (ex-info "Supply a query, fully qualified symbol, or example topic (1–250 characters)." {})))
    (case name
      "journal_api_search"
      (let [matches (ranked text (vals (:functions catalog)) #(str (:symbol %) " " (:doc %)))]
        {:capabilities (:capabilities catalog) :total (count matches)
         :matches (mapv #(select-keys % [:symbol :signature :doc :defined-by]) (take 12 matches))
         :note "Use journal_api_read for implementation details or journal_api_examples for runnable patterns."})
      "journal_api_read"
      (if-let [entry (get-in catalog [:functions text])]
        (do (when-not (boolean? (:include_source args)) (throw (ex-info "include_source must be boolean." {})))
            {:function (cond-> entry (not (:include_source args)) (dissoc :source))
             :capabilities (:capabilities catalog)})
        {:error "That symbol is not exposed by the journal runtime. Search the API catalog for supported alternatives."
         :capabilities (:capabilities catalog)})
      "journal_api_examples"
      {:capabilities (:capabilities catalog)
       :examples (mapv #(hash-map :namespace (:namespace %) :title (:title %) :source (doc/source %))
                       (take 2 (ranked text (:examples catalog)
                                       #(str (:title %) " " (:namespace %) " " (str/join " " (map :source (:blocks %)))))))}
      (throw (ex-info "Unknown journal API tool." {})))))

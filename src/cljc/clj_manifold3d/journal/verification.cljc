(ns clj-manifold3d.journal.verification
  "Pure construction of a disposable evaluation notebook. Never include excluded
  panels or unrelated definitions, and never mutate the user's live namespace."
  (:require [clj-manifold3d.journal.context :as context]
            [clj-manifold3d.journal.document :as doc]
            [clj-manifold3d.journal.generation :as gen]
            [clj-manifold3d.journal.namespace :as ns-form]
            [clojure.string :as str]))

(defn code-change? [plan]
  (boolean (some #(#{"code" "namespace"} (:kind %)) (:edits plan))))

(defn- definitions [document items]
  (let [index (context/index-document document)
        wanted (set (map :symbol items))
        entries (filter #(wanted (:symbol %)) (vals (:definitions index)))
        positions (zipmap (map :id (:blocks document)) (range))
        panels (into {} (map (juxt :id :source) (:blocks document)))
        entries (sort-by #(vector (get positions (:panel %) -1)
                                  (str/index-of (get panels (:panel %) "") (:source %))) entries)]
    (when (seq entries)
      {:id (str "verification-defs-" (ns-form/header-id (:namespace document))) :kind "code"
       :source (str "(declare " (str/join " " (map :name entries)) ")\n"
                    (str/join "\n" (map :source entries)))})))

(defn freeze
  "Snapshot selected panels plus the implementation closure of referenced vars.
  Implementations are used only inside the evaluator, not sent wholesale to the
  model. Excluded panels and unrelated vars never enter the evaluator."
  [request documents]
  (let [snapshot (gen/snapshot request) panels (:panels snapshot)
        original (first (filter #(= (:namespace request) (:namespace %)) documents))
        prompt (first (filter #(= (:prompt-block request) (:id %)) (:blocks original)))
        config (context/settings prompt)
        ;; Dependency inclusion is still governed by the context picker.
        deps (when (seq (:dependencies snapshot))
               (:items (context/dependencies documents original panels
                                             (assoc config :dependencies "implementations"))))
        grouped (group-by :namespace deps)
        header (first (filter #(= "namespace" (:kind %)) panels))
        main {:namespace (:namespace request) :title (:namespace request)
              :ns-source (:source header)
              :verification/full? (= (mapv :id (filter #(= "code" (:kind %)) (:blocks original)))
                                     (mapv :id (filter #(= "code" (:kind %)) panels)))
              :blocks (vec (remove #(= "namespace" (:kind %)) panels))}
        prefixes (into {} (keep (fn [d] (when-let [b (definitions d (get grouped (:namespace d)))]
                                         [(:namespace d) b])) documents))
        deps-docs (vec (for [d documents
                            :when (and (not= (:namespace d) (:namespace request))
                                       (get prefixes (:namespace d)))]
                        {:namespace (:namespace d) :ns-source (:ns-source d)
                         :blocks [(get prefixes (:namespace d))]}))
        provided (conj (set (map :namespace deps-docs)) (:namespace request))
        ;; An unused :require can load an empty namespace, but cannot inspect
        ;; any of its unselected vars. Only names explicitly in headers qualify.
        imports (set (for [d (conj deps-docs main)
                           {:keys [form]} (:forms (ns-form/parse-code (:ns-source d)))
                           spec (ns-form/require-specs form)]
                       (str (if (vector? spec) (first spec) spec))))
        stubs (for [d documents :when (and (imports (:namespace d)) (not (provided (:namespace d))))]
                {:namespace (:namespace d) :ns-source (ns-form/declaration (:namespace d) []) :blocks []})]
    {:main main :prefix (get prefixes (:namespace request))
     :dependencies (into deps-docs stubs)}))

(defn candidate [request frozen plan]
  (when (code-change? plan)
    (let [main (:main frozen)
          _ (when-not (:ns-source main)
              (throw (ex-info "Include the namespace header as a reference or target to test code." {:type :verification-context})))
          main (update main :blocks gen/insert-after (:prompt-block request)
                       [{:id (:id request) :kind "thinking" :source ""}])
          edited (gen/apply-output main (assoc request :status "complete") plan)
          edited (cond-> edited (:prefix frozen) (update :blocks #(into [(:prefix frozen)] %)))
          created (mapv #(assoc % :verification/full? true) (gen/created-documents request plan))]
      {:documents (into [edited] (concat created (:dependencies frozen)))
       :namespaces (into [(:namespace request)] (map :namespace created))})))

(defn matching-run? [document run]
  (let [sources (into {} (map (juxt :id :source) (:blocks document)))]
    (and (= "passed" (:status run)) (= (:namespace document) (:namespace run))
         (= (:ns-source document) (:ns-source run))
         (or (not (:full? run)) (= (mapv #(select-keys % [:id :source]) (filter #(= "code" (:kind %)) (:blocks document))) (:sources run)))
         (every? #(= (:source %) (get sources (:id %))) (:sources run)))))

(defn diagnostic [report]
  ;; Neither binary assets nor source snapshots belong in repair messages/logs.
  (update report :runs
          #(mapv (fn [run]
                   (-> (dissoc run :sources :ns-source)
                       (update :results (fn [rs] (mapv (fn [r] (dissoc r :asset-base64 :polygons :asset)) rs))))) %)))

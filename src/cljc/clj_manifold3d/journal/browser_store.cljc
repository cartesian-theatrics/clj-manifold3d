(ns clj-manifold3d.journal.browser-store
  "Pure snapshot transactions for the optional, backend-free journal. The live
  application database is still DataScript; IndexedDB only persists snapshots."
  (:require [clj-manifold3d.journal.document :as doc]
            [clj-manifold3d.journal.namespace :as ns-form]))

(def example-namespaces doc/curated-namespaces)

(defn seed []
  {:documents (mapv #(assoc % :revision 0) (filter #(example-namespaces (:namespace %)) (doc/examples)))
   :workspace {:vim false :instructions "" :codex-model ""
               :active-pane "pane-night" :panes doc/castle-panes}
   :requests [] :library "{}"})

(defn add-missing-examples [snapshot]
  (let [existing (set (map :namespace (:documents snapshot)))]
    (update snapshot :documents into (remove #(existing (:namespace %)) (:documents (seed))))))

(defn validate-documents! [documents]
  (when-not (and (vector? documents) (seq documents)
                (= (count documents) (count (set (map :namespace documents)))))
    (throw (ex-info "Expected documents with unique namespaces." {})))
  (doseq [{:keys [namespace title ns-source blocks]} documents]
    (when-not (and (doc/valid-namespace? namespace) (string? title)
                  (string? ns-source) (vector? blocks)
                  (every? #(and (string? (:id %)) (seq (:id %))
                                (#{"code" "prose" "thinking"} (:kind %))
                                (string? (:source %))) blocks))
      (throw (ex-info "Invalid journal document." {:namespace namespace})))
    (ns-form/assert-declaration! namespace ns-source))
  (let [ids (mapcat #(map :id (ns-form/panels %)) documents)]
    (when-not (= (count ids) (count (set ids)))
      (throw (ex-info "Panel IDs must be unique across documents." {}))))
  documents)

(defn save-documents [snapshot incoming]
  (validate-documents! incoming)
  (let [previous (into {} (map (juxt :namespace identity)) (:documents snapshot))
        saved (mapv (fn [{:keys [namespace revision create-only] :as document}]
                      (let [old (get previous namespace)]
                        (when (and old (or create-only (not= revision (:revision old 0))))
                          (throw (ex-info (str namespace " changed in another tab. Export your edits before reloading.")
                                          {:type :conflict :namespace namespace})))
                        (-> document (dissoc :base-document :create-only)
                            (assoc :revision (if old (inc (:revision old 0)) 0))))) incoming)
        documents (->> saved (reduce #(assoc %1 (:namespace %2) %2) previous) vals
                       (sort-by :namespace) vec)]
    (validate-documents! documents)
    [(assoc snapshot :documents documents) saved]))

(defn save-workspace [snapshot workspace]
  (let [panes (:panes workspace) ids (map :id panes)
        namespaces (set (map :namespace (:documents snapshot)))]
    (when-not (and (vector? panes) (<= 1 (count panes) 6)
                  (= (count ids) (count (set ids)))
                  (some #{(:active-pane workspace)} ids)
                  (every? #(and (string? (:id %)) (seq (:id %))
                                (namespaces (:document %))
                                (number? (:width %)) (<= 0.2 (:width %) 5)) panes))
      (throw (ex-info "Invalid workspace layout or missing document." {})))
    [(assoc snapshot :workspace workspace) workspace]))

(defn backup [documents workspace]
  {:format :modeling-journal :version 1 :documents documents :workspace workspace})

(defn read-backup [value]
  (when-not (and (= :modeling-journal (:format value)) (= 1 (:version value)))
    (throw (ex-info "Not a Modeling Journal v1 backup." {})))
  (validate-documents! (:documents value))
  (save-workspace value (:workspace value))
  value)

(ns clj-manifold3d.journal.generation
  "Pure request/output transformations, shared by the browser and server."
  (:require [clojure.string :as str]
            [clj-manifold3d.journal.document :as doc]))

(def active-statuses #{"queued" "running"})
(defn active? [request] (contains? active-statuses (:status request)))
(def fields [:id :namespace :prompt-block :prompt :context :status :progress :output :error :created :updated])
(defn entity [request]
  (into {} (keep (fn [k] (when-some [v (get request k)] [(keyword "request" (name k)) v])) fields)))
(defn from-entity [entity]
  (into {} (keep (fn [k] (when-some [v (get entity (keyword "request" (name k)))] [k v])) fields)))

(defn request-input [document prompt-id request-id]
  (let [prompt (first (filter #(= prompt-id (:id %)) (:blocks document)))
        before (take-while #(not= prompt-id (:id %)) (:blocks document))]
    (when (and (= "prose" (:kind prompt)) (not (str/blank? (:source prompt))))
      {:id request-id :namespace (:namespace document) :prompt-block prompt-id
       :prompt (:source prompt)
       :context (doc/source (assoc document :blocks (filter #(not= "thinking" (:kind %)) before)))})))

(defn insert-after [blocks id additions]
  (vec (mapcat #(if (= id (:id %)) (cons % additions) [%]) blocks)))

(defn output-blocks [id output]
  (when-not (and (map? output) (vector? (:panels output)) (<= 1 (count (:panels output)) 20)
                 (every? #(and (#{"prose" "code"} (:kind %)) (string? (:source %))
                                (<= (count (:source %)) 50000)) (:panels output)))
    (throw (ex-info "Codex returned invalid panels; expected prose/code source strings." {})))
  (mapv (fn [i panel] (assoc (select-keys panel [:kind :source]) :id (str id "-" i) :hidden false))
        (range) (:panels output)))

(defn apply-output [document request panels]
  (let [id (:id request)]
    (when (and (= "complete" (:status request))
               (some #(and (= id (:id %)) (= "thinking" (:kind %))) (:blocks document))
               (not (some #{id} (:applied-requests document))))
      (-> document
          (update :blocks insert-after id panels)
          (update :applied-requests (fnil conj []) id)))))

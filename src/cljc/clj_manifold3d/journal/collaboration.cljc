(ns clj-manifold3d.journal.collaboration
  "Pure shared editing-session rules. The operation log only grows; repair
  turns edit the current panels, never replay an earlier proposed document."
  (:refer-clojure :exclude [extends?])
  (:require [clj-manifold3d.journal.generation :as gen]
            [clj-manifold3d.journal.namespace :as ns-form]
            [clj-manifold3d.journal.document :as doc]))

(def empty-plan {:summary "" :edits []})
(defn extends? [old-edits edits]
  (= (vec old-edits) (vec (take (count old-edits) edits))))

(defn panels [request plan]
  (let [sources (gen/patched-sources request (:edits plan))
        original (:panels (gen/snapshot request))
        added (for [e (:edits plan) :when (#{"insert" "create"} (:action e))]
                {:id (:id e) :kind (:kind e) :role "target" :action (:action e)
                 :namespace (if (= "create" (:action e)) (:target e) (:namespace request))})
        main (gen/insert-after original (:prompt-block request) (filter #(= "insert" (:action %)) added))
        created (mapcat (fn [namespace]
                          (cons {:id (ns-form/header-id namespace) :kind "namespace" :role "target" :namespace namespace}
                                (filter #(and (= namespace (:namespace %)) (not= "namespace" (:kind %))) added)))
                        (distinct (map :target (filter #(= "create" (:action %)) (:edits plan)))))]
    ;; Preserve execution order even for documents large enough to exceed an
    ;; array-map's ordered representation. Repairs must see definitions first.
    (mapv #(-> % (dissoc :action) (assoc :source (get sources (:id %))
                                       :namespace (or (:namespace %) (:namespace request))))
          (concat main created))))

(defn locked-ids [request plan]
  (if (and (:collaborative? request) (gen/active? request))
    (into #{} (comp (filter #(and (= "target" (:role %)) (#{"code" "namespace"} (:kind %)))) (map :id))
          (panels request plan)) #{}))

(defn changes [request old-plan plan documents]
  (let [before (into {} (map (juxt :id :source) (panels request old-plan)))
        after (into {} (map (juxt :id :source) (panels request plan)))
        originals (into {} (map (juxt :namespace identity) documents))
        created (gen/created-documents request plan)
        fresh (remove #(get originals (:namespace %)) created)
        added (filter #(= "insert" (:action %)) (:edits plan))
        touched (set (keep (fn [[id source]] (when (not= source (get before id)) id)) after))]
    (let [present (set (map :id (mapcat ns-form/panels documents)))]
      (when (some #(and (contains? before %) (not (present %))) touched)
        (throw (ex-info "A target panel was deleted during this editing session." {:type :editing-conflict :status 409}))))
    (mapv
     (fn [document]
       (let [namespace (:namespace document) header-id (ns-form/header-id namespace)
             update-panel (fn [b]
                            (if (touched (:id b))
                              (do (when (and (contains? before (:id b)) (not= (:source b) (get before (:id b))))
                                    (throw (ex-info "Panel changed outside this editing session; stop and retry with the current source." {:type :editing-conflict :status 409})))
                                  (assoc b :source (get after (:id b)) :prompt-id (:prompt-block request))) b))
             existing (set (map :id (:blocks document)))
             additions (when (= namespace (:namespace request))
                         (mapv #(assoc (select-keys % [:id :kind]) :source (get after (:id %))
                                       :hidden false :prompt-id (:prompt-block request)) (remove #(existing (:id %)) added)))
             created-additions (when-let [d (first (filter #(= namespace (:namespace %)) created))]
                                 (remove #(existing (:id %)) (:blocks d)))
             blocks (mapv update-panel (:blocks document))
             ;; Insert after the last session-owned panel, preserving earlier
             ;; additions and unrelated user panels between requests.
             anchor (or (:id (last (filter #(some #{(:id %)} (map :id added)) blocks))) (:id request))
             blocks (if (seq additions) (gen/insert-after blocks anchor additions) blocks)]
         (when (> (+ (count blocks) (count created-additions)) 500)
           (throw (ex-info "Document exceeds the 500-panel limit." {:status 409})))
         (when (and (touched header-id) (contains? before header-id)
                    (not= (:ns-source document) (get before header-id)))
           (throw (ex-info "Namespace imports changed outside this editing session." {:type :editing-conflict :status 409})))
         (cond-> (assoc document :blocks (into blocks created-additions))
           (touched header-id) (assoc :ns-source (get after header-id)))))
     (concat (filter #(or (= (:namespace request) (:namespace %))
                         (some #{(:namespace %)} (map :namespace created))) documents)
             fresh))))

(defn merge-remote
  "Three-way merge server edits with locally unsaved changes to other panels.
  Locked source is server-owned. Presentation changes remain user-owned."
  [base local remote locked]
  (if-not base remote
    (let [old (into {} (map (juxt :id identity) (:blocks base)))
          current (into {} (map (juxt :id identity) (:blocks local)))
          received (set (map :id (:blocks remote)))
          merge-panel (fn [r]
                        (if-let [l (get current (:id r))]
                          (merge r (into {} (filter (fn [[k v]]
                                                     (and (not (#{:id :generation-status :generation-message} k))
                                                          (not (and (= :source k) (locked (:id r))))
                                                          (not= v (get-in old [(:id r) k])))) l))) r))
          blocks (reduce (fn [blocks b]
                           (let [following (rest (drop-while #(not= (:id b) (:id %)) (:blocks local)))
                                 ids (set (map :id blocks))
                                 next-id (:id (first (filter #(ids (:id %)) following)))
                                 [head tail] (split-with #(not= next-id (:id %)) blocks)]
                             (vec (concat head [b] tail))))
                         (mapv merge-panel (remove #(and (old (:id %)) (not (current (:id %))) (not (locked (:id %)))) (:blocks remote)))
                         (remove #(or (old (:id %)) (received (:id %))) (:blocks local)))
          local-attrs (into {} (filter (fn [[k v]] (and (not (#{:blocks :revision :applied-requests} k))
                                                       (not (and (= :ns-source k) (locked (ns-form/header-id (:namespace remote)))))
                                                       (not= v (get base k)))) local))]
      (merge remote local-attrs {:blocks blocks}))))

(defn conflicting-edits? [base local remote locked]
  (let [index #(into {} (map (juxt :id gen/content) (ns-form/panels %)))
        b (index base) l (index local) r (index remote)]
    (or (some (fn [id] (and (not (locked id)) (not= (get b id) (get l id))
                            (not= (get b id) (get r id)) (not= (get l id) (get r id)))) (keys b))
        (some #(and (not= (get base %) (get local %)) (not= (get base %) (get remote %))
                    (not= (get local %) (get remote %))) [:title :instructions :instructions-mode]))))

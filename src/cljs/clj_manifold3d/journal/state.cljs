(ns clj-manifold3d.journal.state
  (:require [datascript.core :as d]
            [clj-manifold3d.journal.schema :as schema]
            [clj-manifold3d.journal.generation :as gen]
            [clj-manifold3d.journal.context :as context]
            [clj-manifold3d.journal.namespace :as ns-form]
            [clj-manifold3d.journal.verification :as verification]
            [clj-manifold3d.journal.collaboration :as collaboration]
            [cljs.reader :as reader]
            [clj-manifold3d.journal.document :as doc]))

(defonce conn (d/create-conn schema/schema))
(defn transact! [tx] (d/transact! conn tx))
(defn q [query & args] (apply d/q query @conn args))
(defn pull [pattern id] (d/pull @conn pattern id))

(defn subscribe!
  "Recompute a Datalog query after transactions; notify only on value changes.
  Callback receives one result, including scalar queries. Returns disposer."
  [query callback & inputs]
  (let [key (random-uuid) previous (atom ::uninitialized)
        run (fn [db]
              (let [result (apply d/q query db inputs)]
                (when-not (= @previous result)
                  (reset! previous result)
                  (callback result))))]
    (d/listen! conn key #(run (:db-after %)))
    (run @conn)
    #(d/unlisten! conn key)))

(defn document [namespace]
  (doc/from-entity (pull doc/pull-pattern [:document/namespace namespace])))
(defn documents []
  (mapv doc/from-entity
        (q '[:find [(pull ?d pattern) ...] :in $ pattern :where [?d :document/id]] doc/pull-pattern)))
(defn panes []
  (sort-by :pane/order (q '[:find [(pull ?p [* {:pane/document [:document/namespace]}]) ...]
                           :where [?p :pane/id]])))
(defn workspace [] (pull '[*] [:workspace/id "default"]))
(defn workspace-data []
  {:vim (:workspace/vim? (workspace) false)
   :instructions (:workspace/instructions (workspace) "")
   :codex-model (:workspace/codex-model (workspace) "")
   :active-pane (:workspace/active-pane (workspace))
   :panes (mapv #(hash-map :id (:pane/id %) :width (:pane/width %)
                           :document (get-in % [:pane/document :document/namespace])) (panes))})
(defn workspace! [attrs] (transact! [(assoc attrs :workspace/id "default")]))
(defn initialize! [{:keys [documents workspace requests library]}]
  (transact! (vec (concat (mapcat doc/entity-tx documents)
                         (map gen/entity requests)
                         (map-indexed (fn [i p] {:pane/id (:id p) :pane/order i :pane/width (:width p)
                                                :pane/document [:document/id (:document p)]}) (:panes workspace))
                         [{:workspace/id "default" :workspace/vim? (:vim workspace false)
                           :workspace/instructions (:instructions workspace "")
                           :workspace/codex-model (:codex-model workspace "")
                           :workspace/active-pane (:active-pane workspace)
                           :ui/engine "starting" :ui/save-status "Saved"}
                          {:library/id "built-in" :library/catalog (or library "{}")}]))))

(defn context-workspace []
  (assoc (workspace-data) :library (reader/read-string (:library/catalog (pull '[:library/catalog] [:library/id "built-in"]) "{}"))))

(defn put-document! [document] (transact! (doc/entity-tx document)))
(defn open-castle-panes! []
  (transact! (vec (concat (map #(vector :db/retractEntity [:pane/id (:pane/id %)]) (panes))
                         (map-indexed (fn [i p] {:pane/id (:id p) :pane/order i :pane/width (:width p)
                                                :pane/document [:document/id (:document p)]}) doc/castle-panes)
                         [{:workspace/id "default" :workspace/active-pane "pane-night"}]))))
(defn import-documents!
  "Replace imported documents atomically, preserving save baselines and panes.
  Retract old multi-valued refs and previews, including panels absent in backup."
  [incoming]
  (transact!
   (vec (concat
         (mapcat (fn [{:keys [namespace]}]
                   (let [old (document namespace) entity (pull '[*] [:document/id namespace])]
                     (concat
                      (for [b (:blocks old)] [:db/retractEntity [:block/id (:id b)]])
                      (for [b (ns-form/panels old) :when (pull '[:result/id] [:result/id (:id b)])]
                        [:db/retractEntity [:result/id (:id b)]])
                      (for [attr [:document/instructions :document/instructions-mode :document/deleted-panels
                                  :document/applied-requests]
                            :when (contains? entity attr)]
                        [:db/retractAttribute (:db/id entity) attr])))) incoming)
         (mapcat doc/entity-tx incoming)))))
(defn request [id] (gen/from-entity (pull '[*] [:request/id id])))
(defn requests []
  (mapv gen/from-entity (q '[:find [(pull ?r [*]) ...] :where [?r :request/id]])))
(defn working-plan [r]
  (if-let [s (:working-plan r)] (js->clj (js/JSON.parse s) :keywordize-keys true) collaboration/empty-plan))
(defn owner [id]
  (first (filter #((collaboration/locked-ids % (working-plan %)) id) (requests))))
(defn drafts [namespace]
  (mapcat (fn [request]
            (when (and (= namespace (:namespace request)) (gen/active? request)
                       (not (some #{(:id request)} (:applied-requests (document namespace))))
                       (pull '[:block/id] [:block/id (:id request)]))
              (map #(assoc % :request-id (:id request))
                   (js->clj (js/JSON.parse (or (:preview request) "[]")) :keywordize-keys true))))
          (sort-by #(or (:created %) 0) (requests))))
(defn in-place-drafts [namespace]
  (let [current (into {} (map (juxt :id identity) (ns-form/panels (document namespace))))]
    (merge
     (reduce (fn [result {:keys [action target request-id] :as draft}]
              (let [r (request request-id)
                    original (first (filter #(= target (:id %)) (:panels (gen/snapshot r))))]
                (if (and (= "patch" action) (get current target)
                         (= (:prompt r) (:source (get current (:prompt-block r))))
                         (= (gen/content original) (gen/content (get current target))))
                  (let [prior (when (= request-id (:request-id (get result target))) (get result target))]
                    (assoc result target
                           (assoc draft :source (gen/patch-source (or (:source prior) (:source original)) draft)
                                        :patches (conj (or (:patches prior) []) (select-keys draft [:before :after])))))
                  result))) {} (filter #(not (:collaborative? (request (:request-id %)))) (drafts namespace)))
     (into {}
           (mapcat
            (fn [r]
              (let [edits (js->clj (js/JSON.parse (or (:preview r) "[]")) :keywordize-keys true)
                    originals (into {} (map (juxt :id :source) (:panels (gen/snapshot r))))
                    sources (gen/patched-sources r edits)
                    ids (into (collaboration/locked-ids r (working-plan r)) (map :target (filter #(= "patch" (:action %)) edits)))]
                (for [id ids :when (contains? current id)]
                  [id {:request-id (:id r) :source (get sources id (:source (get current id)))
                       :base (get originals id (if (some #(and (= id (:id %)) (#{"insert" "create"} (:action %))) edits) "" (:source (get current id))))
                       :patches (vec (keep (fn [e]
                                             (cond
                                               (and (= "patch" (:action e)) (= id (:target e))) (select-keys e [:before :after])
                                               (and (#{"insert" "create"} (:action e)) (= id (:id e))) {:before "" :after (:after e)})) edits))}])) )
            (filter #(and (:collaborative? %) (gen/active? %)
                          (or (= namespace (:namespace %))
                              (some #{namespace} (map :target (filter (fn [e] (= "create" (:action e))) (:edits (working-plan %)))))))
                    (requests)))))))
(defn request! [request] (transact! [(gen/entity request)]))
(defn request-input [namespace prompt-id id]
  (gen/request-input (document namespace) prompt-id id (requests) (documents) (context-workspace)))
(defn context-settings! [id config]
  (transact! [{:block/id id :block/context-settings (pr-str config)}]))
(defn begin-request!
  ([namespace prompt-id] (begin-request! namespace prompt-id nil))
  ([namespace prompt-id prepared]
  (let [document (document namespace) id (or (:id prepared) (str (random-uuid)))
        request (request-input namespace prompt-id id)]
    (when (and prepared (not= prepared request))
      (throw (js/Error. "Context changed since inspection. Inspect the request again before sending.")))
    (when request
      (let [thinking {:id id :kind "thinking" :source (str "Codex response to prose block " prompt-id) :hidden false}]
        (transact! (conj (doc/entity-tx (update document :blocks gen/insert-after prompt-id [thinking]))
                         (gen/entity (assoc request :collaborative? true :status "queued" :progress "Sending prompt to Codex…"))))
        request)))))
(defn accept-document!
  ([remote locked] (accept-document! remote locked nil))
  ([remote locked sent]
  (let [namespace (:namespace remote) local (document namespace)
        saved (:document/saved-content (pull '[:document/saved-content] [:document/id namespace]))
        base (or sent (when (seq saved) (reader/read-string saved)))]
    (when (>= (:revision remote) (:revision local -1))
      (let [next (collaboration/merge-remote base local remote locked)
            old (into {} (map (juxt :id gen/content) (:blocks local)))
            ids (set (map :id (:blocks next)))
            changed (if (not= (:ns-source local) (:ns-source next)) (:blocks next)
                        (drop-while #(= (gen/content %) (get old (:id %))) (:blocks next)))
            stale (keep #(when (pull '[:result/id] [:result/id (:id %)]) {:result/id (:id %) :result/status "stale"}) changed)]
        (transact! (into (vec (mapcat (fn [b] (cond-> [[:db/retractEntity [:block/id (:id b)]]]
                                              (pull '[:result/id] [:result/id (:id b)])
                                              (conj [:db/retractEntity [:result/id (:id b)]])))
                                     (remove #(ids (:id %)) (:blocks local))))
                         (concat (doc/entity-tx next) stale
                                 [{:document/id namespace :document/saved-content (pr-str (dissoc remote :revision))}]))))))))

(defn accept-request! [request]
  (let [previous (clj-manifold3d.journal.state/request (:id request))]
  (when (>= (:updated request 0) (:updated previous 0))
  (request! request)
  (if (:collaborative? request)
    (let [prior (documents)]
      (doseq [remote (:documents request)]
        (let [locked (if (or (gen/active? request) (gen/active? previous))
                       (into (collaboration/locked-ids (assoc request :status "running") (working-plan request))
                             (map :target (filter #(= "patch" (:action %)) (:edits (working-plan request))))) #{})
              _ (accept-document! remote locked)]))
      (not= prior (documents)))
  (let [document (document (:namespace request))
        panels (when-let [s (:output request)] (js->clj (js/JSON.parse s) :keywordize-keys true))]
    (when-let [next (gen/apply-output document request panels (documents))]
      (let [old (into {} (map (juxt :id gen/content) (:blocks document)))
            changed (if (not= (:ns-source document) (:ns-source next)) (:blocks next)
                        (drop-while #(= (gen/content %) (get old (:id %))) (:blocks next)))
            stale (keep #(when (pull '[:result/id] [:result/id (:id %)])
                           {:result/id (:id %) :result/status "stale"}) changed)]
        (transact! (into (doc/entity-tx next)
                         (concat stale
                                 (when (= "applied" (:generation-status (first (filter #(= (:id request) (:id %)) (:blocks next)))))
                                   (mapcat doc/entity-tx (gen/created-documents request panels)))))))
      true))))))
(defn source! [id text] (when-not (owner id) (transact! [{:block/id id :block/source text}])))

(defn verified-request-applied? [request]
  (and (some #{(:id request)} (:applied-requests (document (:namespace request))))
       (not= "conflict" (:block/generation-status (pull '[:block/generation-status] [:block/id (:id request)])))))

(defn accept-verified! [request]
  (when (and (= "complete" (:status request)) (seq (:verified-results request))
             (verified-request-applied? request))
    (let [report (js->clj (js/JSON.parse (:verified-results request)) :keywordize-keys true)]
      (doseq [run (:runs report) :when (verification/matching-run? (document (:namespace run)) run)
              result (:results run)
              :let [id (:block result) existing (pull '[*] [:result/id id])]
              ;; Polling must not overwrite a later manual evaluation or stale
              ;; result. A page reload has no result entities and may hydrate.
              :when (nil? existing)]
        (transact! [(cond-> {:result/id id :result/status "ready" :result/kind (:kind result)
                            :result/description (:description result) :result/output (:output result "")
                            :result/points "[]"}
                     (:asset result) (assoc :result/asset (:asset result))
                     (:polygons result) (assoc :result/polygons (js/JSON.stringify (clj->js (:polygons result)))))])))))
(defn set-active! [id] (workspace! {:workspace/active-pane id}))
(defn set-document! [id namespace]
  (transact! [{:pane/id id :pane/document [:document/id namespace]}]))
(defn split! [id]
  (when (< (count (panes)) 6)
    (let [pane (pull '[* {:pane/document [:document/namespace]}] [:pane/id id])
          new-id (str (random-uuid))]
      (transact! [{:pane/id new-id :pane/order (inc (apply max -1 (map :pane/order (panes))))
                   :pane/width 1 :pane/document [:document/id (get-in pane [:pane/document :document/namespace])]}])
      (set-active! new-id))))
(defn close! [id]
  (when (> (count (panes)) 1)
    (let [next-id (:pane/id (first (remove #(= id (:pane/id %)) (panes))))]
      (transact! [[:db/retractEntity [:pane/id id]]])
      (set-active! next-id))))
(defn add-block! [namespace after kind]
  (let [blocks (:blocks (document namespace))
        index (if (= after (ns-form/header-id namespace)) 0
                  (if-let [i (first (keep-indexed #(when (= (:id %2) after) %1) blocks))]
                    (inc i) (count blocks)))
        b (doc/block kind "")
        blocks (vec (concat (take index blocks) [b] (drop index blocks)))]
    (put-document! (assoc (document namespace) :blocks blocks))
    (:id b)))
(defn remove-block! [namespace id]
  (let [document (document namespace) blocks (:blocks document)
        index (first (keep-indexed #(when (= id (:id %2)) %1) blocks))]
    (when (and (some? index) (not (owner id)))
      ;; Keep an empty prose editor when deleting the final panel, so the
      ;; document remains writable and satisfies the server's invariant.
      (let [next (doc/delete-panel document id)
            remaining (:blocks next)
            active? (= id (:ui/active-block (workspace)))
            next-id (:id (nth remaining (min index (dec (count remaining)))))
            stale (keep #(when (pull '[:result/id] [:result/id (:id %)])
                           {:result/id (:id %) :result/status "stale"}) (drop (inc index) blocks))]
        (transact!
         (into (cond-> [[:db/retractEntity [:block/id id]]]
                 (pull '[:result/id] [:result/id id]) (conj [:db/retractEntity [:result/id id]])
                 active? (conj {:workspace/id "default" :ui/active-block next-id}))
               (concat (doc/entity-tx next) stale)))
        next-id))))
(defn undo-delete! [namespace]
  (let [original (document namespace)]
    (when-let [restored (doc/undo-delete original)]
      (let [ids (set (map :id (:blocks restored)))
            id (get-in (peek (doc/deletion-history original)) [:panel :id])]
        (transact! (into (mapv #(vector :db/retractEntity [:block/id (:id %)])
                              (remove #(ids (:id %)) (:blocks original)))
                         (concat (doc/entity-tx restored)
                                 (keep #(when (pull '[:result/id] [:result/id (:id %)])
                                          {:result/id (:id %) :result/status "stale"}) (:blocks restored)))))
        id))))
(defn toggle-block! [namespace id]
  (when-let [block (first (filter #(= id (:id %)) (:blocks (document namespace))))]
    (transact! [{:block/id id :block/hidden? (not (:hidden block))}])))
(defn split-block!
  "Split a code block at the exact cursor offset in a single transaction.
  Both halves preserve their text, including whitespace and incomplete forms."
  [namespace id offset]
  (let [document (document namespace) blocks (:blocks document)
        i (first (keep-indexed #(when (= id (:id %2)) %1) blocks))
        original (when (some? i) (blocks i))]
    (when (and (not (owner id)) (= "code" (:kind original)) (integer? offset)
               (<= 0 offset (count (:source original))))
      (let [next-block (doc/block "code" (subs (:source original) offset))
            before (assoc original :source (subs (:source original) 0 offset))
            blocks (vec (concat (take i blocks) [before next-block] (drop (inc i) blocks)))
            stale (keep #(when (pull '[:result/id] [:result/id (:id %)])
                           {:result/id (:id %) :result/status "stale"}) (drop i blocks))]
        (transact! (into (doc/entity-tx (assoc document :blocks blocks)) stale))
        (:id next-block)))))
(defn move-block! [namespace id direction]
  (let [document (document namespace) blocks (:blocks document)
        i (first (keep-indexed #(when (= id (:id %2)) %1) blocks)) j (+ i direction)]
    (when (and (not (owner id)) (some? i) (< -1 j (count blocks)) (not (owner (:id (blocks j)))))
      (put-document! (assoc document :blocks (assoc blocks i (blocks j) j (blocks i)))))))

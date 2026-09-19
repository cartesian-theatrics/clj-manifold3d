(ns clj-manifold3d.journal.state
  (:require [datascript.core :as d]
            [clj-manifold3d.journal.schema :as schema]
            [clj-manifold3d.journal.generation :as gen]
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
   :active-pane (:workspace/active-pane (workspace))
   :panes (mapv #(hash-map :id (:pane/id %) :width (:pane/width %)
                           :document (get-in % [:pane/document :document/namespace])) (panes))})
(defn workspace! [attrs] (transact! [(assoc attrs :workspace/id "default")]))
(defn initialize! [{:keys [documents workspace requests]}]
  (transact! (vec (concat (mapcat doc/entity-tx documents)
                         (map gen/entity requests)
                         (map-indexed (fn [i p] {:pane/id (:id p) :pane/order i :pane/width (:width p)
                                                :pane/document [:document/id (:document p)]}) (:panes workspace))
                         [{:workspace/id "default" :workspace/vim? (:vim workspace false)
                           :workspace/active-pane (:active-pane workspace)
                           :ui/engine "starting" :ui/save-status "Saved"}]))))

(defn put-document! [document] (transact! (doc/entity-tx document)))
(defn request [id] (gen/from-entity (pull '[*] [:request/id id])))
(defn requests []
  (mapv gen/from-entity (q '[:find [(pull ?r [*]) ...] :where [?r :request/id]])))
(defn request! [request] (transact! [(gen/entity request)]))
(defn begin-request! [namespace prompt-id]
  (let [document (document namespace) id (str (random-uuid))]
    (when-let [request (gen/request-input document prompt-id id)]
      (let [thinking {:id id :kind "thinking" :source (str "Codex response to prose block " prompt-id) :hidden false}]
        (transact! (conj (doc/entity-tx (update document :blocks gen/insert-after prompt-id [thinking]))
                         (gen/entity (assoc request :status "queued" :progress "Sending prompt to Codex…"))))
        request))))
(defn accept-request! [request]
  (request! request)
  (let [document (document (:namespace request))
        panels (when-let [s (:output request)] (js->clj (js/JSON.parse s) :keywordize-keys true))]
    (when-let [next (gen/apply-output document request panels)]
      (put-document! next)
      true)))
(defn source! [id text] (transact! [{:block/id id :block/source text}]))
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
        index (if-let [i (first (keep-indexed #(when (= (:id %2) after) %1) blocks))]
                (inc i) (count blocks))
        b (doc/block kind "")
        blocks (vec (concat (take index blocks) [b] (drop index blocks)))]
    (put-document! (assoc (document namespace) :blocks blocks))
    (:id b)))
(defn remove-block! [namespace id]
  (when (> (count (:blocks (document namespace))) 1)
    (transact! [[:db/retractEntity [:block/id id]]])))
(defn split-block!
  "Split a code block at the exact cursor offset in a single transaction.
  Both halves preserve their text, including whitespace and incomplete forms."
  [namespace id offset]
  (let [document (document namespace) blocks (:blocks document)
        i (first (keep-indexed #(when (= id (:id %2)) %1) blocks))
        original (when (some? i) (blocks i))]
    (when (and (= "code" (:kind original)) (integer? offset)
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
    (when (< -1 j (count blocks))
      (put-document! (assoc document :blocks (assoc blocks i (blocks j) j (blocks i)))))))

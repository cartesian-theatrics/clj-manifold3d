(ns clj-manifold3d.journal.app
  (:refer-clojure :exclude [run!])
  (:require [clj-manifold3d.journal.state :as state]
            [clj-manifold3d.journal.document :as doc]
            [clj-manifold3d.journal.namespace :as ns-form]
            [clj-manifold3d.journal.verification :as verification]
            [clj-manifold3d.journal.generation :as gen]
            [clj-manifold3d.journal.context-ui :as context-ui]
            [clj-manifold3d.journal.viewer :as viewer-data]
            [clj-manifold3d.journal.browser-store :as browser-store]
            [clj-manifold3d.journal.transport :as transport]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [goog.object :as gobj]))

;; These atoms contain disposable browser resources only. Application facts,
;; including focus, save/engine status, Vim mode and results, live in DataScript.
(defonce panes (atom {}))
(defonce blocks (atom {}))
(defonce worker (atom nil))
(defonce evaluation-timer (atom nil))
(defonce save-timer (atom nil))
(defonce save-chain (atom (js/Promise.resolve nil)))
(defonce subscriptions (atom []))
(defonce asset-urls (atom #{}))
(defonce request-polls (atom {}))
(defonce session-poll (atom nil))
(defn by-id [id] (.getElementById js/document id))
(defn qs [root selector] (.querySelector root selector))
(defn call [object method & args] (.apply (gobj/get object method) object (to-array args)))
(defn bridge [method & args] (apply call (gobj/get js/window "journalBridge") method args))
(defn text! [node text] (set! (.-textContent node) (str text)))
(defn node [tag class-name]
  (let [el (.createElement js/document tag)] (set! (.-className el) class-name) el))
(defn append! [root & children] (doseq [child children] (.appendChild root child)) root)
(defn button [label title f]
  (let [el (node "button" "action")]
    (text! el label) (.setAttribute el "title" title) (.setAttribute el "aria-label" title)
    (.addEventListener el "click" (fn [e] (.stopPropagation e) (f))) el))
(def api transport/api)
(defn content [document] (dissoc document :revision))
(defn acknowledged [namespace]
  (:document/saved-content (state/pull '[:document/saved-content] [:document/id namespace])))
(defn acknowledge!
  ([document] (acknowledge! document nil))
  ([document sent]
   (state/accept-document! document (into #{} (filter state/owner) (map :id (ns-form/panels document))) sent)))
(defn for-save [document]
  (cond-> document
    (acknowledged (:namespace document))
    (assoc :base-document (reader/read-string (acknowledged (:namespace document))))))
(defn pending-request []
  (let [w (state/workspace)]
    {:id (:ui/request-id w)
     :document (when-let [s (:ui/request-document w)] (reader/read-string s))}))
(defn status! [s] (state/workspace! {:ui/save-status s}))

(declare persist! run! start-worker! render-library! mount-block! add-block! split-block! active-pane active-document poll-request! cancel-codex!)

(defn persist! []
  (when @save-timer (js/clearTimeout @save-timer))
  (status! "Unsaved changes")
  (reset! save-timer
          (js/setTimeout
           (fn []
             ;; Serialize writes, including document creation before layout refs.
             (swap! save-chain
                    (fn [chain]
                      (-> chain (.catch (fn [_] nil))
                          (.then (fn [_]
                                   (status! "Saving…")
                                   (let [dirty (into [] (comp (remove #(= (pr-str (content %)) (acknowledged (:namespace %))))
                                                             (map #(cond-> % (nil? (acknowledged (:namespace %))) (assoc :create-only true))))
                                                     (state/documents))]
                                     (when (seq dirty)
                                       (-> (api "PUT" "/api/documents-batch" (mapv for-save dirty))
                                           (.then (fn [saved] (doseq [document saved]
                                                               (acknowledge! document (first (filter #(= (:namespace %) (:namespace document)) dirty)))))))))))
                          (.then (fn [_]
                                   (let [layout (state/workspace-data)]
                                     (when-not (= (pr-str layout) (:ui/saved-layout (state/workspace)))
                                       (-> (api "PUT" "/api/workspace" layout)
                                           (.then #(state/workspace! {:ui/saved-layout (pr-str %)})))))))
                          (.then (fn [_] (status! "Saved")))
                          (.catch (fn [e] (status! (str "Save failed: " (.-message e)))))))))
           650)))

(defn active-pane [] (:workspace/active-pane (state/workspace)))
(defn active-document []
  (get-in (state/pull '[{:pane/document [:document/namespace]}] [:pane/id (active-pane)])
          [:pane/document :document/namespace]))
(defn active-block []
  (let [id (:ui/active-block (state/workspace))]
    (when (some #(= id (:id %)) (ns-form/panels (state/document (active-document)))) id)))
(defn focus-block! [pane-id block-id]
  (state/workspace! {:workspace/active-pane pane-id :ui/active-block block-id}))

(defn accept-request! [request]
  (let [applied? (state/accept-request! request)]
    (when applied?
      ;; Newly verified edits replace their previous previews. Subsequent polls
      ;; cannot overwrite manual results; hydration after reload is also safe.
      (when (and (seq (:verified-results request)) (state/verified-request-applied? request))
        (doseq [run (:runs (js->clj (js/JSON.parse (:verified-results request)) :keywordize-keys true))
                :when (verification/matching-run? (state/document (:namespace run)) run)
                result (:results run)
                :let [id (:block result) existing (state/pull '[*] [:result/id id])]
                :when (and existing (= "stale" (:result/status existing)))]
          (state/transact! [[:db/retractEntity (:db/id existing)]])))
      (persist!))
    (state/accept-verified! request)))

(defn poll-sessions! []
  (-> (api "GET" (str "/api/codex/sessions?since=" (:ui/session-cursor (state/workspace) 0)) nil)
      (.then (fn [{:keys [cursor requests]}]
               (doseq [request requests] (accept-request! request))
               (state/workspace! {:ui/session-cursor cursor})))
      (.catch (fn [_] nil))
      (.finally #(reset! session-poll (js/setTimeout poll-sessions! 1000)))))
(defn poll-request! [id]
  (when-not (contains? @request-polls id)
    (swap! request-polls assoc id
           (js/setTimeout
            (fn []
              (-> (api "GET" (str "/api/codex/requests/" id) nil)
                  (.then (fn [request]
                           (state/transact! [{:request/id id :request/poll-error ""}])
                           (accept-request! request)))
                  (.catch (fn [e]
                            (state/transact! [{:request/id id :request/poll-error (str "Reconnecting: " (.-message e))}])))
                  (.finally (fn []
                              (swap! request-polls dissoc id)
                              (when (gen/active? (state/request id)) (poll-request! id))))))
            200))))
(defn prompt-codex!
  ([namespace prompt-id] (prompt-codex! namespace prompt-id nil))
  ([namespace prompt-id prepared]
  (if (transport/static?) (status! "AI prompting requires the local server-backed journal.")
  (when-not (some #(and (= prompt-id (:prompt-block %)) (gen/active? %)) (state/requests))
    (when-let [request (state/begin-request! namespace prompt-id prepared)]
      ;; Save the anchor before launching. Serializing with ordinary saves avoids
      ;; racing revisions and lets an in-flight request survive closing the tab.
      (status! "Saving prompt…")
      (swap! save-chain
             (fn [chain]
               (-> chain (.catch (fn [_] nil))
                   (.then (fn [_]
                            (let [sent (state/document namespace)]
                              (-> (api "PUT" (str "/api/documents/" (js/encodeURIComponent namespace)) (for-save sent))
                                  (.then (fn [saved] (acknowledge! saved sent) saved))))))
                   (.then (fn [saved] (status! "Saved")
                            (if (state/pull '[:block/id] [:block/id (:id request)])
                              (api "POST" "/api/codex/requests" request)
                              (assoc request :status "cancelled" :progress "Panel deleted before submission."))))
                   (.then (fn [r]
                            (accept-request! r)
                            (when (gen/active? r)
                              ;; Deletion may race the POST: cancel once the
                              ;; backend has acknowledged this request, too.
                              (when-not (state/pull '[:block/id] [:block/id (:id r)])
                                (cancel-codex! (:id r)))
                              (poll-request! (:id r)))))
                   (.catch (fn [e]
                             (state/request! (assoc request :status "error" :error (.-message e)))))))))))))
(defn cancel-codex! [id]
  (-> (api "POST" (str "/api/codex/requests/" id "/cancel") {})
      (.then accept-request!)
      (.catch #(state/transact! [{:request/id id :request/poll-error (.-message %)}]))))
(defn editor-command! [command]
  (when-let [editor (:editor (get @blocks [(active-pane) (active-block)]))]
    (call editor "command" command)))

(defn clear-request! []
  (when @evaluation-timer (js/clearTimeout @evaluation-timer))
  (reset! evaluation-timer nil)
  (let [w (state/workspace)]
    (state/transact! (into [[:db/add (:db/id w) :ui/engine "ready"]]
                          (keep #(when-let [v (get w %)] [:db/retract (:db/id w) % v])
                                [:ui/request-id :ui/request-document])))))
(defn stop! []
  (when @worker (.terminate @worker))
  (doseq [id (state/q '[:find [?id ...] :where [?e :result/id ?id] [?e :result/status "running"]])]
    (state/transact! [{:result/id id :result/status "error" :result/description "Evaluation stopped. Run the block to start a fresh session."}]))
  (clear-request!) (start-worker!))

(defn run! [namespace target selection all?]
  (when (and (= "ready" (:ui/engine (state/workspace)))
             (nil? (get (state/in-place-drafts namespace) (ns-form/header-id namespace)))
             (if all? (empty? (state/in-place-drafts namespace))
                 (nil? (get (state/in-place-drafts namespace) target))))
    (let [document (state/document namespace) id (str (random-uuid))
          targets (if all? (filter #(= "code" (:kind %)) (:blocks document))
                      (filter #(= target (:id %)) (:blocks document)))]
      (state/workspace! {:ui/engine "running"})
      (when-let [result (state/pull '[*] [:result/id (ns-form/header-id namespace)])]
        (state/transact! [[:db/retractEntity (:db/id result)]]))
      (state/transact! (mapv #(hash-map :result/id (:id %) :result/status "running") targets))
      (state/workspace! {:ui/request-id id :ui/request-document (pr-str document)})
      (reset! evaluation-timer (js/setTimeout stop! 300000))
      (.postMessage @worker (clj->js {:id id :namespace namespace :blocks (:blocks document)
                                      :target target :selection selection :all all?
                                      :documents (state/documents)})))))

(defn- accept-result! [data]
  (let [id (gobj/get data "block") namespace (:namespace (:document (pending-request)))
        original (:blocks (:document (pending-request))) current (:blocks (state/document namespace))]
    ;; A late evaluation must not overwrite results for edited/deleted code.
    ;; Collapsing a panel is presentation only, including during evaluation.
    (when (and (= (:ns-source (:document (pending-request))) (:ns-source (state/document namespace)))
               (= (mapv #(dissoc % :hidden :viewer) original) (mapv #(dissoc % :hidden :viewer) current)))
      (let [previous (state/pull '[*] [:result/id id])
            buffer (gobj/get data "buffer") polygons (gobj/get data "polygons")
            url (when buffer (.createObjectURL js/URL (js/Blob. #js [buffer] #js {:type "model/gltf-binary"})))
            tx (cond-> {:result/id id :result/status "ready"
                        :result/points "[]"
                        :result/kind (gobj/get data "kind")
                        :result/description (gobj/get data "description")
                        :result/output (or (gobj/get data "output") "")}
                 url (assoc :result/asset url)
                 polygons (assoc :result/polygons (js/JSON.stringify polygons)))]
        (when url (swap! asset-urls conj url))
        (state/transact! (into (mapv #(vector :db/retract (:db/id previous) % (get previous %))
                                     (filter #(contains? previous %) [:result/asset :result/polygons])) [tx]))
        (when-let [old (:result/asset previous)] (.revokeObjectURL js/URL old) (swap! asset-urls disj old))))))

(defn start-worker! []
  (state/workspace! {:ui/engine "starting"})
  (let [w (js/Worker. (transport/asset-url "worker/worker.js"))]
    (reset! worker w)
    (set! (.-onerror w) (fn [e] (state/workspace! {:ui/engine (str "Engine error: " (.-message e))})))
    (set! (.-onmessage w)
          (fn [event]
            (let [data (.-data event) type (gobj/get data "type")]
              (cond
                (= type "ready") (state/workspace! {:ui/engine "ready"})
                (= type "fatal") (state/workspace! {:ui/engine (gobj/get data "message")})
                (= (gobj/get data "id") (:id (pending-request)))
                (case type
                  "result" (accept-result! data)
                  "done" (do
                           (doseq [id (state/q '[:find [?id ...] :where [?e :result/id ?id] [?e :result/status "running"]])]
                             (state/transact! [{:result/id id :result/status "stale" :result/description "Source changed during evaluation. Run again."}]))
                           (clear-request!))
                  "error" (do
                            (doseq [id (distinct (conj (state/q '[:find [?id ...] :where [?e :result/id ?id] [?e :result/status "running"]])
                                                       (gobj/get data "block")))]
                              (when (and id (or (state/pull '[:block/id] [:block/id id])
                                                (= id (ns-form/header-id (:namespace (:document (pending-request)))))))
                                (state/transact! [{:result/id id :result/status "error"
                                                          :result/description (gobj/get data "message") :result/output (or (gobj/get data "output") "")}])) )
                            (clear-request!)) nil)))))))

(defn dispose-block! [key]
  (when-let [{:keys [editor viewer presentation unsubs root]} (get @blocks key)]
    (doseq [f unsubs] (f)) (when presentation (call presentation "dispose"))
    (when editor (call editor "destroy")) (when viewer (call viewer "dispose"))
    (.remove root) (swap! blocks dissoc key)
    (when (state/pull '[:display/id] [:display/id (pr-str key)])
      (state/transact! [[:db/retractEntity [:display/id (pr-str key)]]]))))

(defn viewer-settings [id]
  (viewer-data/settings (:block/viewer (state/pull '[:block/viewer] [:block/id id]))))
(defn viewer-settings! [id attrs]
  (when (state/pull '[:block/id] [:block/id id])
    (let [previous (viewer-settings id) next (merge previous attrs)]
      (when-not (= previous next)
        (state/transact! [{:block/id id :block/viewer (pr-str next)}])
        (persist!)))))

(defn viewer-action! [pane-id namespace id command]
  (when id
    (let [result (state/pull '[*] [:result/id id])
          {:keys [viewer presentation]} (get @blocks [pane-id id])]
      (case command
        "popout" (when presentation (call presentation "popOut"))
        "fullscreen" (when presentation (call presentation "fullscreen"))
        "fit" (when viewer (call viewer "fit"))
        "wireframe" (state/transact! [{:result/id id :result/wireframe? (not (:result/wireframe? result))}])
        "pause" (state/transact! [{:result/id id :result/paused? (not (:result/paused? result))}])
        "tools" (state/transact! [{:result/id id :result/tools? (not (:result/tools? result))}])
        "grid" (viewer-settings! id {:grid (not (:grid (viewer-settings id)))})
        "measure" (state/transact! [(cond-> {:result/id id :result/measuring? (not (:result/measuring? result))}
                                     (not (:result/measuring? result)) (assoc :result/paused? true))])
        "clear" (state/transact! [{:result/id id :result/points "[]"}])
        "download" (when-let [url (:result/asset result)]
                     (let [a (node "a" "")]
                       (set! (.-href a) url) (set! (.-download a) (str namespace "-" id ".glb")) (.click a)))))))

(defn result-view! [key result]
  (when-let [{:keys [result-root viewer asset]} (get @blocks key)]
    (let [root result-root container root label (qs root ".result-label")
          output (qs root ".result-output") preview (qs root ".result-preview")
          next-asset [(:result/asset result) (:result/polygons result)]]
      (set! (.-hidden container) (nil? result))
      (.setAttribute container "data-status" (or (:result/status result) "empty"))
      (text! label (case (:result/status result) "running" "Evaluating…" (or (:result/description result) "")))
      (text! output (:result/output result ""))
      (when (not= asset next-asset)
        ;; Disposing may publish the final camera pose. Detach handles first
        ;; so the resulting DataScript notification cannot dispose twice.
        (swap! blocks assoc-in [key :viewer] nil)
        (swap! blocks assoc-in [key :asset] next-asset)
        (when viewer (call viewer "dispose"))
        (.replaceChildren preview)
        (cond
          (:result/asset result)
          (do (set! (.-className preview) "result-preview solid-preview")
              (swap! blocks assoc-in [key :viewer]
                     (bridge "createViewer" preview (:result/asset result)
                             (clj->js (assoc (viewer-settings (second key))
                                              :onCamera #(viewer-settings! (second key) {:camera (js->clj % :keywordize-keys true)})
                                              :onMeasure #(state/transact! [{:result/id (second key) :result/points (js/JSON.stringify %)}]))))))
          (:result/polygons result)
          (do (set! (.-className preview) "result-preview section-preview")
              (bridge "sectionSvg" preview (js/JSON.parse (:result/polygons result))))
          :else (set! (.-className preview) "result-preview")))
      (when-let [v (:viewer (get @blocks key))]
        (call v "setWireframe" (boolean (:result/wireframe? result)))
        (call v "setPaused" (boolean (:result/paused? result)))
        (call v "setGrid" (:grid (viewer-settings (second key))))
        (call v "setCamera" (clj->js (:camera (viewer-settings (second key)))))
        (call v "setMeasuring" (boolean (:result/measuring? result)))
        (call v "setPoints" (js/JSON.parse (:result/points result "[]"))))
      (let [points (js->clj (js/JSON.parse (:result/points result "[]")))
            distance (viewer-data/distance points)]
        (text! (qs root ".measurement-label")
               (if distance (str (.toFixed distance 4) " model units · straight-line distance")
                   (if (seq points) "Pick the second point on the model." "Measure: pick two points on the model (not the grid)."))))
      (set! (.-hidden (qs root ".viewer-advanced")) (not (:result/tools? result)))
      (doseq [[selector value] [[".viewer-tools-toggle" (:result/tools? result)]
                                [".viewer-measure" (:result/measuring? result)]
                                [".viewer-grid" (:grid (viewer-settings (second key)))]]]
        (.setAttribute (qs root selector) "aria-pressed" (str (boolean value))))
      (let [display (state/pull '[*] [:display/id (pr-str key)])
            mode (:display/mode display "inline")
            detached? (contains? #{"pip" "window"} mode)
            popout (qs root "[data-viewer-command=popout]")
            fullscreen (qs root "[data-viewer-command=fullscreen]")]
        (.setAttribute root "data-presentation" mode)
        (text! popout (if detached? "Return to journal" "Pop out"))
        (text! fullscreen (if (= "fullscreen" mode) "Exit fullscreen" "Fullscreen"))
        (set! (.-disabled fullscreen) detached?)
        (set! (.-title fullscreen) (if detached? "Return to the journal to use fullscreen"
                                     "Toggle model fullscreen · Ctrl+Alt+M · Esc to exit"))
        (text! (qs root ".viewer-message") (:display/message display "")))
      (set! (.-hidden (qs root ".viewer-tools")) (not (:result/asset result))))))

(defn mark-stale! [namespace id]
  (let [after (if (= id (ns-form/header-id namespace)) (:blocks (state/document namespace))
                  (drop-while #(not= id (:id %)) (:blocks (state/document namespace))))
        ids (keep #(when (state/pull '[:result/id] [:result/id (:id %)]) (:id %)) after)]
    (when (seq ids) (state/transact! (mapv #(hash-map :result/id % :result/status "stale") ids)))))

(defn toggle-panel! [namespace id]
  (state/toggle-block! namespace id)
  (persist!))

(defn delete-panel! [pane-id namespace id]
  ;; Removing a thinking anchor also stops its work; late output cannot
  ;; resurrect the deleted panel because generation requires that anchor.
  (when (gen/active? (state/request id)) (cancel-codex! id))
  (let [asset (:result/asset (state/pull '[:result/asset] [:result/id id]))]
    (when-let [next-id (state/remove-block! namespace id)]
      (when asset (.revokeObjectURL js/URL asset) (swap! asset-urls disj asset))
      (focus-block! pane-id next-id)
      (persist!)
      (js/requestAnimationFrame
       #(when-let [root (:root (get @blocks [pane-id next-id]))]
          (.focus (qs root ".panel-toggle")))))))

(defn undo-delete! [pane-id namespace]
  (when-let [id (state/undo-delete! namespace)]
    (focus-block! pane-id id)
    (persist!)
    (js/requestAnimationFrame #(when-let [root (:root (get @blocks [pane-id id]))]
                                (.focus (qs root ".panel-toggle"))))))

(defn panel-controls! [pane-id namespace id ^js root toolbar]
  (let [controls (node "span" "panel-controls")
        toggle (button "Hide" "Hide panel · Ctrl+Alt+H"
                       #(do (focus-block! pane-id id) (toggle-panel! namespace id)))
        delete (button "Delete" "Delete panel · Ctrl+Alt+Backspace"
                       #(delete-panel! pane-id namespace id))]
    (.add (.-classList toggle) "panel-toggle")
    (.add (.-classList delete) "panel-delete")
    (.addEventListener root "focusin" #(focus-block! pane-id id))
    (.setAttribute toolbar "data-panel-label" (str (.getAttribute root "data-kind") " panel (hidden)"))
    (append! controls toggle delete)
    (append! toolbar controls)
    (state/subscribe!
     '[:find ?hidden . :in $ ?id :where [?b :block/id ?id] [?b :block/hidden? ?hidden]]
     (fn [hidden]
       (.toggle (.-classList root) "panel-collapsed" (boolean hidden))
       (.setAttribute toggle "aria-expanded" (str (not hidden)))
       (let [label (if hidden "Show" "Hide") title (str label " panel · Ctrl+Alt+H")]
         (text! toggle label)
         (.setAttribute toggle "title" title)
         (.setAttribute toggle "aria-label" title))) id)))

(defn mount-thinking! [pane-id namespace id]
  (let [key [pane-id id] root (node "section" "journal-block thinking-block")
        head (node "div" "thinking-head") title (node "strong" "thinking-title") model (node "span" "thinking-model")
        progress (node "pre" "thinking-progress") note (node "small" "thinking-note")
        tools (node "details" "thinking-api") tool-list (node "pre" "thinking-tool-log")
        checks (node "details" "thinking-verification") check-list (node "pre" "thinking-tool-log")
        stop (button "Stop Codex" "Stop this Codex request" #(cancel-codex! id))
        retry (button "Retry" "Send the current prose prompt again"
                      #(prompt-codex! namespace (:prompt-block (state/request id))))]
    (.setAttribute root "data-block-id" id) (.setAttribute root "data-kind" "thinking")
    (.setAttribute progress "role" "status") (.setAttribute progress "aria-live" "polite")
    (text! note "Codex edits the locked panels in place and tests them. Repairs patch the current code; Stop keeps completed edits and releases the panels.")
    (append! tools (doto (node "summary" "") (text! "API lookups (read-only)")) tool-list)
    (append! checks (doto (node "summary" "") (text! "Evaluation & repairs")) check-list)
    (append! head title model stop retry) (append! root head progress tools checks note)
    (swap! blocks assoc key {:root root :unsubs []})
    (let [render (fn [_]
                  (let [entity (state/pull '[*] [:request/id id])
                        block (state/pull '[*] [:block/id id])
                        request (gen/from-entity entity) active? (gen/active? request)
                        status (if (= "conflict" (:block/generation-status block)) "conflict" (:status request))]
                                (.setAttribute root "data-status" (or status "missing"))
                                (text! model (or (not-empty (:resolved-model request)) (not-empty (:model request)) "Codex default"))
                                (.setAttribute model "title" "Model used for this request and its automatic repairs")
                                (text! title (case status "conflict" "Codex · changes not applied"
                                                  "complete" "Codex · complete" "error" "Codex · error"
                                                  "cancelled" "Codex · stopped" "interrupted" "Codex · interrupted" "Thinking · Codex"))
                                (text! progress (str (or (:block/generation-message block) (:error request) (:progress request) "No saved request. You can send the prose prompt again.")
                                                     (when-let [e (not-empty (:request/poll-error entity))] (str "\n" e))))
                                (set! (.-hidden stop) (not active?))
                                (set! (.-hidden tools) (or (nil? (:tool-log request)) (= "[]" (:tool-log request))))
                                (text! tool-list (when-let [log (:tool-log request)]
                                                   (js/JSON.stringify (js/JSON.parse log) nil 2)))
                                (set! (.-hidden checks) (or (nil? (:verification request)) (= "[]" (:verification request))))
                                (text! check-list (when-let [log (:verification request)]
                                                   (js/JSON.stringify (js/JSON.parse log) nil 2)))
                                (set! (.-hidden retry) (or active? (nil? (:prompt-block request))))))
          unsubscribe (state/subscribe! '[:find (pull ?r [*]) . :in $ ?id :where [?r :request/id ?id]] render id)
          outcome-sub (state/subscribe! '[:find (pull ?b [:block/generation-status :block/generation-message]) .
                                          :in $ ?id :where [?b :block/id ?id]] render id)]
      (swap! blocks assoc-in [key :unsubs]
             [unsubscribe outcome-sub (panel-controls! pane-id namespace id root head)]))
    root))

(defn render-editor! [pane-id namespace id draft]
  (when-let [{:keys [root editor]} (get @blocks [pane-id id])]
    (when editor
      (call editor "setPreview" (:source draft) (clj->js (:patches draft)))
      (when-not draft
        (when-let [source (if (= id (ns-form/header-id namespace)) (:ns-source (state/document namespace))
                            (:block/source (state/pull '[:block/source] [:block/id id]))) ]
          (call editor "setValue" source)))
      (.toggle (.-classList ^js root) "panel-streaming" (boolean draft))
      (.setAttribute (qs root ".block-editor") "aria-busy" (str (boolean draft)))
      (set! (.-hidden (qs root ".streaming-tools")) (not draft))
      (doseq [button (array-seq (.querySelectorAll root ".block-toolbar > .action"))]
        (set! (.-disabled button) (boolean draft)))
      (when-let [delete (qs root ".panel-delete")] (set! (.-disabled delete) (boolean draft))))))

(defn mount-namespace! [pane-id namespace]
  (let [id (ns-form/header-id namespace) key [pane-id id]
        root (node "section" "namespace-header") toolbar (node "div" "block-toolbar")
        editor-root (node "div" "block-editor") error (node "pre" "namespace-error")
        editor (bridge "createCodeEditor" editor-root
                       (clj->js {:value (or (:base (get (state/in-place-drafts namespace) id)) (:ns-source (state/document namespace)))
                                 :label "Namespace declaration" :vim (:workspace/vim? (state/workspace))
                                 :onFocus #(focus-block! pane-id id)
                                 :onChange (fn [source]
                                             (when-not (state/owner id)
                                               (state/transact! [{:document/id namespace :document/ns-source source}])
                                               (mark-stale! namespace id) (persist!)))
                                 :onRun #(run! namespace nil nil true)
                                 :onEvaluate (fn [_] (run! namespace nil nil true))
                                 :onRunAll #(run! namespace nil nil true)}))
        render (fn [_]
                 (render-editor! pane-id namespace id (get (state/in-place-drafts namespace) id))
                 (let [message (or (ns-form/declaration-error namespace (:ns-source (state/document namespace)))
                                   (:result/description (state/pull '[:result/description] [:result/id id])))]
                   (text! error message) (set! (.-hidden error) (nil? message))))]
    (.setAttribute root "data-kind" "namespace") (.setAttribute root "data-block-id" id)
    (.setAttribute error "role" "status")
    (append! toolbar (doto (node "span" "namespace-title") (text! "Namespace & imports"))
             (node "span" "context-role")
             (button "Slurp →" "Forward slurp · Ctrl+Alt+Right / < in Vim visual mode" #(call editor "command" "slurp-forward"))
             (button "Barf ←" "Forward barf · Ctrl+Alt+Left / > in Vim visual mode" #(call editor "command" "barf-forward")))
    (let [tools (node "span" "streaming-tools")]
      (set! (.-hidden tools) true)
      (append! tools (doto (node "span" "streaming-label") (text! "Updating imports…"))
               (button "Stop & edit" "Stop generation and keep the current namespace declaration"
                       #(when-let [draft (get (state/in-place-drafts namespace) id)] (cancel-codex! (:request-id draft)))))
      (append! toolbar tools))
    (append! root toolbar editor-root error)
    (swap! blocks assoc key {:root root :editor editor :unsubs []})
    (swap! blocks assoc-in [key :unsubs]
           [(state/subscribe! '[:find ?source . :in $ ?ns :where [?d :document/id ?ns] [?d :document/ns-source ?source]] render namespace)
            (state/subscribe! '[:find (pull ?r [*]) . :in $ ?id :where [?r :result/id ?id]] render id)])
    root))

(defn mount-block! [pane-id namespace block]
  (let [{:keys [id kind source hidden]} block key [pane-id id]
        root (node "section" (str "journal-block " kind "-block"))
        toolbar (node "div" "block-toolbar") editor-root (node "div" "block-editor")
        focus #(focus-block! pane-id id)
        action (fn [label title f] (button label title #(do (focus) (f))))
        editor (bridge (if (= kind "code") "createCodeEditor" "createProseEditor") editor-root
                       (clj->js {:value (or (:base (get (state/in-place-drafts namespace) id)) source) :vim (:workspace/vim? (state/workspace))
                                 :onFocus focus
                                 :onChange (fn [source] (state/source! id source) (mark-stale! namespace id) (persist!))
                                 :onEvaluate (fn [selection] (run! namespace id (js->clj selection :keywordize-keys true) false))
                                 :onRun #(run! namespace id nil false)
                                 :onPrompt #(prompt-codex! namespace id)
                                 :onSplit #(split-block! pane-id namespace id %)
                                 :onRunAll #(run! namespace nil nil true)}))]
    (.setAttribute root "data-block-id" id) (.setAttribute root "data-kind" kind)
    (append! root toolbar editor-root)
    (if (= kind "code")
      (do (append! toolbar
                   (action "▶ Run" "Run block · Shift+Enter" #(run! namespace id nil false))
                   (action "Form" "Evaluate form or selection · Ctrl/Cmd+Enter" #(call editor "command" "form"))
                   (action "Split code" "Split code at cursor · Ctrl/Cmd+Shift+Enter" #(call editor "command" "split"))
                   (action "Slurp →" "Forward slurp · Ctrl+Alt+Right / < in Vim visual mode" #(call editor "command" "slurp-forward"))
                   (action "Barf ←" "Forward barf · Ctrl+Alt+Left / > in Vim visual mode" #(call editor "command" "barf-forward")))
          (let [result (node "div" "block-result") tools (node "div" "viewer-tools")]
            (set! (.-hidden result) true)
            (doseq [[label command shortcut] [["Fit" "fit" "F"] ["Wireframe" "wireframe" "X"] ["Play/pause" "pause" "P"]
                                               ["Download" "download" "D"]
                                               ["Pop out" "popout" "O"] ["Fullscreen" "fullscreen" "M"]]]
              (let [b (action label (str (case command
                                          "download" "Download this result as GLB"
                                          "popout" "Pop out live model (always on top when supported) / return to journal"
                                          (str label " model")) " · Ctrl+Alt+" shortcut)
                              #(viewer-action! pane-id namespace id command))]
                (.setAttribute b "data-viewer-command" command)
                (append! tools b)))
            (let [toggle (action "Tools" "Show measurement and floor grid controls" #(viewer-action! pane-id namespace id "tools"))
                  advanced (node "div" "viewer-advanced")]
              (.add (.-classList toggle) "viewer-tools-toggle")
              (set! (.-hidden advanced) true)
              (doseq [[label command title] [["Measure" "measure" "Pick two surface points to measure straight-line distance; pauses animation"]
                                             ["Clear measure" "clear" "Clear both measured points"]
                                             ["Floor grid" "grid" "Show or hide the XY floor grid (on by default)"]]]
                (let [b (action label title #(viewer-action! pane-id namespace id command))]
                  (.add (.-classList b) (str "viewer-" command)) (append! advanced b)))
              (let [label (node "span" "measurement-label")]
                (.setAttribute label "role" "status") (append! advanced label))
              (append! tools toggle advanced))
            (append! result (node "pre" "result-output") (node "div" "result-preview") (node "pre" "result-label") tools)
            (let [message (node "div" "viewer-message")]
              (.setAttribute message "role" "status") (append! result message))
            (append! root result)))
      (do
        (when-not (transport/static?)
          (append! toolbar (action "✦ Prompt Codex" "Send prose to Codex · Ctrl/Cmd+Enter (prose)" #(prompt-codex! namespace id))
                   (action "Context…" "Choose targets, references and pinned instructions · Ctrl+Alt+K (prose)" #(context-ui/open! namespace id)))
          (let [hint (node "div" "prompt-hint")]
            (text! hint "Context… controls which panels and definitions are available. Codex tests its code, repairs failures, and shows verified results automatically.")
            (append! root hint)))
        (doseq [[label command shortcut] [["B" "bold" "Ctrl/Cmd+B"] ["I" "italic" "Ctrl/Cmd+I"]
                                        ["H2" "heading" "Ctrl/Cmd+Alt+2"] ["¶" "paragraph" "Ctrl/Cmd+Alt+0"]
                                        ["• List" "list" "Ctrl/Cmd+Shift+8"] ["<>" "code" "Ctrl/Cmd+`"]]]
          (append! toolbar (action label (str command " · " shortcut) #(call editor "command" command))))))
    (append! toolbar (node "span" "context-role")
             (action "↑" "Move block up · Alt+Up" #(do (state/move-block! namespace id -1) (persist!)))
             (action "↓" "Move block down · Alt+Down" #(do (state/move-block! namespace id 1) (persist!))))
    (let [tools (node "span" "streaming-tools") label (node "span" "streaming-label")]
      (text! label "Codex owns this panel · editing live…")
      (set! (.-hidden tools) true)
      (append! tools label
               (action "Stop & edit" "Stop generation and keep completed edits for you to continue"
                       #(when-let [draft (get (state/in-place-drafts namespace) id)]
                          (-> (cancel-codex! (:request-id draft))
                              (.then (fn [_]
                                       (when-let [editor (:editor (get @blocks key))]
                                         (call editor "focus"))))))))
      (append! toolbar tools))
    (let [insert (node "div" "insert-row")]
      (append! insert (action "+ prose" "Insert prose after this block · Ctrl+Alt+T" #(add-block! pane-id namespace id "prose"))
               (action "+ code" "Insert code after this block · Ctrl+Alt+C" #(add-block! pane-id namespace id "code")))
      (append! root insert))
    (swap! blocks assoc key {:root root :editor editor :result-root (qs root ".block-result") :unsubs []})
    (when (= kind "code")
      (state/transact! [{:display/id (pr-str key) :display/mode "inline" :display/message ""}])
      (swap! blocks assoc-in [key :presentation]
             (bridge "createPresentation" (qs root ".block-result")
                     (clj->js {:title (str namespace " · Model")
                               :onMove #(when-let [v (:viewer (get @blocks key))] (call v "relocate"))
                               :onChange (fn [value]
                                           (let [{:keys [mode message]} (js->clj value :keywordize-keys true)]
                                             (state/transact! [{:display/id (pr-str key) :display/mode mode :display/message message}])))}))))
    (let [source-sub (state/subscribe! '[:find ?source . :in $ ?id :where [?b :block/id ?id] [?b :block/source ?source]]
                                     #(when (some? %) (render-editor! pane-id namespace id (get (state/in-place-drafts namespace) id))) id)
          hidden-sub (panel-controls! pane-id namespace id root toolbar)
          result-sub (state/subscribe! '[:find (pull ?r [*]) . :in $ ?id :where [?r :result/id ?id]]
                                     #(when (= kind "code") (result-view! key %)) id)
          display-sub (state/subscribe! '[:find (pull ?d [*]) . :in $ ?id :where [?d :display/id ?id]]
                                       (fn [_] (when (= kind "code") (result-view! key (state/pull '[*] [:result/id id])))) (pr-str key))
          viewer-sub (state/subscribe! '[:find ?v . :in $ ?id :where [?b :block/id ?id] [?b :block/viewer ?v]]
                                     (fn [_] (when (= kind "code") (result-view! key (state/pull '[*] [:result/id id])))) id)]
      (swap! blocks assoc-in [key :unsubs] [source-sub hidden-sub result-sub viewer-sub display-sub]))
    root))

(defn add-block! [pane-id namespace after kind]
  (let [id (state/add-block! namespace after kind)]
    (persist!)
    (js/requestAnimationFrame #(when-let [editor (:editor (get @blocks [pane-id id]))] (call editor "focus")))))

(defn split-block! [pane-id namespace id offset]
  (when-let [new-id (state/split-block! namespace id offset)]
    (focus-block! pane-id new-id)
    (persist!)
    (js/requestAnimationFrame
     #(when-let [editor (:editor (get @blocks [pane-id new-id]))]
        (call editor "select" 0) (call editor "focus")))))

(defn render-drafts! [pane-id namespace]
  (when-let [root (:root (get @panes pane-id))]
    (let [drafts (filter #(and (#{"insert" "create"} (:action %))
                              (nil? (state/pull '[:block/id] [:block/id (:id %)]))
                              (not (and (= "namespace" (:kind %))
                                        (:ns-source (state/document (:target %)))))) (state/drafts namespace))
          rewrites (state/in-place-drafts namespace)
          wanted (set (map #(str (:request-id %) "/" (:id %)) drafts))]
      (doseq [[p id] (keys @blocks) :when (= p pane-id)]
        (render-editor! pane-id namespace id (get rewrites id)))
      (doseq [el (array-seq (.querySelectorAll root ".live-draft"))]
        (when-not (wanted (.getAttribute el "data-draft-id")) (.remove el)))
      (doseq [{:keys [id request-id action target kind after]} drafts]
        (let [key (str request-id "/" id)
              host (:root (get @blocks [pane-id request-id]))]
          (when host
            (let [el (or (first (filter #(= key (.getAttribute % "data-draft-id"))
                                       (array-seq (.querySelectorAll root ".live-draft"))))
                         (let [el (node "section" "live-draft")]
                           (.setAttribute el "data-draft-id" key)
                           (.setAttribute el "data-action" action)
                           (append! el (node "div" "draft-label") (node "pre" "draft-source"))
                           (.insertBefore host el (qs host ".block-result")) el))]
              (text! (qs el ".draft-label") (str "Adding " kind (when (= action "create") (str " in " target)) " · live draft · not applied"))
              (when-not (= after (.-textContent (qs el ".draft-source")))
                (text! (qs el ".draft-source") after)))))))))

(defn render-blocks! [pane-id namespace rows]
  (when-let [container (:body (get @panes pane-id))]
    (let [ordered (sort-by :block/order rows) wanted (conj (set (map :block/id ordered)) (ns-form/header-id namespace))]
      (doseq [[p id :as key] (keys @blocks)] (when (and (= p pane-id) (not (wanted id))) (dispose-block! key)))
      (doseq [[i row] (map-indexed vector ordered)]
        (let [id (:block/id row)
              root (or (:root (get @blocks [pane-id id]))
                       (if (= "thinking" (:block/kind row))
                         (mount-thinking! pane-id namespace id)
                         (mount-block! pane-id namespace
                                       (first (filter #(= id (:id %)) (:blocks (state/document namespace)))))))
              at (aget (.-children container) i)]
          (when-not (identical? root at) (.insertBefore container root (or at nil))))))
    (render-drafts! pane-id namespace)
    (context-ui/render-badges!)))

(defn dispose-pane! [id]
  (doseq [[p _ :as key] (keys @blocks)] (when (= p id) (dispose-block! key)))
  (when-let [{:keys [root unsub]} (get @panes id)] (unsub) (.remove root))
  (swap! panes dissoc id))

(defn mount-pane! [id namespace]
  (let [root (node "article" "journal-pane") head (node "header" "pane-head")
        title (node "div" "pane-identity") select (node "select" "document-select") body (node "div" "journal-page")]
    (.setAttribute root "data-pane-id" id)
    (.setAttribute select "title" "Open a document in this pane")
    (doseq [document (sort-by :title (state/documents))]
      (let [option (node "option" "")] (set! (.-value option) (:namespace document)) (text! option (:title document)) (append! select option)))
    (set! (.-value select) namespace)
    (.addEventListener select "change" #(do (state/set-document! id (.-value select)) (persist!)))
    (append! title select (doto (node "small" "namespace-label") (text! namespace)))
    (append! head title
             (button "Run page" "Evaluate document · Ctrl/Cmd+Alt+Enter" #(run! namespace nil nil true))
             (doto (button "Undo delete" "Restore last deleted panel · Ctrl+Alt+Z" #(undo-delete! id namespace))
               (.setAttribute "class" "action undo-delete"))
             (button "Split" "Split document vertically · Ctrl+Alt+S" #(do (state/split! id) (persist!)))
             (button "×" "Close pane · Ctrl+Alt+W" #(do (state/close! id) (persist!))))
    (append! root head (mount-namespace! id namespace) body)
    (.addEventListener root "pointerdown" #(when-not (= id (active-pane)) (state/set-active! id) (persist!)))
    (swap! panes assoc id {:root root :body body :namespace namespace :unsub (fn [])})
    (let [block-sub (state/subscribe!
            '[:find [(pull ?b [:block/id :block/order :block/kind]) ...]
              :in $ ?ns :where [?d :document/namespace ?ns] [?d :document/blocks ?b]]
            #(render-blocks! id namespace %) namespace)
          undo-sub (state/subscribe! '[:find ?h . :in $ ?ns :where [?d :document/namespace ?ns] [?d :document/deleted-panels ?h]]
                                     (fn [_] (set! (.-disabled (qs root ".undo-delete")) (empty? (doc/deletion-history (state/document namespace))))) namespace)
          draft-sub (state/subscribe!
                     '[:find [(pull ?r [:request/id :request/status :request/preview :request/working-plan :request/collaborative?]) ...]
                       :where [?r :request/id]]
                     (fn [_] (render-drafts! id namespace)))]
      (swap! panes assoc-in [id :unsub] #(do (block-sub) (draft-sub) (undo-sub))))
    root))

(defn render-panes! [rows]
  (let [grid (by-id "pane-grid") rows (sort-by :pane/order rows) wanted (set (map :pane/id rows))]
    (doseq [id (keys @panes)] (when-not (wanted id) (dispose-pane! id)))
    (doseq [[i row] (map-indexed vector rows)]
      (let [id (:pane/id row) namespace (get-in row [:pane/document :document/namespace])]
        (when (and (get @panes id) (not= namespace (:namespace (get @panes id)))) (dispose-pane! id))
        (let [root (or (:root (get @panes id)) (mount-pane! id namespace)) at (aget (.-children grid) i)]
          (when-not (identical? root at) (.insertBefore grid root (or at nil))))))
    (.setProperty (.-style grid) "--pane-count" (str (count rows)))))

(defn render-library! [rows]
  (let [list (by-id "document-list") rows (sort-by :document/title rows)]
    (.replaceChildren list)
    (doseq [row rows]
      (let [namespace (:document/namespace row) el (button (:document/title row) (str "Open " namespace)
                    #(do (state/set-document! (active-pane) namespace) (persist!))) small (node "small" "")]
        (.setAttribute el "data-document" namespace) (text! small namespace) (append! el small) (append! list el)))
    ;; Refresh selectors without replacing a focused editor.
    (doseq [[_ {:keys [root namespace]}] @panes]
      (let [select (qs root ".document-select")]
        (.replaceChildren select)
        (doseq [row rows] (let [o (node "option" "")] (set! (.-value o) (:document/namespace row)) (text! o (:document/title row)) (append! select o)))
        (set! (.-value select) namespace)))))

(defn create-document! []
  (state/workspace! {:ui/dialog "new-document-dialog" :ui/dialog-error ""})
  (.focus (by-id "document-name")))
(defn new-document-submit! [event]
  (.preventDefault event)
  (let [namespace (:ui/draft-namespace (state/workspace) "") title (:ui/draft-title (state/workspace) "")]
    (if (and (doc/valid-namespace? namespace)
             (not (some #(= namespace (:namespace %)) (state/documents))))
      (do (state/put-document! (doc/new-document namespace (if (str/blank? title) namespace title)))
          (state/set-document! (active-pane) namespace) (persist!)
          (state/workspace! {:ui/dialog "" :ui/draft-namespace "" :ui/draft-title ""}))
      (state/workspace! {:ui/dialog-error "Choose a unique namespace such as workshop.bracket."}))))

(defn global-keys! [e]
  (when-not (.-defaultPrevented e)
    (let [ctrl (.-ctrlKey e) alt (.-altKey e) key (.-key e)
          pane (active-pane) namespace (active-document) id (active-block)
          action (cond
                   (= key "F1") #(state/workspace! {:ui/dialog "shortcuts"})
                   (and (or ctrl (.-metaKey e)) alt (= key "Enter")) #(run! namespace nil nil true)
                   (and ctrl alt) (case (str/lower-case key)
                                    "n" create-document!
                                    "s" #(do (state/split! pane) (persist!))
                                    "w" #(do (state/close! pane) (persist!))
                                    "v" #(do (state/workspace! {:workspace/vim? (not (:workspace/vim? (state/workspace)))}) (persist!))
                                    "f" #(viewer-action! pane namespace id "fit")
                                    "x" #(viewer-action! pane namespace id "wireframe")
                                    "p" #(viewer-action! pane namespace id "pause")
                                    "d" #(viewer-action! pane namespace id "download")
                                    "o" #(viewer-action! pane namespace id "popout")
                                    "m" #(viewer-action! pane namespace id "fullscreen")
                                    "c" #(add-block! pane namespace id "code")
                                    "t" #(add-block! pane namespace id "prose")
                                    "h" #(when id (toggle-panel! namespace id))
                                    "z" #(undo-delete! pane namespace)
                                    "k" #(when (and (not (transport/static?)) (= "prose" (:block/kind (state/pull '[:block/kind] [:block/id id]))))
                                            (context-ui/open! namespace id))
                                    "backspace" #(when id (delete-panel! pane namespace id)) nil)
                   (and alt (not ctrl) id) (case key "ArrowUp" #(do (state/move-block! namespace id -1) (persist!))
                                                               "ArrowDown" #(do (state/move-block! namespace id 1) (persist!)) nil))]
      (when action (.preventDefault e) (action)))))

(defn refresh-models! []
  (state/transact! [{:codex/id "default" :codex/loading? true :codex/error ""}])
  (-> (api "GET" "/api/codex/models" nil)
      (.then (fn [catalog]
               (state/transact! [{:codex/id "default" :codex/loading? false
                                 :codex/models (js/JSON.stringify (clj->js (:models catalog)))
                                 :codex/error (:error catalog "")}])) )
      (.catch #(state/transact! [{:codex/id "default" :codex/loading? false :codex/error (.-message %)}]))))

(defn render-models! [_]
  (let [catalog (state/pull '[*] [:codex/id "default"])
        models (js->clj (js/JSON.parse (:codex/models catalog "[]")) :keywordize-keys true)
        selected (:workspace/codex-model (state/workspace) "")
        select (by-id "codex-model")]
    (.replaceChildren select)
    (doseq [[value label description]
            (concat [["" "Codex default" "Use JOURNAL_CODEX_MODEL or the Codex configuration default"]]
                    (map #(vector (:model %) (or (:displayName %) (:model %)) (:description %)) models)
                    (when (and (seq selected) (not-any? #(= selected (:model %)) models))
                      [[selected (str selected " (saved)") "Saved selection is not in the current catalog; Codex will report if unavailable."]]))]
      (let [option (node "option" "")]
        (set! (.-value option) value) (text! option label)
        (.setAttribute option "title" (or description label)) (.appendChild select option)))
    (set! (.-value select) selected)
    (set! (.-disabled (by-id "refresh-codex-models")) (boolean (:codex/loading? catalog)))
    (text! (by-id "codex-model-status") (if (:codex/loading? catalog) "Loading available models…" (:codex/error catalog "")))))

(defn export-backup! []
  (let [data (browser-store/backup (state/documents) (state/workspace-data))
        url (.createObjectURL js/URL (js/Blob. #js [(pr-str data)] #js {:type "application/edn"}))
        link (node "a" "")]
    (set! (.-href link) url) (set! (.-download link) "modeling-journal.edn")
    (.click link)
    (js/setTimeout #(.revokeObjectURL js/URL url) 1000)))

(defn import-backup! [file]
  (when file
    (if (> (.-size file) (* 20 1024 1024)) (status! "Backup is larger than the 20 MB import limit.")
      (-> (.text file)
          (.then (fn [text]
                   (let [{:keys [documents]} (browser-store/read-backup (reader/read-string text))
                         current (into {} (map (juxt :namespace identity)) (state/documents))
                         merged (vals (reduce #(assoc %1 (:namespace %2) %2) current documents))]
                     (browser-store/validate-documents! (vec merged))
                     (when (js/confirm (str "Import " (count documents) " documents? Matching namespaces will be replaced. Other documents and your pane layout will be kept. Export a backup first to keep existing edits."))
                       (state/import-documents!
                        (mapv #(assoc % :revision (:revision (get current (:namespace %)) 0)) documents))
                       (persist!)))))
          (.catch #(status! (str "Import failed: " (.-message %))))))))

(defn update-examples! []
  (if (or (= "running" (:ui/engine (state/workspace))) (some gen/active? (state/requests)))
    (status! "Stop evaluation and AI requests before updating examples.")
    (when (js/confirm "Replace the castle, flag and README examples with their latest walkthroughs, and open the castle split view? A backup of all current documents will download first. Other documents are kept.")
      (export-backup!)
      (state/import-documents!
       (mapv #(assoc % :revision (:revision (state/document (:namespace %)) 0))
             (filter #(doc/curated-namespaces (:namespace %)) (doc/examples))))
      (state/open-castle-panes!)
      (persist!))))

(defn main []
  ;; Dialog visibility and unsaved form fields are app state too. The DOM is
  ;; only a projection of these facts, not a second source of truth.
  (doseq [dialog-id ["shortcuts" "new-document-dialog" "context-dialog"]]
    (.addEventListener (by-id dialog-id) "cancel"
                       #(do (.preventDefault %) (state/workspace! {:ui/dialog ""}))))
  (doseq [el (array-seq (.querySelectorAll js/document "[data-dialog-close]"))]
    (.addEventListener el "click" #(state/workspace! {:ui/dialog ""})))
  (.addEventListener (by-id "show-shortcuts") "click" #(state/workspace! {:ui/dialog "shortcuts"}))
  (doseq [[id attr] [["document-name" :ui/draft-namespace] ["document-title" :ui/draft-title]]]
    (.addEventListener (by-id id) "input" #(state/workspace! {attr (.-value (by-id id))})))
  (.addEventListener (by-id "new-document") "click" create-document!)
  (.addEventListener (by-id "update-examples") "click" update-examples!)
  (.addEventListener (by-id "document-form") "submit" new-document-submit!)
  (.addEventListener (by-id "stop") "click" stop!)
  (.addEventListener (by-id "refresh-codex-models") "click" refresh-models!)
  (.addEventListener (by-id "codex-model") "change"
                     #(do (state/workspace! {:workspace/codex-model (.-value (by-id "codex-model"))})
                          (context-ui/clear-inspection!) (persist!)))
  (.addEventListener (by-id "vim-toggle") "click" #(do (state/workspace! {:workspace/vim? (not (:workspace/vim? (state/workspace)))}) (persist!)))
  (.addEventListener js/document "keydown" global-keys!)
  (when (transport/static?)
    (set! (.-hidden (by-id "browser-storage")) false)
    (set! (.-hidden (qs js/document ".codex-model-controls")) true)
    (.addEventListener (by-id "export-backup") "click" export-backup!)
    (.addEventListener (by-id "import-backup") "click" #(.click (by-id "backup-file")))
    (.addEventListener (by-id "backup-file") "change"
                       (fn [_] (import-backup! (aget (.-files (by-id "backup-file")) 0))
                         (set! (.-value (by-id "backup-file")) ""))))
  (-> (api "GET" "/api/state" nil)
      (.then
       (fn [snapshot]
         (state/initialize! snapshot)
         (doseq [document (:documents snapshot)] (acknowledge! document))
         (state/workspace! {:ui/saved-layout (pr-str (:workspace snapshot))})
         (reset! subscriptions
                 [(state/subscribe! '[:find (pull ?w [:ui/dialog :ui/draft-namespace :ui/draft-title :ui/dialog-error]) .
                                      :where [?w :workspace/id "default"]]
                                    (fn [w]
                                      (doseq [id ["shortcuts" "new-document-dialog" "context-dialog"]]
                                        (let [el (by-id id)]
                                          (if (= id (:ui/dialog w))
                                            (when-not (.-open el) (.showModal el))
                                            (when (.-open el) (.close el)))))
                                      (doseq [[id attr] [["document-name" :ui/draft-namespace] ["document-title" :ui/draft-title]]]
                                        (let [el (by-id id) value (get w attr "")]
                                          (when-not (= value (.-value el)) (set! (.-value el) value))))
                                      (text! (by-id "new-document-error") (:ui/dialog-error w ""))))
                  (state/subscribe! '[:find [(pull ?p [* {:pane/document [:document/namespace]}]) ...] :where [?p :pane/id]] render-panes!)
                  (state/subscribe! '[:find [(pull ?d [:document/namespace :document/title]) ...] :where [?d :document/id]] render-library!)
                  (state/subscribe! '[:find (pull ?w [*]) . :where [?w :workspace/id "default"]]
                                    (fn [w]
                                      (text! (by-id "engine") (case (:ui/engine w) "ready" "Ready" "starting" "Starting engine…" "running" "Evaluating…" (:ui/engine w)))
                                      (text! (by-id "save-status") (:ui/save-status w))
                                      (set! (.-disabled (by-id "stop")) (not= "running" (:ui/engine w)))
                                      (doseq [[id {:keys [root]}] @panes] (.toggle (.-classList root) "active" (= id (:workspace/active-pane w))))))
                  (state/subscribe! '[:find ?vim . :where [?w :workspace/id "default"] [?w :workspace/vim? ?vim]]
                                    (fn [enabled]
                                      (text! (by-id "vim-toggle") (if enabled "Vim on" "Vim off"))
                                      (.setAttribute (by-id "vim-toggle") "aria-pressed" (str enabled))
                                      (doseq [[_ {:keys [editor root]}] @blocks]
                                        (when (#{"code" "namespace"} (.getAttribute root "data-kind")) (call editor "setVim" enabled)))))])
         (swap! subscriptions conj (context-ui/install! persist! prompt-codex!))
         (swap! subscriptions conj
                (state/subscribe! '[:find (pull ?c [*]) . :where [?c :codex/id "default"]] render-models!)
                (state/subscribe! '[:find ?model . :where [?w :workspace/id "default"] [?w :workspace/codex-model ?model]] render-models!))
         (when-not (transport/static?)
           (refresh-models!)
           (poll-sessions!))
         (doseq [request (sort-by :created > (state/requests))]
           (accept-request! request)
           (when (gen/active? request) (poll-request! (:id request))))
         (start-worker!)))
      (.catch #(do (text! (by-id "engine") "Could not open journal") (text! (by-id "save-status") (.-message %))))))

(ns clj-manifold3d.journal.app
  (:refer-clojure :exclude [run!])
  (:require [clj-manifold3d.journal.state :as state]
            [clj-manifold3d.journal.document :as doc]
            [clj-manifold3d.journal.generation :as gen]
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
(defn api [method path data]
  (-> (js/fetch path (clj->js (cond-> {:method method :headers {"Content-Type" "application/json"}}
                               data (assoc :body (js/JSON.stringify (clj->js data))))))
      (.then (fn [response]
               (-> (.json response)
                   (.then (fn [body]
                            (if (.-ok response) (js->clj body :keywordize-keys true)
                                (throw (js/Error. (gobj/get body "error")))))))))))
(defn content [document] (dissoc document :revision))
(defn acknowledged [namespace]
  (:document/saved-content (state/pull '[:document/saved-content] [:document/id namespace])))
(defn acknowledge! [document]
  (state/transact! [{:document/id (:namespace document)
                    :document/revision (:revision document)
                    :document/saved-content (pr-str (content document))}]))
(defn pending-request []
  (let [w (state/workspace)]
    {:id (:ui/request-id w)
     :document (when-let [s (:ui/request-document w)] (reader/read-string s))}))
(defn status! [s] (state/workspace! {:ui/save-status s}))

(declare persist! run! start-worker! render-library! mount-block! add-block! split-block! active-pane active-document poll-request!)

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
                                   (reduce (fn [p namespace]
                                             (.then p
                                                    (fn [_]
                                                      (let [document (state/document namespace)]
                                                        (when-not (= (pr-str (content document)) (acknowledged namespace))
                                                          (-> (api "PUT" (str "/api/documents/" (js/encodeURIComponent namespace)) document)
                                                              (.then (fn [saved]
                                                                       (acknowledge! saved)))))))))
                                           (js/Promise.resolve nil) (map :namespace (state/documents)))))
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
    (when (some #(= id (:id %)) (:blocks (state/document (active-document)))) id)))
(defn focus-block! [pane-id block-id]
  (state/workspace! {:workspace/active-pane pane-id :ui/active-block block-id}))

(defn accept-request! [request]
  (when (state/accept-request! request) (persist!)))
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
            600))))
(defn prompt-codex! [namespace prompt-id]
  (when-not (some #(and (= prompt-id (:prompt-block %)) (gen/active? %)) (state/requests))
    (when-let [request (state/begin-request! namespace prompt-id)]
      ;; Save the anchor before launching. Serializing with ordinary saves avoids
      ;; racing revisions and lets an in-flight request survive closing the tab.
      (status! "Saving prompt…")
      (swap! save-chain
             (fn [chain]
               (-> chain (.catch (fn [_] nil))
                   (.then (fn [_]
                            (api "PUT" (str "/api/documents/" (js/encodeURIComponent namespace)) (state/document namespace))))
                   (.then (fn [saved] (acknowledge! saved) (status! "Saved")
                            (api "POST" "/api/codex/requests" request)))
                   (.then (fn [r] (accept-request! r) (when (gen/active? r) (poll-request! (:id r)))))
                   (.catch (fn [e]
                             (state/request! (assoc request :status "error" :error (.-message e)))))))))))
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
  (when (= "ready" (:ui/engine (state/workspace)))
    (let [document (state/document namespace) id (str (random-uuid))
          targets (if all? (filter #(= "code" (:kind %)) (:blocks document))
                      (filter #(= target (:id %)) (:blocks document)))]
      (state/workspace! {:ui/engine "running"})
      (state/transact! (mapv #(hash-map :result/id (:id %) :result/status "running") targets))
      (state/workspace! {:ui/request-id id :ui/request-document (pr-str document)})
      (reset! evaluation-timer (js/setTimeout stop! 30000))
      (.postMessage @worker (clj->js {:id id :namespace namespace :blocks (:blocks document)
                                      :target target :selection selection :all all?
                                      :documents (state/documents)})))))

(defn- accept-result! [data]
  (let [id (gobj/get data "block") namespace (:namespace (:document (pending-request)))
        original (:blocks (:document (pending-request))) current (:blocks (state/document namespace))]
    ;; A late evaluation must not overwrite results for edited/deleted code.
    (when (= original current)
      (let [previous (state/pull '[*] [:result/id id])
            buffer (gobj/get data "buffer") polygons (gobj/get data "polygons")
            url (when buffer (.createObjectURL js/URL (js/Blob. #js [buffer] #js {:type "model/gltf-binary"})))
            tx (cond-> {:result/id id :result/status "ready"
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
  (let [w (js/Worker. "/journal/worker/worker.js")]
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
                              (when id (state/transact! [{:result/id id :result/status "error"
                                                          :result/description (gobj/get data "message") :result/output (or (gobj/get data "output") "")}])) )
                            (clear-request!)) nil)))))))

(defn dispose-block! [key]
  (when-let [{:keys [editor viewer unsubs root]} (get @blocks key)]
    (doseq [f unsubs] (f)) (when editor (call editor "destroy")) (when viewer (call viewer "dispose"))
    (.remove root) (swap! blocks dissoc key)))

(defn viewer-action! [pane-id namespace id command]
  (when id
    (let [result (state/pull '[*] [:result/id id]) viewer (:viewer (get @blocks [pane-id id]))]
      (case command
        "fit" (when viewer (call viewer "fit"))
        "wireframe" (state/transact! [{:result/id id :result/wireframe? (not (:result/wireframe? result))}])
        "pause" (state/transact! [{:result/id id :result/paused? (not (:result/paused? result))}])
        "download" (when-let [url (:result/asset result)]
                     (let [a (node "a" "")]
                       (set! (.-href a) url) (set! (.-download a) (str namespace "-" id ".glb")) (.click a)))))))

(defn result-view! [key result]
  (when-let [{:keys [root viewer asset]} (get @blocks key)]
    (let [container (qs root ".block-result") label (qs root ".result-label")
          output (qs root ".result-output") preview (qs root ".result-preview")
          next-asset [(:result/asset result) (:result/polygons result)]]
      (set! (.-hidden container) (nil? result))
      (.setAttribute container "data-status" (or (:result/status result) "empty"))
      (text! label (case (:result/status result) "running" "Evaluating…" (or (:result/description result) "")))
      (text! output (:result/output result ""))
      (when (not= asset next-asset)
        (when viewer (call viewer "dispose"))
        (.replaceChildren preview)
        (swap! blocks assoc-in [key :viewer] nil)
        (swap! blocks assoc-in [key :asset] next-asset)
        (cond
          (:result/asset result)
          (do (set! (.-className preview) "result-preview solid-preview")
              (swap! blocks assoc-in [key :viewer] (bridge "createViewer" preview (:result/asset result))))
          (:result/polygons result)
          (do (set! (.-className preview) "result-preview section-preview")
              (bridge "sectionSvg" preview (js/JSON.parse (:result/polygons result))))
          :else (set! (.-className preview) "result-preview")))
      (when-let [v (:viewer (get @blocks key))]
        (call v "setWireframe" (boolean (:result/wireframe? result)))
        (call v "setPaused" (boolean (:result/paused? result))))
      (set! (.-hidden (qs root ".viewer-tools")) (not (:result/asset result))))))

(defn mark-stale! [namespace id]
  (let [after (drop-while #(not= id (:id %)) (:blocks (state/document namespace)))
        ids (keep #(when (state/pull '[:result/id] [:result/id (:id %)]) (:id %)) after)]
    (when (seq ids) (state/transact! (mapv #(hash-map :result/id % :result/status "stale") ids)))))

(defn mount-thinking! [pane-id namespace id]
  (let [key [pane-id id] root (node "section" "journal-block thinking-block")
        head (node "div" "thinking-head") title (node "strong" "thinking-title")
        progress (node "pre" "thinking-progress") note (node "small" "thinking-note")
        stop (button "Stop Codex" "Stop this Codex request" #(cancel-codex! id))
        retry (button "Retry" "Send the current prose prompt again"
                      #(prompt-codex! namespace (:prompt-block (state/request id))))]
    (.setAttribute root "data-block-id" id) (.setAttribute root "data-kind" "thinking")
    (.setAttribute progress "role" "status") (.setAttribute progress "aria-live" "polite")
    (text! note "Public progress only. Generated code is not run automatically.")
    (append! head title stop retry) (append! root head progress note)
    (swap! blocks assoc key {:root root :unsubs []})
    (let [unsubscribe
          (state/subscribe! '[:find (pull ?r [*]) . :in $ ?id :where [?r :request/id ?id]]
                            (fn [entity]
                              (let [request (gen/from-entity entity) status (:status request) active? (gen/active? request)]
                                (.setAttribute root "data-status" (or status "missing"))
                                (text! title (case status "complete" "Codex · complete" "error" "Codex · error"
                                                  "cancelled" "Codex · stopped" "interrupted" "Codex · interrupted" "Thinking · Codex"))
                                (text! progress (str (or (:error request) (:progress request) "No saved request. You can send the prose prompt again.")
                                                     (when-let [e (not-empty (:request/poll-error entity))] (str "\n" e))))
                                (set! (.-hidden stop) (not active?))
                                (set! (.-hidden retry) (or active? (nil? (:prompt-block request)))))) id)]
      (swap! blocks assoc-in [key :unsubs] [unsubscribe]))
    root))

(defn mount-block! [pane-id namespace block]
  (let [{:keys [id kind source hidden]} block key [pane-id id]
        root (node "section" (str "journal-block " kind "-block"))
        toolbar (node "div" "block-toolbar") editor-root (node "div" "block-editor")
        focus #(focus-block! pane-id id)
        action (fn [label title f] (button label title #(do (focus) (f))))
        editor (bridge (if (= kind "code") "createCodeEditor" "createProseEditor") editor-root
                       (clj->js {:value source :vim (:workspace/vim? (state/workspace))
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
                   (action "Barf ←" "Forward barf · Ctrl+Alt+Left / > in Vim visual mode" #(call editor "command" "barf-forward"))
                   (action "Code" "Show/hide code · Ctrl+Alt+H"
                           #(do (state/transact! [{:block/id id :block/hidden? (not (:block/hidden? (state/pull '[*] [:block/id id])))}]) (persist!))))
          (let [result (node "div" "block-result") tools (node "div" "viewer-tools")]
            (set! (.-hidden result) true)
            (doseq [[label command shortcut] [["Fit" "fit" "F"] ["Wireframe" "wireframe" "X"] ["Play/pause" "pause" "P"]
                                               ["Download" "download" "D"]]]
              (append! tools (action label (str (if (= command "download") "Download this result as GLB" (str label " model")) " · Ctrl+Alt+" shortcut)
                                     #(viewer-action! pane-id namespace id command))))
            (append! result (node "pre" "result-output") (node "div" "result-preview") (node "pre" "result-label") tools)
            (append! root result)))
      (do
        (append! toolbar (action "✦ Prompt Codex" "Send prose to Codex · Ctrl/Cmd+Enter (prose)" #(prompt-codex! namespace id)))
        (let [hint (node "div" "prompt-hint")]
          (text! hint "Prompt Codex sends this prose and the preceding document context to your signed-in Codex account.")
          (append! root hint))
        (doseq [[label command shortcut] [["B" "bold" "Ctrl/Cmd+B"] ["I" "italic" "Ctrl/Cmd+I"]
                                        ["H2" "heading" "Ctrl/Cmd+Alt+2"] ["¶" "paragraph" "Ctrl/Cmd+Alt+0"]
                                        ["• List" "list" "Ctrl/Cmd+Shift+8"] ["<>" "code" "Ctrl/Cmd+`"]]]
          (append! toolbar (action label (str command " · " shortcut) #(call editor "command" command))))))
    (append! toolbar (action "↑" "Move block up · Alt+Up" #(do (state/move-block! namespace id -1) (persist!)))
             (action "↓" "Move block down · Alt+Down" #(do (state/move-block! namespace id 1) (persist!)))
             (action "×" "Delete block · Ctrl+Alt+Backspace" #(do (state/remove-block! namespace id) (persist!))))
    (let [insert (node "div" "insert-row")]
      (append! insert (action "+ prose" "Insert prose after this block · Ctrl+Alt+T" #(add-block! pane-id namespace id "prose"))
               (action "+ code" "Insert code after this block · Ctrl+Alt+C" #(add-block! pane-id namespace id "code")))
      (append! root insert))
    (swap! blocks assoc key {:root root :editor editor :unsubs []})
    (let [source-sub (state/subscribe! '[:find ?source . :in $ ?id :where [?b :block/id ?id] [?b :block/source ?source]]
                                     #(when (some? %) (call editor "setValue" %)) id)
          hidden-sub (state/subscribe! '[:find ?hidden . :in $ ?id :where [?b :block/id ?id] [?b :block/hidden? ?hidden]]
                                     #(set! (.-hidden editor-root) (boolean %)) id)
          result-sub (state/subscribe! '[:find (pull ?r [*]) . :in $ ?id :where [?r :result/id ?id]]
                                     #(when (= kind "code") (result-view! key %)) id)]
      (swap! blocks assoc-in [key :unsubs] [source-sub hidden-sub result-sub]))
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

(defn render-blocks! [pane-id namespace rows]
  (when-let [container (:body (get @panes pane-id))]
    (let [ordered (sort-by :block/order rows) wanted (set (map :block/id ordered))]
      (doseq [[p id :as key] (keys @blocks)] (when (and (= p pane-id) (not (wanted id))) (dispose-block! key)))
      (doseq [[i row] (map-indexed vector ordered)]
        (let [id (:block/id row)
              root (or (:root (get @blocks [pane-id id]))
                       (if (= "thinking" (:block/kind row))
                         (mount-thinking! pane-id namespace id)
                         (mount-block! pane-id namespace
                                       (first (filter #(= id (:id %)) (:blocks (state/document namespace)))))))
              at (aget (.-children container) i)]
          (when-not (identical? root at) (.insertBefore container root (or at nil))))))))

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
             (button "Split" "Split document vertically · Ctrl+Alt+S" #(do (state/split! id) (persist!)))
             (button "×" "Close pane · Ctrl+Alt+W" #(do (state/close! id) (persist!))))
    (append! root head body)
    (.addEventListener root "pointerdown" #(when-not (= id (active-pane)) (state/set-active! id) (persist!)))
    (swap! panes assoc id {:root root :body body :namespace namespace :unsub (fn [])})
    (swap! panes assoc-in [id :unsub]
           (state/subscribe!
            '[:find [(pull ?b [:block/id :block/order :block/kind]) ...]
              :in $ ?ns :where [?d :document/namespace ?ns] [?d :document/blocks ?b]]
            #(render-blocks! id namespace %) namespace))
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
                                    "c" #(add-block! pane namespace id "code")
                                    "t" #(add-block! pane namespace id "prose")
                                    "h" #(when id (state/transact! [{:block/id id :block/hidden? (not (:block/hidden? (state/pull '[*] [:block/id id])))}]) (persist!))
                                    "backspace" #(when id (state/remove-block! namespace id) (persist!)) nil)
                   (and alt (not ctrl) id) (case key "ArrowUp" #(do (state/move-block! namespace id -1) (persist!))
                                                               "ArrowDown" #(do (state/move-block! namespace id 1) (persist!)) nil))]
      (when action (.preventDefault e) (action)))))

(defn main []
  ;; Dialog visibility and unsaved form fields are app state too. The DOM is
  ;; only a projection of these facts, not a second source of truth.
  (doseq [dialog-id ["shortcuts" "new-document-dialog"]]
    (.addEventListener (by-id dialog-id) "cancel"
                       #(do (.preventDefault %) (state/workspace! {:ui/dialog ""}))))
  (doseq [el (array-seq (.querySelectorAll js/document "[data-dialog-close]"))]
    (.addEventListener el "click" #(state/workspace! {:ui/dialog ""})))
  (.addEventListener (by-id "show-shortcuts") "click" #(state/workspace! {:ui/dialog "shortcuts"}))
  (doseq [[id attr] [["document-name" :ui/draft-namespace] ["document-title" :ui/draft-title]]]
    (.addEventListener (by-id id) "input" #(state/workspace! {attr (.-value (by-id id))})))
  (.addEventListener (by-id "new-document") "click" create-document!)
  (.addEventListener (by-id "document-form") "submit" new-document-submit!)
  (.addEventListener (by-id "stop") "click" stop!)
  (.addEventListener (by-id "vim-toggle") "click" #(do (state/workspace! {:workspace/vim? (not (:workspace/vim? (state/workspace)))}) (persist!)))
  (.addEventListener js/document "keydown" global-keys!)
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
                                      (doseq [id ["shortcuts" "new-document-dialog"]]
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
                                        (when (= "code" (.getAttribute root "data-kind")) (call editor "setVim" enabled)))))])
         (doseq [request (state/requests)]
           (accept-request! request)
           (when (gen/active? request) (poll-request! (:id request))))
         (start-worker!)))
      (.catch #(do (text! (by-id "engine") "Could not open journal") (text! (by-id "save-status") (.-message %))))))

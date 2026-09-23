(ns clj-manifold3d.journal.context-ui
  (:require [clj-manifold3d.journal.state :as state]
            [clj-manifold3d.journal.context :as context]
            [clj-manifold3d.journal.document :as doc]
            [clj-manifold3d.journal.namespace :as ns-form]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(defn by-id [id] (.getElementById js/document id))
(defn text! [el s] (set! (.-textContent el) (or s "")))
(defn element [tag class-name text]
  (let [el (.createElement js/document tag)] (set! (.-className el) class-name) (text! el text) el))
(defn value! [id value]
  (let [el (by-id id) value (or value "")]
    (when-not (= value (.-value el)) (set! (.-value el) value))))
(defn selection []
  (let [w (state/workspace) document (state/document (:ui/context-namespace w))
        id (:ui/context-prompt w) prompt (first (filter #(= id (:id %)) (:blocks document)))]
    (when (= "prose" (:kind prompt)) {:document document :prompt prompt :config (context/settings prompt)})))
(defn clear-inspection! []
  (state/workspace! {:ui/context-request "" :ui/context-inspection "" :ui/context-error ""}))
(defn open! [namespace prompt-id]
  (clear-inspection!)
  (state/workspace! {:ui/context-namespace namespace :ui/context-prompt prompt-id :ui/dialog "context-dialog"}))
(defn config! [f persist!]
  (when-let [{:keys [prompt config]} (selection)]
    (let [next (f config)] (clear-inspection!) (state/context-settings! (:id prompt) next) (persist!))))
(defn role-label [role] (get {"target" "Target" "reference" "Read-only" "exclude" "Excluded" "prompt" "Prompt"} role ""))
(defn render-badges! []
  (let [{journal :document prompt :prompt} (selection) roles (when journal (context/panel-roles journal (:id prompt)))]
    (doseq [el (array-seq (.querySelectorAll js/document ".journal-block, .namespace-header"))]
      (when-let [badge (.querySelector el ".context-role")]
        (let [role (get roles (.getAttribute el "data-block-id"))]
          (text! badge (role-label role))
          (.setAttribute badge "title" (str "Context role for prompt " (:id prompt) ": " (role-label role)))
          (.setAttribute el "data-context-role" (or role "")))))))

(defn render! [persist!]
  (render-badges!)
  (when (= "context-dialog" (:ui/dialog (state/workspace)))
    (let [w (state/workspace) {:keys [document prompt config]} (selection)]
      (if-not prompt
        (do (text! (by-id "context-error") "This prompt was deleted. Close this dialog and choose another prompt.")
            (set! (.-disabled (by-id "context-send")) true))
        (let [snapshot (context/build document (:id prompt) (state/documents) (state/context-workspace))
              roles (context/panel-roles document (:id prompt))
              prepared (some-> (:ui/context-request w) not-empty reader/read-string)
              changed? (and prepared (not= prepared (state/request-input (:namespace document) (:id prompt) (:id prepared))))
              inspection (some-> (:ui/context-inspection w) not-empty reader/read-string)]
          (text! (by-id "context-identity") (str (:namespace document) " · " (subs (:source prompt) 0 (min 90 (count (:source prompt))))))
          (set! (.-checked (by-id "context-create-namespaces")) (:allow-create-namespaces? config))
          (doseq [[id value] [["context-scope" (:scope config)] ["context-dependencies" (:dependencies config)]
                              ["context-workspace-instructions" (:workspace/instructions w)]
                              ["context-document-instructions" (:instructions document)]
                              ["context-instructions-mode" (:instructions-mode document "extend")]]]
            (value! id value))
          (.replaceChildren (by-id "context-panels"))
          (doseq [p (ns-form/panels document) :when (contains? roles (:id p))]
            (let [row (element "div" "context-panel-row" nil)
                  label (element "label" "" (str (:kind p) (when (:hidden p) " · hidden") " · " (subs (:source p) 0 (min 100 (count (:source p))))))
                  select (element "select" "context-panel-role" nil)]
              (.setAttribute row "data-panel-id" (:id p))
              (.setAttribute select "aria-label" (str "Context role for panel " (:id p)))
              (.setAttribute select "title" "Target: editable · Reference: read-only · Excluded: not sent")
              (doseq [role (if (= (:id prompt) (:id p)) ["prompt"] ["target" "reference" "exclude"])]
                (let [o (element "option" "" (role-label role))] (set! (.-value o) role) (.appendChild select o)))
              (set! (.-value select) (get roles (:id p)))
              (set! (.-disabled select) (= (:id prompt) (:id p)))
              (.addEventListener select "change" #(config! (fn [c] (assoc-in c [:roles (:id p)] (.-value select))) persist!))
              (.appendChild label select) (.appendChild row label)
              (.appendChild row (element "small" "subtle"
                                          (or (:reason (first (filter #(= (:id p) (:id %)) (:panels snapshot))))
                                              (if (contains? (:roles config) (:id p)) "Explicitly excluded" "Outside selected scope"))))
              (.appendChild (by-id "context-panels") row)))
          (.replaceChildren (by-id "context-definitions"))
          (when (empty? (:dependencies snapshot))
            (text! (by-id "context-definitions") "No additional journal definitions selected."))
          (doseq [{:keys [symbol signature doc source mode reason]} (:dependencies snapshot)]
            (let [row (element "section" "context-definition" nil)
                  title (element "strong" "" (str symbol " · read-only · " mode))
                  why (element "p" "subtle" reason) code (element "pre" "" (str source (when (seq doc) (str "\n\n" doc))))
                  toggle (element "button" "" (if (= "implementation" mode) "Signature only" "Include implementation"))]
              (.setAttribute toggle "title" (str "Toggle implementation context for " symbol))
              (set! (.-disabled toggle) (= "implementations" (:dependencies config)))
              (.addEventListener toggle "click"
                                 #(config! (fn [c] (update c :expanded
                                                          (fn [xs] (if (some #{symbol} xs) (vec (remove #{symbol} xs)) (conj (vec xs) symbol))))) persist!))
              (doseq [el [title why code toggle]] (.appendChild row el))
              (.appendChild (by-id "context-definitions") row)))
          (text! (by-id "context-summary") (str (count (filter #(= "target" (:role %)) (:panels snapshot))) " editable targets · "
                                                (count (filter #(= "reference" (:role %)) (:panels snapshot))) " read-only panels · "
                                                (count (:dependencies snapshot)) " referenced definitions"))
          (text! (by-id "context-warning") (str/join "\n" (concat (:warnings snapshot)
                                                       (when changed? ["Context changed since inspection. Inspect again before sending."]))))
          (text! (by-id "context-error") (:ui/context-error w))
          (set! (.-disabled (by-id "context-send")) (boolean (or changed? (and prepared (not inspection)) (str/blank? (:source prompt)))))
          (set! (.-hidden (by-id "context-inspection")) (nil? inspection))
          (when inspection
            (text! (by-id "context-input") (:text inspection))
            (text! (by-id "context-output-schema") (js/JSON.stringify (clj->js (select-keys inspection [:output-schema :tools :tool-log])) nil 2))
            (text! (by-id "context-token-estimate") (str "Model: " (:model inspection) " · ≈ " (:estimated-tokens inspection) " tokens / " (:characters inspection) " characters. " (:estimate-note inspection)))))))))

(defn inspect! []
  (when-let [{:keys [document prompt]} (selection)]
    (if-let [request (state/request-input (:namespace document) (:id prompt) (str (random-uuid)))]
      (let [serialized (pr-str request)]
        (state/workspace! {:ui/context-request serialized :ui/context-inspection "" :ui/context-error ""})
        (-> (js/fetch "/api/codex/inspect" (clj->js {:method "POST" :headers {"Content-Type" "application/json"}
                                                    :body (js/JSON.stringify (clj->js request))}))
            (.then (fn [r] (-> (.json r) (.then (fn [body]
                                                (let [body (js->clj body :keywordize-keys true)]
                                                  (when-not (.-ok r) (throw (js/Error. (:error body)))) body))))))
            (.then #(when (= serialized (:ui/context-request (state/workspace)))
                      (state/workspace! {:ui/context-inspection (pr-str %)})))
            (.catch #(when (= serialized (:ui/context-request (state/workspace)))
                       (state/workspace! {:ui/context-error (.-message %) :ui/context-request ""})))))
      (state/workspace! {:ui/context-error "Write a prompt before inspecting its request."}))))

(defn install! [persist! send!]
  (.addEventListener (by-id "context-create-namespaces") "change"
                     #(config! (fn [c] (assoc c :allow-create-namespaces? (.-checked (by-id "context-create-namespaces")))) persist!))
  (doseq [[id key] [["context-scope" :scope] ["context-dependencies" :dependencies]]]
    (.addEventListener (by-id id) "change" #(config! (fn [c] (assoc c key (.-value (by-id id)))) persist!)))
  (.addEventListener (by-id "context-reset") "click" #(config! (fn [c] (assoc c :roles {})) persist!))
  (doseq [[id attr] [["context-workspace-instructions" :workspace/instructions]
                     ["context-document-instructions" :document/instructions]
                     ["context-instructions-mode" :document/instructions-mode]]]
    (.addEventListener (by-id id) (if (= id "context-instructions-mode") "change" "input")
                       #(when-let [{:keys [document]} (selection)]
                          (let [value (.-value (by-id id))]
                          (clear-inspection!)
                          (state/transact! [(assoc (if (= attr :workspace/instructions) {:workspace/id "default"}
                                                      {:document/id (:namespace document)}) attr value)])
                          (persist!)))))
  (.addEventListener (by-id "context-inspect") "click" inspect!)
  (.addEventListener (by-id "context-send") "click"
                     #(try
                        (when-let [{:keys [document prompt]} (selection)]
                          (send! (:namespace document) (:id prompt) (some-> (:ui/context-request (state/workspace)) not-empty reader/read-string))
                          (state/workspace! {:ui/dialog ""}))
                        (catch :default e (state/workspace! {:ui/context-error (.-message e)}))))
  (let [docs-sub (state/subscribe! '[:find [(pull ?d pattern) ...] :in $ pattern :where [?d :document/id]]
                                   (fn [_] (render! persist!)) doc/pull-pattern)
        ui-sub (state/subscribe! '[:find (pull ?w [:workspace/instructions :ui/dialog :ui/context-namespace :ui/context-prompt
                                                :ui/context-request :ui/context-inspection :ui/context-error]) .
                                   :where [?w :workspace/id "default"]] (fn [_] (render! persist!)))]
    #(do (docs-sub) (ui-sub))))

(ns clj-manifold3d.journal.document
  (:require [clojure.string :as str]
            [clj-manifold3d.journal.readme-example :as readme]
            [clj-manifold3d.journal.namespace :as ns-form]
            #?(:clj [clj-manifold3d.journal.example-source :as example-source])
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn]))
  #?(:cljs (:require-macros [clj-manifold3d.journal.example-source :as example-source])))

(defn valid-namespace? [s]
  (boolean (and (string? s)
                (re-matches #"[A-Za-z][A-Za-z0-9_-]*(?:\.[A-Za-z][A-Za-z0-9_-]*)*" s))))

(defn namespace-path [s]
  (when-not (valid-namespace? s) (throw (ex-info "Invalid namespace" {:namespace s})))
  (str (-> s (str/replace "-" "_") (str/replace "." "/")) ".clj"))

(def curated-namespaces
  #{"journal.castle-architecture" "journal.castle-night" "journal.flag-uv" "journal.readme"
    "journal.raptor-3"})
(def castle-panes
  [{:id "pane-architecture" :document "journal.castle-architecture" :width 1}
   {:id "pane-night" :document "journal.castle-night" :width 1}])

(defn source [{:keys [namespace ns-source blocks]}]
  (ns-form/assert-declaration! namespace ns-source)
  (str ns-source "\n\n"
       (str/join "\n\n"
                 (map (fn [{:keys [kind source]}]
                        (if (not= kind "code")
                          (str/join "\n" (map #(str ";; " %) (str/split (or source "") #"\n" -1)))
                          source)) blocks)) "\n"))

(defn entity-tx [{:keys [namespace ns-source title revision blocks applied-requests instructions instructions-mode deleted-panels]}]
  (into (mapv (fn [i {:keys [id kind source hidden prompt-id generation-status generation-message context-settings viewer]}]
                (cond-> {:block/id id :block/kind kind :block/source source
                         :block/order i :block/hidden? (boolean hidden)}
                  prompt-id (assoc :block/prompt-id prompt-id)
                  context-settings (assoc :block/context-settings context-settings)
                  viewer (assoc :block/viewer viewer)
                  generation-status (assoc :block/generation-status generation-status)
                  generation-message (assoc :block/generation-message generation-message))) (range) blocks)
        [(cond-> {:document/id namespace :document/namespace namespace
          :document/title title :document/revision (or revision 0)
          :document/applied-requests (vec applied-requests)
          :document/blocks (mapv #(vector :block/id (:id %)) blocks)}
           (some? instructions) (assoc :document/instructions instructions)
           ns-source (assoc :document/ns-source ns-source)
           deleted-panels (assoc :document/deleted-panels deleted-panels)
           instructions-mode (assoc :document/instructions-mode instructions-mode))]))

(def pull-pattern
  [:document/namespace :document/title :document/revision :document/applied-requests
   :document/instructions :document/instructions-mode :document/deleted-panels :document/ns-source
   {:document/blocks [:block/id :block/kind :block/source :block/order :block/hidden?
                      :block/prompt-id :block/generation-status :block/generation-message :block/context-settings :block/viewer]}])

(defn from-entity [e]
  (cond-> {:namespace (:document/namespace e) :title (:document/title e)
   :revision (:document/revision e 0)
   :applied-requests (vec (sort (:document/applied-requests e)))
   :blocks (mapv (fn [b] (cond-> {:id (:block/id b) :kind (:block/kind b)
                                 :source (:block/source b) :hidden (:block/hidden? b false)}
                          (:block/prompt-id b) (assoc :prompt-id (:block/prompt-id b))
                          (:block/context-settings b) (assoc :context-settings (:block/context-settings b))
                          (:block/viewer b) (assoc :viewer (:block/viewer b))
                          (:block/generation-status b) (assoc :generation-status (:block/generation-status b))
                          (:block/generation-message b) (assoc :generation-message (:block/generation-message b))))
                 (sort-by :block/order (:document/blocks e)))}
    (some? (:document/instructions e)) (assoc :instructions (:document/instructions e))
    (:document/ns-source e) (assoc :ns-source (:document/ns-source e))
    (:document/deleted-panels e) (assoc :deleted-panels (:document/deleted-panels e))
    (:document/instructions-mode e) (assoc :instructions-mode (:document/instructions-mode e))))

(defn block [kind source]
  {:id (str (random-uuid)) :kind kind :source source :hidden false})

(defn deletion-history [document]
  (edn/read-string (or (:deleted-panels document) "[]")))

(defn delete-panel
  "Keep ten reversible deletions, not whole-document snapshots. A subsequent
  edit to a surviving panel is never lost when undoing a deletion."
  [document id]
  (let [blocks (:blocks document)
        i (first (keep-indexed #(when (= id (:id %2)) %1) blocks))]
    (when (some? i)
      (let [removed (blocks i) remaining (vec (concat (take i blocks) (drop (inc i) blocks)))
            placeholder (when (empty? remaining) (block "prose" ""))
            entry {:panel removed :index i :next-id (:id (get blocks (inc i)))
                   :previous-id (:id (get blocks (dec i))) :placeholder placeholder}]
        (cond-> (assoc document :blocks (if placeholder [placeholder] remaining)
                       :deleted-panels (pr-str (vec (take-last 10 (conj (deletion-history document) entry)))))
          ;; Restoring a cancelled thinking panel must not reapply late output.
          (= "thinking" (:kind removed)) (update :applied-requests #(vec (distinct (conj (vec %) id)))))))))

(defn undo-delete [document]
  (when-let [{:keys [panel index next-id previous-id placeholder]} (peek (deletion-history document))]
    (when-not (some #(= (:id panel) (:id %)) (:blocks document))
      (let [[document panel] (ns-form/restore-panel document panel)
            blocks (vec (remove #(= placeholder %) (:blocks document)))
            find-index (fn [id] (first (keep-indexed #(when (= id (:id %2)) %1) blocks)))
            at (or (find-index next-id) (some-> (find-index previous-id) inc) (min index (count blocks)))]
        (assoc document :blocks (vec (concat (take at blocks) [panel] (drop at blocks)))
               :deleted-panels (pr-str (pop (deletion-history document))))))))

(defn new-document [namespace title]
  {:namespace namespace :title title :revision 0
   :ns-source (ns-form/declaration namespace)
   :blocks [(block "prose" (str "# " title "\n\nWrite a note, try a form, and keep the result beside your thinking."))
            (block "code" "(m/cube 2 2 2 true)")]})

(defn- raw-examples []
  [#?(:clj (example-source/flag-document) :cljs (example-source/embedded-flag-document))
   {:namespace "journal.first-shapes" :title "A study in solids" :revision 0
    :blocks [(block "prose" "# A study in solids\n\nA modeling journal is a place to **think with shapes**. These paragraphs are editable: click here and start writing. Code and its results live in the same page.")
             (block "code" "(def plate\n  (-> (m/cube 12 8 1 true)\n      (m/color [0.20 0.55 0.72 1])))\n\nplate")
             (block "prose" "## Make a hole\n\nTry changing the radius below. **Shift+Enter** runs a block; **Ctrl/Cmd+Enter** evaluates the form at your cursor or your selection. Drag a model to orbit it.")
             (block "code" "(m/difference plate\n  (m/cylinder 5 1.4 1.4 48 true))")
             (block "prose" "## Start in two dimensions\n\nCross-sections stay flat in the journal. Extrude one when you are ready to give it depth.")
             (block "code" "(m/difference (m/square 8 6 true)\n              (m/circle 2 48))")
             (block "prose" "Use **+ prose** or **+ code** between entries to extend this page. Split the workspace to keep another namespace beside this one.")]}
   {:namespace "journal.assembly" :title "Across namespaces" :revision 0
    :blocks [(block "prose" "# Across namespaces\n\nDocuments are ordinary namespaces. Require a value from another journal and build on it.")
             (block "code" "(require '[journal.first-shapes :as shapes])\n\n(-> shapes/plate\n    m/model\n    (m/color [0.76 0.39 0.18 1])\n    (m/rotate [0 0 25]))")]}
   {:namespace "journal.pivot" :title "A moving joint" :revision 0
    :blocks [(block "prose" "# A moving joint\n\nA parent node holds the pivot. Its child carries the arm away from the axis. Animate the parent and the geometry follows.")
             (block "code" "(let [track (animation/keyframes\n              (mapv (fn [[t z w]]\n                      {:time t :translation [0 0 0]\n                       :rotation [0 0 z w] :scale [1 1 1]})\n                    [[0 0 1] [1 0.70710678 0.70710678] [2 0 1]]))]\n  (m/scene\n    {:nodes [{:id :base :geometry (m/cylinder 2 3 3 32 true)}\n             {:id :pivot :children [:arm]}\n             {:id :arm :geometry (m/cube 16 2 2 true)\n              :transform {:translation [8 0 2]}}]\n     :animations [{:name \"Swing\"\n                   :channels [{:node :pivot :path :rotation :track track}]}]}))")]}])

(defn examples []
  (mapv ns-form/migrate
        (concat (raw-examples) [(readme/document)
                               #?(:clj (example-source/raptor-document)
                                  :cljs (example-source/embedded-raptor-document))]
                #?(:clj (example-source/castle-documents)
                   :cljs (example-source/embedded-castle-documents)))))

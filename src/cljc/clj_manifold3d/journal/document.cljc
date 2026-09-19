(ns clj-manifold3d.journal.document
  (:require [clojure.string :as str]))

(defn valid-namespace? [s]
  (boolean (and (string? s)
                (re-matches #"[A-Za-z][A-Za-z0-9_-]*(?:\.[A-Za-z][A-Za-z0-9_-]*)*" s))))

(defn namespace-path [s]
  (when-not (valid-namespace? s) (throw (ex-info "Invalid namespace" {:namespace s})))
  (str (-> s (str/replace "-" "_") (str/replace "." "/")) ".clj"))

(defn source [{:keys [namespace blocks]}]
  (str "(ns " namespace "\n  (:require [clj-manifold3d.core :as m]\n"
       "            [clj-manifold3d.texture :as texture]\n"
       "            [clj-manifold3d.animation :as animation]))\n\n"
       (str/join "\n\n"
                 (map (fn [{:keys [kind source]}]
                        (if (not= kind "code")
                          (str/join "\n" (map #(str ";; " %) (str/split (or source "") #"\n" -1)))
                          source)) blocks)) "\n"))

(defn entity-tx [{:keys [namespace title revision blocks applied-requests]}]
  (into (mapv (fn [i {:keys [id kind source hidden]}]
                {:block/id id :block/kind kind :block/source source
                 :block/order i :block/hidden? (boolean hidden)}) (range) blocks)
        [{:document/id namespace :document/namespace namespace
          :document/title title :document/revision (or revision 0)
          :document/applied-requests (vec applied-requests)
          :document/blocks (mapv #(vector :block/id (:id %)) blocks)}]))

(def pull-pattern
  [:document/namespace :document/title :document/revision :document/applied-requests
   {:document/blocks [:block/id :block/kind :block/source :block/order :block/hidden?]}])

(defn from-entity [e]
  {:namespace (:document/namespace e) :title (:document/title e)
   :revision (:document/revision e 0)
   :applied-requests (vec (sort (:document/applied-requests e)))
   :blocks (mapv (fn [b] {:id (:block/id b) :kind (:block/kind b)
                          :source (:block/source b) :hidden (:block/hidden? b false)})
                 (sort-by :block/order (:document/blocks e)))})

(defn block [kind source]
  {:id (str (random-uuid)) :kind kind :source source :hidden false})

(defn new-document [namespace title]
  {:namespace namespace :title title :revision 0
   :blocks [(block "prose" (str "# " title "\n\nWrite a note, try a form, and keep the result beside your thinking."))
            (block "code" "(m/cube 2 2 2 true)")]})

(defn examples []
  [{:namespace "journal.first-shapes" :title "A study in solids" :revision 0
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

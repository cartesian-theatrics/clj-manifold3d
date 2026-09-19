(ns clj-manifold3d.journal.worker
  (:require [clj-manifold3d.core :as m]
            [clj-manifold3d.texture :as texture]
            [clj-manifold3d.animation :as animation]
            [clj-manifold3d.runtime :as rt]
            [clj-manifold3d.journal.document :as doc]
            [clj-manifold3d.journal.schema :as schema]
            [datascript.core :as d]
            [cljs.reader :as reader]
            [sci.core :as sci]
            [sci.lang :as lang]
            [clojure.string :as str]
            [goog.object :as gobj]))

;; SCI contexts and native allocations cannot be database values. Evaluation
;; history is data, however, and belongs in the worker's DataScript database.
(defonce sessions (atom {}))
(defonce conn (d/create-conn schema/schema))
(defn history [namespace]
  (some-> (d/pull @conn '[:evaluation/history] [:evaluation/namespace namespace])
          :evaluation/history reader/read-string))
(defn history! [namespace value]
  (d/transact! conn [{:evaluation/namespace namespace :evaluation/history (pr-str value)}]))
(defn send! [data] (.postMessage js/self (clj->js data)))
(defn release! [session] (apply rt/dispose! (reverse @(:resources session))))

(defn context [documents]
  (sci/init
   {:features #{:cljs}
    :load-fn (fn [{:keys [namespace]}] (when-let [s (get documents (str namespace))] {:source s}))
    :namespaces
    {'clj-manifold3d.core
     (sci/copy-ns clj-manifold3d.core (sci/create-ns 'clj-manifold3d.core)
                  {:exclude [init! dispose! with-disposal import-mesh export-mesh export-model export-scene
                             text load-image load-surface ply-file-to-surface]})
     'clj-manifold3d.texture
     {'uv texture/uv 'planar-uv-native texture/planar-uv-native 'unwrap-native texture/unwrap-native
      'geodesic-uv texture/geodesic-uv 'geodesic-uv-native texture/geodesic-uv-native 'bake texture/bake}
     'clj-manifold3d.animation
     {'scene animation/scene 'scene? animation/scene? 'keyframes animation/keyframes
      'sample animation/sample 'sample-times animation/sample-times 'pivot-arm-scene animation/pivot-arm-scene}
     'math {'pi js/Math.PI 'sin js/Math.sin 'cos js/Math.cos 'sqrt js/Math.sqrt 'pow js/Math.pow}}
    :aliases {'m 'clj-manifold3d.core 'texture 'clj-manifold3d.texture
              'animation 'clj-manifold3d.animation 'math 'math}}))

(defn describe [value]
  (cond
    (instance? lang/Var value) (describe @value)
    (m/cross-section? value)
    {:kind "cross-section" :description (str "Cross-section · area " (.toFixed (m/area value) 3))
     :polygons (m/to-polygons value)}
    (m/scene? value)
    {:kind "scene" :description (str "Scene · " (count (:nodes value)) " nodes · " (count (:animations value)) " animations")
     :bytes (animation/scene-bytes value)}
    (or (m/manifold? value) (m/model? value))
    {:kind (if (m/model? value) "model" "manifold")
     :description (str (if (m/model? value) "Model" "Manifold") " · volume " (.toFixed (:volume (m/get-properties value)) 3))
     :bytes (if (m/model? value)
              (m/export-model value nil)
              (let [channels (rt/call value "numProp")
                    colored? (= channels 4)
                    smooth (m/calculate-normals value channels 55)]
                (m/export-model smooth nil :material
                                (cond-> {:normal-idx channels :roughness 0.65 :metalness 0.05
                                         :color (if colored? [1 1 1] [0.22 0.52 0.46])}
                                  colored? (assoc :color-idx 0 :alpha-idx 3)))))}
    :else {:kind "value" :description (binding [*print-length* 60 *print-level* 6] (pr-str value))}))

(defn emit-result! [id block-id value output]
  (let [result (describe value) message (clj->js (assoc (dissoc result :bytes) :type "result" :id id :block block-id :output @output))]
    (if-let [bytes (:bytes result)]
      (let [copy (.slice bytes)]
        (gobj/set message "buffer" (.-buffer copy))
        (.postMessage js/self message #js [(.-buffer copy)]))
      (.postMessage js/self message))))

(defn evaluate! [event]
  (let [{:keys [id namespace documents blocks target selection all]} (js->clj (.-data event) :keywordize-keys true)
        sources (into {} (map (juxt :namespace doc/source) documents))
        dependencies (dissoc sources namespace)
        code-blocks (vec (filter #(= "code" (:kind %)) blocks))
        before (vec (take-while #(not= target (:id %)) code-blocks))
        old (get @sessions namespace)
        previous (history namespace)
        reset? (or all (nil? old) (not= dependencies (:dependencies previous))
                   ;; Editing earlier forms invalidates the namespace, but
                   ;; repeated evaluations in an unchanged document keep defs.
                   (some (fn [{:keys [id source]}]
                           (when-let [entry (get-in previous [:evaluated id])]
                             (not= source (:source entry)))) code-blocks)
                   (and old (not= (mapv :id code-blocks) (:order previous))))
        session (if reset?
                  (do (when old (release! old))
                      {:ctx (context sources) :resources (atom [])}) old)
        output (atom "") current (atom target)]
    (swap! sessions assoc namespace session)
    (when reset? (history! namespace {:dependencies dependencies :order (mapv :id code-blocks) :evaluated {}}))
    (try
      (binding [rt/*resources* (:resources session)]
        (sci/binding [sci/ns (sci/create-ns (symbol namespace))
                      sci/print-newline true
                      sci/print-fn #(swap! output (fn [s] (subs (str s %) 0 (min 16000 (count (str s %))))))]
          (when reset?
            (sci/eval-string* (:ctx session) (doc/source {:namespace namespace :blocks []})))
          (doseq [b (if all code-blocks (concat before (filter #(= target (:id %)) code-blocks)))]
            (reset! current (:id b))
            (when (or all (= target (:id b)) (not (get-in (history namespace) [:evaluated (:id b) :complete?])))
              (reset! output "")
              (let [selected? (and selection (= target (:id b)))
                    entry (get-in (history namespace) [:evaluated (:id b)])
                    through (:through entry 0)
                    prefix-end (:topFrom selection 0)
                    _ (when (and selected? (not (:complete? entry)) (> prefix-end through))
                        (sci/eval-string* (:ctx session) (subs (:source b) through prefix-end)))
                    value (sci/eval-string* (:ctx session) (if selected? (:source selection) (:source b)))]
                (history! namespace (assoc-in (history namespace) [:evaluated (:id b)]
                                              {:source (:source b) :complete? (or (not selected?) (:complete? entry) false)
                                               :through (if selected? (max through prefix-end) (count (:source b)))}))
                (emit-result! id (:id b) value output))))))
      (send! {:type "done" :id id})
      (catch :default error
        ;; Failed initialization/evaluation may have partially mutated vars.
        (release! session) (swap! sessions dissoc namespace)
        (send! {:type "error" :id id :block @current :output @output :message (or (.-message error) (str error))})))))

(defn main []
  (js/importScripts "/wasm/manifold.js")
  (-> (m/init! {:wasm-url "/wasm/manifold.wasm"})
      (.then (fn [_] (set! (.-onmessage js/self) evaluate!) (send! {:type "ready"})))
      (.catch #(send! {:type "fatal" :message (str %)}))))

(ns clj-manifold3d.try-it.worker
  "Only explicitly exposed modeling functions can be called by the editor.
  Every evaluation gets a fresh SCI context and an owned WASM resource scope."
  (:require [clj-manifold3d.core :as m]
            [clj-manifold3d.texture :as texture]
            [clj-manifold3d.animation :as animation]
            [clj-manifold3d.runtime :as rt]
            [sci.core :as sci]
            [goog.object :as gobj]))

(defn- send! [data] (.postMessage js/self (clj->js data)))
(defn- numeric-surface [object & {:keys [depth-map] :as options}]
  (when (string? depth-map)
    (throw (ex-info "Use a numeric depth grid in this sandbox; asset URL loading is not enabled." {})))
  (apply texture/geodesic-uv object (mapcat identity options)))

(defn- context []
  (sci/init
    {:features #{:cljs}
     :namespaces
     {'clj-manifold3d.core
      (sci/copy-ns clj-manifold3d.core (sci/create-ns 'clj-manifold3d.core)
                   {:exclude [init! dispose! with-disposal import-mesh export-mesh export-model
                              export-scene text load-image load-surface ply-file-to-surface]})
      'clj-manifold3d.texture
      {'uv texture/uv 'planar-uv-native texture/planar-uv-native 'unwrap-native texture/unwrap-native
       'geodesic-uv numeric-surface 'geodesic-uv-native numeric-surface 'bake texture/bake}
      'clj-manifold3d.animation
      {'scene animation/scene 'scene? animation/scene? 'keyframes animation/keyframes
       'sample animation/sample 'sample-times animation/sample-times 'pivot-arm-scene animation/pivot-arm-scene}
      'math {'pi js/Math.PI 'sin js/Math.sin 'cos js/Math.cos 'tan js/Math.tan
             'sqrt js/Math.sqrt 'pow js/Math.pow 'abs js/Math.abs 'atan2 js/Math.atan2}}
     :aliases {'m 'clj-manifold3d.core 'texture 'clj-manifold3d.texture
               'animation 'clj-manifold3d.animation}}))

(defn- result-data [result]
  (cond
    (m/model? result)
    {:bytes (m/export-model result nil)
     :kind "solid" :vertices (rt/call result "numVert") :triangles (rt/call result "numTri")
     :volume (:volume (m/get-properties result)) :bounds (m/bounds result)}

    (m/scene? result)
    {:bytes (animation/scene-bytes result)
     :kind "scene" :nodes (count (:nodes result)) :animations (count (:animations result))}

    (or (m/csg? result) (and (map? result) (m/manifold? (:geometry result)) (:texture result)))
    (let [image (when (map? result) (:texture result))
          result-shape (if image (:geometry result) result)
          section? (m/cross-section? result-shape)
          shape (if section? (m/extrude result-shape 1) result-shape)
          _ (when (m/is-empty? shape) (throw (ex-info "The result is empty. Try changing your geometry." {})))
          channels (rt/call shape "numProp")
          colored? (= 4 channels)
          smooth (m/calculate-normals shape channels 55)
          material (cond-> {:normal-idx channels :roughness 0.37 :metalness 0.12
                            :color (if colored? [1 1 1] [0.92 0.47 0.23])}
                     colored? (assoc :color-idx 0 :alpha-idx 3))]
      {:bytes (if image (texture/glb-bytes shape image :prop-index (get result :prop-index 3))
                  (m/export-model smooth nil :material material))
       :kind (if section? "section" "solid")
       :vertices (rt/call shape "numVert") :triangles (rt/call shape "numTri")
       :volume (:volume (m/get-properties shape)) :bounds (m/bounds shape)})

    :else (throw (ex-info "Return a Manifold, CrossSection, scene, or {:geometry manifold :texture PNG-bytes}." {}))))

(defn- append-output! [output s]
  (swap! output (fn [old] (str old (subs s 0 (min (count s) (- 10000 (count old))))))))

(defn- evaluate! [event]
  (let [data (.-data event) id (gobj/get data "id") code (gobj/get data "code")
        output (atom "") started (js/performance.now)]
    (send! {:type "started" :id id})
    (try
      (when-not (and (string? code) (<= (count code) 200000))
        (throw (ex-info "Please keep the program under 200,000 characters." {})))
      (let [result (m/with-disposal
                     #(sci/binding [sci/print-newline true
                                    sci/print-fn (fn [s] (append-output! output s))]
                        (result-data (sci/eval-string* (context) code))))
            bytes (:bytes result)
            message (clj->js (assoc (dissoc result :bytes) :type "result" :id id :output @output
                                   :elapsed (- (js/performance.now) started)))]
        (gobj/set message "buffer" (.-buffer bytes))
        (.postMessage js/self message #js [(.-buffer bytes)]))
      (catch :default error
        (send! {:type "error" :id id :message (or (.-message error) (str error))
                :line (:line (ex-data error)) :column (:column (ex-data error)) :output @output})))))

(defn main []
  (js/importScripts "/wasm/manifold.js")
  (-> (m/init! {:wasm-url "/wasm/manifold.wasm"})
      (.then (fn [_]
               (set! (.-onmessage js/self) evaluate!)
               (send! {:type "ready"})))
      (.catch (fn [error] (send! {:type "fatal" :message (str error)})))))

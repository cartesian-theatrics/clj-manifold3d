(ns clj-manifold3d.glb
  "Small platform-independent GLB encoder. Public output is Uint8Array."
  (:require [clj-manifold3d.runtime :as rt]
            [goog.object :as gobj]))

(defn align4 [n] (+ n (mod (- 4 (mod n 4)) 4)))
(defn pad-bytes [bytes fill]
  (let [result (js/Uint8Array. (align4 (alength bytes)))]
    (.fill result fill) (.set result bytes) result))
(defn floats->bytes [values]
  (let [bytes (js/Uint8Array. (* 4 (count values))) view (js/DataView. (.-buffer bytes))]
    (doseq [[i value] (map-indexed vector values)] (.setFloat32 view (* 4 i) value true)) bytes))
(defn ints->bytes [values]
  (let [bytes (js/Uint8Array. (* 4 (count values))) view (js/DataView. (.-buffer bytes))]
    (doseq [[i value] (map-indexed vector values)] (.setUint32 view (* 4 i) value true)) bytes))
(defn empty-state [] {:segments [] :views [] :accessors [] :length 0})
(defn add-segment [state bytes target]
  (let [offset (align4 (:length state))
        view (cond-> {"buffer" 0 "byteOffset" offset "byteLength" (alength bytes)}
               target (assoc "target" target))]
    [(-> state (update :segments into [(js/Uint8Array. (- offset (:length state))) bytes])
         (update :views conj view) (assoc :length (+ offset (alength bytes))))
     (count (:views state))]))
(defn add-accessor [state view component-type value-count type minimum maximum]
  [(update state :accessors conj
           (cond-> {"bufferView" view "componentType" component-type "count" value-count "type" type}
             minimum (assoc "min" minimum) maximum (assoc "max" maximum)))
   (count (:accessors state))])
(defn join-segments [segments length]
  (let [result (js/Uint8Array. length)]
    (reduce (fn [offset segment] (.set result segment offset) (+ offset (alength segment))) 0 segments)
    result))
(defn encode [document segments bin-length]
  (let [json (pad-bytes (.encode (js/TextEncoder.) (js/JSON.stringify (clj->js document))) 32)
        binary (pad-bytes (join-segments segments bin-length) 0)
        total (+ 28 (alength json) (alength binary))
        result (js/Uint8Array. total) view (js/DataView. (.-buffer result))]
    (doseq [[offset value] [[0 0x46546c67] [4 2] [8 total] [12 (alength json)] [16 0x4e4f534a]
                             [(+ 20 (alength json)) (alength binary)] [(+ 24 (alength json)) 0x004e4942]]]
      (.setUint32 view offset value true))
    (.set result json 20) (.set result binary (+ 28 (alength json))) result))
(defn mesh-data [solid-or-mesh]
  (when (rt/instance-of? "Model" solid-or-mesh)
    (throw (ex-info "This exporter cannot retain Model appearance; use core/export-model directly" {})))
  (let [mesh (if (rt/instance-of? "Manifold" solid-or-mesh)
               (rt/call solid-or-mesh "getMesh") solid-or-mesh)
        width (gobj/get mesh "numProp")
        props (gobj/get mesh "vertProperties")
        rows (mapv vec (partition width (array-seq props)))
        positions (vec (mapcat #(subvec % 0 3) rows))]
    (when (empty? rows) (throw (ex-info "Cannot export empty geometry" {})))
    {:positions positions :rows rows :num-prop width
     :indices (vec (array-seq (gobj/get mesh "triVerts")))
     :min (reduce #(mapv min %1 %2) (map #(subvec % 0 3) rows))
     :max (reduce #(mapv max %1 %2) (map #(subvec % 0 3) rows))}))

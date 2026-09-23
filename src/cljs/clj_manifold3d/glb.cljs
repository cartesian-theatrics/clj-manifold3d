(ns clj-manifold3d.glb
  "Small platform-independent GLB encoder. Public output is Uint8Array."
  (:require [clj-manifold3d.runtime :as rt]
            [clj-manifold3d.glb-assets :as assets]
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

(defn document? [x] (= ::document (:model/type x)))
(defn document [gltf binary] {:model/type ::document :gltf gltf :binary binary})
(defn read-glb
  "Read embedded, single-buffer glTF 2.0 from a Uint8Array or ArrayBuffer."
  [source]
  (let [bytes (if (instance? js/ArrayBuffer source) (js/Uint8Array. source) source)
        n (alength bytes)]
    (when (< n 20) (throw (ex-info "Truncated GLB header" {})))
    (let [view (js/DataView. (.-buffer bytes) (.-byteOffset bytes) n)
          uint #(.getUint32 view % true)]
      (when-not (and (= 0x46546c67 (uint 0)) (= 2 (uint 4)) (= n (uint 8)))
        (throw (ex-info "Invalid GLB 2.0 header or length" {})))
      (loop [offset 12 gltf nil binary nil]
        (if (< offset n)
          (do
            (when (> (+ offset 8) n) (throw (ex-info "Truncated GLB chunk header" {})))
            (let [length (uint offset) kind (uint (+ offset 4)) end (+ offset 8 length)]
              (when (or (> end n) (not (zero? (mod length 4))))
                (throw (ex-info "Invalid GLB chunk length" {:length length})))
              (let [chunk (.slice bytes (+ offset 8) end)]
                (case kind
                  0x4e4f534a (do (when gltf (throw (ex-info "Duplicate JSON chunk" {})))
                                 (recur end (js->clj (js/JSON.parse (.decode (js/TextDecoder.) chunk))) binary))
                  0x004e4942 (do (when (or (nil? gltf) binary) (throw (ex-info "Unexpected BIN chunk" {})))
                                 (recur end gltf chunk))
                  (throw (ex-info "Unsupported GLB chunk" {:type kind}))))))
          (let [buffers (get gltf "buffers" []) binary (or binary (js/Uint8Array. 0))]
            (when-not (and (= "2.0" (get-in gltf ["asset" "version"]))
                           (<= (count buffers) 1) (not-any? #(contains? % "uri") buffers)
                           (<= (get (first buffers) "byteLength" 0) (alength binary))
                           (not-any? #(contains? % "uri") (get gltf "images" [])))
              (throw (ex-info "Expected glTF 2.0 with embedded buffers and images" {})))
            (document gltf binary)))))))

(defn append-document [a b]
  (let [binary (pad-bytes (:binary a) 0) other (:binary b)
        n (+ (alength binary) (alength other))]
    (document (assets/append-gltf (:gltf a) (:gltf b) (alength binary))
              (join-segments [binary other] n))))
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

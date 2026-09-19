(ns clj-manifold3d.test-support
  "Representation/IO adapters only. Behavioral assertions live in shared tests."
  (:refer-clojure :exclude [abs])
  (:require [clj-manifold3d.core :as m]
            [clojure.walk :as walk]
            #?(:cljs [clj-manifold3d.runtime :as rt])
            #?(:cljs [goog.object :as gobj])
            #?(:clj [clojure.data.json :as json])
            #?(:cljs ["fast-png" :as png])
            [clj-manifold3d.animation :as animation])
  #?(:clj (:import [java.nio ByteBuffer ByteOrder]
                   [java.io ByteArrayInputStream]
                   [javax.imageio ImageIO]
                   [java.nio.file Files])))

(defn abs [x] (#?(:clj Math/abs :cljs js/Math.abs) (double x)))
(defn numeric= [& values]
  (apply = (map #(walk/postwalk (fn [x] (if (number? x) (double x) x)) %) values)))
(defn sqrt [x] (#?(:clj Math/sqrt :cljs js/Math.sqrt) (double x)))
(def pi #?(:clj Math/PI :cljs js/Math.PI))
(def nan #?(:clj Double/NaN :cljs js/NaN))
(def infinity #?(:clj Double/POSITIVE_INFINITY :cljs js/Infinity))
(defn finite? [x] #?(:clj (Double/isFinite (double x)) :cljs (js/Number.isFinite x)))
(defn genus [shape] #?(:clj (.genus shape) :cljs (rt/call shape "genus")))
(defn with-disposal [f] #?(:clj (f) :cljs (m/with-disposal f)))
(defn mesh-field [mesh key]
  #?(:cljs (let [v (gobj/get mesh key)] (if (number? v) v (vec (array-seq v))))
     :clj (case key
            "numProp" (.numProp mesh)
            "vertProperties" (vec (.toFloatArray (.vertProperties mesh)))
            "triVerts" (vec (.toIntArray (.triVerts mesh)))
            "mergeFromVert" (vec (.toIntArray (.mergeFromVert mesh)))
            "mergeToVert" (vec (.toIntArray (.mergeToVert mesh)))
            "faceID" (vec (.toIntArray (.faceID mesh)))
            "runIndex" (vec (.toIntArray (.runIndex mesh)))
            "runOriginalID" (vec (.toIntArray (.runOriginalID mesh))))))
(defn rows [shape]
  (let [mesh (m/get-mesh-gl shape)]
    (mapv vec (partition (mesh-field mesh "numProp") (mesh-field mesh "vertProperties")))))
(defn triangle-count [shape]
  (/ (count (mesh-field (m/get-mesh-gl shape) "triVerts")) 3))
(defn bounds [shape]
  #?(:cljs (m/bounds shape)
     :clj (let [b (m/bounds shape)
                values (fn [v] (cond-> [(.x v) (.y v)] (or (m/manifold? shape) (m/model? shape)) (conj (.z v))))
                center (values (.Center b)) half (mapv #(/ % 2) (values (.Size b)))]
            {:min (mapv - center half) :max (mapv + center half)})))
(defn polygons [section]
  #?(:cljs (m/to-polygons section)
     :clj (mapv (fn [polygon] (mapv #(vector (.x %) (.y %)) polygon)) (m/to-polygons section))))
(defn uint32 [bytes offset]
  #?(:cljs (.getUint32 (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes)) offset true)
     :clj (.getInt (doto (ByteBuffer/wrap bytes) (.order ByteOrder/LITTLE_ENDIAN)) offset)))
(defn glb-json [bytes]
  (let [length (uint32 bytes 12)]
    #?(:cljs (js->clj (js/JSON.parse (.decode (js/TextDecoder.) (.subarray bytes 20 (+ 20 length)))) :keywordize-keys true)
       :clj (json/read-str (String. bytes 20 length "UTF-8") :key-fn keyword))))

(defn glb-image [bytes index]
  (let [doc (glb-json bytes)
        view (get-in doc [:bufferViews (get-in doc [:images index :bufferView])])
        offset (+ 28 (uint32 bytes 12) (get view :byteOffset 0))
        length (:byteLength view)]
    #?(:clj
       (let [image (ImageIO/read (ByteArrayInputStream. bytes offset length))]
         {:width (.getWidth image) :height (.getHeight image)
          :pixel (fn [x y] (let [argb (.getRGB image x y)]
                             (mapv #(bit-and 255 (unsigned-bit-shift-right argb %)) [16 8 0 24])))})
       :cljs
       (let [image ((gobj/get png "decode") (.subarray bytes offset (+ offset length)))
             width (gobj/get image "width") data (gobj/get image "data")]
         {:width width :height (gobj/get image "height")
          :pixel (fn [x y] (let [start (* 4 (+ x (* y width)))]
                             (vec (array-seq (.subarray data start (+ start 4))))))}))))

(defn glb-accessor [bytes index]
  (let [doc (glb-json bytes) accessor (get-in doc [:accessors index])
        view (get-in doc [:bufferViews (:bufferView accessor)])
        width ({"SCALAR" 1 "VEC2" 2 "VEC3" 3 "VEC4" 4} (:type accessor))
        offset (+ 28 (uint32 bytes 12) (get view :byteOffset 0) (get accessor :byteOffset 0))
        buffer #?(:clj (doto (ByteBuffer/wrap bytes) (.order ByteOrder/LITTLE_ENDIAN))
                  :cljs (js/DataView. (.-buffer bytes) (.-byteOffset bytes) (.-byteLength bytes)))]
    (mapv (fn [i]
            (let [at (+ offset (* i 4))]
              (case (:componentType accessor)
                5126 #?(:clj (.getFloat buffer at) :cljs (.getFloat32 buffer at true))
                5125 #?(:clj (.getInt buffer at) :cljs (.getUint32 buffer at true)))))
          (range (* width (:count accessor))))))
(defn scene-bytes [scene]
  #?(:cljs (animation/scene-bytes scene)
     :clj (let [file (java.io.File/createTempFile "shared-scene-" ".glb")]
            (try (animation/export-scene scene (.getPath file)) (Files/readAllBytes (.toPath file))
                 (finally (.delete file))))))

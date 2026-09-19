(ns clj-manifold3d.asset-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [clj-manifold3d.core :as m]
            [clj-manifold3d.runtime :as rt]
            [clj-manifold3d.texture :as texture]
            [clj-manifold3d.fixtures :as fixtures]
            [clj-manifold3d.animation :as animation]
            [clj-manifold3d.raster :as raster]
            ["fast-png" :as png]
            [goog.object :as gobj]
            [clj-manifold3d.portable-animation-test :refer [glb-json]]
            [clj-manifold3d.portable-texture-test :refer [rows close? corner corner-patch flat]]))

(deftest colored-artwork-bakes-to-png-in-node-and-browser
  (m/with-disposal
    #(let [red (m/color (m/cube 1 1 0.1) [1 0 0 1])
           blue (-> (m/cube 0.5 0.5 0.2) (m/translate [0 0.5 0]) (m/color [0 0 1 1]))
           artwork (m/union red blue)
           bytes (texture/bake artwork :width 8 :height 8 :bounds [-0.5 -0.5 1.5 1.5]
                               :background [0.5 0.5 0.5 1])
           decoded ((gobj/get png "decode") bytes (js-obj "checkCrc" true))
           data (gobj/get decoded "data")
           pixel (fn [x y] (vec (array-seq (.subarray data (* 4 (+ x (* 8 y))) (* 4 (+ (inc x) (* 8 y)))))))]
       (is (= 8 (gobj/get decoded "width")))
       (is (= 8 (gobj/get decoded "height")))
       (is (= [188 188 188 255] (pixel 0 0)) "Linear background is encoded as sRGB")
       (is (= [0 0 255 255] (pixel 2 2)) "+Y is image-up, frontmost blue occludes red")
       (is (= [255 0 0 255] (pixel 5 5)))
       (is (= :NoError (m/status artwork)))
       (is (close? 0.125 (:volume (m/get-properties artwork)))))))

(deftest texture-baking-depth-buffer-and-interpolation
  (m/with-disposal
    (fn []
      (let [front [[0 0 1 1 0 0 1] [2 0 1 0 1 0 1] [0 2 1 0 0 1 1]]
            back [[0 0 0 0 0 0 1] [2 0 0 0 0 0 1] [0 2 0 0 0 0 1]]]
        (doseq [faces [[[0 1 2] [3 4 5]] [[3 4 5] [0 1 2]]]]
          (let [mesh (m/mesh :num-prop 7 :vert-properties (vec (mapcat identity (concat front back))) :tri-verts faces)
                {:keys [data]} (raster/render mesh :width 2 :height 2)]
            (is (= [188 137 137 255] (vec (array-seq (.subarray data 8 12))))
                "Front triangle interpolates linear RGB regardless of draw order")))))))

(deftest texture-baking-validates-inputs
  (m/with-disposal
    #(let [shape (m/cube 1 1 1) colored (m/color shape [1 0 0 1])]
       (is (thrown? js/Error (texture/bake shape)))
       (is (thrown? js/Error (texture/bake colored :width 0)))
       (is (thrown? js/Error (texture/bake colored :width 2049)))
       (is (thrown? js/Error (texture/bake colored :height 2.5)))
       (is (thrown? js/Error (texture/bake colored :bounds [0 0 0 1])))
       (is (thrown? js/Error (texture/bake colored :color-index 4)))
       (is (thrown? js/Error (texture/bake (m/color shape [1 0 0 0.5])))))))

(deftest native-image-depth-matches-numeric-grids
  (m/with-disposal
    (fn []
      (doseq [[asset denominator samples]
              [[:depth8 255 [[0 19 41] [51 127 180] [193 211 255]]]
               [:depth16 65535 [[0 4001 8001] [12345 31234 45678] [56789 60001 65535]]]]
              boundary [:fade :step]
              patch [:flat :corner]]
        (let [source (if (= :flat patch) (m/cube 10 10 2 true) (corner))
              mapping (if (= :flat patch) flat corner-patch)
              numeric (mapv #(mapv (fn [v] (/ v denominator)) %) samples)
              options [:depth-scale 0.03 :depth-boundary boundary]
              image (apply mapping source :depth-map (get @fixtures/data asset) options)
              grid (apply mapping source :depth-map numeric options)
              actual (sort (rows image)) expected (sort (rows grid))]
          (is (= :NoError (m/status image)))
          (is (= (count expected) (count actual)))
          (is (every? true? (map #(every? true? (map close? %1 %2)) actual expected)))
          (is (close? (:volume (m/get-properties image)) (:volume (m/get-properties grid)))))))))

(deftest native-image-surface-and-point-cloud-loaders
  (async done
    (let [ply (.encode (js/TextEncoder.)
                       (str "ply\nformat ascii 1.0\nelement vertex 9\n"
                            "property float x\nproperty float y\nproperty float z\n"
                            "property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n"
                            (apply str (for [y (range 3) x (range 3)] (str x " " y " 0 51 102 153\n")))))
          checked (fn [promise check]
                    (.then promise (fn [shape]
                                     (try (is (= :NoError (m/status shape))) (check shape)
                                          (finally (m/dispose! shape))))))]
      (-> (js/Promise.all
            #js [(checked (m/load-image (:depth8 @fixtures/data) 0.5)
                          #(do (is (close? 2 (:volume (m/get-properties %))))
                               (is (some (fn [[x y z c]] (and (close? x 2) (close? y 0) (close? z 0.5)
                                                               (close? c (/ 41 255)))) (rows %)))))
                 (checked (m/load-surface (:depth8 @fixtures/data))
                          #(is (pos? (:volume (m/get-properties %)))))
                 (checked (m/ply-file-to-surface ply 1 2 1)
                          #(do (is (close? 2 (:volume (m/get-properties %))))
                               (is (some (fn [[_ _ z r g b]] (and (close? z 2) (every? true? (map close? [r g b] [0.2 0.4 0.6]))))
                                         (rows %)))))])
          (.catch (fn [error] (is false (str error))))
          (.finally done)))))

(deftest embedded-texture-glb-has-real-uvs-and-image
  (m/with-disposal
    (fn []
      (let [source (texture/planar-uv-native (m/cube 2 3 4))
            bytes (texture/glb-bytes source (:depth8 @fixtures/data))
            doc (glb-json bytes)]
        (is (= "image/png" (get-in doc [:images 0 :mimeType])))
        (is (integer? (get-in doc [:meshes 0 :primitives 0 :attributes :TEXCOORD_0])))
        (is (pos? (get-in doc [:bufferViews (get-in doc [:images 0 :bufferView]) :byteLength])))))))

(deftest async-image-loading-and-export
  (async done
    (let [source (m/cube 10 10 2 true) result (atom nil)]
      (-> (flat source :depth-map (fixtures/path "depth16.png") :depth-scale 0.05)
          (.then (fn [shape]
                   (reset! result shape)
                   (is (= :NoError (m/status shape)))
                   (texture/export-glb shape nil (fixtures/path "depth8.png"))))
          (.then (fn [bytes] (is (= "2.0" (get-in (glb-json bytes) [:asset :version])))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn [] (m/dispose! source @result) (done)))))))

(deftest font-bytes-work-through-the-private-wasm-filesystem
  (async done
    (-> (m/text (:font @fixtures/data) "WASM" 12 8)
        (.then (fn [shape]
                 (try (is (m/cross-section? shape)) (is (pos? (m/area shape)))
                      (finally (m/dispose! shape)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest mesh-file-formats-round-trip-in-node-and-browser
  (async done
    (let [source (m/cube 2 3 4)
          jobs (map (fn [format]
                      (-> (m/import-mesh (m/export-mesh (m/get-mesh-gl source) nil :format format) {:format format})
                          (.then (fn [mesh]
                                   (m/with-disposal
                                     #(let [shape (m/manifold mesh)]
                                        (is (= :NoError (m/status shape)))
                                        (is (close? 24 (:volume (m/get-properties shape))))))))))
                    [:glb :stl :obj])]
      (-> (js/Promise.all (to-array jobs))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn [] (m/dispose! source) (done)))))))

(deftest material-and-uv-channels-survive-glb-round-trip
  (async done
    (let [bytes (m/with-disposal
                  #(let [source (m/color (texture/planar-uv-native (m/cube 2 3 4)) [0.2 0.4 0.6 0.8] 5)]
                     (m/export-mesh source nil :faceted true
                                    :material (m/material :uv-idx 0 :color-idx 2 :alpha-idx 5 :roughness 0.25))))
          doc (glb-json bytes)]
      (is (= 0.25 (get-in doc [:materials 0 :pbrMetallicRoughness :roughnessFactor])))
      (is (= "BLEND" (get-in doc [:materials 0 :alphaMode])))
      (doseq [attribute [:POSITION :TEXCOORD_0 :COLOR_0 :NORMAL]]
        (is (integer? (get-in doc [:meshes 0 :primitives 0 :attributes attribute]))))
      (-> (m/import-mesh bytes)
          (.then (fn [mesh]
                   (m/with-disposal
                     #(let [shape (m/manifold mesh)]
                        (is (= :NoError (m/status shape)))
                        (is (close? 24 (:volume (m/get-properties shape))))
                        (doseq [[x _ z u v r g b a] (rows shape)]
                          (is (every? true? (map close? [u v r g b a] [x z 0.2 0.4 0.6 0.8]))))))))
          (.catch (fn [error] (is false (str error))))
          (.finally done)))))

(deftest unsupported-mesh-formats-and-scene-import-fail-explicitly
  (async done
    (let [bytes (m/with-disposal #(animation/scene-bytes (animation/pivot-arm-scene)))]
      (m/with-disposal
        #(is (thrown? js/Error (m/export-model (m/cube 1 1 1) nil :format :3mf))))
      (-> (m/import-mesh bytes)
          (.then (fn [_] (is false "Animated scenes must not silently import as a static mesh")))
          (.catch (fn [error] (is (boolean (re-find #"scene/animation import" (str error))))))
          (.finally done)))))

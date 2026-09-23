(ns clj-manifold3d.portable-animation-test
  (:require #?(:clj [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [clj-manifold3d.test-support :as support]
            [clj-manifold3d.animation :as animation]
            [clj-manifold3d.core :as m]
            [clj-manifold3d.glb-assets :as assets]))

(use-fixtures :each support/with-disposal)
(def glb-json support/glb-json)

(deftest lights-cameras-and-emission-survive-export-and-animation
  (let [track [{:time 0 :translation [0 0 3] :rotation [0 0 0 1] :scale [1 1 1]}
               {:time 2 :translation [3 0 3] :rotation [0 0 0 1] :scale [1 1 1]}]
        scene (m/scene {:nodes [{:id :light :light {:type :spot :intensity 500 :range 40 :inner-cone 0.2 :outer-cone 0.6}}
                                {:id :camera :camera {:yfov 0.7 :znear 0.1 :zfar 1000}}
                                {:id :moon :light {:type :directional :color [0.2 0.4 1]}}
                                {:id :gem :geometry (m/cube 1 1 1)
                                 :material {:emissive [1 0.3 0.1] :emissive-strength 4}}]
                        :animations [{:channels [{:node :light :path :translation :track track}
                                                  {:node :camera :path :translation :track track}]}]})
        doc (glb-json (support/scene-bytes scene))]
    (is (= 2 (count (get-in doc [:extensions :KHR_lights_punctual :lights]))))
    (is (= "spot" (get-in doc [:extensions :KHR_lights_punctual :lights 0 :type])))
    (is (support/numeric= 0.6 (get-in doc [:extensions :KHR_lights_punctual :lights 0 :spot :outerConeAngle])))
    (is (= 0 (get-in doc [:nodes 0 :extensions :KHR_lights_punctual :light])))
    (is (= 1 (get-in doc [:nodes 2 :extensions :KHR_lights_punctual :light])))
    (is (= 0 (get-in doc [:nodes 1 :camera])))
    (is (support/numeric= 0.7 (get-in doc [:cameras 0 :perspective :yfov])))
    (is (= #{"KHR_lights_punctual" "KHR_materials_emissive_strength"} (set (:extensionsUsed doc))))
    (is (support/numeric= [1 0.3 0.1] (get-in doc [:materials 0 :emissiveFactor])))
    (is (= 4 (get-in doc [:materials 0 :extensions :KHR_materials_emissive_strength :emissiveStrength])))
    (is (= 2 (count (get-in doc [:animations 0 :channels]))))))

(deftest appending-scenes-remaps-light-and-camera-indices
  (let [doc {"extensionsUsed" ["KHR_lights_punctual"]
             "extensions" {"KHR_lights_punctual" {"lights" [{"type" "point"}]}}
             "cameras" [{"type" "perspective" "perspective" {"yfov" 0.6 "znear" 0.1}}]
             "nodes" [{"camera" 0 "extensions" {"KHR_lights_punctual" {"light" 0}}}]
             "scenes" [{"nodes" [0]}] "scene" 0}
        combined (assets/append-gltf doc doc 0)]
    (is (= 2 (count (get-in combined ["extensions" "KHR_lights_punctual" "lights"]))))
    (is (= 1 (get-in combined ["nodes" 1 "extensions" "KHR_lights_punctual" "light"])))
    (is (= 1 (get-in combined ["nodes" 1 "camera"])))
    (is (= [0 1] (get-in combined ["scenes" 0 "nodes"])))
    (is (= 0 (get-in doc ["nodes" 0 "extensions" "KHR_lights_punctual" "light"])))))

(deftest invalid-light-camera-and-emission-settings-fail-early
  (doseq [node [{:light {:type :area}} {:light {:type :point :intensity -1}}
                {:light {:type :point :color [1 2 1]}} {:light {:type :directional :range 10}}
                {:light {:type :spot :inner-cone 0.7 :outer-cone 0.5}}
                {:light {:type :point :inner-cone 0.1}}
                {:camera {:yfov 0 :znear 0.1}} {:camera {:yfov 0.6 :znear 1 :zfar 0.5}}
                {:material {:emissive [1 2 0]}} {:material {:emissive-strength 2}}
                {:material {:emissive [1 1 1] :emissive-strength support/infinity}}]]
    (is (thrown? #?(:clj Exception :cljs js/Error) (m/scene {:nodes [(assoc node :id :test)]})))))

(deftest scene-and-pivot-animation-survive-export
  (let [scene (animation/pivot-arm-scene)
        bytes (support/scene-bytes scene)
        doc (glb-json bytes)]
    (is (animation/scene? scene))
    (is (support/numeric= 0x46546c67 (support/uint32 bytes 0)))
    (is (support/numeric= 2 (support/uint32 bytes 4)))
    (is (support/numeric= (alength bytes) (support/uint32 bytes 8)))
    (is (support/numeric= "2.0" (get-in doc [:asset :version])))
    (is (support/numeric= [2] (get-in doc [:nodes 1 :children])))
    (is (support/numeric= [0 1] (get-in doc [:scenes 0 :nodes])))
    (is (support/numeric= "rotation" (get-in doc [:animations 0 :channels 0 :target :path])))
    (is (support/numeric= 1 (get-in doc [:animations 0 :channels 0 :target :node])))
    (is (support/numeric= "LINEAR" (get-in doc [:animations 0 :samplers 0 :interpolation])))
    (is (support/numeric= 2 (count (:meshes doc))))
    (doseq [buffer-view (:bufferViews doc)]
      (is (zero? (mod (:byteOffset buffer-view) 4))))))

(deftest track-sampling-interpolates-and-clamps
  (let [track (animation/keyframes [{:time 0 :translation [0 0 0] :rotation [0 0 0 1] :scale [1 1 1]}
                                    {:time 2 :translation [2 4 6] :rotation [0 0 1 0] :scale [3 3 3]}])
        middle (animation/sample track 1)]
    (is (support/numeric= [1 2 3] (:translation middle)))
    (is (support/numeric= [2 2 2] (:scale middle)))
    (is (< (support/abs (- (nth (:rotation middle) 2) (support/sqrt 0.5))) 1e-8))
    (is (support/numeric= [0 0 0] (:translation (animation/sample track -1))))
    (is (support/numeric= [2 4 6] (:translation (animation/sample track 3))))
    (is (support/numeric= 3 (count (animation/sample-times track [0 1 2]))))))

(deftest invalid-scenes-fail-before-writing
  (doseq [data [{} {:nodes [{:id :x} {:id :x}]}
                {:nodes [{:id :x :children [:missing]}]}
                {:nodes [{:id :x :children [:y]} {:id :y :children [:x]}]}
                {:nodes [{:id :x :transform {:translation [0 0 support/nan]}}]}
                {:nodes [{:id :x}] :animations [{:channels [{:node :x :path :rotation :track []}]}]}]]
    (is (thrown? #?(:clj Exception :cljs js/Error) (animation/scene data)))))

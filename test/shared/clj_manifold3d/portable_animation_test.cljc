(ns clj-manifold3d.portable-animation-test
  (:require #?(:clj [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [clj-manifold3d.test-support :as support]
            [clj-manifold3d.animation :as animation]))

(use-fixtures :each support/with-disposal)
(def glb-json support/glb-json)

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

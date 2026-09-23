(ns clj-manifold3d.portable-core-test
  (:require #?(:clj [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [clj-manifold3d.core :as m]
            [clj-manifold3d.test-support :as support]
            #?(:cljs [clj-manifold3d.runtime :as rt])))

(use-fixtures :each support/with-disposal)
(defn close? [a b] (< (support/abs (- a b)) 1e-5))
(defn volume [object] (:volume (m/get-properties object)))

(deftest fresh-wasm-and-immutable-solids
  (let [a (m/cube [2 3 4]) shifted (m/translate a [10 20 30])]
    (is (m/manifold? a))
    #?(:cljs (is (not (instance? js/Promise a))))
    (is (support/numeric= :NoError (m/status a)))
    (is (support/numeric= {:min [0 0 0] :max [2 3 4]} (support/bounds a)))
    (is (support/numeric= {:min [10 20 30] :max [12 23 34]} (support/bounds shifted)))
    (doseq [[expected result]
            [[24 (volume a)]
             [24 (volume (m/mirror shifted [0 1 0]))]
             [192 (volume (m/scale a [2 2 2]))]
             [24 (volume (m/rotate a [23 41 67]))]]]
      (is (close? expected result)))))

(deftest boolean-varargs-and-collections
  (let [a (m/cube 2 2 2) b (m/translate a [1 0 0]) c (m/translate a [0 1 0])]
    (doseq [[expected result]
            [[12 (volume (m/union a b))]
             [4 (volume (m/intersection a b))]
             [4 (volume (m/difference a b))]
             [8 (volume (m/union a))]]]
      (is (close? expected result)))
    (doseq [op [m/union m/difference m/intersection m/hull]]
      (is (close? (volume (op a b c)) (volume (op [a b c]))))))
  (is (thrown? #?(:clj Exception :cljs js/Error) (m/union [])))
  (is (thrown? #?(:clj Exception :cljs js/Error) (m/union (m/cube 1 1 1) (m/square 1 1)))))

(deftest cross-sections-and-extrusion
  (let [ring (m/difference (m/square 6 6 true) (m/square 2 2 true))
        polygons (support/polygons ring)]
    (is (m/cross-section? ring))
    (is (support/numeric= 2 (count polygons)))
    (is (support/numeric= {:min [-3 -3] :max [3 3]} (support/bounds ring)))
    (doseq [[expected result]
            [[32 (m/area ring)]
             [32 (m/area (m/cross-section polygons :non-zero))]
             [96 (volume (m/extrude ring 3))]
             [32 (m/area (m/simplify ring 0.001))]
             [10 (m/get-height (m/scale-to-height ring 10))]]]
      (is (close? expected result)))
    (doseq [join [:square :round :miter]]
      (is (> (m/area (m/offset ring 0.2 join)) (m/area ring))))))

(deftest primitives-and-revolution
  (is (m/is-empty? (m/manifold)))
  (is (close? (/ 8 3) (volume (m/tetrahedron))))
  (is (< (support/abs (- (* support/pi 4 5) (volume (m/cylinder 5 2 2 256)))) 0.02))
  (is (< (support/abs (- (* (/ 4 3) support/pi 27) (volume (m/sphere 3 128)))) 0.2))
  (let [torus (m/revolve (m/translate (m/circle 2 48) [5 0]) 64)]
    (is (support/numeric= 1 (support/genus torus)))
    (is (support/numeric= :NoError (m/status torus)))))

(deftest decomposition-splits-and-projection
  (let [a (m/cube 2 2 2) b (m/translate a [4 0 0])
        parts (m/decompose (m/compose [a b]))
        [front back] (m/split-by-plane a [0 0 1] 1)
        [overlap remainder] (m/split a (m/translate a [1 0 0]))]
    (is (support/numeric= 2 (count parts)))
    (is (support/numeric= 3 (count (m/slices a 0.25 1.75 3))))
    (doseq [[expected result]
            [[16 (reduce + (map volume parts))]
             [4 (volume front)] [4 (volume back)]
             [4 (volume overlap)] [4 (volume remainder)]
             [4 (m/area (m/cross-section (m/project a)))] [4 (m/area (m/slice a 1))]
             [4 (volume (m/trim-by-plane a [0 0 1] 1))]]]
      (is (close? expected result)))))

(deftest mesh-round-trip-and-physical-halfedges
  (let [a (m/cube 2 3 4) b (m/manifold (m/get-mesh-gl a))
        halfedges (m/get-halfedges b)]
    (is (close? (volume a) (volume b)))
    (is (support/numeric= 8 (count (m/get-vertices b))))
    (is (support/numeric= 12 (support/triangle-count b)))
    (is (support/numeric= 18 (count (m/get-edges b))))
    (is (support/numeric= 36 (count halfedges)))
    (doseq [[i {:keys [start-vert end-vert paired-halfedge]}] (map-indexed vector halfedges)]
      (let [paired (nth halfedges paired-halfedge)]
        (is (support/numeric= i (:paired-halfedge paired)))
        (is (support/numeric= start-vert (:end-vert paired)))
        (is (support/numeric= end-vert (:start-vert paired)))))))

(deftest polyhedron-surface-and-loft
  (let [vertices [[0 0 0] [2 0 0] [2 2 0] [0 2 0] [0 0 2] [2 0 2] [2 2 2] [0 2 2]]
        faces [[0 3 2 1] [4 5 6 7] [0 1 5 4] [1 2 6 5] [2 3 7 6] [3 0 4 7]]
        section (m/square 2 3) frames [(m/frame) (m/translate (m/frame) [0 0 4])]]
    (is (close? 8 (volume (m/polyhedron vertices faces))))
    (is (close? 6 (volume (m/surface [[2 2 2 2] [2 2 2 2]]))))
    (doseq [algorithm [:isomorphic :eager-nearest-neighbor]]
      (let [solid (m/loft section frames algorithm)]
        (is (support/numeric= :NoError (m/status solid)))
        (is (close? 24 (volume solid)))))))

(deftest frames-centering-and-arcs
  (let [a (m/cube 2 3 4)
        f (-> (m/frame) (m/rotate [0.2 0.3 0.4]) (m/translate [3 4 5]))
        b (-> a (m/transform f) (m/transform (m/invert-frame f)))]
    (is (every? true? (map close? [0 0 0] (:min (support/bounds b)))))
    (is (every? true? (map close? [2 3 4] (:max (support/bounds b)))))
    (is (support/numeric= {:min [-1 -1.5 0] :max [1 1.5 4]} (support/bounds (m/center a))))
    (is (support/numeric= 0 (first (:min (support/bounds (m/snap (m/translate a [7 0 0])))))))
    (is (close? 2 (m/get-height (m/circle [1 0] [0 1] [-1 0] 64))))
    (is (support/numeric= 17 (count (m/three-point-arc-points [1 0] [0 1] [-1 0] 16))))))

(deftest loft-segments-share-the-same-functional-state-transitions
  (let [shape (m/loft [{:cross-section (m/square 2 3)
                        :cross-section-fn #(m/scale % [2 1])
                        :frame (m/translate (m/frame) [0 0 1])
                        :frame-fn #(m/translate % [0 0 1])
                        :algorithm :isomorphic}
                       {:frame-fn #(m/translate % [0 0 4])}])]
    (is (close? 48 (volume shape)))
    (is (support/numeric= {:min [0 0 2] :max [4 3 6]} (support/bounds shape)))))

(deftest native-spatial-queries-and-provenance
  (let [cube (m/cube 2 2 2) colored (m/color cube [0.2 0.4 0.6 1])
        original (m/as-original colored)]
    (is (close? (volume colored) (volume original)))
    (is (= (sort (support/rows colored)) (sort (support/rows original))))
    (doseq [shape [cube (m/model colored)]]
      (m/with-spatial-index shape
        (fn [index]
          (let [hit (m/ray-cast index [1 1 5] [0 0 -4])]
            (is (close? 3 (:distance hit)))
            (is (support/numeric= [1 1 2] (:position hit)))
            (is (close? 1 (reduce + (:barycentric hit)))))
          (is (nil? (m/ray-cast index [1 1 5] [0 0 -1] :max-distance 2)))
          (is (nil? (m/ray-cast index [4 4 5] [0 0 -1])))
          (is (m/contains-point? index [1 1 1]))
          (is (m/contains-point? index [0 1 1]))
          (is (not (m/contains-point? index [0 1 1] :boundary? false)))
          (is (not (m/contains-point? index [3 1 1])))
          (is (close? 1 (:distance (m/closest-point index [3 1 1]))))
          (is (m/overlap? index (m/translate cube [2 0 0])))
          (is (not (m/overlap? index (m/translate cube [3 0 0]))))
          (is (thrown? #?(:clj Exception :cljs js/Error) (m/ray-cast index [1 1 5] [0 0 0])))))))
  (is (nil? (m/closest-point (m/difference (m/cube 1 1 1) (m/cube 1 1 1)) [0 0 0])))
  #?(:cljs
     (let [index (atom nil)]
       (is (thrown? js/Error (m/with-spatial-index (m/cube 1 1 1)
                              #(do (reset! index %) (throw (js/Error. "scope"))))))
       (is (rt/call @index "isDeleted")))))

(deftest smoothing-and-warp
  (let [a (m/tetrahedron)
        refined (m/refine (m/smooth (m/get-mesh a)) 4)
        cube (m/cube 2 2 2)
        warped #?(:cljs (m/warp cube (fn [[x y z]] [(* 2 x) y z])) :clj nil)]
    (is (support/numeric= :NoError (m/status refined)))
    (is (> (support/triangle-count refined) (support/triangle-count a)))
    #?(:cljs (is (close? 16 (volume warped))))
    (is (support/numeric= :NoError (m/status (m/refine-to-length (m/smooth-out a) 0.2))))
    (is (support/numeric= :NoError (m/status (m/calculate-normals a 0 60))))))

#?(:cljs
(deftest resource-scopes-release-handles-on-exceptions
  (let [handle (atom nil)]
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (support/with-disposal
                   #(do (reset! handle (m/cube 1 1 1)) (throw (js/Error. "test"))))))
    (is (rt/call @handle "isDeleted"))
    (m/dispose! @handle)))
)

(deftest upstream-minkowski-and-solid-simplification
  (let [a (m/cube [2 2 2] true) b (m/cube [1 1 1] true)]
    (is (close? 27 (volume (m/minkowski-sum a b))))
    (is (close? 1 (volume (m/minkowski-difference a b))))
    (is (close? 8 (volume a)))
    (let [refined (m/refine a 4) simple (m/simplify refined 0.001)]
      (is (close? 8 (volume simple)))
      (is (< (support/triangle-count simple) (support/triangle-count refined))))
    (is (close? 8 (volume (m/simplify (m/model (m/color a [1 0 0 1])) 0.001))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (m/minkowski-sum (m/model a) b)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (m/simplify a -1)))))

(deftest upstream-tolerance-refinement-and-properties
  (let [a (m/sphere 2 16) tol (m/set-tolerance a 0.01)
        normals (m/calculate-normals a)
        smooth (m/smooth-by-normals normals)
        refined (m/refine-to-tolerance smooth 0.05)
        curvature (m/calculate-curvature a 0 1)]
    (is (close? 0.01 (m/get-tolerance tol)))
    (is (< (m/get-tolerance a) 0.01))
    (is (= :NoError (m/status refined)))
    (is (> (support/triangle-count refined) (support/triangle-count a)))
    (is (= 5 (support/mesh-field (m/get-mesh curvature) "numProp")))
    (is (every? support/finite? (mapcat #(drop 3 %) (support/rows curvature))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (m/refine-to-tolerance a 0)))))

(deftest upstream-bevel-and-gap
  (let [a (m/cube 1 1 1) b (m/translate a [3 0 0])]
    (is (close? 2 (m/min-gap a b 5)))
    (is (close? 1 (m/min-gap a b 1)))
    (is (close? 0 (m/min-gap a a 5)))
    (is (close? 2 (m/min-gap (m/model a) (m/model b) 5)))
    (is (close? 8.5 (m/area (m/offset (m/square 2 2) 0.5 :bevel))))))

(deftest upstream-segment-rays-retain-old-ray-api
  (let [a (m/cube 2 2 2)
        hits (m/ray-cast-segment a [-1 0.6 0.7] [3 0.6 0.7])]
    (is (= 2 (count hits)))
    (is (every? true? (map close? [0.25 0.75] (map :distance hits))))
    (is (support/numeric= [[0 0.6 0.7] [2 0.6 0.7]] (mapv :position hits)))
    (is (every? #(and (integer? (:face-id %)) (= 3 (count (:normal %)))) hits))
    (is (empty? (m/ray-cast-segment a [-1 3 3] [3 3 3])))
    (is (close? 1 (:distance (m/ray-cast a [-1 0.6 0.7] [1 0 0]))))))

(deftest upstream-context-cancellation-is-explicit-and-immutable
  (let [a (m/cube 2 2 2) context (m/execution-context)]
    (is (false? (m/cancelled? context)))
    (is (<= 0 (m/progress context) 1))
    (is (identical? context (m/cancel! context)))
    (is (m/cancelled? context))
    (is (= :Cancelled (m/status (m/refine (m/with-context a context) 2))))
    (is (= :Cancelled (m/status (m/manifold (m/get-mesh a) context))))
    (is (= :Cancelled (m/status (m/smooth (m/get-mesh a) [] context))))
    (is (= :NoError (m/status a)))
    (is (close? 8 (volume a)))
    (let [fresh (m/execution-context)]
      (is (= :NoError (m/status (m/manifold (m/get-mesh a) fresh))))
      (is (= :NoError (m/status (m/refine (m/with-context a fresh) 2))))
      (is (close? 1 (m/progress fresh))))))

(deftest upstream-obj-is-a-geometry-only-round-trip
  (let [a (m/color (m/cube [2 3 4]) [0.1 0.3 0.7 1])
        encoded (m/write-obj-string a) b (m/read-obj-string encoded)]
    (is (string? encoded))
    (is (= :NoError (m/status b)))
    (is (close? 24 (volume b)))
    (is (support/numeric= (support/bounds a) (support/bounds b)))
    (is (= 3 (support/mesh-field (m/get-mesh b) "numProp")))
    (is (= (sort (map #(vec (take 3 %)) (support/rows a))) (sort (support/rows b))))))

(deftest upstream-level-set-and-context-factories
  (let [box {:min [-1.5 -1.5 -1.5] :max [1.5 1.5 1.5]}
        sdf (fn [[x y z]] (- 1 (+ (* x x) (* y y) (* z z))))
        a (m/level-set sdf box 0.2)
        b (m/level-set sdf box 0.2 :context (m/execution-context))]
    (is (= :NoError (m/status a)))
    (is (< 3.8 (volume a) 4.3))
    (is (close? (volume a) (volume b)))
    (is (= :Cancelled (m/status (m/level-set sdf box 0.2 :context (m/cancel! (m/execution-context))))))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (m/level-set (fn [_] (throw (ex-info "SDF callback error" {}))) box 1)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (m/level-set sdf box 0)))))

(deftest upstream-mesh-run-flags-and-portable-data
  (let [a (m/calculate-normals (m/cube 2 3 4))
        mesh (m/get-mesh a) data (m/mesh-data mesh)
        clone (apply m/mesh (mapcat identity data)) b (m/manifold clone)]
    (is (seq (:run-flags data)))
    (is (every? :has-normals? (m/mesh-run-info mesh)))
    (is (= data (m/mesh-data clone)))
    (is (= :NoError (m/status b)))
    (is (close? 24 (volume b)))
    (is (every? :has-normals? (m/mesh-run-info (m/get-mesh b))))))

(deftest upstream-mesh-data-keeps-unsigned-identifiers
  ;; Copy arbitrary metadata without constructing a solid: these values exercise
  ;; the full uint32/uint8 representation across JNI and JS typed arrays.
  (let [mesh (m/mesh :run-original-id [2147483649] :face-id [4294967295]
                     :run-flags [255] :tolerance 0.125)
        data (m/mesh-data mesh)]
    (is (= [2147483649] (:run-original-id data)))
    (is (= [4294967295] (:face-id data)))
    (is (= [255] (:run-flags data)))
    (is (= data (m/mesh-data (apply m/mesh (mapcat identity data)))))))

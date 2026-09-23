(ns clj-manifold3d.spatial
  "Native BVH queries over immutable WASM snapshots. Same MeshUtils as the JVM."
  (:require [clj-manifold3d.runtime :as rt]))

(defn spatial-index [object]
  (cond
    (rt/instance-of? "Model" object)
    (let [solid (rt/call object "geometry")]
      (try (rt/native "spatialIndex" solid) (finally (rt/dispose! solid))))
    (rt/instance-of? "Manifold" object) (rt/native "spatialIndex" object)
    :else (throw (ex-info "Expected a Manifold or Model" {}))))
(defn with-spatial-index [object f]
  (let [index (spatial-index object)]
    (try (f index) (finally (rt/dispose! index)))))
(defn- query [object f]
  (if (rt/instance-of? "SpatialIndex" object) (f object) (with-spatial-index object f)))
(defn- xyz [v]
  (when-not (and (sequential? v) (= 3 (count v)) (every? #(and (number? %) (js/Number.isFinite %)) v))
    (throw (ex-info "Expected three finite coordinates" {:value v})))
  v)
(defn- result [data]
  (when (pos? (alength data))
    (let [v (vec data)]
      (cond-> {:triangle (v 0) :distance (v 1) :position (subvec v 2 5) :normal (subvec v 5 8)}
        (= 10 (count v)) (assoc :barycentric [(- 1 (v 8) (v 9)) (v 8) (v 9)])))))
(defn ray-cast [object origin direction & {:keys [max-distance] :or {max-distance js/Infinity}}]
  (let [args (concat (xyz origin) (xyz direction) [max-distance])]
    (query object #(result (apply rt/call % "rayCast" args)))))
(defn closest-point [object point]
  (query object #(result (apply rt/call % "closestPoint" (xyz point)))))
(defn classify-point [object point & {:keys [tolerance] :or {tolerance 1e-7}}]
  (query object #(case (apply rt/call % "classifyPoint" (concat (xyz point) [tolerance]))
                  -1 :outside 0 :boundary 1 :inside)))
(defn contains-point? [object point & {:keys [tolerance boundary?] :or {tolerance 1e-7 boundary? true}}]
  (let [c (classify-point object point :tolerance tolerance)]
    (or (= c :inside) (and boundary? (= c :boundary)))))
(defn overlap? [a b & {:keys [tolerance] :or {tolerance 1e-7}}]
  (query a (fn [ia] (query b #(rt/call ia "overlaps" % tolerance)))))

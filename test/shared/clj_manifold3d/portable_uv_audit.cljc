(ns clj-manifold3d.portable-uv-audit
  "Independent, geometry-based checks for boolean UV interpolation."
  (:require [clj-manifold3d.core :as m]
            [clj-manifold3d.test-support :as support]))

(defn- subtract [[ax ay az] [bx by bz]]
  [(- ax bx) (- ay by) (- az bz)])

(defn- dot [[ax ay az] [bx by bz]]
  (+ (* ax bx) (* ay by) (* az bz)))

(defn- cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])

(defn- snapshot [model]
  (let [mesh (m/get-mesh-gl model)
        width (support/mesh-field mesh "numProp")
        rows (mapv vec (partition width (seq (support/mesh-field mesh "vertProperties"))))
        indices (vec (seq (support/mesh-field mesh "triVerts")))
        faces (vec (seq (support/mesh-field mesh "faceID")))
        runs (vec (seq (support/mesh-field mesh "runIndex")))
        originals (vec (seq (support/mesh-field mesh "runOriginalID")))]
    {:rows rows
     :originals (set originals)
     :positions (set (map #(subvec % 0 3) rows))
     :triangles
     (vec (for [run (range (count originals))
                f (range (quot (runs run) 3) (quot (runs (inc run)) 3))]
            {:source [(originals run) (faces f)]
             :corners (mapv #(rows (indices (+ (* 3 f) %))) (range 3))}))}))

(defn- prepare [{:keys [corners] :as triangle}]
  (let [[a b c] corners
        ab (subtract b a)
        ac (subtract c a)
        normal (cross ab ac)
        squared-area (dot normal normal)]
    (assoc triangle :ab ab :ac ac :normal normal :squared-area squared-area
           :minimum (mapv #(reduce min (map (fn [row] (row %)) corners)) (range 3))
           :maximum (mapv #(reduce max (map (fn [row] (row %)) corners)) (range 3))
           :longest-edge (support/sqrt (max (dot ab ab) (dot ac ac)
                                         (let [bc (subtract c b)] (dot bc bc)))))))

(defn- interpolation-error
  "Returns nil when the sample is not on this input triangle. Otherwise
  compares both UV components against barycentric interpolation of its corners."
  [{:keys [corners ab ac normal squared-area longest-edge]} sample position-tolerance]
  (when (pos? squared-area)
    (let [[a b c] corners
          ap (subtract sample a)
          area (support/sqrt squared-area)
          plane-distance (/ (support/abs (double (dot ap normal))) area)]
      (when (<= plane-distance position-tolerance)
        (let [beta (/ (dot (cross ap ac) normal) squared-area)
              gamma (/ (dot (cross ab ap) normal) squared-area)
              alpha (- 1 beta gamma)
              tolerance (/ (* position-tolerance longest-edge) area)]
          (when (>= (min alpha beta gamma) (- tolerance))
            (reduce max 0.0
                    (for [p [3 4]]
                      (support/abs (double (- (sample p)
                                          (+ (* alpha (a p)) (* beta (b p))
                                             (* gamma (c p))))))))))))))

(defn- in-bounds? [{:keys [minimum maximum]} sample tolerance]
  (every? (fn [axis] (<= (- (minimum axis) tolerance)
                         (sample axis)
                         (+ (maximum axis) tolerance)))
          (range 3)))

(defn- best-error [triangles sample tolerance]
  (reduce min support/infinity
          (keep #(interpolation-error % sample tolerance) triangles)))

(defn report
  "Audit a boolean of independently constructed inputs (distinct original IDs).
  Checks every output corner and centroid against input geometry and UVs at
  offsets 3/4. Centroids catch incorrect interpolation across a UV seam even
  when a corner happens to match the opposite side of that seam. Tolerances
  account for the Java binding's float MeshGL snapshots. No meshes are changed."
  [operands result & {:keys [uv-tolerance position-tolerance]
                      :or {uv-tolerance 2.0e-5 position-tolerance 2.0e-5}}]
  (let [inputs (into {} (map (fn [[name model]] [name (snapshot model)])) operands)
        owners (reduce-kv
                 (fn [owners name input]
                   (reduce (fn [owners id]
                             (when (contains? owners id)
                               (throw (ex-info "Audit inputs must have distinct original IDs" {:id id})))
                             (assoc owners id name))
                           owners (:originals input)))
                 {} inputs)
        prepared (mapv prepare (mapcat :triangles (vals inputs)))
        source-triangles (group-by :source prepared)
        originals (group-by (comp first :source) prepared)
        output (snapshot result)
        initial (into {} (map (fn [name]
                               [name {:triangles 0 :samples 0 :new-corners 0
                                      :max-uv-error 0.0 :failures 0 :missing-source 0
                                      :face-id-fallbacks 0
                                      :examples []}]))
                      (keys operands))]
    (reduce
      (fn [reports {:keys [source corners]}]
        (let [owner (owners (first source))
              _ (when-not owner
                  (throw (ex-info "Unknown original ID in boolean output" {:source source})))
              candidates (source-triangles source)
              centroid (mapv #(/ (reduce + (map (fn [row] (row %)) corners)) 3.0) (range 5))
              samples (conj (mapv #(vector :corner %) corners) [:centroid centroid])]
          (reduce
            (fn [reports [kind sample]]
              (let [direct-error (best-error candidates sample position-tolerance)
                    fallback? (> direct-error uv-tolerance)
                    ;; Coplanar simplification can change the representative
                    ;; faceID without changing the surface. The original ID
                    ;; identifies the operand; resolve these cases by geometry.
                    error (if fallback?
                            (best-error (filter #(in-bounds? % sample position-tolerance)
                                                (originals (first source)))
                                        sample position-tolerance)
                            direct-error)
                    failed? (or (not (support/finite? error)) (> error uv-tolerance))
                    new? (and (= :corner kind)
                              (not (contains? (:positions (inputs owner)) (subvec sample 0 3))))]
                (update reports owner
                        (fn [stats]
                          (cond-> (-> stats
                                      (update :samples inc)
                                      (update :max-uv-error max error))
                            new? (update :new-corners inc)
                            failed? (update :failures inc)
                            (not (support/finite? error)) (update :missing-source inc)
                            fallback? (update :face-id-fallbacks inc)
                            (and failed? (< (count (:examples stats)) 3))
                            (update :examples conj {:kind kind :point (subvec sample 0 3)
                                                    :uv (subvec sample 3 5) :error error
                                                    :source source}))))))
            (update-in reports [owner :triangles] inc)
            samples)))
      initial (:triangles output))))

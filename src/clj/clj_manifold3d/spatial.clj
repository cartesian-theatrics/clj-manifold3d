(ns clj-manifold3d.spatial
  "Native BVH queries over an immutable geometry snapshot (JVM)."
  (:import [manifold3d SpatialIndex Manifold Model]))

(defn spatial-index
  "Build a reusable native BVH from a Manifold or Model. It owns a snapshot, so
  the source can be closed. Use with-open for deterministic native cleanup."
  [object]
  (cond
    (instance? Model object) (with-open [solid (.geometry ^Model object)] (SpatialIndex. solid))
    (instance? Manifold object) (SpatialIndex. ^Manifold object)
    :else (throw (ex-info "Expected a Manifold or Model" {:value object}))))

(defn- query [object f]
  (if (instance? SpatialIndex object) (f object)
    (with-open [index (spatial-index object)] (f index))))

(defn- xyz [v]
  (when-not (and (sequential? v) (= 3 (count v)) (every? number? v))
    (throw (ex-info "Expected three coordinates" {:value v})))
  (mapv double v))

(defn- result [data]
  (when (and data (pos? (alength ^doubles data)))
    (let [v (vec data)]
      (cond-> {:triangle (long (v 0)) :distance (v 1)
               :position (subvec v 2 5) :normal (subvec v 5 8)}
        (= 10 (count v)) (assoc :barycentric [(- 1 (v 8) (v 9)) (v 8) (v 9)])))))

(defn ray-cast
  "Nearest forward hit, or nil. Direction need not be normalized. Distance and
  :max-distance are in model units. Two-sided hits include t=0 surface contact.
  Accepts a Manifold, Model, or reusable spatial-index."
  [object origin direction & {:keys [max-distance] :or {max-distance Double/POSITIVE_INFINITY}}]
  (let [[x y z] (xyz origin) [dx dy dz] (xyz direction)]
    (query object #(result (.RayCast ^SpatialIndex % x y z dx dy dz (double max-distance))))))

(defn closest-point
  "Closest triangle point, outward face normal and unsigned distance, or nil
  for empty geometry. Accepts a Manifold, Model or spatial-index."
  [object point]
  (let [[x y z] (xyz point)]
    (query object #(result (.ClosestPoint ^SpatialIndex % x y z)))))

(defn classify-point
  "Return :inside, :boundary, or :outside. :tolerance defaults to 1e-7 model
  units. Native ray parity respects cavities and disconnected closed shells."
  [object point & {:keys [tolerance] :or {tolerance 1e-7}}]
  (let [[x y z] (xyz point)]
    (query object #(case (.ClassifyPoint ^SpatialIndex % x y z (double tolerance))
                     -1 :outside 0 :boundary 1 :inside))))

(defn contains-point?
  "Containment including boundary by default. :boundary? false excludes it."
  [object point & {:keys [tolerance boundary?] :or {tolerance 1e-7 boundary? true}}]
  (let [classification (classify-point object point :tolerance tolerance)]
    (or (= :inside classification) (and boundary? (= :boundary classification)))))

(defn overlap?
  "True for intersecting, touching or nested solids (not just positive-volume
  intersection). Native BVH triangle tests plus containment; no boolean mesh is
  constructed. :tolerance defaults to 1e-7, a contact-axis tolerance in model
  units, not an exact Euclidean clearance distance. Inputs may be reusable indices."
  [a b & {:keys [tolerance] :or {tolerance 1e-7}}]
  (query a (fn [ia] (query b #(.Overlaps ^SpatialIndex ia ^SpatialIndex % (double tolerance))))))

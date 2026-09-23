(ns clj-manifold3d.upstream
  "Manifold 3.5 APIs shared by the JVM and WASM front ends."
  #?(:cljs (:require [clj-manifold3d.runtime :as rt]))
  #?(:clj (:import [manifold3d Manifold Model ExecutionContext Upstream Upstream$ScalarField]
                   [manifold3d.manifold CrossSection MeshGL]
                   [manifold3d.pub Smoothness SmoothnessVector]
                   [manifold3d.linalg DoubleVec3])))

(defn- model? [x] #?(:clj (instance? Model x) :cljs (rt/instance-of? "Model" x)))
(defn- geometry [x] (if (model? x) #?(:clj (.geometry ^Model x) :cljs (rt/call x "geometry")) x))
(defn- bare! [op x]
  (when (model? x)
    (throw (ex-info "Apply this operation before assigning model appearance" {:operation op})))
  x)
(defn- finite! [label x]
  (when-not (and (number? x) #?(:clj (Double/isFinite (double x)) :cljs (js/Number.isFinite x)))
    (throw (ex-info "Expected a finite number" {:parameter label :value x})))
  x)
(defn- nonnegative! [label x]
  (finite! label x)
  (when (neg? x) (throw (ex-info "Expected a nonnegative number" {:parameter label :value x}))) x)
(defn- point! [point]
  (when-not (= 3 (count point)) (throw (ex-info "Expected a three-coordinate point" {:point point})))
  (mapv #(finite! :coordinate %) point))
#?(:clj (defn- vec3 [p] (let [[x y z] (point! p)] (DoubleVec3. x y z))))

(defn execution-context
  "Create an independent progress/cancellation context. Cancellation is permanent.
  CLJS evaluation is synchronous: cancellation from another task needs a worker."
  [] #?(:clj (ExecutionContext.) :cljs (rt/construct "ExecutionContext")))
(defn cancel! "Request cancellation; returns the context." [context]
  #?(:clj (.cancel ^ExecutionContext context) :cljs (rt/call context "cancel")) context)
(defn cancelled? [context] #?(:clj (.cancelled ^ExecutionContext context) :cljs (rt/call context "cancelled")))
(defn progress "Native progress in [0,1]; an idle context reports 1." [context]
  #?(:clj (.progress ^ExecutionContext context) :cljs (rt/call context "progress")))
(defn with-context
  "Attach a context to a bare Manifold without modifying it. Attach immediately
  before status, refinement, hull or Minkowski operations: ordinary transforms
  and booleans do not propagate the attachment. Other queries do not observe it."
  [object context]
  (bare! :with-context object)
  #?(:clj (.withContext ^Manifold object context) :cljs (rt/call object "withContext" context)))
(defn from-mesh [mesh context]
  #?(:clj (.fromMesh ^ExecutionContext context mesh) :cljs (rt/call context "fromMesh" mesh)))
(defn smooth-with-context [mesh sharp context]
  #?(:clj (with-open [edges (SmoothnessVector.)]
            (doseq [{:keys [halfedge smoothness]} sharp]
              (with-open [edge (doto (Smoothness.) (.halfedge halfedge) (.smoothness smoothness))]
                (.pushBack edges edge)))
            (.smooth ^ExecutionContext context mesh edges))
     :cljs (rt/call context "smooth" mesh (clj->js sharp))))

(defn minkowski-sum "Minkowski sum (dilation) of two bare solids." [a b]
  (bare! :minkowski-sum a) (bare! :minkowski-sum b)
  #?(:clj (.minkowskiSum ^Manifold a b) :cljs (rt/call a "minkowskiSum" b)))
(defn minkowski-difference "Minkowski difference (erosion) of two bare solids." [a b]
  (bare! :minkowski-difference a) (bare! :minkowski-difference b)
  #?(:clj (.minkowskiDifference ^Manifold a b) :cljs (rt/call a "minkowskiDifference" b)))
(defn simplify
  "Simplify a solid, native Model, or cross-section within tolerance. Zero uses
  the solid's native tolerance; the cross-section default remains 1e-6."
  ([object] (simplify object (if #?(:clj (instance? CrossSection object)
                                   :cljs (rt/instance-of? "CrossSection" object)) 1e-6 0)))
  ([object tolerance]
   (nonnegative! :tolerance tolerance)
   #?(:clj (cond (model? object) (.simplify ^Model object tolerance)
                 (instance? CrossSection object) (.simplify ^CrossSection object tolerance)
                 :else (.simplify ^Manifold object tolerance))
      :cljs (rt/call object "simplify" tolerance))))
(defn get-tolerance [object]
  #?(:clj (.getTolerance ^Manifold (geometry object)) :cljs (rt/call (geometry object) "tolerance")))
(defn set-tolerance "Return a solid with the supplied simplification tolerance." [object tolerance]
  (nonnegative! :tolerance tolerance)
  #?(:clj (if (model? object) (.setTolerance ^Model object tolerance) (.setTolerance ^Manifold object tolerance))
     :cljs (rt/call object "setTolerance" tolerance)))
(defn refine-to-tolerance "Refine a smooth surface within the requested positive tolerance." [object tolerance]
  (nonnegative! :tolerance tolerance)
  (when (zero? tolerance) (throw (ex-info "Refinement tolerance must be positive" {})))
  #?(:clj (if (model? object) (.refineToTolerance ^Model object tolerance) (.refineToTolerance ^Manifold object tolerance))
     :cljs (rt/call object "refineToTolerance" tolerance)))
(defn smooth-by-normals
  "Create smoothing tangents from three existing normal property channels."
  ([object] (smooth-by-normals object 0))
  ([object index]
   (bare! :smooth-by-normals object)
   #?(:clj (.smoothByNormals ^Manifold object index) :cljs (rt/call object "smoothByNormals" index))))
(defn calculate-curvature
  "Write Gaussian and mean curvature to property channels; -1 skips a channel."
  [object gaussian-index mean-index]
  (bare! :calculate-curvature object)
  #?(:clj (.calculateCurvature ^Manifold object gaussian-index mean-index)
     :cljs (rt/call object "calculateCurvature" gaussian-index mean-index)))
(defn min-gap "Minimum separation, capped at search-length. Overlap returns zero." [a b search-length]
  (nonnegative! :search-length search-length)
  #?(:clj (.minGap ^Manifold (geometry a) (geometry b) search-length)
     :cljs (rt/call (geometry a) "minGap" (geometry b) search-length)))
(defn ray-cast-segment
  "All native triangle hits on the closed segment [origin, endpoint], sorted
  along it. :distance is a fraction in [0,1], NOT a distance in model units.
  Edge/vertex hits can appear once for each incident triangle. This does not
  change the existing nearest-hit, direction-based ray-cast API."
  [object origin endpoint]
  (point! origin) (point! endpoint)
  #?(:clj (with-open [a (vec3 origin) b (vec3 endpoint)
                      hits (.rayCast ^Manifold (geometry object) a b)]
            (mapv (fn [i] (let [hit (.get hits i) p (.point hit) n (.normal hit)]
                            {:face-id (.faceID hit) :distance (.distance hit)
                             :position [(.x p) (.y p) (.z p)] :normal [(.x n) (.y n) (.z n)]}))
                  (range (.size hits))))
     :cljs (mapv (fn [hit] {:face-id (rt/get-property hit "faceID")
                            :distance (rt/get-property hit "distance")
                            :position (js->clj (rt/get-property hit "position"))
                            :normal (js->clj (rt/get-property hit "normal"))})
                 (array-seq (rt/call (geometry object) "rayCast" (clj->js origin) (clj->js endpoint))))))
(defn read-obj-string
  "Read upstream's precision-preserving OBJ dialect from text (not a filename).
  Inspect status on the returned Manifold; this is not a general OBJ importer."
  [text] #?(:clj (Upstream/ReadOBJ text) :cljs (rt/native "readOBJString" text)))
(defn write-obj-string
  "Serialize geometry using upstream's OBJ dialect. Vertex properties and Model
  appearance are not serialized; use GLB export when materials, textures or animation matter."
  [object] #?(:clj (Upstream/WriteOBJ (geometry object)) :cljs (rt/native "writeOBJString" (geometry object))))
(defn level-set
  "Sample an SDF f([x y z]) in {:min [...], :max [...]} bounds. Positive values
  are inside. Options: :level (0), :tolerance (-1), :context (nil). Callback
  execution is synchronous on the calling thread on both platforms."
  [f {:keys [min max]} edge-length & {:keys [level tolerance context] :or {level 0 tolerance -1}}]
  (point! min) (point! max) (finite! :level level) (finite! :tolerance tolerance)
  (nonnegative! :edge-length edge-length)
  (when-not (and (pos? edge-length) (every? true? (map < min max)))
    (throw (ex-info "Level-set needs positive edge length and nonempty bounds" {})))
  (let [error (atom nil)
        sample (fn [p] (if @error 0
                         (try (finite! :sdf-value (f p))
                              (catch #?(:clj Throwable :cljs :default) e (reset! error e) 0))))
        result #?(:clj (with-open [callback (proxy [Upstream$ScalarField] []
                                             (call [x y z] (double (sample [x y z]))))
                                   lo (vec3 min) hi (vec3 max)]
                         (Upstream/LevelSet callback lo hi edge-length level tolerance context))
                  :cljs (let [args [(fn [p] (sample (js->clj p)))
                                    (clj->js {:min min :max max}) edge-length level tolerance]]
                          (if context (apply rt/call context "levelSet" args)
                              (apply rt/static "Manifold" "levelSet" args))))]
    (if-let [e @error]
      (do #?(:clj (.close ^Manifold result) :cljs (rt/dispose! result)) (throw e))
      result)))

(defn mesh-data
  "Copy MeshGL buffers into portable data suitable for (apply m/mesh (mapcat
  identity data)). Includes 3.5 run flags, properties and mesh tolerance."
  [mesh]
  #?(:clj {:num-prop (.numProp ^MeshGL mesh)
           :vert-properties (vec (.toFloatArray (.vertProperties ^MeshGL mesh)))
           :tri-verts (mapv vec (partition 3 (.toLongArray (.triVerts ^MeshGL mesh))))
           :merge-from-vert (vec (.toLongArray (.mergeFromVert ^MeshGL mesh)))
           :merge-to-vert (vec (.toLongArray (.mergeToVert ^MeshGL mesh)))
           :run-index (vec (.toLongArray (.runIndex ^MeshGL mesh)))
           :run-original-id (vec (.toLongArray (.runOriginalID ^MeshGL mesh)))
           :run-transform (vec (.toFloatArray (.runTransform ^MeshGL mesh)))
           :run-flags (mapv #(bit-and 255 %) (.toByteArray (.runFlags ^MeshGL mesh)))
           :face-id (vec (.toLongArray (.faceID ^MeshGL mesh)))
           :halfedge-tangent (vec (.toFloatArray (.halfedgeTangent ^MeshGL mesh)))
           :tolerance (.tolerance ^MeshGL mesh)}
     :cljs (let [buffer (fn [key] (vec (array-seq (or (rt/get-property mesh key) #js []))))]
             {:num-prop (rt/get-property mesh "numProp")
              :vert-properties (buffer "vertProperties")
              :tri-verts (mapv vec (partition 3 (buffer "triVerts")))
              :merge-from-vert (buffer "mergeFromVert") :merge-to-vert (buffer "mergeToVert")
              :run-index (buffer "runIndex") :run-original-id (buffer "runOriginalID")
              :run-transform (buffer "runTransform") :run-flags (buffer "runFlags")
              :face-id (buffer "faceID") :halfedge-tangent (buffer "halfedgeTangent")
              :tolerance (or (rt/get-property mesh "tolerance") 0)})))
(defn mesh-run-info "Per-run original ID and upstream backside/normal flags." [mesh]
  (let [{:keys [run-original-id run-flags]} (mesh-data mesh)]
    (mapv (fn [i id] (let [flags (get run-flags i 0)]
                      {:original-id id :backside? (not (zero? (bit-and flags 1)))
                       :has-normals? (not (zero? (bit-and flags 2)))}))
          (range) run-original-id)))

(ns clj-manifold3d.animation
  "Small, renderer-independent animation primitives for rigid Manifold parts.

  Geometry is intentionally not stored in an animation track. Build each
  solid once, keep it in local coordinates, and sample transforms as needed
  for collision checks, previews, or a scene/export layer.")

(defn- finite-number? [x]
  (and (number? x)
       #?(:clj (Double/isFinite (double x))
          :cljs (js/Number.isFinite x))))

(defn- assert-vector! [name value n]
  (when-not (and (vector? value) (= n (count value))
                 (every? finite-number? value))
    (throw (ex-info (str name " must be a finite numeric vector of length " n)
                    {:name name :value value :length n}))))

(defn- validate-frame! [{:keys [time translation rotation scale] :as frame}]
  (when-not (finite-number? time)
    (throw (ex-info ":time must be finite" {:frame frame})))
  (assert-vector! ":translation" translation 3)
  (assert-vector! ":rotation" rotation 4)
  (assert-vector! ":scale" scale 3)
  frame)

(defn- validate-keyframes! [frames]
  (let [frames (vec frames)]
    (when (empty? frames)
      (throw (ex-info "Animation requires at least one keyframe" {})))
    (doseq [[a b] (partition 2 1 frames)]
      (when-not (< (:time a) (:time b))
        (throw (ex-info "Keyframe times must be strictly increasing"
                        {:a a :b b}))))
    (mapv validate-frame! frames)))

(defn keyframes
  "Create a validated immutable rigid-transform track.

  Each frame has `:time`, `:translation` (3), `:rotation` quaternion (x y z w),
  and `:scale` (3). Times are in seconds."
  [frames]
  (validate-keyframes! frames))

(defn- lerp [a b u]
  (+ (double a) (* u (- (double b) (double a)))))

(defn- lerp-vector [a b u]
  (mapv #(lerp %1 %2 u) a b))

(defn- dot4 [a b]
  (reduce + (map * a b)))

(defn- normalize4 [q]
  (let [length (Math/sqrt (dot4 q q))]
    (when (< length 1.0e-12)
      (throw (ex-info "Quaternion must have non-zero length" {:rotation q})))
    (mapv #(/ (double %) length) q)))

(defn- slerp [a b u]
  (let [a (normalize4 a)
        b0 (normalize4 b)
        cosine (dot4 a b0)
        [b cosine] (if (neg? cosine) [(mapv - b0) (- cosine)] [b0 cosine])]
    (if (> cosine 0.9995)
      (normalize4 (lerp-vector a b u))
      (let [theta (Math/acos (max -1.0 (min 1.0 cosine)))
            sin-theta (Math/sin theta)
            wa (/ (Math/sin (* (- 1.0 u) theta)) sin-theta)
            wb (/ (Math/sin (* u theta)) sin-theta)]
        (normalize4 (mapv #(+ (* wa %1) (* wb %2)) a b))))))

(defn- bracket [frames time]
  (let [time (double time)]
    (cond
      (<= time (:time (first frames))) [(first frames) (first frames) 0.0]
      (>= time (:time (last frames))) [(last frames) (last frames) 0.0]
      :else
      (let [[i [a b]] (first (keep-indexed
                              (fn [i [a b]]
                                (when (<= (:time a) time (:time b)) [i [a b]]))
                              (partition 2 1 frames)))
            u (/ (- time (:time a)) (- (:time b) (:time a)))]
        [a b (max 0.0 (min 1.0 u))]))))

(defn sample
  "Sample a rigid-transform track at time `t`, clamping outside its range."
  [frames t]
  (when-not (finite-number? t)
    (throw (ex-info "Sample time must be finite" {:time t})))
  (let [frames (validate-keyframes! frames)
        [a b u] (bracket frames t)]
    {:time (double t)
     :translation (lerp-vector (:translation a) (:translation b) u)
     :rotation (slerp (:rotation a) (:rotation b) u)
     :scale (lerp-vector (:scale a) (:scale b) u)}))

(defn sample-times
  "Sample `frames` at every time in `times`, preserving time order."
  [frames times]
  (mapv #(sample frames %) times))

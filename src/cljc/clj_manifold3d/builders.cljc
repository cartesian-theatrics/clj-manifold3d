(ns clj-manifold3d.builders
  "Specific construction patterns found repeatedly in scad-etc.

  This is intentionally a small pattern library, not a replacement for the
  core API. It packages the verbose parts of translated hole fields,
  point-to-point rods, slots, sleeves, and batched CSG while leaving the
  resulting geometry ordinary and threadable."
  (:require [clj-manifold3d.core :as m]))

(defn- sqrt [x]
  (#?(:clj Math/sqrt :cljs js/Math.sqrt) (double x)))

(defn- abs* [x]
  (#?(:clj Math/abs :cljs js/Math.abs) (double x)))

(defn- finite-number? [x]
  (and (number? x)
       #?(:clj (Double/isFinite (double x))
          :cljs (js/Number.isFinite x))))

(defn- vector3! [name value]
  (when-not (and (sequential? value)
                 (= 3 (count value))
                 (every? finite-number? value))
    (throw (ex-info (str name " must be a finite 3-vector")
                    {name value})))
  (mapv double value))

(defn- subtract [[ax ay az] [bx by bz]]
  [(- ax bx) (- ay by) (- az bz)])

(defn- dot [[ax ay az] [bx by bz]]
  (+ (* ax bx) (* ay by) (* az bz)))

(defn- cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by))
   (- (* az bx) (* ax bz))
   (- (* ax by) (* ay bx))])

(defn- length [v]
  (sqrt (dot v v)))

(defn- unit [v]
  (let [length (length v)]
    (when-not (pos? length)
      (throw (ex-info "Rod endpoints must differ" {:vector v})))
    (mapv #(/ % length) v)))

(defn- object-list [objects]
  (let [objects (if (and (= 1 (count objects))
                         (sequential? (first objects)))
                  (first objects)
                  objects)]
    (vec (remove nil? objects))))

(defn fuse
  "Union non-nil objects in one native batch.

  Accepts `(fuse a b c)` or `(fuse [a b c])`. A single object is returned
  unchanged; an empty collection returns nil for optional pattern branches."
  [& objects]
  (let [objects (object-list objects)]
    (case (count objects)
      0 nil
      1 (first objects)
      (m/union objects))))

(defn cut
  "Subtract non-nil cutters from `solid` in one native batch.

  Both `(cut solid a b)` and `(cut solid [a b])` are supported. With no
  cutters, the original solid is returned unchanged."
  [solid & cutters]
  (let [cutters (object-list cutters)]
    (if (seq cutters)
      (m/difference (into [solid] cutters))
      solid)))

(defn hull
  "Convex hull of non-nil objects, accepting collection or vararg form."
  [& objects]
  (let [objects (object-list objects)]
    (case (count objects)
      0 nil
      1 (first objects)
      (m/hull objects))))

(defn copies-at
  "Translate one already-built object to each position.

  This is the common native replacement for repeated
  `(map #(m/translate shape %) positions)` forms."
  [object positions]
  (mapv #(m/translate object %) positions))

(defn fuse-at
  "Fuse translated copies of `object` at each position in one native batch."
  [object positions]
  (fuse (copies-at object positions)))

(defn hull-at
  "Take the convex hull of translated copies of `object` at positions."
  [object positions]
  (hull (copies-at object positions)))

(defn disks-at
  "Fuse equal-radius 2D circular disks at [x y] positions.

  This directly captures the hole-field pattern used by plate_geometry and
  contoured_plate_truss."
  ([radius positions]
   (disks-at radius positions 32))
  ([radius positions facets]
   (fuse-at (m/circle radius facets) positions)))

(defn bolt-pattern
  "Four equal circular holes at the corners of a rectangular bolt pattern.

  `x-spacing` and `y-spacing` are center-to-center distances."
  ([radius x-spacing y-spacing]
   (bolt-pattern radius x-spacing y-spacing 32))
  ([radius x-spacing y-spacing facets]
   (when-not (and (finite-number? x-spacing) (pos? x-spacing)
                  (finite-number? y-spacing) (pos? y-spacing))
     (throw (ex-info "Bolt-pattern spacings must be positive"
                     {:x-spacing x-spacing :y-spacing y-spacing})))
   (disks-at radius
             [[(- (/ x-spacing 2)) (- (/ y-spacing 2))]
              [(/ x-spacing 2) (- (/ y-spacing 2))]
              [(- (/ x-spacing 2)) (/ y-spacing 2)]
              [(/ x-spacing 2) (/ y-spacing 2)]]
             facets)))

(defn capsule
  "Create a 2D slot as the hull of equal circular ends at `a` and `b`."
  ([a b radius]
   (capsule a b radius 32))
  ([a b radius facets]
   (hull-at (m/circle radius facets) [a b])))

(defn- rod-frame [a b]
  (let [direction (unit (subtract b a))
        ;; Pick a reference that is not parallel to the rod, then construct
        ;; a stable right-handed frame with the rod along local Z.
        reference (if (< (abs* (nth direction 2)) 0.9)
                    [0.0 0.0 1.0]
                    [0.0 1.0 0.0])
        y-axis (unit (cross reference direction))
        x-axis (cross y-axis direction)]
    (m/frame x-axis y-axis direction a)))

(defn rod-between
  "Create a native cylindrical rod from 3D point `a` to point `b`.

  This is the shared implementation for the duplicate rod-between functions
  in scad-etc/truss_geometry and scad-etc/triangular_truss. The default is 32
  circular facets; the result begins at `a` and ends at `b`."
  ([a b radius]
   (rod-between a b radius 32))
  ([a b radius facets]
   (let [a (vector3! :a a)
         b (vector3! :b b)
         delta (subtract b a)
         rod-length (length delta)]
     (when-not (pos? rod-length)
       (throw (ex-info "Rod endpoints must differ" {:a a :b b})))
     (m/transform (m/cylinder rod-length radius radius facets)
                  (rod-frame a b)))))

(defn rods-between
  "Fuse rods for a sequence of [start end] pairs in one native batch."
  ([segments radius]
   (rods-between segments radius 32))
  ([segments radius facets]
   (fuse (map (fn [[a b]] (rod-between a b radius facets)) segments))))

(defn tube
  "Create a hollow cylindrical sleeve.

  `height`, `outer-radius`, and `inner-radius` match m/cylinder. `center?`
  defaults to false and controls whether the sleeve is centered on Z."
  ([height outer-radius inner-radius]
   (tube height outer-radius inner-radius 32 false))
  ([height outer-radius inner-radius facets]
   (tube height outer-radius inner-radius facets false))
  ([height outer-radius inner-radius facets center?]
   (when-not (and (finite-number? height) (pos? height))
     (throw (ex-info "Tube height must be positive" {:height height})))
   (when-not (and (finite-number? inner-radius)
                  (finite-number? outer-radius)
                  (<= 0 inner-radius outer-radius))
     (throw (ex-info "Expected 0 <= inner radius <= outer radius"
                     {:inner-radius inner-radius :outer-radius outer-radius})))
   (m/difference (m/cylinder height outer-radius outer-radius facets center?)
                 (m/cylinder height inner-radius inner-radius facets center?))))

(defn torus
  "Create a torus from a circular section revolved around the Y axis.

  `outer-radius` and `inner-radius` describe the outside and inside radii of
  the torus. `cross-section-facets` controls the tube circle and
  `revolution-facets` controls the revolution."
  ([outer-radius inner-radius]
   (torus outer-radius inner-radius 32 64))
  ([outer-radius inner-radius cross-section-facets revolution-facets]
   (let [tube-radius (/ (- outer-radius inner-radius) 2)
         center-radius (+ inner-radius tube-radius)]
     (when-not (and (finite-number? inner-radius)
                    (finite-number? outer-radius)
                    (<= 0 inner-radius outer-radius)
                    (pos? tube-radius))
       (throw (ex-info "Expected outer radius > inner radius >= 0"
                       {:outer-radius outer-radius :inner-radius inner-radius})))
     (m/revolve (m/translate (m/circle tube-radius cross-section-facets)
                             [center-radius 0])
                revolution-facets))))

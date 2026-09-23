(ns clj-manifold3d.journal.readme-example
  "README modeling examples adapted to inline, synchronous browser evaluation."
  (:require [clj-manifold3d.journal.namespace :as ns-form]))

(def stages
  [["booleans" "## Solids and boolean operations\n\nThe README starts with union, difference and intersection. Compare them side by side; translating a result does not change its inputs."
    "(def box (m/cube 20 20 20 true))
(def ball (m/sphere 12 30))

(m/union
  (m/translate (m/union box ball) [30 0 0])
  (m/translate (m/difference box ball) [-30 0 0])
  (m/intersection box ball))"]
   ["minkowski" "## Round a box with Minkowski sum\n\nMinkowski sum adds every point of one solid to every point of the other. Adding a sphere rounds a box's edges and grows it outward by the sphere's radius. Left: the original 20 × 14 × 10 box. Right: the rounded 24 × 18 × 14 result. Change `radius` and run this panel again."
    "(let [size [20 14 10]
      radius 2
      box (m/cube size true)
      rounded (m/minkowski-sum box (m/sphere radius 32))]
  (m/union
    (m/translate (m/color box [0.55 0.6 0.65 1]) [-18 0 0])
    (m/translate (m/color rounded [0.2 0.6 0.9 1]) [18 0 0])))"]
   ["profile" "## Draw a cross-section\n\nA sequence of points makes a 2D polygon. Leave it flat to inspect the profile before extrusion."
    "(def profile
  (m/cross-section
    (vec (cons [0 0]
           (for [i (range 18)
                 :let [a (* i (/ (* 2 math/pi) 19))]]
             [(* 20 (math/cos a)) (* 20 (math/sin a))])))))

profile"]
   ["extrude" "## Give the profile depth\n\nExtrude the same cross-section into a watertight solid. Change the height and run just this block."
    "(def wedge (m/extrude profile 5))

wedge"]
   ["hole" "## Polygon winding makes holes\n\nAn outer contour runs counterclockwise; reversing the inner contour makes a hole. Boolean difference between circles is another way to express this."
    "(defn ring-points [radius]
  (mapv (fn [i]
          (let [a (* i (/ (* 2 math/pi) 32))]
            [(* radius (math/cos a)) (* radius (math/sin a))]))
        (range 32)))

(def ring-profile
  (m/cross-section [(ring-points 20) (vec (reverse (ring-points 18)))]))

(m/extrude ring-profile 4)"]
   ["revolve" "## Revolve an offset profile\n\nRevolve a thin triangular wall through 135 degrees. Offset creates its hollow interior."
    "(let [section (m/translate (m/cross-section [[-10 0] [10 0] [0 10]]) [30 0])]
  (-> (m/difference section (m/offset section -1))
      (m/revolve 50 135)))"]
   ["hull-2d" "## Connect shapes with a 2D hull\n\nThe convex hull spans a circle and a translated square. Extrusion turns the outline into a solid."
    "(def hull-profile
  (m/hull (m/circle 5)
          (m/translate (m/square 10 10 true) [30 0])))

(m/extrude hull-profile 10)"]
   ["hull-3d" "## A hull in three dimensions\n\nThe same operation works on solids. Here it connects a low disk to an elevated sphere."
    "(m/hull (m/cylinder 2 12 12 64)
        (m/translate (m/sphere 4 64) [0 0 20]))"]
   ["polyhedron" "## Specify vertices and faces\n\nA polyhedron exposes the mesh construction directly. Face winding determines the outward normals."
    "(m/polyhedron
  [[0 0 0] [5 0 0] [5 5 0] [0 5 0]
   [0 0 5] [5 0 5] [5 5 5] [0 5 5]]
  [[0 3 2 1] [4 5 6 7] [0 1 5 4]
   [1 2 6 5] [2 3 7 6] [3 0 4 7]])"]
   ["frames" "## Place geometry with a frame\n\nFrame rotations use radians, unlike solid rotations in degrees. Translations follow the rotated frame, like turtle graphics."
    "(def tilted-frame
  (-> (m/frame 1)
      (m/rotate [0 (/ math/pi 4) 0])
      (m/translate [0 0 30])))

(m/transform (m/cylinder 50 5) tilted-frame)"]
   ["loft" "## Loft between framed cross-sections\n\nAn expanded middle profile makes this hollow square column bulge. All three sections retain their holes."
    "(def hollow-square (m/difference (m/square 10 10 true) (m/square 8 8 true)))
(def loft-frames [(m/frame 1)
                  (m/translate (m/frame) [0 0 15])
                  (m/translate (m/frame) [0 0 30])])

(m/loft [hollow-square (m/scale hollow-square [1.5 1.5]) hollow-square]
        loft-frames)"]
   ["loft-topology" "## Change the vertex count along a loft\n\nConnect a 15-sided circle, a square and a 20-sided circle. Native lofting matches their boundaries."
    "(m/loft [(m/circle 20 15) (m/square 30 30 true) (m/circle 20 20)]
        loft-frames)"]
   ["loft-steps" "## Incremental loft steps\n\nThe single-argument form carries forward a section or frame when a step omits it."
    "(m/loft [{:cross-section (m/circle 50 12) :frame (m/frame)}
         {:frame (m/translate (m/frame) [0 0 20])}
         {:cross-section (m/circle 46 12)}
         {:frame (m/translate (m/frame) [0 0 3])}])"]
   ["slice" "## Slice a solid\n\nIntersect a stretched tetrahedron with the XY plane and inspect its flat cross-section."
    "(def tetra (m/scale (m/tetrahedron) [5 10 15]))

(m/slice tetra)"]
   ["slices" "## Stack a series of slices\n\nThe multi-slice operation samples equally spaced planes. Extrude and separate the sections to see how the solid changes with height."
    "(apply m/union
  (for [[i section] (map-indexed vector (m/slices tetra 5 10 10))]
    (-> section (m/extrude 0.125) (m/translate [0 0 (* i 0.5)]))))"]
   ["surface" "## Build a surface from heights\n\nA vector of rows becomes a closed terrain solid. This is the small version of the castle's mountain construction."
    "(defn sinewave-heatmap [size frequency amplitude phase]
  (mapv (fn [y]
          (mapv (fn [x]
                  (+ 10 amplitude
                     (* amplitude
                        (math/sin (+ (* frequency (/ x size))
                                     (* frequency (/ y size)) phase)))))
                (range size)))
        (range size)))

(m/surface (sinewave-heatmap 50 10 5 0) 1.0)"]
   ["color" "## Colors survive booleans\n\nVertex properties preserve the blue object's exterior and the red cutter's newly exposed faces."
    "(m/difference
  (m/color (m/cube 20 20 20) [0 0 1 1])
  (m/color (m/cube 20 20 40 true) [1 0 0 1]))"]
   ["compose" "## Compose without boolean fusion\n\nComposition keeps separate shells instead of resolving intersections. Transparency lets you see the sphere inside the cube."
    "(m/compose
  (m/color (m/cube 30 30 30 true) [1 1 1 0.3])
  (m/color (m/sphere 12 40) [0 0 1 1]))"]
   ["circle" "## A circle through three points\n\nConstruct the circle from points on its perimeter, then mark those points with holes."
    "(let [points [[0 0] [1 12] [15 0]]]
  (m/extrude
    (m/difference (apply m/circle (conj points 100))
      (apply m/union (map #(m/translate (m/circle 1 10) %) points)))
    1))"]
   ["arc" "## A three-point arc\n\nThe middle point chooses which side of the circle the arc follows. Small disks mark the construction points."
    "(let [points [[0 0] [2 5] [-6 10]]]
  (m/extrude
    (apply m/union (apply m/three-point-arc (conj points 20))
      (map #(m/translate (m/circle 1 10) %) points))
    1))"]
   ["vertices" "## Turn mesh vertices into geometry\n\nRead the cube's vertices and place a small sphere at each one. Data inspection can produce a useful visual result."
    "(def vertices (m/get-vertices (m/cube 20 20 20 true)))

(apply m/union (map #(m/translate (m/sphere 2 20) %) vertices))"]
   ["halfedges" "## Visualize the halfedge graph\n\nEach triangle edge has a direction and an adjacent face. Tapered hulls show direction; red and blue distinguish forward and reverse halfedges. This is the topology used by surface mapping."
    "(let [solid (m/cube 40 40 40 true)
      vertices (m/get-vertices solid)]
  (apply m/compose
    (m/color solid [0 1 0 0.25])
    (for [edge (m/get-halfedges solid)]
      (-> (m/hull
            (m/translate (m/sphere 1 12) (nth vertices (:start-vert edge)))
            (m/translate (m/sphere 3 12) (nth vertices (:end-vert edge))))
          (m/color (if (m/is-forward edge) [1 0 0 0.7] [0 0 1 0.7]))))))"]
   ["texture" "## A model carries its texture\n\nGenerate a checker PNG, cover every face with repeating tiles, then cut the textured model. For a curved decal with depth, open the flag journal."
    "(def checker
  (texture/image 64 64
    (fn [x y]
      (if (even? (+ (quot x 16) (quot y 16)))
        [0.9 0.7 0.2 1] [0.08 0.2 0.3 1]))))

(def textured
  (-> (m/cube 20 20 20 true)
      (m/texture-all checker :size [8 8])
      (m/difference (m/cylinder 30 5 5 48 true))))

textured"]
   ["scene" "## Light and animate an assembly\n\nKeep geometry in local coordinates, then animate the node's quaternion. The scene carries the texture, keyframes and authored light together. Download the result as GLB from its toolbar."
    "(def turn
  (animation/keyframes
    [{:time 0 :translation [0 0 0] :rotation [0 0 0 1] :scale [1 1 1]}
     {:time 2 :translation [0 0 0] :rotation [0 0 1 0] :scale [1 1 1]}
     {:time 4 :translation [0 0 0] :rotation [0 0 0 1] :scale [1 1 1]}]))

(m/scene
  {:nodes [{:id :part :geometry textured}
           {:id :key :light {:type :point :intensity 1000 :range 200}
            :translation [30 -40 50]}]
   :animations [{:name \"Turntable\"
                 :channels [{:node :part :path :rotation :track turn}]}]})"]])

(defn document []
  {:namespace "journal.readme" :title "Library · README walkthrough" :revision 0
   :ns-source (ns-form/declaration "journal.readme"
                ['[clj-manifold3d.core :as m] '[clj-manifold3d.math :as math]
                 '[clj-manifold3d.texture :as texture] '[clj-manifold3d.animation :as animation]])
   :blocks (vec
             (cons {:id "journal-readme-intro" :kind "prose" :hidden false
                    :source "# Learn by making\n\n[GitHub repository](https://github.com/cartesian-theatrics/clj-manifold3d) · [Library README](https://github.com/cartesian-theatrics/clj-manifold3d#readme)\n\nA runnable companion to the README's Examples section. Read a note, run a block, and inspect its shape. Each code panel returns geometry, a cross-section or an animated scene instead of writing a file. Later panels reuse earlier definitions; Run page executes the whole walkthrough.\n\nThe examples use portable math and in-memory data. Font-file text, image/PLY imports and filesystem export require asynchronous assets or a local environment and are not run here. Use each model's Download action for GLB export."}
                   (mapcat (fn [[id prose source]]
                             [{:id (str "journal-readme-" id "-notes") :kind "prose" :source prose :hidden false}
                              {:id (str "journal-readme-" id) :kind "code" :source source :hidden false}]) stages)))})

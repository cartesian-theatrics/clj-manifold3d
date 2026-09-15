[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.cartesiantheatrics/clj-manifold3d.svg?include_prereleases)](https://clojars.org/org.clojars.cartesiantheatrics/clj-manifold3d)


# clj-manifold3d

This library provides a Clojure(Script) wrapper over Emmett Lalish's incredible Manifold 3D geometry library. The CLJ implementation is based on JNI bindings to c++ produced via. javacpp: see https://github.com/SovereignShop/manifold.

It implements most of the library functionality, plus extends it to support polyhedrons and lofts. It provides nearly a full superset of OpenSCAD functionality.

# Install

You need include the native [Manifold Bindings](https://github.com/SovereignShop/manifold) for your platform separately. For example:

``` clojure
;; Linux
{:deps {org.clojars.cartesiantheatrics/manifold3d$linux-x86_64 {:mvn/version "1.0.73"}}}
;; Mac
{:deps {org.clojars.cartesiantheatrics/manifold3d$mac-x86_64 {:mvn/version "1.0.73"}}}
;; See build artifacts for experimental Windows jars: https://github.com/SovereignShop/manifold/actions
```

The Manifold .so libs are included in the bindings jar. You'll also need to have libassimp installed on your system:

``` sh
;; Ubuntu
sudo apt install libassimp-dev
;; Mac
brew install pkg-config assimp
;; Windows
git clone https://github.com/assimp/assimp.git
cd assimp
git checkout v5.2.5
cmake CMakeLists.txt -DASSIMP_BUILD_ZLIB=ON -DCMAKE_BUILD_TYPE=Release
cmake --build . --config Release
cmake --install . --config Release
```

The ClojureScript lib is not yet well supported or available via. Maven. You'll have to clone the repo and move `public/manifold.wasm` into `public/js/`. Run `npm install` to install the gltf (for rendering meshes) then connect via. shadow. There's a half-baked function called `createGLTF` in `manifold_viewer.js` that will take a manifold and throw it onto the `model-viewer` element defined in the index.html.

# Development

This project uses the Clojure CLI. The `:clj-dev` alias supplies the JVM
native Manifold binding, source paths, and CIDER's nREPL middleware.

The development and test aliases use the native JAR at
`../manifold/bindings/java/target/manifold3d-1.0.39.jar`. The new UV methods
require the matching bindings; older published JARs do not expose them.
For a fresh Linux x86-64 checkout, clone the tested native branch alongside
this repository (it includes the rebuilt JAR):

```sh
git clone --branch surface-uv-mapping https://github.com/SovereignShop/manifold.git ../manifold
```

If `../manifold` already exists, use a matching checkout without overwriting
local changes. The native runtime still requires the system libraries described
under Install above.

For a local JVM REPL:

```sh
clojure -M:clj-dev:clj-repl
```

For CIDER or another nREPL client, start the server on port `7888`:

```sh
clojure -M:clj-dev:nrepl
```

Then connect the editor to `localhost:7888`. The project-specific native
binding is intentionally kept in the development aliases so published
artifacts remain platform-independent.

Run the test suite with:

```sh
clojure -M:clj-test
```

# Examples

Examples should look familiar if you've ever used OpenSCAD.

## Manifolds

Manifolds are the core datatype representing a 3D object. They are water-tight meshes comprised of triangular faces. They are guaranteed closed under the fundamental CSG operations: `union`, `difference`, and `intersection`.

``` clojure
(let [cube (m/cube 20 20 20 true)
      sphere (m/sphere 12 30)]
  (-> (m/union
       (-> (m/union cube sphere)
           (m/translate [30 0 0]))
       (-> (m/difference cube sphere)
           (m/translate [-30 0 0]))
       (m/intersection cube sphere))
      (m/get-mesh)
      (m/export-mesh "manifolds.glb" :material mesh-material)))
```

![Manifold](resources/images/manifolds.png)

## Cross Sections

Cross Sections represent 2D shapes. There are basic cross section constructors like `square` and `circle`, or they can be constructed from one or multiple polygons (vertex sequences).

``` clojure
(-> (m/cross-section (cons [0 0]
                           (for [i (range 18)]
                             [(* 20 (Math/cos (* i (/ (* 2 Math/PI) 19))))
                              (* 20 (Math/sin (* i (/ (* 2 Math/PI) 19))))])))
    (m/extrude 1)
    (m/get-mesh)
    (m/export-mesh "cross-section.glb" :material mesh-material))
```

![Cross Section](resources/images/cross-section.png)

Cross Sections are (roughly) isomorphic to a set of polygons for which vertex order determines whether a polygon encodes a hole.

``` clojure
(-> (m/cross-section [(for [i (range 20)]
                        [(* 20 (Math/cos (* i (/ (* 2 Math/PI) 19))))
                         (* 20 (Math/sin (* i (/ (* 2 Math/PI) 19))))])
                      (reverse
                       (for [i (range 20)]
                         [(* 18 (Math/cos (* i (/ (* 2 Math/PI) 19))))
                          (* 18 (Math/sin (* i (/ (* 2 Math/PI) 19))))]))])
    (m/extrude 1)
    (m/get-mesh)
    (m/export-mesh "cross-section-with-hole.glb" :material mesh-material))
```

![Cross Section With Hole](resources/images/cross-section-with-hole.png)

## Revolve

``` clojure
(let [m (-> (m/cross-section [[-10 0] [10 0] [0 10]])
            (m/translate [30 0]))]
  (-> (m/difference m (m/offset m -1))
      (m/revolve 50 135)
      (m/get-mesh)
      (m/export-mesh "revolve.glb" :material mesh-material)))
```

![Partial revolve](resources/images/revolve.png)



## 2D hulls

``` clojure
(require '[clj-manifold3d.core :as m])
                                       
(def mesh-material (m/material :roughness 0.0 :metalness 0.0 :color [0.0 0.7 0.7 1]))

(-> (m/hull
     (m/circle 5)
     (-> (m/square 10 10 true)
         (m/translate [30 0])))
    (m/extrude 10)
    (m/get-mesh)
    (m/export-mesh "hull2D.glb" :material mesh-material))
```

![2D hull](resources/images/hull2D.png)

## 3D hulls

``` clojure
(-> (m/hull (m/cylinder 2 12 12 120)
            (-> (m/sphere 4 120)
                (m/translate [0 0 20])))
    (m/get-mesh)
    (m/export-mesh "hull3D.glb" :material mesh-material))
```

![3D hull](resources/images/hull3d.png)

## Polyhedron

``` clojure
(-> (m/polyhedron [[0 0 0]
                   [5 0 0]
                   [5 5 0]
                   [0 5 0]
                   [0 0 5]
                   [5 0 5]
                   [5 5 5]
                   [0 5 5]]
                  [[0 3 2 1]
                   [4 5 6 7]
                   [0 1 5 4]
                   [1 2 6 5]
                   [2 3 7 6]
                   [3 0 4 7]])
    (m/get-mesh)
    (m/export-mesh "polyhedron-cube.glb" :material mesh-material))
```

![Partial revolve](resources/images/polyhedron-cube.png)


## Frames

Transform frames, which are 3x4 affine transformation matrices, can be manipulated similar to manifolds.


``` clojure
(mapv vec
      (-> (frame 1)
          (translate [0 0 10])
          (vec)))
;; => [[1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0] [0.0 0.0 10.0]]
```

Frames transform slightly differently than manifolds. The rotation components are best thought of as basis vectors of a coordinate frame, with the last component representing the position of that frame. Rotations and translations are applied relative to the frame, turtle-graphics style. Here is an example of applying a transform to a cylinder:


``` clojure
(-> (m/cylinder 50 5)
    (m/transform (-> (m/frame 1)
                     (m/rotate [0 (/ Math/PI 4) 0])
                     (m/translate [0 0 30])))
    (m/get-mesh)
    (m/export-mesh "transform.glb" :material mesh-material)) 
```
![Tranform](resources/images/transform.png)


2D transform frames can also be manipulated similar to cross sections.

``` clojure
(mapv vec
      (-> (m/frame-2d 1)
          (m/translate [0 10])
          (vec)))
;; => [[1.0 0.0] [0.0 1.0] [0.0 0.0]]
```


## Loft

Loft connects a series of cross sections positioned in 3D space to form a manifold. Edges are constructed between vertices of adjacent cross sections.

``` clojure
(-> (let [c (m/difference (m/square 10 10 true) (m/square 8 8 true))]
      (m/loft [c (m/scale c [1.5 1.5]) c]
              [(m/frame 1)
               (m/translate (m/frame) [0 0 15])
               (m/translate (m/frame) [0 0 30])]))
    (m/get-mesh)
    (m/export-mesh "loft.glb" :material mesh-material))
```

![Partial revolve](resources/images/simple-loft.png)

Loft and also handle one-to-many and many-to-one vertex mappings.

``` clojure
(-> (m/loft [(m/circle 20 15)
             (m/square 30 30 true)
             (m/circle 20 20)]
            [(m/frame 1)
             (m/translate (m/frame) [0 0 15])
             (m/translate (m/frame) [0 0 30])])
    (m/get-mesh)
    (m/export-mesh "monomorphic-loft.glb" :material mesh-material))
```

![Monomorphic Loft](resources/images/monomorphic-loft.png)


There is also a single arity version of loft.

``` clojure
(-> (m/loft [{:cross-section (m/circle 50 12)
              :frame (m/frame)}
             {:frame (m/translate (m/frame) [0 0 20])}
             {:cross-section (m/circle 46 12)}
             {:frame (m/translate (m/frame) [0 0 3])}])
    (m/get-mesh)
    (m/export-mesh "single-arity-loft.glb" :material mesh-material))
```


![Single Arity Loft](resources/images/single-arity-loft.png)

## Text

``` clojure
(-> (m/text "resources/fonts/Cinzel-Regular.ttf" "Manifold" 10 20 :non-zero)
    (m/scale-to-height 100)
    (m/extrude 20)
    (m/get-mesh)
    (m/export-mesh "text.glb" :material mesh-material))
```
![Text](resources/images/text.png)

## Slice 

Slice solves for the cross-section of a manifold that intersects the x/y plane.

``` clojure
(-> (m/slice (m/scale (m/tetrahedron) [5 10 15]))
    (m/extrude 1/2)
    (m/get-mesh)
    (m/export-mesh "slice.glb" :material mesh-material))
```

![Slice](resources/images/slice.png)

There is an efficient aglorithm that solves for N equally spaces slices.

``` clojure
(-> (m/union
     (for [[i slice] (map-indexed vector (m/slices (m/scale (m/tetrahedron) [5 10 15]) 5 10 10) )]
       (-> slice
           (m/extrude 1/8)
           (m/translate [0 0 (* i 0.5)]))))
    (m/get-mesh)
    (m/export-mesh "slices.glb" :material mesh-material))
```

![Slices](resources/images/slices.png)

## Surface 

Surface creates a manifold from a heatmap structure.

``` clojure
(defn sinewave-heatmap
  "Generates a 3D sinewave heatmap.
  The output is a vector of vectors representing a square matrix.
  Each cell represents the height at that x/y coordinate based on a sinewave.

  Args:
  - size: The size of the matrix (width and height).
  - frequency: Frequency of the sinewave (controls the number of wave oscillations).
  - amplitude: Amplitude of the sinewave (controls the height of the wave).
  - phase: Phase shift of the sinewave.

  Returns a matrix where each value is the sinewave height at that coordinate."
  [size frequency amplitude phase]
  (vec
   (for [y (range size)]
     (vec
      (for [x (range size)]
        (+ (+ 10 amplitude)
           (* amplitude
              (Math/sin
               (+ (* frequency (/ x size))
                  (* frequency (/ y size))
                  phase)))))))))

(-> (sinewave-heatmap 50 10 5 0)
    (m/surface 1.0)
    (m/get-mesh)
    (m/export-mesh "sine-wave-surface.glb" :material mesh-material))
```

![Slices](resources/images/sine-wave-surface.png)

Use the underlying `MeshUtils/CreateSurface` for max performance when generating large heatmaps. You can also use create a surface from a `.png`, `.jpg` or other image file using `load-surface`.

There is also a primitive algorithm to parse a .ply point cloud to a surface mesh, useful primarily for geo-mapping applications. Here's an example from a ~8GB point cloud:

![PLY](resources/images/ply_to_mesh.png)

## Color

In addition to specifying a uniform color when exporting a manifold, color attributes can be added to a manifold's vertex properties. This ensures vertex colors preserved under boolean operations.

``` clojure
(->
 (m/difference
  (m/color (m/cube 20 20 20) [0 0 1 1])
  (m/color (m/cube 20 20 40 true) [1 0 0 1]))
 (m/get-mesh-gl)
 (m/export-mesh "colored-manifold.glb"
                :material (m/material :roughness 0.0 :metalness 0.0 :color-idx 0)))
```

![Color](resources/images/colored-manifold.png)

## Texture coordinates

On the JVM, UV coordinates can be attached as MeshGL vertex properties. Manifold
interpolates those properties across newly created boolean faces, so apply the
same property slot to every operand before combining them:

```clojure
(require '[clj-manifold3d.texture :as texture])

(def uv-map
  (fn [[x y z]] [(* 0.05 x) (* 0.05 z)]))

(def textured
  (m/difference
   (texture/uv (m/cube 20 20 20 true) uv-map :prop-index 3)
   (texture/uv (m/translate (m/cube 12 12 30 true) [0 0 5])
               uv-map
               :prop-index 3)))

(texture/export-glb textured
                    "boolean-textured.glb"
                    "resources/images/colored-manifold.png")
```

`export-glb` embeds the PNG or JPEG and writes `TEXCOORD_0` plus a
base-color texture into the GLB. The generated `boolean-textured.glb` is a
small self-contained example that can be opened directly in F3D.

`texture/uv` also accepts one `[u v]` pair per MeshGL vertex when explicit UV
seams are needed. `:prop-index` is an absolute property offset, including the
three position channels. It defaults to `:append`; when used after
`m/color`, this appends UV0 after the default four color channels.

For the common planar case, the projection can run natively without a
per-vertex Clojure callback:

```clojure
(def textured
  (texture/planar-uv-native (m/cube 20 20 20 true)
                            :axes [:x :z]
                            :scale 0.05
                            :prop-index 3))
```

For curved or otherwise non-planar surfaces, `unwrap-native` builds charts from
halfedge adjacency and solves each chart with a native least-squares conformal
map. Sharp edges become seams automatically; closed components also receive
topology-aware cuts. MeshGL vertices are split at seams while retaining merge
metadata, so the resulting UV properties remain compatible with booleans:

```clojure
(def unwrapped
  (texture/unwrap-native (m/sphere 10 32)
                         :seam-angle 45.0
                         :padding 0.01
                         :pack? true
                         :prop-index 3))

(texture/export-glb unwrapped
                    "sphere-unwrapped.glb"
                    "resources/images/colored-manifold.png")
```

With `:pack? true` (the default), chart UVs are packed into `[0, 1]`. Set it
to false to retain the solved coordinates and use `:scale` for world-space
texture repetition.

For a local sticker, `geodesic-uv` walks plane/surface intersections in native
C++, crossing neighboring faces through paired halfedges. `:size` specifies
the distance walked along the center baseline and each column. `:pixel-size`
sets the physical distance between samples; it defaults to min(width,height)/32.
Samples can land inside faces or on edges. The affected faces are subdivided
locally, and UVs remain vertex properties compatible with booleans.

The image can occupy an atlas rectangle with `:uv-rect`. A geometric boundary
with separate inside/outside UVs prevents interpolation into `:outside-uv`:

```clojure
(def sticker-uv
  (texture/geodesic-uv (m/sphere 10 96)
                       :origin [0 10 0]
                       :normal [0 1 0]
                       :u-direction [1 0 0]
                       :size [6 4]
                       :pixel-size 0.1
                       :uv-rect [0.25 0.333 0.75 0.667]
                       :outside-uv [0.05 0.05]
                       :prop-index 3))

(texture/export-glb sticker-uv
                    "sphere-sticker.glb"
                    "resources/images/american-flag-atlas.png")
```

The original triangle surface and its volume are preserved. Positive local V
points toward the top of the image. Width is measured along the center baseline;
spacing between columns can vary away from it on curved surfaces. Despite the
function name, these are plane-cut paths, not shortest geodesics or a stretch-free
flattening. The patch must be a single sheet over its chosen tangent plane.
Folds, tangencies, incomplete coverage and grids exceeding two million samples
throw an error. Refine meshes with smooth halfedge tangents before mapping.
`geodesic-uv-native` calls the same implementation; there is no JVM distance solver.

REPL examples for the sphere, cylinder, and a boolean cut through the sticker
are in `examples/surface_sticker.clj`:

```clojure
(require '[surface-sticker :as sticker])
(def models (sticker/models))
(sticker/export! models)
```

## Compose

`compose` combines manifolds or cross sections together without performing any CSG operations on them. 

``` clojure
(-> (m/compose
     (m/color (m/cube 30 30 30 true)
              [1 1 1 0.5])
     (m/color (m/sphere 12 40)
              [0 0 1 1.0]))
    (m/get-mesh)
    (m/export-mesh "compose.glb"
                   :material (m/material :roughness 0.0 :metalness 0.0 :color-idx 0 :alpha-idx 3)))
```

![Compose](resources/images/compose.png)

## Three Point Circles & Arcs

Define a circle by providing three points that its perimeter intersects.

``` clojure
(let [p1 [0 0] p2 [1 12] p3 [15 0]]
  (-> (m/difference
       (m/circle p1 p2 p3 100)
       (m/union
        (for [p [p1 p2 p3]]
          (-> (m/circle 1 10)
              (m/translate p)))))
      (m/extrude 1)
      (m/get-mesh)
      (m/export-mesh "three-point-circle.glb" :material mesh-material)))
```

![Three Point Circle](resources/images/three-point-circle.png)

Likewise for partial circles/arcs.

``` clojure
(let [p1 [0 0] p2 [2 5] p3 [-6 10]]
  (-> (m/union
       (m/three-point-arc p1 p2 p3 20)
       (m/union
        (for [p [p1 p2 p3]]
          (-> (m/circle 1 10)
              (m/translate p)))))
      (m/extrude 1)
      (m/get-mesh)
      (m/export-mesh "three-point-arc.glb" :material mesh-material)))
```

![Three Point Circle](resources/images/three-point-arc.png)

Use `three-point-arc-points` to get a vector of the points corresponding to the arc segment.

## Get Vertices 

Get the vertices of a Manifold using `get-vertices`:

``` clojure
(-> (let [verts (m/get-vertices (m/cube 20 20 20 true))]
      (m/union
       (for [vert verts]
         (-> (m/sphere 2 20)
             (m/translate vert)))))
    (m/get-mesh)
    (m/export-mesh "get-vertices.glb" :material mesh-material))
```

![Get Vertices](resources/images/get-vertices.png)

## Get Halfedges

You can get the Halfedges of a Manifold using `get-halfedges`:

```clojure
(-> (let [m (m/cube 40 40 40 true)
          verts (m/get-vertices m)
          halfedges (m/get-halfedges m)]
      (m/compose
       (cons (m/color m [0 1 0 0.4])
             (for [halfedge halfedges]
               (let [v1 (nth verts (:start-vert halfedge))
                     v2 (nth verts (:end-vert halfedge))
                     m (m/hull
                        (m/translate (m/sphere 1 15) v1)
                        (m/translate (m/sphere 3 15) v2))]
                 (if (m/is-forward halfedge)
                   (m/color m [1 0 0 0.6])
                   (m/color m [0 0 1 0.6])))))))
    (m/get-mesh)
    (m/export-mesh "get-halfedges.glb" :material (m/material :roughness 0.0
                                                             :metalness 0.0
                                                             :color-idx 0
                                                             :alpha-idx 3)))
```

![Get Halfedges](resources/images/get-halfedges.png)

The halfedge array is a useful data-structure that can be used to "walk" over adjacent faces of manifolds.

For unique undirected edges, use `get-edges`. It returns sorted `Edge` records
with `:start-vert` and `:end-vert` indexes:

```clojure
(m/get-edges (m/cube 40 40 40 true))
;; => [#clj_manifold3d.core.Edge{:start-vert 0, :end-vert 1} ...]
```

## Rigid animation tracks

The JVM `clj-manifold3d.animation` namespace provides keyframes for rigid
parts. Geometry stays in local coordinates; only the translation, quaternion
rotation, and scale are sampled:

```clojure
(require '[clj-manifold3d.animation :as animation])
(def track
  (animation/keyframes
   [{:time 0 :translation [0 0 0] :rotation [0 0 0 1] :scale [1 1 1]}
    {:time 1 :translation [100 0 0] :rotation [0 0 1 0] :scale [1 1 1]}]))
(animation/sample track 0.5)
;; => {:time 0.5, :translation [50.0 0.0 0.0], ...}
```

This layer feeds collision checks, previews, and the JVM GLB scene writer; it
does not recompute CSG for every frame. A small pivot-arm scene demonstrates
the complete path from Manifold solids to an animated GLB:

```clojure
(require '[clj-manifold3d.animation :as animation])
(def pivot-scene (animation/pivot-arm-scene))
(animation/export-scene pivot-scene "pivot-arm.glb")
```

`scene` accepts a set of stable-ID nodes. Nodes without geometry can act as
transform parents or pivots, while animation channels target those node IDs.
The resulting file contains separate base, pivot, and arm nodes. Open it with
an animation-capable glTF viewer, or render a particular time with F3D:

```sh
f3d --animation-time 0 pivot-arm.glb
f3d --animation-time 1 pivot-arm.glb
```

# Example Projects

A Simple rapidly printable hydroponic tower:
https://github.com/SovereignShop/spiralized-hydroponic-tower

Kossel delta printer:
https://github.com/SovereignShop/kossel-printer/

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

For ClojureScript, use this checkout (for example a `:local/root` dependency)
and build the matching WASM bindings as described below. Java/JNI dependencies
are not required by CLJS applications. Old generated loaders under `src/js`
and the original viewer prototype are not used by the current bindings.

## scad-etc construction patterns

`clj-manifold3d.builders` is a small `.cljc` namespace for patterns that recur
in the native portions of `scad-etc`; it is intentionally not a second general
modeling DSL. It packages batched `fuse`, `cut`, and `hull`, translated-copy
helpers (`copies-at`, `fuse-at`, `hull-at`), `disks-at`, rectangular
`bolt-pattern`, 2D `capsule`, 3D `rod-between`/`rods-between`, hollow `tube`,
and `torus`.

```clojure
(require '[clj-manifold3d.core :as m]
         '[clj-manifold3d.builders :as b])

(let [holes (b/disks-at (/ hole-diameter 2) hole-centers facets)
      plate (b/cut (m/square plate-width plate-height true) holes)
      rods (b/rods-between (map (juxt :start :end) rod-specs)
                           (/ rod-diameter 2) facets)]
  (b/fuse (m/extrude plate plate-thickness) rods))
```

These helpers came from repeated `apply m/union`, translated-circle fields,
slot hulls, and duplicated point-to-point rod frames in `scad-etc`. They keep
the normal immutable `m/*` values, so a result can continue through `->`,
boolean operations, appearance functions, or export.

## ClojureScript / WASM

### Local “Try it!” playground

After `npm ci` and `npm run build:wasm`, run:

```sh
npm run try-it
```

Open **http://localhost:8091/**. The page starts with an editable twisted loft,
an orbitable Three.js model viewer, wireframe/grid controls, and GLB download.
Other examples demonstrate booleans, a raised American-flag UV patch, and a playing pivot
animation with its full scene hierarchy and quaternion keyframes in the editor.
The CodeMirror editor provides ClojureScript syntax highlighting, matching
brackets, automatic closing parentheses/brackets/quotes, indentation, and undo.
Run code with the button or Ctrl/Cmd+Enter; edits are saved locally per example.
Set `PORT` to change the server port.

The editor uses [SCI](https://github.com/babashka/sci) to interpret a CLJS subset
against the real native bindings. It is not a self-hosted full CLJS compiler.
`m`, `texture`, `animation`, and `math` are preconfigured; explicit `require`
for those namespaces works too. Return a solid, cross-section, scene, or
`{:geometry solid :texture png-bytes :prop-index 3}` for a textured model.
The flag example draws 13 stripes and 50 stars using `m/cube`, `m/cross-section`,
and `m/color`, bakes the colored geometry with `texture/bake`, then maps it onto
a sphere with native `texture/geodesic-uv` and `:depth-boundary :step`.
`texture/bake` is a synchronous CLJS helper for unlit, opaque +Z projections;
it supports Node and browser workers. Its colors are linear RGBA (like `m/color`),
encoded as an sRGB PNG. No external flag image or network request is involved.
Arbitrary JS/npm imports and asset/file I/O are not exposed in the editor.

Evaluation runs entirely in a dedicated browser worker, with a fresh context
and native-handle cleanup each time. Stop (or the 30-second timeout) terminates
and restarts the worker. Errors preserve the previous model. The local server
serves static app/WASM/Three.js files only, binds to loopback, and never executes
submitted code. Worker isolation is for responsiveness, not a hard browser
memory quota or a security guarantee for running hostile programs.

`npm run build:try-it` bundles the editor and compiles both the app and worker
with Closure advanced optimizations; `npm run serve:try-it` serves the existing build. Run
`npm run test:try-it` after building to check the actual viewer, editable loft,
GLB download, editor highlighting/bracket pairing, errors, infinite-loop
cancellation, animation, baked flag colors/stars/UVs/displacement, persistence,
and mobile layout in Chromium. No CDN or external service is needed at runtime.

The implementation in `src/cljs/clj_manifold3d` uses the same native geometry
algorithms as the JVM, including halfedge surface mapping, UV unwrapping,
image/numeric depth, stepped boundaries, and inner-corner miters. Modeling
operations are synchronous and immutable **after awaiting `init!` once**.
This replaces the old experimental promise-per-operation API.

### Build and test

Prerequisites: Node 18+, npm, Clojure CLI/JDK, CMake, and an activated Emscripten
SDK (tested with Emscripten 3.1.64). The sibling `../manifold` checkout must
include the WASM MeshUtils extensions. The JVM tests additionally need the
local native JAR and its system libraries described above.

```sh
source /path/to/emsdk/emsdk_env.sh
npm ci
npm run build:wasm
npx playwright install chromium
npm test
```

`build:wasm` compiles the C++ sources in `../manifold` into a separate
`build-cljs` directory; it never reuses native object files or the old checked-in
WASM. Override `MANIFOLD_SOURCE`, `MANIFOLD_WASM_BUILD`, or `BUILD_JOBS` if needed.
Generated artifacts are ignored by git:

- `public/wasm/manifold.js` and `manifold.wasm`: browser loader and binary.
- `target/wasm/manifold.cjs` and `manifold.wasm`: Node loader and binary.

`npm test` runs the shared `.cljc` behavioral tests on the JVM, generates JVM
reference fixtures, then runs CLJS in Node (development and Closure **advanced**)
and headless Chromium (advanced). Individual commands are `test:shared:jvm`,
`fixtures:jvm`, `test:cljs`, `test:advanced`, and `test:browser`.
Run `fixtures:jvm` before standalone CLJS tests. `CHROMIUM_PATH` can select an
existing Chromium executable. No application or REPL is stopped by these tests.

Portable assertions live in `test/shared/clj_manifold3d/portable_*_test.cljc`;
`test_support.cljc` adapts only platform representations and file I/O. Browser,
WASM ownership, async assets, and mesh-codec integration tests also exercise the
optimized builds. The test configuration explicitly retains tests in release
builds and fails if zero tests execute.

### Browser usage

Load the generated loader before your compiled application:

```html
<script src="/wasm/manifold.js"></script>
<script src="/js/app.js"></script>
```

```clojure
(ns example.app
  (:require [clj-manifold3d.core :as m]
            [clj-manifold3d.texture :as texture]))

(-> (m/init! {:wasm-url "/wasm/manifold.wasm"})
    (.then
      (fn [_]
        (m/with-disposal
          (fn []
            (let [shape (texture/geodesic-uv
                          (m/sphere 5 64)
                          :origin [0 0 5] :normal [0 0 1]
                          :size [3 2] :pixel-size 0.2
                          :depth-map [[0 0 0] [0 1 0] [0 0 0]]
                          :depth-scale 0.2)]
              (m/export-model shape "surface.glb"))))))
    (.catch js/console.error))
```

For Node, pass the fresh loader and binary to `init!`:

```clojure
(require '[goog.object :as gobj])

(m/init! {:factory (js/require "/absolute/path/to/target/wasm/manifold.cjs")
          :wasm-binary ((gobj/get (js/require "node:fs") "readFileSync")
                        "/absolute/path/to/target/wasm/manifold.wasm")})
```

The Emscripten loader stays **outside Closure compilation**. Every Manifold
method/property accessed by the CLJS bindings uses a string-keyed boundary,
so advanced property renaming cannot change the native ABI. Application code
can use ordinary CLJS calls such as `(m/cube 2 3 4)`; direct calls on foreign
JS handles should use `goog.object` or declared externs.

### Ownership, I/O, and parity boundaries

- Native handles own WASM memory. Call `(m/dispose! shape ...)` when finished,
  or use synchronous `(m/with-disposal (fn [] ...))`. The scope releases its
  created handles even on exceptions; return ordinary data/bytes, not handles
  or promises. Inputs created outside the scope are never implicitly released.
  Mesh snapshots, frames, scene maps, and byte arrays are JS-managed data.
- Numeric grids and `Uint8Array`/`ArrayBuffer` depth images map synchronously.
  Filename/URL depth inputs return a Promise. `text`, `load-image`,
  `load-surface`, `ply-file-to-surface`, `import-mesh`, and `texture/export-glb`
  always return promises. Keep source handles alive until async work finishes.
  Font/image decoding uses the native implementations and cleans up temporary
  files in WASM's private filesystem.
  This change also fixes native PLY storage allocation and image color-channel
  indexing; rebuild the Java JAR separately to apply those two fixes on the JVM.
- `m/scene` and `m/export-scene` support the JVM animation data model.
  `m/export-model` accepts a solid or scene. Export writes a file in Node,
  downloads in a browser, or returns `Uint8Array` when the filename is `nil`.
  Use `texture/export-glb` to embed a PNG/JPEG with UV-mapped geometry.
- `export-mesh` supports GLB, binary STL, and geometry-only OBJ, with
  `:format` available for in-memory output. GLB material options include
  `:color`, `:alpha`, `:roughness`, `:metalness`, `:normal-idx`, `:color-idx`,
  `:alpha-idx`, and `:uv-idx`; channel selectors exclude XYZ, as on the JVM.
  Texture mapping's `:prop-index`, by contrast, includes XYZ.
- `import-mesh` reads STL, triangulated geometry-only OBJ, and one static,
  untransformed triangle primitive from GLB. UV/color/normal attributes are
  retained and physical seams repaired. It rejects unsupported GLB scenes,
  animation, and required extensions explicitly. 3MF/3DS and other Assimp
  formats remain JVM-only; this is not complete Assimp format parity.
- CLJS `bounds` returns `{:min [...] :max [...]}`, `to-polygons` returns
  Clojure vectors, and `get-mesh`/`get-mesh-gl` return native JS Mesh snapshots.
  Frames use radians; solid rotations use degrees, matching the JVM API.
  Surface depth has the same geometric limits as native code: very large
  offsets can fold or self-intersect; global self-intersection detection is
  not provided.

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

### Threading a complete native model

`m/model` promotes a Manifold into an immutable C++ `manifold::Model` that
owns geometry, base colors, images, and ordered texture layers. The JVM and
WASM bindings use the same native implementation, not a Clojure-side asset
registry. Build it with ordinary `->`:

```clojure
(require '[clj-manifold3d.core :as m])

(-> (m/sphere 12 128)
    m/model
    (m/color [0.65 0.7 0.68 1])
    (m/texture "resources/images/american-flag-generated.png"
               :origin [0 -9.6 7.2] :normal [0 -0.8 0.6]
               :u-direction [1 0 0] :size [13.3 7] :pixel-size 0.18
               :depth-map [[1 1] [1 1]] :depth-scale 0.65
               :depth-boundary :step)
    (m/rotate [0 0 15])
    (m/export-model "flag-model.glb"))
```

On CLJS, await `m/init!` and load image bytes first: `m/texture` takes a
`Uint8Array` or `ArrayBuffer`, making the modeling pipeline synchronous.
On the JVM it accepts image bytes or a filename. `texture/bake` output can be
passed directly as the image in either runtime. In CLJS use `m/with-disposal`
around a completed build/export to release intermediate native handles.

Another `(m/texture image ...placement...)` appends another independently
mapped layer; `:opacity` controls source-over blending in linear color space.
`:mapping :geodesic` is the default and requires `:origin` and physical `:size`.
`:mapping :planar` instead accepts `:axes`, UV `:scale`, and `:offset`, and
repeats the image. Depth accepts numeric grids or grayscale image bytes/files,
including 16-bit PNG, with the existing `:fade` / `:step` boundary options.
The mapper assigns fresh UV channels automatically, including the outside-patch
mask. There is no vertex callback and no manual `:prop-index` to coordinate.

Transforms, refinement, booleans, and compose/decompose retain appearance.
Boolean cutter faces keep the cutter's own images, even when both operands
were derived from the same original. Inputs are unchanged and native copies
share immutable image storage. Bare operands are promoted automatically when
combined with a Model; `m/texture` also promotes a bare input. `m/model-info`
reports the retained layer, image, and surface counts. `m/color` on a Model
sets the base color underneath its layers.

`m/export-model` exports a self-contained GLB, or returns bytes with a `nil`
filename. Solid colors and opaque textured regions use ordinary glTF materials
in a single mesh, preserving the original UVs and source image resolution.
This includes disjoint decals, opaque overlaps, and textured boolean operands;
shared images are embedded once. There is no per-triangle rebaking on this path.

When a visible layer needs alpha/opacity compositing, or its coverage boundary
crosses a triangle, export conservatively falls back to the full-model render
atlas. Standard glTF has no arbitrary ordered-layer shader. In-memory layers
remain intact on both paths. `:tile-size` (2–256, default 16) controls samples
per triangle edge **only for that fallback**; it does not downsample direct
textures. The atlas is limited to 64 million pixels. GLB is a render output, not an editable Model
round-trip format. JVM scene import/edit/export preserves its rendered appearance,
but does not reconstruct its original ordered native Model layers. Textured Model
nodes in animation scenes are supported on the JVM; CLJS scene appearance support
and other Model export formats remain follow-ups. Hulls and plane cuts also need an explicit
new-surface appearance policy before they can accept Model operands; perform
those operations before texturing for now.

See `examples/layered_model.clj` for two decals plus a textured boolean cutter.

### Whole-surface textures

`texture-all` covers every face, including the backs and insides of a solid.
It accepts either a Manifold or a Model and returns an immutable Model, with
the same threading, layers, booleans, colors and GLB export as `texture`:

```clojure
(-> (m/sphere 15 64)
    (m/texture-all "resources/images/colored-manifold.png" :size [5 5])
    (m/export-model "target/whole-sphere.glb"))
```

In ClojureScript pass PNG/JPEG bytes (for example `texture/bake` output) instead
of a filename. Both runtimes use the same native C++ implementation; neither
walks the vertices in Clojure. Build the updated sibling Java bindings for the
JVM (`:clj-dev` already points to the local JAR), or run `npm run build:wasm`
for CLJS. A JVM that already loaded the older native bindings needs a fresh
process to use the new modes; an existing REPL can remain running.

The default `:mapping :box` selects a projection for each face from its dominant
normal. Unlike a single planar projection, no nondegenerate face is edge-on to
its texture. `:size [width height]` is physical tile size (default `[1 1]`),
`:origin [0 0 0]` anchors the pattern, `:scale` multiplies UVs (scalar or pair),
and `:offset [u v]` shifts them. Negative scales mirror the pattern. Images
repeat on all faces. UV seams split property vertices, not the solid's physical
connectivity; existing colors, UVs and boolean provenance are retained.
**This is box projection, not seamless triplanar blending:** seams are visible
where the selected projection changes, especially on curved surfaces.

For an image authored as an atlas, use `:mapping :unwrap`. This exposes the
existing native halfedge/least-squares conformal solver through the Model API:

```clojure
(m/texture-all shape atlas-image :mapping :unwrap
               :seam-angle 45 :padding 0.01 :pack? true)
```

Packed charts occupy `[0,1]` and the image is clamped. They use separate parts
of one image, **not a separate complete copy of the image on each face**.
Packing normalizes chart scale. With `:pack? false`, each chart instead uses
model-unit UVs multiplied by a positive uniform `:scale`, and the image repeats.
Unwrapping introduces cuts and distortion; it is not a seamless wrap-once
parameterization. `:mapping :planar` is also available, with the existing
edge-on limitation. `:opacity` and `:name` apply in every mode.

You can append a local `m/texture` decal (including depth) over a whole-surface
material, or append `texture-all` over earlier layers. Whole-surface mappings
do not displace geometry; depth remains a geodesic decal option. `m/texture`
still defaults to the original local `:geodesic` behavior; it also accepts
explicit `:mapping :box` and `:mapping :unwrap`. A newly exposed boolean cut
uses the cutter's own appearance, so texture both operands to texture the cut.

See `examples/whole_surface_texture.clj` for a tiled sphere, torus and textured
boolean cavity exported together in one GLB.

### Low-level UV channels

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

### Surface-normal depth

Add `:depth-map` to the same surface-mapping call to emboss or engrave real
geometry, not just shade a texture. It accepts a rectangular numeric grid or
an image filename. Image decoding and displacement run natively:

```clojure
(def relief
  (texture/geodesic-uv (m/sphere 10 96)
    :origin [0 0 10] :normal [0 0 1] :u-direction [1 0 0]
    :size [6 4] :pixel-size 0.1
    :depth-map [[0 0 0]
                [0 0.5 0]
                [0 0 0]]
    :depth-fade 0))
```

Depth is `sample * :depth-scale + :depth-offset`, in model units; the defaults
are 1 and 0. On smooth surfaces, positive depth moves outward along the
**local surface normal**; negative depth moves inward. Normals are interpolated
from angle-weighted halfedge fans on the original surface, not from the fixed
patch orientation.
The input shape is immutable, and omitting depth retains UV-only behavior.

Images are decoded as grayscale `[0,1]` (including 16-bit PNG precision, with
alpha ignored). Use, for example, `:depth-map "height.png" :depth-scale 0.5`.
Both grids and images run top-to-bottom and are bilinearly sampled over the
whole local patch, independently of `:uv-rect`. `:pixel-size` still controls
geometry resolution: a finer image alone does not add more surface samples.

`:depth-boundary` selects `:fade` (default) or `:step`.
With `:fade`, `:depth-fade` is a smooth transition back to zero at the patch
edge, measured in model units. It defaults to twice `:pixel-size`, capped at
half the patch size. Setting it to zero requires zero depth at every input
boundary sample.

`:step` retains the boundary depth and adds side walls with `:outside-uv`.
For example, `:depth-map [[1 1] [1 1]] :depth-scale 0.3 :depth-boundary :step`
creates a raised patch with a sharp edge; negative scale engraves a recess.
Step mode defaults to zero fade and rejects a nonzero `:depth-fade`.
Sign changes between stepped boundary vertices are rejected unless a vertex
samples the zero crossing; use a fade for such maps.
The surface outside the patch stays unchanged, and UV seams remain physically
joined. UVs from both operands
are preserved through subsequent booleans, including newly cut faces.

Sharp planar corners automatically use a **mitered join**. The native halfedge
graph identifies incident faces and solves a common direction whose dot product
with each face normal is one. Depth is the normal distance from each original
plane; tangential motion keeps the entire UV chart joined, without clipping the
image. At an inner square corner, depth `d` moves the patch by `[d d d]`.
Both signs, images, numeric grids, and fade/step boundaries work with this join.
Patches crossing sharp creases currently require at most three planar face
orientations. Inconsistent joins and miters longer than eight times depth are
rejected; this is not a general offset of arbitrary sharp or curved junctions.

Local triangle folds are rejected. Global self-intersections are not checked;
choose depths small relative to local curvature, wall thickness, and nearby
surfaces. Examples of raised, engraved, and image-driven torus and corner patches are in
`examples/surface_depth.clj`.

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

## JVM scene assembly and spatial queries

### Authored lighting, cameras and glowing materials

On both CLJ and CLJS, scene nodes can carry `:light` or `:camera` as well as
geometry. These survive GLB export and scene append; their node transforms can
be animated using the ordinary animation channels.

```clojure
(m/scene
  {:nodes [{:id :lamp :translation [0 -10 8]
            :light {:type :point :color [1 0.7 0.4] :intensity 500 :range 50}}
           {:id :camera :translation [0 -25 15]
            :camera {:yfov 0.7 :znear 0.1 :zfar 1000}}
           {:id :glowing-ball :geometry (m/sphere 1 24)
            :material {:emissive [1 0.4 0.1] :emissive-strength 3}}]})
```

Lights use glTF `KHR_lights_punctual`: `:point`, `:spot` or `:directional`.
Point/spot intensity is candela; directional intensity is lux. Positive
`:range` applies only to point/spot lights. Spots accept `:inner-cone` and
`:outer-cone` in radians, with `0 <= inner < outer <= pi/2`. Cameras and directed
lights face local **-Z**; camera up is local +Y. Set node rotations explicitly.
Perspective cameras require `:yfov` (radians) and positive `:znear`; `:zfar`
and `:aspect-ratio` are optional. Selecting a camera is up to the viewer.

Materials accept linear `:emissive [r g b]` in `[0,1]`; optional nonnegative
`:emissive-strength` uses `KHR_materials_emissive_strength`. Emissive surfaces
do not automatically illuminate neighbors. Bloom, shadows, environment maps
and water reflections are rendering features, not baked into the GLB.

### Castle night scene

`examples/fairytale_castle_night.clj` builds a stone-textured castle, arched
bridge, landscape, forest and stars, six authored lights, an animated camera,
fireworks and a sweeping sparkle trail. Its ten-second clip uses real Manifold
geometry with GLB transform animation; no per-frame CSG or external meshes.
The landscape uses `m/surface` height fields for ridges, uneven snowcaps,
foothills and shorelines. Trees are ray-seated on the ground; the nine-span
bridge meets a supported, level road cut into the bank. The static castle's
bridge still defaults to four spans (`:bridge-spans` configures the count).
The repeating stone PNG is generated procedurally and embedded via
`m/texture-all`. Both castle builds subtract window openings after assembling
the masonry/trim, then ray-check 30 exposed window samples. The preview uses
24-bit depth attachments and a suitable near plane to avoid glass/wall z-fighting.

```sh
npm run build:castle
npm run serve:castle    # http://localhost:8092/
npm run test:castle    # headless browser + exported-asset checks
```

The standalone preview uses the exported lights/camera/animation, adding HDR
bloom and rippled planar reflections. It offers scrubbing, pause (Space),
orbit mode, fullscreen (F), and GLB download. It does not modify the journal,
Try it! app, user documents or REPL. The GLB opens in other capable viewers,
but their lighting and postprocessing may look different.

### Assembly operations

`core/scene`, `core/export-scene`, and `core/export-model` accept scenes containing
Manifolds or native Models. Each node retains its Model's colors, normals, UVs,
and embedded images. Identical geometry/material pairs share a glTF mesh.
Optional node `:material` overrides use `:color` (RGBA), `:roughness`, and
`:metalness`. Transform-only pivots, node `:extras`, and animation clips survive
export. Matrices are column-major; quaternions are `[x y z w]`.

```clojure
(require '[clj-manifold3d.core :as m]
         '[clj-manifold3d.scene :as scene])

(def assembly
  (-> (m/import-scene "assembly.glb")
      (scene/update-node "Arm" update :extras assoc "inspected" true)
      ;; A parent offset retains the child's original animated transforms.
      (scene/wrap-node "Arm" {:translation [10 0 0]})
      (scene/append
        (m/scene {:nodes [{:id :bracket :geometry (m/cube 2 3 4)}]}))))

(m/export-model assembly "assembly-edited.glb")

(def pose (scene/sample-scene assembly 0.5))
(scene/bounds pose "Arm")
(with-open [arm (scene/solid pose "Arm")
            index (m/spatial-index arm)]
  (m/ray-cast index [100 0 0] [-1 0 0])
  (m/closest-point index [100 0 0])
  (m/contains-point? index [0 0 0]))
```

Scene edits return new documents. `scene/node`/`update-node` use keyword keys at
the node's top level; nested glTF fields and imported extras retain string keys.
Selectors are integer indices or unique names; keywords also select default
authored ID names. `remove-node` detaches a subtree without renumbering assets or
compacting binary storage. `append` adds the other document's active roots and
remaps all core asset references, retaining its animation clips as separate clips.

`sample-scene` freezes a rigid pose, removes clips from the returned snapshot,
and clamps time to each sampler's range. Select a clip with `:animation` (index or
unique name); `nil` selects the rest pose. Imported LINEAR, STEP, and CUBICSPLINE
TRS channels are supported. `world-transforms`, `vertices`, and `bounds` operate
on the current pose. `solid` reconstructs each closed node mesh and unions the
selected subtree; it extracts **geometry only**, leaving appearance in the scene.
For repeated sampling, first call `scene/document` once to compile an authored
scene, or use `import-scene`, which already returns a compiled document.

Import/export supports embedded single-buffer GLB 2.0, preserving original assets
and metadata. It does not load external buffers/images. Append supports core glTF,
`KHR_materials_unlit`, and `KHR_texture_transform`; other source extensions are
rejected because their index semantics need explicit remapping. Geometry queries
require rigid, uncompressed triangle meshes; skinning, morph deformation and
compressed primitives are not evaluated. These restrictions do not prevent
unchanged assets from being retained in an imported/exported scene. Units are
preserved, with no automatic mm/metre conversion.

Native `spatial-index` owns a BVH snapshot independent of the source's lifetime.
Queries also accept a Manifold or Model directly (building a temporary index).
Ray hits return `:triangle`, `:distance`, `:position`, `:normal`, and `:barycentric`;
closest-point results omit barycentrics. Both return nil for no result. Point
containment includes boundaries by default; use `:boundary? false` to exclude
them, or `spatial/classify-point` for `:inside`, `:boundary`, `:outside`.
`overlap?` includes touching and complete containment, including disconnected
shells and cavities. Its `:tolerance` is a contact-axis tolerance, not an exact
clearance distance or a guarantee of positive-volume intersection. Tolerance
defaults to `1e-7` model units; choose appropriately for your model's scale.

`m/mesh-merge` returns a copy repaired by native `MeshGL.Merge`, without changing
the input or its vertex properties. This is best effort, not general mesh repair:
check `(m/status (m/manifold merged))` for `:NoError`.

All additions in this section are JVM implementations. Scene data and functional
APIs are designed for a subsequent CLJS port; no CLJS parity is claimed yet.
They require the freshly rebuilt sibling Java bindings, already selected by
the development/test aliases. Rebuild with a full JDK (including JNI headers):

```sh
cd ../manifold/bindings/java
mvn -Dmaven.test.skip=true package
```

The skip flag bypasses the binding project's stale Java test sources; the native
queries are exercised through the Clojure integration tests. Already-running JVMs
keep loaded native classes: use a fresh JVM for the new bindings, without stopping
an existing REPL session. Run the working assembly example with:

```sh
clojure -M:clj-dev -m scene-assembly target/scene-assembly.glb
```

The example reports no interference at times 0 and 0.5, and interference at 1.
The GLB remains animated and keeps the colored materials.

# Modeling Journal

This separate, Maria-inspired notebook interleaves editable rich prose, code,
and inline results. Solids and native Models render as orbitable, downloadable
GLBs; scenes play their animations; cross-sections render as flat SVGs. It has
its own builds, assets, worker and server, independent of Try it!.

Build and host it locally with:

```sh
npm run build:journal
npm run serve:journal
```

Open http://localhost:8090/journal/. `JOURNAL_PORT` and `JOURNAL_DATA` override
the port and database directory. `npm run test:journal` checks the advanced
build in Chromium with an isolated temporary Datahike database.
`npm run test:journal:state` verifies DataScript subscriptions and the shared
`.cljc` document/schema tests under advanced optimization.

## Browser-only journal / GitHub Pages

The [hosted journal](https://cartesian-theatrics.github.io/clj-manifold3d/journal/)
uses the same editor, DataScript schema/subscriptions, SCI worker and native
WASM geometry engine, without a Clojure server. Its initial library contains
**Castle · night scene**, **Castle · architecture**, **American flag · surface UV**,
and **Library · README walkthrough**. The two castle documents open side by side
by default. Choose **Run page** to evaluate a document; every modeling block
returns a shape or scene, showing the construction stages rather than only the
finished assembly. The full castle can take a minute or more, depending on the device.

The README journal has 23 runnable panels covering booleans, cross-sections,
extrusion, offsets, revolution, hulls, polyhedra, frames, three loft styles,
slicing, height fields, colors, composition, circles/arcs, vertices/halfedges,
texturing and a lit animated scene. File/font-dependent examples are explained
but not executed in the browser-only runtime.

Existing journals and pane layouts are kept on reload. **Update examples**
downloads a backup before replacing the four bundled examples and restoring
the castle split view (after confirmation). Other documents are untouched.
Newly introduced examples are added automatically without replacing saved ones.

Edits, panel deletion history, viewer settings, and splits persist in IndexedDB
on that browser and origin. DataScript remains the sole live application database.
Closing a tab loses evaluation results, not saved source; re-run to recreate them.
Clearing browser storage (or ending a private browsing session) can erase edits:
use **Export backup** to download an EDN snapshot. **Import backup** validates it
and asks before replacing matching namespaces; other documents and the current
pane layout are kept. Conflicting document saves from another tab are rejected,
with an instruction to export unsaved edits before reloading. There is no cloud
sync, filesystem mirror, login, or AI prompting in this edition. Namespace imports
resolve other browser documents. The local server edition still uses Datahike,
writes `.clj` namespace files, and supports Codex.

To build from source, install Node/npm, Clojure CLI, CMake and Emscripten. Use the
extended bindings from `cartesian-theatrics/manifold` (commit `72c71d4d` or a
compatible newer revision) in `../manifold`, or set `MANIFOLD_SOURCE`:

```sh
npm ci
npm run build:wasm
npm run build:journal:static
npm run test:journal:static
```

The build prints a fresh `target/journal-static-*` directory; its absolute path
is also in `target/journal-static-path`. Serve that directory with any static
HTTP host, or upload its contents as a Pages artifact. The root links to
`journal/`; all app and WASM URLs are relative, including under a repository
subdirectory. Use HTTP(S), not `file://`. Only an explicit public-asset allowlist
is copied: **never upload the repository, `data/`, local journals, or credentials**.
The published site is served from the `gh-pages` branch; source stays on
`3d-journal`. Building the static artifact does not switch the local app into
static mode or modify its database.

`test:journal:static` serves the production bundle under a nested path with no
API server and checks every walkthrough panel, namespace imports, GLB export,
backups, reload persistence, and conflicting tabs. Set `JOURNAL_STATIC_URL` to
exercise a deployed static site instead (test documents stay in an isolated
browser profile). Shared `.cljc` tests cover snapshot validation and revision
transactions; `test:journal:state` also covers replacement imports in DataScript.

## Editor and panels

- Ctrl/Cmd+Enter evaluates the selection or form at the cursor;
  Ctrl/Cmd+Shift+E evaluates the enclosing top-level form.
  Ctrl/Cmd+Shift+Enter splits the code block at the exact cursor position and
  focuses the new block. Shift+Enter runs the block; Ctrl/Cmd+Alt+Enter
  runs the page from scratch. Results stay beside the code that produced them.
- Prose supports headings, bold, italic, code, lists and Markdown shortcuts.
  Insert, reorder or remove blocks without editing a special file syntax.
- CodeMirror provides highlighting, automatic brackets, optional Vim bindings,
  and parser-based forward/backward slurp and barf, with automatic reindentation
  in the same undo step. In Vim visual mode, `>` barfs and `<` slurps forwards.
  F1 opens the shortcut guide;
  actions show their shortcuts on hover.
- Split panes show independently scrollable documents. Opening the same
  document twice synchronizes edits and results without replacing editors.

All application facts live in DataScript: documents and blocks, layout, focus,
preferences, dialog drafts, save acknowledgements, pending evaluations and
results (including viewer display toggles). UI subscriptions run Datalog
queries and notify only when their result changes. Atoms outside the database
hold disposable resources: editors, renderers, workers, timers, promise chains
and subscription caches. Editor-internal cursor/undo machinery and camera/GPU
internals remain owned by their respective components. Worker evaluation
history also lives in DataScript; SCI contexts and native allocations are
runtime resources, not serializable application records.

The Clojure backend persists documents, blocks, and workspace preferences in
Datahike using the shared `journal.schema` vocabulary. Datahike is the source
of truth; saves also materialize readable `.clj` namespaces under
`data/journal/documents/` (e.g. `workshop.my-part` → `workshop/my_part.clj`).
Prose becomes ordinary Clojure comments. Files are repaired from the database
at startup; external file edits are not imported. Save revisions reject
conflicting writes instead of silently overwriting another window's work.

Evaluation currently runs in browser SCI with real WASM geometry, **not on the
JVM server** and not through a full ClojureScript compiler. Documents can
require other documents; code blocks share a namespace session. Each document
has one visible, editable `(ns … (:require …))` header above its panels. Add
library and document imports there, not in code panels. There are no hidden
`m`, `texture`, `animation`, or `math` aliases; new documents explicitly require
core as `m`. The header supports the same syntax highlighting, auto-parens,
Vim and slurp/barf as code. It cannot be deleted or split. Changes to imports,
code or dependencies invalidate the session at the next evaluation. Existing
documents migrate their implicit aliases and literal standalone requires into
the header without reformatting modeling code. Stop/30-second timeout
restarts the worker. JVM/JS interop and arbitrary dependency loading are not
exposed. The server binds to loopback. Manual evaluation runs in the page's
worker; generated code is checked in a disposable headless browser worker,
never in the backend JVM.
Original prototype databases/files under `data/maria-*` are left untouched.
The replaced prototype source is archived in
`target/journal-prototype-before-replacement.tar` for recovery.

Every code, prose, and thinking panel has **Hide/Show** and **Delete** controls.
Hide collapses the panel (including its result) to a compact header; it does
not remove code from evaluation or the namespace file. Visibility is shared
across splits and saved with the document. Use Ctrl+Alt+H to toggle the focused
panel, or Ctrl+Alt+Backspace to delete it. Deleting a thinking panel stops an
active request and prevents late output from reappearing; deleting the final
panel leaves an empty prose editor so the journal stays editable.
Use **Undo delete** in the document header or **Ctrl+Alt+Z** to restore a deleted
panel. The last ten deletions per document survive reloads. Undo preserves
intervening edits and panel settings, but does not resurrect expired render
results or restart a deleted Codex request. Editor Ctrl/Cmd+Z still undoes text.

Model previews have a **Tools** toggle (closed by default). Enable **Measure**
and click two points on the model for their straight-line distance in model
units; animation pauses while picking. Clear removes the markers. The **Floor
grid** is on by default and can be switched off. Camera position, orbit target,
and grid preference are stored per panel in DataScript/Datahike and survive
re-evaluation, offscreen viewer suspension, and page reload. **Fit** recenters
the camera. Measurements are cleared when new geometry replaces the result.

**Pop out** (Ctrl+Alt+O) moves the live model and its controls to a floating
window. Where supported, [Document Picture-in-Picture](https://developer.chrome.com/docs/web-platform/document-picture-in-picture)
keeps it always on top, with one such window per journal tab at a time. Other
browsers get an ordinary window with an explicit notice that pinning must be
done through the operating system's window manager. Re-evaluations update the
same window. Closing it or choosing **Return to journal** restores the panel;
deleting the owning panel or closing the journal closes its pop-out.
**Fullscreen** (Ctrl+Alt+M, Esc to exit) expands the viewer in place, preserving
camera position and controls. Return a detached viewer to the journal first.
Window/fullscreen facts are per-pane DataScript state, not saved preferences.

Open **American flag · surface UV** (`journal.flag-uv`) for a runnable example
of colored flag geometry baked into an image, native surface UVs on a sphere,
and a wavy depth grid. Its downloadable GLB embeds the texture and UVs.

Open **Castle · night scene** (`journal.castle-night`) and choose **Run page**
for the complete textured, animated castle and `m/surface` landscape. Its
visible `ns` header requires **Castle · architecture**; both documents contain
editable implementation code seeded from the canonical files in `examples/`.
Existing edits are never overwritten on startup. The first build is substantial.
The viewer uses the authored camera and lights; the separate castle preview
additionally provides cinematic bloom and water reflections.

The example uses portable `texture/image` (a synchronous sRGB RGBA pixel
function returning PNG bytes), `m/as-original`, and `m/with-spatial-index`.
WASM spatial queries use the same native BVH as the JVM: `ray-cast`,
`closest-point`, `contains-point?`, and `overlap?`. `with-spatial-index` releases
its snapshot even when the callback throws. Run `npm run test:journal:castle`
for the full advanced-optimized browser evaluation and GLB regression test.

#### Prompting Codex from prose

Write a request in any prose block, then choose **Prompt Codex** or press
Ctrl/Cmd+Enter while editing that prose. A Thinking panel appears immediately
underneath it. Codex receives the selected panel IDs and content,
including selected responses below the prompt, plus the previous completed
prompt at that panel. Revisions update the relevant panels in place; new topics,
additional examples, and explicit alternatives insert new panels below Thinking.
A new prose prompt can also ask to revise an earlier panel. One response can
mix updates and additions; unchanged panels are left alone. Editing prose alone
does not start a request: choose Prompt Codex again when ready.

Choose **Context…** on a prose panel (Ctrl+Alt+K while focused on prose) to set
that prompt's context. Scope can be the document, its heading-defined section,
or selected panels only. Each panel can explicitly be a **Target** (editable),
**Read-only** reference, or **Excluded**. The active prompt is always read-only.
Badges in the journal show roles for the prompt you are configuring. Roles are
independent of Hide/Show and persist per prompt in Datahike/DataScript. The host
rejects patches to references, excluded panels, and dependencies.
**Allow Codex to create new namespaces** is an explicit per-prompt permission,
off by default. When enabled, the context includes existing namespace names
(not their contents) to avoid collisions. `create` edits group code/prose panels
into new documents, which are saved as ordinary `.clj` namespace files and can
be required from other journals. The host refuses existing/library namespaces,
unsafe paths, and namespace names that map to the same file.
New documents and edits to the referring document are saved in one Datahike
transaction, so a namespace collision cannot partially commit a save batch.

**What will be sent?** previews the exact app-provided model input and output
and tool schemas without calling Codex. It shows inclusion reasons and a rough token
estimate (characters / 4, not a tokenizer count; Codex's built-in instructions
and protocol overhead are not included). Sending after inspection checks that
the context has not changed. The backend stores the actual input with the
request. Nothing is silently truncated to meet the request-size limit.

Pinned workspace instructions provide defaults such as units and fabrication
constraints. Document instructions can extend them, with document-specific
requirements taking precedence, or replace workspace instructions entirely.
These instructions are context, not executable namespace source.

Namespace-aware lookup adds referenced definitions from other journal documents
and the journal's ClojureScript core/texture/animation/model/mesh-io APIs.
It understands ordinary require aliases, named refers, fully qualified symbols,
and common lexical bindings. Signatures, literal constants, and docstrings are
included by default. **Include implementation** expands a specific definition;
you can also select implementations globally or turn automatic lookup off.
Expanded implementations include their discoverable dependencies, with cycle
detection and a visible 100-definition limit. External private vars, unrelated
definitions, and explicitly excluded panels are not automatically included.
Lookup only parses code; it never evaluates forms or loads namespaces. Macro-
generated references may need manual reference selection. Imports belong in
the document's namespace header, including imports added by generated deltas.

Independently, Codex can discover supported library APIs using three read-only
tools: `journal_api_search`, `journal_api_read` (documentation and optional
implementation), and `journal_api_examples` (trusted built-in examples). It
does not need existing references or manual documentation expansion. The
catalog is frozen in Datahike per request and matches the SCI exposure list;
these tools never inspect unrelated user documents or evaluate code. Calls and
results appear under **API lookups** in Thinking. There is a 32-call budget,
40k-character result limit and 200k-character cumulative result budget.

The namespace header is a target by default, even for section/selected scope;
you can make it read-only or exclude it in Context. Generated imports are
ordinary minimal patches to that header, applied in place. A targeted namespace
header is locked until the request finishes or you choose Stop & edit.
Animated scenes preserve vertex colors and native Model materials, UVs, normals
and embedded textures on both JVM and ClojureScript. Place the colored Manifold
or textured Model in a node's `:geometry`; optional `:material` accepts `:color`,
`:roughness` and `:metalness`. Repeated geometry/material pairs share their GLB
assets. The discovery tools report these capabilities; lighting still comes
from the viewer rather than per-scene light constructors.

Thinking reports how many panels were updated or added. Updates preserve panel
identity, position, and visibility. Completed valid edits are saved immediately
to the shared document and its `.clj` file. The host then executes the selected
notebook in a disposable instance of the actual
SCI/WASM worker, including geometry export. Errors and printed output go back
to Codex for up to two repair attempts against the **current scratchpad**.
Repairs send only additional deltas, never replay earlier edits. Failed code
stays visible in its panel while being repaired; it is not executed in the
user's existing worker session.

Successful results appear automatically in their code panels: GLB viewers for
models/scenes, SVG previews for cross-sections, and values/printed output.
Results and binary artifacts are stored in Datahike and restored after reload
when their source still matches; no second execution or manual Run is needed.
Thinking's **Evaluation & repairs** disclosure shows the checks. Runtime success
does not prove that a model visually matches the request.

Verification requires Node and Playwright Chromium on the server (`npx playwright
install chromium`); set `JOURNAL_NODE_BIN` if Node is not on its PATH. It permits
only the worker/WASM assets, not journal APIs or arbitrary network access. Each
namespace has a 30-second execution limit, with 60 seconds for the entire
verification process and a 16 MiB rendered-result limit. Stop also terminates
verification. The evaluator includes selected context and referenced definition
implementations, not unrelated documents or explicitly excluded panels.

While Codex writes, additions populate incrementally below Thinking. Existing
panels receive **literal deltas**, not regenerated panel text. Each `patch`
contains a panel ID, a small unique `before` fragment, and its replacement
`after`. Multiple patches can address the same panel, in order; insertions use
a short unchanged anchor, and deletions use an empty `after`. Unchanged code,
comments, and whitespace are not sent back by the model. Missing or ambiguous
matches are rejected, never guessed. New-panel `insert` operations use null
`target`/`before` and place the new text in `after`.
For a missing or ambiguous patch fragment, the host sends the exact failing
panel source and diagnostic back in the same isolated Codex thread, for at most
two correction attempts. The feedback contains current panel sources and stable
IDs, including newly added panels. The host keeps an append-only operation log
and rejects attempts to rewind completed edits. Original context permissions
still apply. Rejected output, the latest repair input, and attempt count
are retained with the request; `/api/codex/requests/:id/inspection` exposes them.

Each completed patch object is applied immediately **inside the existing code
or rich-text editor** while the remaining operations are still arriving, with syntax
highlighting/formatting and an “Editing live…” indicator, not a secondary panel.
Target code panels and imports are locked from request submission through
evaluation and repair. The server rejects competing writes/deletes with HTTP
423 and rejects overlapping Codex sessions. You can still edit other prose
or untargeted panels; incoming edits merge with unrelated unsaved changes.
**Stop & edit** cancels generation, keeps completed edits, and hands the panel
back to you. Cancel, failure, and server restart also release ownership without
discarding completed work. Only an unfinished streamed operation is discarded.
A code editing session is one undoable editor action, including after Stop.
Use editor Undo if you explicitly want to revert it.

Completed new code panels become ordinary syntax-highlighted editors immediately,
with stable IDs that subsequent repairs patch in place. An unfinished insertion
is shown as a temporary preview until its operation is complete. All state lives
in Datahike/DataScript. The initiating tab polls every 200 ms; other open tabs
discover active editing sessions and their locks every second. Reloading does
not lose the working code or its operation log.

Retry starts from the current document, including completed edits and your changes.
Codex cannot delete panels or overwrite the active prompt. Request snapshots
and proposed edits remain in Datahike for history, including conflicts.

This uses the local, signed-in Codex CLI (`codex login`). The prose prompt and
selected code/prose panels, effective pinned instructions, and selected dependency
summaries/implementations are sent to Codex; unrelated documents are not sent
wholesale. The CLI runs with a read-only sandbox,
no shell, no web search, no subagents, and no user-configured app integrations.
The frontend never receives credentials. This is a local trusted-user app,
not an authenticated multi-user hosting service.

Thinking shows public progress updates, **not private chain-of-thought**.
Raw reasoning events are discarded. Requests, progress, prompt snapshots and
structured outputs are stored in Datahike and mirrored in DataScript; only
process handles, polling timers and executors are runtime resources. Requests
survive a browser refresh, completion is applied at most once, and generated
blocks use the normal document-save and namespace-file workflow. Stop Codex
cancels the subprocess. A server restart marks unfinished requests interrupted
rather than silently rerunning a paid request. At most two requests run at a
time, with a five-minute timeout.

Choose **Codex model** in the sidebar before sending a prompt. The selector
reads the installed Codex app-server's paginated `model/list` catalog; **Refresh
models** reloads it. The workspace choice is saved in Datahike and mirrored in
DataScript. Each request freezes its choice, so switching models does not affect
an in-flight request or automatic repairs. Thinking shows the resolved model;
request inspection also includes the selected model. Explicit Retry uses the
current selection. If discovery fails, the saved choice and Codex default remain
usable, with an error shown beside the selector; unavailable models report errors
instead of silently falling back. Reasoning effort uses Codex's configured/default
behavior rather than forcing the same effort across different models.

`JOURNAL_CODEX_BIN` selects the CLI executable. Choosing **Codex default** uses
`JOURNAL_CODEX_MODEL` if set, otherwise the CLI default. An explicit UI selection
overrides that environment default. The backend uses an ephemeral
Codex app-server thread over stdio with real `item/agentMessage/delta` events.
Existing CLI authentication/model settings are reused, but shell, subagents,
apps, MCP servers, plugins, hooks, notifications, and web search are explicitly
disabled; effective integration settings are checked before starting a thread.
Only the three registered read-only journal tools are accepted; other tool
and approval requests are rejected. `npm run test:journal`
uses an explicit deterministic subprocess fixture, never your Codex account.
See [Codex app-server](https://learn.chatgpt.com/docs/app-server) for the delta
stream and per-turn structured-output protocol used here. The optional
`scripts/journal-stream-smoke.clj` and `scripts/journal-api-smoke.clj` tests each
make a real, authenticated request; neither runs in the regular test suite.

# Example Projects

A Simple rapidly printable hydroponic tower:
https://github.com/SovereignShop/spiralized-hydroponic-tower

Kossel delta printer:
https://github.com/SovereignShop/kossel-printer/

# Changelog

## 1.2.0 — Manifold 3.5.3 update

This checkout targets upstream **v3.5.3** (`0edd9d54876f3135e431575214dd6d8a72866fee`)
plus this project's native extensions (fork integration commit `d8778ce9`).
Release **1.2.0** uses native bindings **2.2.0**. The JAR includes matching
WASM assets at `clj_manifold3d/wasm/manifold.js` and `manifold.wasm`;
copy these to your web server and initialize the CLJS runtime as described in
the [browser usage guide](README.md#browser-usage).

Both CLJ and CLJS now expose:

| API | Purpose |
| --- | --- |
| `minkowski-sum`, `minkowski-difference` | Solid dilation and erosion |
| `simplify`, `get-tolerance`, `set-tolerance`, `refine-to-tolerance` | Solid simplification and refinement control |
| `smooth-by-normals`, `calculate-curvature` | Smoothing tangents and curvature properties |
| `level-set` | Build a solid from a signed distance function |
| `min-gap`, `ray-cast-segment` | Separation and all triangle hits on a finite segment |
| `execution-context`, `with-context`, `progress`, `cancel!`, `cancelled?` | Native progress and cancellation |
| `read-obj-string`, `write-obj-string` | Upstream's precision-preserving, geometry-only OBJ dialect |
| `mesh-data`, `mesh-run-info` | Portable mesh buffers, provenance, backside and normal flags |

`offset` accepts `:bevel`. `calculate-normals` now also accepts one argument
(default property index 0) or two arguments (explicit index), with upstream's
52.5 degree sharp-angle default. Existing explicit arities remain valid.
`simplify` still accepts cross-sections and JVM polygons; it additionally accepts
solids and native Models. Its cross-section default remains `1e-6`. The existing
`smooth-out` default remains 60 degrees. Model appearance is retained by
`simplify`, `set-tolerance`, and `refine-to-tolerance`; operations that create
new surfaces without an appearance policy require a bare Manifold.

```clojure
(require '[clj-manifold3d.core :as m])

;; In CLJS, await the existing m/init! once before calling geometry functions.
(def rounded
  (m/minkowski-sum (m/cube 10 10 10) (m/sphere 1 24)))

;; Positive SDF values are inside, on both runtimes.
(def implicit-ball
  (m/level-set (fn [[x y z]] (- 4 (+ (* x x) (* y y) (* z z))))
               {:min [-3 -3 -3] :max [3 3 3]} 0.3))

(def hits (m/ray-cast-segment rounded [-5 5 5] [20 5 5]))
;; Each hit has :face-id, :position, :normal, and :distance.
;; :distance is the fraction along the segment, not a length in model units.
```

The existing `ray-cast` still takes a direction and returns the nearest hit.
The new segment API returns all hits, including coincident hits on adjacent
triangles. OBJ string round-tripping retains geometry, not colors, UVs, normals,
materials or animation; use GLB for those assets.

Contexts follow upstream's limited propagation rules: attach one immediately
before `status`, an eager refinement, hull or Minkowski operation. Transforms
and booleans return values without the attachment; queries such as `volume`
and `get-mesh-gl` do not observe it. Mesh factories also accept a context via
`(m/manifold mesh ctx)` and `(m/smooth mesh sharp-edges ctx)`, and `level-set`
accepts `:context ctx`. Cancellation is permanent for that context. CLJS geometry
calls are synchronous, so UI cancellation during a call needs worker isolation.
Level-set callbacks execute synchronously on the calling thread and propagate
Clojure/ClojureScript exceptions after native cleanup.

The stable release was selected to preserve the existing fill rules, tolerance,
and smoothing APIs; post-release upstream development removes some of them.
See the sibling fork's `UPSTREAM_UPDATE.md` for the native integration details.

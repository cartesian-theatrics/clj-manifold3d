(require '[clojure.test :as test])

(def suites
  '[clj-manifold3d.core-test clj-manifold3d.animation-test
    clj-manifold3d.boolean-uv-test clj-manifold3d.texture-test
    clj-manifold3d.split-lifetime-test clj-manifold3d.surface-depth-test
    clj-manifold3d.scene-spatial-test clj-manifold3d.fairytale-castle-test
    clj-manifold3d.fairytale-castle-night-test clj-manifold3d.raptor-3-test])

(doseq [suite suites] (require suite))
;; Both legacy animation test files share a namespace. Load both explicitly
;; so classpath ordering cannot silently omit the GLB export checks.
(load-file "test/cljc/clj_manifold3d/animation_test.cljc")
(load-file "test/clj/clj_manifold3d/animation_test.clj")
(let [result (apply test/run-tests suites)]
  (System/exit (if (and (pos? (:test result)) (test/successful? result)) 0 1)))

(require '[clojure.test :as test]
         'clj-manifold3d.portable-core-test
         'clj-manifold3d.portable-builders-test
         'clj-manifold3d.portable-texture-test
         'clj-manifold3d.portable-model-test
         'clj-manifold3d.portable-animation-test)
  (let [result (test/run-tests 'clj-manifold3d.portable-core-test
                             'clj-manifold3d.portable-builders-test
                             'clj-manifold3d.portable-texture-test
                             'clj-manifold3d.portable-model-test
                             'clj-manifold3d.portable-animation-test)]
  (System/exit (if (and (pos? (:test result)) (test/successful? result)) 0 1)))

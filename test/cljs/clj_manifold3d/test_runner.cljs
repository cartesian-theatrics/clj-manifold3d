(ns clj-manifold3d.test-runner
  (:require [cljs.test :as test :refer-macros [run-tests]]
            [clj-manifold3d.core :as m]
            [clj-manifold3d.portable-core-test]
            [clj-manifold3d.portable-builders-test]
            [clj-manifold3d.portable-texture-test]
            [clj-manifold3d.portable-model-test]
            [clj-manifold3d.portable-animation-test]
            [clj-manifold3d.fixtures :as fixtures]
            [clj-manifold3d.parity-test]
            [clj-manifold3d.asset-test]
            [goog.object :as gobj]))

(defmethod test/report [::test/default :end-run-tests] [result]
  (gobj/set js/process "exitCode" (if (and (pos? (:test result)) (test/successful? result)) 0 1)))

(defn main []
  (let [path (js/require "node:path")
        resolve (gobj/get path "resolve")
        factory (js/require (resolve "target/wasm/manifold.cjs"))
        bytes ((gobj/get (js/require "node:fs") "readFileSync") (resolve "target/wasm/manifold.wasm"))]
    (-> (m/init! {:factory factory :wasm-binary bytes})
        (.then (fn [_] (fixtures/prepare!)))
        (.then (fn [_] (run-tests 'clj-manifold3d.portable-core-test 'clj-manifold3d.portable-texture-test
                                  'clj-manifold3d.portable-builders-test
                                  'clj-manifold3d.portable-animation-test 'clj-manifold3d.portable-model-test 'clj-manifold3d.parity-test
                                  'clj-manifold3d.asset-test)))
        (.catch (fn [e] (js/console.error e) (gobj/set js/process "exitCode" 1))))))

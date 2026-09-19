(ns clj-manifold3d.browser-runner
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
  (gobj/set js/globalThis "CLJS_TEST_RESULT"
            (clj->js (assoc result :success (and (pos? (:test result)) (test/successful? result))))))

(defn main []
  (enable-console-print!)
  (-> (m/init! {:wasm-url "/wasm/manifold.wasm"})
      (.then (fn [_] (fixtures/prepare!)))
      (.then (fn [_] (run-tests 'clj-manifold3d.portable-core-test 'clj-manifold3d.portable-builders-test
                                'clj-manifold3d.portable-texture-test
                                'clj-manifold3d.portable-animation-test 'clj-manifold3d.portable-model-test 'clj-manifold3d.parity-test
                                'clj-manifold3d.asset-test)))
      (.catch (fn [error]
                (js/console.error error)
                (gobj/set js/globalThis "CLJS_TEST_RESULT"
                          (js-obj "success" false "error" (str error)))))))

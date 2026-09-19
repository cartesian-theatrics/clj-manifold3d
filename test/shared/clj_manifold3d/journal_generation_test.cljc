(ns clj-manifold3d.journal-generation-test
  (:require [clj-manifold3d.journal.generation :as gen]
            [clj-manifold3d.journal.document :as doc]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(def document {:namespace "test.ai" :title "AI" :revision 0
               :blocks [{:id "before" :kind "code" :source "(def radius 2)"}
                        {:id "prompt" :kind "prose" :source "Make a sphere"}
                        {:id "later" :kind "code" :source "(never-send-this)"}]})

(deftest bounded-prompt-context
  (let [request (gen/request-input document "prompt" "request")]
    (is (= "Make a sphere" (:prompt request)))
    (is (re-find #"def radius" (:context request)))
    (is (not (re-find #"never-send-this" (:context request))))
    (is (= request (gen/from-entity (gen/entity request)))))
  (is (nil? (gen/request-input document "before" "request")))
  (is (nil? (gen/request-input document "absent" "request")))
  (is (not (gen/active? {:status "complete"})))
  (is (gen/active? {:status "running"})))

(deftest output-placement-and-replay
  (let [document (update document :blocks gen/insert-after "prompt"
                         [{:id "request" :kind "thinking" :source "Progress is not code"}])
        panels (gen/output-blocks "request" {:panels [{:kind "prose" :source "A sphere"}
                                                     {:kind "code" :source "(m/sphere radius)"}]})
        request {:id "request" :status "complete"}
        next (gen/apply-output document request panels)]
    (is (= ["before" "prompt" "request" "request-0" "request-1" "later"] (mapv :id (:blocks next))))
    (is (= ["request"] (:applied-requests next)))
    (is (nil? (gen/apply-output next request panels)) "Polling/reload never duplicates results")
    (is (nil? (gen/apply-output (assoc document :blocks []) request panels)) "Deleting an anchor prevents late insertion")
    (is (nil? (gen/apply-output document (assoc request :status "cancelled") panels)))
    (is (re-find #";; Progress is not code" (doc/source document)))
    (is (every? false? (map :hidden panels)))))

(deftest validate-structured-panels
  (doseq [bad [nil {} {:panels []} {:panels [{:kind "shell" :source "bad"}]}
               {:panels [{:kind "code" :source 42}]}]]
    (is (try (gen/output-blocks "request" bad) false
             (catch #?(:clj Exception :cljs :default) _ true)))))

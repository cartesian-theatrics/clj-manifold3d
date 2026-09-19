(ns clj-manifold3d.journal-document-test
  (:require [clj-manifold3d.journal.document :as doc]
            [clj-manifold3d.journal.schema :as schema]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(deftest namespace-files
  (is (= "workshop/my_part.clj" (doc/namespace-path "workshop.my-part")))
  (doseq [s [nil "" "../escape" "bad/name" ".x" "a..b" "a b" "0thing"]]
    (is (not (doc/valid-namespace? s))))
  (is (doc/valid-namespace? "workshop.part-2")))

(deftest readable-namespace-projection
  (let [s (doc/source {:namespace "test.part"
                       :blocks [{:kind "prose" :source "# A part\n\n**note**"}
                                {:kind "code" :source "(def part (m/cube 2 3 4))"}]})]
    (is (re-find #"\(ns test.part" s))
    (is (re-find #";; # A part\n;; \n;; \*\*note\*\*" s))
    (is (re-find #"\(def part \(m/cube 2 3 4\)\)" s))))

(deftest shared-schema
  (is (= :db.type/ref (get-in schema/schema [:document/blocks :db/valueType])))
  (is (= :db.cardinality/many (get-in schema/schema [:document/blocks :db/cardinality])))
  (is (= :db.unique/identity (get-in schema/schema [:block/id :db/unique])))
  (is (nil? (get-in schema/schema [:block/source :db/valueType])))
  (is (every? :db/valueType schema/attribute-tx))
  (is (= (set (keys schema/schema)) (set (map :db/ident schema/attribute-tx)))))

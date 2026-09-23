(ns clj-manifold3d.journal-document-test
  (:require [clj-manifold3d.journal.document :as doc]
            [clj-manifold3d.journal.schema :as schema]
            [clj-manifold3d.journal.viewer :as viewer]
            [clj-manifold3d.journal.namespace :as ns-form]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(deftest castle-examples-are-editable-linked-source
  (let [examples (into {} (map (juxt :namespace identity) (doc/examples)))
        castle (examples "journal.castle-architecture") night (examples "journal.castle-night")]
    (is (re-find #"journal.castle-architecture :as castle" (:ns-source night)))
    (is (re-find #"defn tower" (doc/source castle)))
    (is (re-find #"defn terrain-height" (doc/source night)))
    (is (re-find #"texture/image" (doc/source night)))
    (is (re-find #"m/with-spatial-index" (doc/source night)))
    (is (= "(assembly)" (:source (last (:blocks night)))))
    (doseq [document [castle night] block (:blocks document) :when (= "code" (:kind block))]
      (is (nil? (:warning (ns-form/parse-code (:source block)))))
      (is (not (ns-form/imports-in-body? (:source block))))
      (is (not (re-find #"Math/|BufferedImage|System/|with-open|\.asOriginal|manifold3d\." (:source block)))))))

(deftest namespace-files
  (is (= "workshop/my_part.clj" (doc/namespace-path "workshop.my-part")))
  (doseq [s [nil "" "../escape" "bad/name" ".x" "a..b" "a b" "0thing"]]
    (is (not (doc/valid-namespace? s))))
  (is (doc/valid-namespace? "workshop.part-2")))

(deftest deletion-undo-preserves-intervening-edits
  (let [a (assoc (doc/block "code" "a") :viewer "{:grid false}" :hidden true)
        b (doc/block "prose" "b") c (doc/block "code" "c")
        original {:blocks [a b c]}
        removed (doc/delete-panel original (:id b))
        changed (assoc-in removed [:blocks 0 :source] "my edit")
        restored (doc/undo-delete changed)]
    (is (= [(:id a) (:id b) (:id c)] (mapv :id (:blocks restored))))
    (is (= "my edit" (get-in restored [:blocks 0 :source])))
    (is (= "{:grid false}" (get-in restored [:blocks 0 :viewer])))
    (is (nil? (doc/undo-delete restored)))
    (let [removed (doc/delete-panel {:blocks [a]} (:id a))]
      (is (= [a] (:blocks (doc/undo-delete removed))))
      (is (= [a (assoc (first (:blocks removed)) :source "new note")]
             (:blocks (doc/undo-delete (assoc-in removed [:blocks 0 :source] "new note"))))))
    (let [removed (doc/delete-panel original (:id b))
          tx (doc/entity-tx (assoc removed :namespace "test.undo" :title "Undo"))
          roundtrip (doc/from-entity (assoc (last tx) :document/blocks (vec (butlast tx))))]
      (is (= (:blocks original) (:blocks (doc/undo-delete roundtrip)))))
    (is (= 10 (count (doc/deletion-history
                     (reduce (fn [d _] (doc/delete-panel d (:id (first (:blocks d))))) original (range 20))))))))

(deftest viewer-preferences-and-measurements
  (is (= {:grid true} (viewer/settings nil)))
  (is (= 5.0 (viewer/distance [[0 0 0] [0 3 4]])))
  (is (nil? (viewer/distance [[0 0 0]])))
  (is (viewer/valid-settings? "{:grid false :camera {:position [1 -2 3] :target [0 0 0] :near 0.01 :far 100}}"))
  (doseq [bad ["{:grid 1}" "{:camera {:position [0 0 0] :target [0 0 0] :near 1 :far 10}}"
               "{:camera {:position [1 2 3] :target [0 0 0] :near 1 :far 0}}" "not-edn"]]
    (is (not (viewer/valid-settings? bad)))))

(deftest readable-namespace-projection
  (let [s (doc/source {:namespace "test.part"
                       :ns-source (ns-form/declaration "test.part")
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

(deftest hidden-panels-still-belong-to-the-namespace
  (let [document {:namespace "test.hidden" :title "Hidden" :revision 0
                  :ns-source (ns-form/declaration "test.hidden")
                  :blocks [{:id "code" :kind "code" :source "(def x 42)" :hidden true}]}
        tx (doc/entity-tx document)
        entity (assoc (last tx) :document/blocks [(first tx)])]
    (is (= (:blocks document) (:blocks (doc/from-entity entity))))
    (is (re-find #"\(def x 42\)" (doc/source document)))))

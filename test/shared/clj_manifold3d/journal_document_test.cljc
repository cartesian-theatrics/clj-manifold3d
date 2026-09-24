(ns clj-manifold3d.journal-document-test
  (:require [clj-manifold3d.journal.document :as doc]
            [clojure.string :as str]
            [clj-manifold3d.journal.schema :as schema]
            [clj-manifold3d.journal.viewer :as viewer]
            [clj-manifold3d.journal.namespace :as ns-form]
            [clj-manifold3d.journal.example-format :as example-format]
            #?(:clj [clj-manifold3d.journal.example-source :as example-source])
            #?(:clj [clojure.pprint :as pprint])
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(deftest refresh-only-old-example-formatting
  (let [night (first (filter #(= "journal.castle-night" (:namespace %)) (doc/examples)))
        camera (last (:blocks night))
        bad (-> (:source camera)
                (str/replace "(def camera-track" "(def\n camera-track")
                (str/replace "(defn assembly" "(defn\n assembly"))
        old (assoc-in night [:blocks (dec (count (:blocks night))) :source] bad)]
    (is (= [{:id (:id camera) :source (:source camera)}] (example-format/updates [old])))
    (is (empty? (example-format/updates [night])))
    (is (empty? (example-format/updates [(update old :blocks pop)])) "Never restore deleted panels")
    (is (empty? (example-format/updates [(assoc old :namespace "my.castle")])))
    (doseq [source [(str bad "\n;; My note")
                    (str/replace bad "(range 41)" "(range 81)")
                    (str/replace bad "A wish over the castle" "A wish  over the castle")]]
      (is (empty? (example-format/updates
                   [(assoc-in old [:blocks (dec (count (:blocks old))) :source] source)]))
          "Keep code, comments and string contents exactly as edited"))))

(deftest formatting-refresh-preserves-reader-token-boundaries
  (let [document (fn [source] {:namespace "journal.castle-night"
                               :blocks [{:id "test" :kind "code" :source source}]})
        fresh "(def sample {:s \"a b; c\" :chars [\\space \\; \\\\ \\\"] :v [foo bar]})"]
    (with-redefs [doc/examples (fn [] [(document fresh)])]
      (is (= [{:id "test" :source fresh}]
             (example-format/updates [(document (str/replace fresh "(def sample" "(def\n sample"))])))
      (doseq [edited [(str/replace fresh "foo bar" "foobar")
                      (str/replace fresh "a b; c" "ab; c")
                      (str/replace fresh "\\space" "\\newline")]]
        (is (empty? (example-format/updates
                     [(document (str/replace edited "(def sample" "(def\n sample"))])))))))

(deftest refresh-scene-expressions-without-definitions-too
  (let [document (fn [source] {:namespace "journal.castle-night"
                               :blocks [{:id "scene" :kind "code" :source source}]})
        fresh "(m/scene {:nodes []})"]
    (with-redefs [doc/examples (fn [] [(document fresh)])]
      (is (= [{:id "scene" :source fresh}]
             (example-format/updates [(document "(m/scene\n {:nodes\n  []})")]))))))

#?(:clj
   (deftest upgrades-the-original-data-printer-camera-panel
     (let [old (with-redefs-fn
                 {#'example-source/code
                  (fn [form]
                    (str/trim (with-out-str
                                (binding [pprint/*print-right-margin* 88]
                                  (pprint/pprint form)))))}
                 example-source/castle-documents)
           updates (into {} (map (juxt :id :source)) (example-format/updates old))]
       (is (re-find #"\(defn\n assembly" (:source (last (:blocks (second old))))))
       (is (re-find #"\(def camera-track\n" (get updates "journal-castle-night-camera" "")))
       (is (re-find #"\(defn assembly\n" (get updates "journal-castle-night-camera" ""))))))

(deftest castle-examples-are-editable-linked-source
  (let [examples (into {} (map (juxt :namespace identity) (doc/examples)))
        castle (examples "journal.castle-architecture") night (examples "journal.castle-night")]
    (is (re-find #"journal.castle-architecture :as castle" (:ns-source night)))
    (is (re-find #"defn tower" (doc/source castle)))
    (is (re-find #"defn terrain-height" (doc/source night)))
    (is (re-find #"texture/image" (doc/source night)))
    (is (re-find #"m/with-spatial-index" (doc/source night)))
    (is (= '(assembly) (:form (last (:forms (ns-form/parse-code (:source (last (:blocks night)))))))))
    (is (= [8 9] (mapv #(count (filter (fn [b] (= "code" (:kind b))) (:blocks %))) [castle night])))
    (doseq [document [castle night] block (:blocks document) :when (= "code" (:kind block))]
      (is (nil? (:warning (ns-form/parse-code (:source block)))))
      (is (not (ns-form/imports-in-body? (:source block))))
      (is (not (re-find #"Math/|BufferedImage|System/|with-open|\.asOriginal|manifold3d\." (:source block)))))))

(deftest raptor-is-a-portable-staged-document
  (let [raptor (first (filter #(= "journal.raptor-3" (:namespace %)) (doc/examples)))
        blocks (filter #(= "code" (:kind %)) (:blocks raptor))]
    (is (doc/curated-namespaces (:namespace raptor)))
    (is (= 6 (count blocks)))
    (is (re-find #"defn tube" (doc/source raptor)))
    (is (re-find #"defn parts-scene" (doc/source raptor)))
    (is (re-find #"preview-parts engine fixture" (:source (last blocks))))
    (doseq [block blocks]
      (is (not (re-find #"Math/|System/|PointerScope|with-open|m/export-scene" (:source block)))))))

(deftest every-walkthrough-block-ends-in-a-preview
  (doseq [document (filter #(doc/curated-namespaces (:namespace %)) (doc/examples))
          block (:blocks document) :when (= "code" (:kind block))]
    (let [{:keys [forms warning]} (ns-form/parse-code (:source block))
          result (:form (last forms))]
      (is (nil? warning) (str (:id block) ": " warning))
      (is (re-matches #"[a-zA-Z0-9_-]+" (:id block)) "IDs also satisfy the server's document contract")
      (is (some? result) (:id block))
      (is (not (and (seq? result) (#{'def 'defn} (first result)))) (:id block)))))

(deftest generated-examples-use-code-formatting
  (let [flag (first (filter #(= "journal.flag-uv" (:namespace %)) (doc/examples)))
        bake (:source (first (filter #(= "journal-flag-uv-bake" (:id %)) (:blocks flag))))
        form (:form (first (:forms (ns-form/parse-code bake))))]
    (is (re-find #"\(def image\n  \(texture/bake flag" bake))
    (doseq [pair [":width 950" ":height 500" ":bounds [-2.375 -1.25 21.375 11.25]"
                 ":background [0.65 0.7 0.68 1]"]]
      (is (str/includes? bake pair) pair))
    (is (= '(def image (texture/bake flag :width 950 :height 500
                                   :bounds [-2.375 -1.25 21.375 11.25]
                                   :background [0.65 0.7 0.68 1])) form)))
  (doseq [document (filter #(doc/curated-namespaces (:namespace %)) (doc/examples))
          block (:blocks document) :when (= "code" (:kind block))]
    (is (not (re-find #"\(def\s*\n" (:source block))) (:id block))))

#?(:clj
   (deftest formatting-preserves-every-generated-form
     (let [format-code @#'example-source/code]
       (with-redefs-fn
         {#'example-source/code
          (fn [form]
            (let [source (format-code form)
                  {:keys [forms warning]} (ns-form/parse-code source)]
              (is (nil? warning) source)
              (is (= [form] (mapv :form forms)) source)
              source))}
         #(do (example-source/castle-documents) (example-source/flag-document))))))

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

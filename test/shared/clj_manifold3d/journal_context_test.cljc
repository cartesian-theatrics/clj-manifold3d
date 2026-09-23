(ns clj-manifold3d.journal-context-test
  (:require [clj-manifold3d.journal.context :as context]
            [clj-manifold3d.journal.generation :as gen]
            [clj-manifold3d.journal.document :as doc]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(def journal
  {:namespace "study.part" :title "Part" :revision 0
   :ns-source "(ns study.part (:require [study.parts :as p :refer [height]]))"
   :blocks [{:id "top" :kind "prose" :source "# Study"}
            {:id "defs" :kind "code" :source "(def width 2)"}
            {:id "heading" :kind "prose" :source "## Bracket"}
            {:id "prompt" :kind "prose" :source "Make this bigger"}
            {:id "code" :kind "code" :source "(p/bracket width height)" :hidden true}
            {:id "note" :kind "prose" :source "Keep this note"}
            {:id "other" :kind "prose" :source "## Other task"}
            {:id "secret" :kind "code" :source "(def secret \"NOT-SENT\")"}]})
(def parts
  {:namespace "study.parts"
   :blocks [{:id "lib" :kind "code"
             :source "(def height 3)\n(defn- helper [x] x)\n(defn bracket \"A bracket, in mm.\" ([w] (bracket w height)) ([w h] (helper [w h])))\n(def unused \"UNRELATED\")"}]})
(defn configured [config]
  (assoc-in journal [:blocks 3 :context-settings] (pr-str (merge context/defaults config))))

(deftest scopes-and-explicit-roles
  (is (= "target" (get (context/panel-roles journal "prompt") "code")) "Hidden is not excluded")
  (is (= #{"heading" "prompt" "code" "note"} (context/section-ids (:blocks journal) "prompt")))
  (let [document (configured {:scope "section" :roles {"note" "reference" "secret" "exclude"}})
        snapshot (context/build document "prompt" [document parts] {})]
    (is (= ["namespace-study_dpart" "heading" "prompt" "code" "note"] (mapv :id (:panels snapshot))))
    (is (= "reference" (:role (last (:panels snapshot)))))
    (is (not (re-find #"NOT-SENT" (pr-str snapshot))))
    (is (= #{"study.parts/bracket" "study.parts/height" "study.part/width"}
           (set (map :symbol (:dependencies snapshot)))))
    (is (every? #(= "signature" (:mode %)) (:dependencies snapshot)))
    (is (not (re-find #"helper|UNRELATED" (pr-str (:dependencies snapshot)))))
    (is (= "A bracket, in mm." (:doc (first (filter #(= "study.parts/bracket" (:symbol %)) (:dependencies snapshot)))))))
  (let [document (configured {:scope "selected" :roles {"code" "target" "note" "reference" "defs" "exclude"}})
        snapshot (context/build document "prompt" [document parts] {})]
    (is (= ["namespace-study_dpart" "prompt" "code" "note"] (mapv :id (:panels snapshot))))
    (is (not (some #(= "study.part/width" (:symbol %)) (:dependencies snapshot))) "Explicit exclusion also prevents automatic inclusion")))

(deftest expansion-and-namespace-resolution
  (let [document (configured {:scope "selected" :roles {"code" "target"} :expanded ["study.parts/bracket"]})
        snapshot (context/build document "prompt" [document parts] {})]
    (is (= #{"study.parts/bracket" "study.parts/height" "study.parts/helper" "study.part/width"}
           (set (map :symbol (:dependencies snapshot)))))
    (is (= "implementation" (:mode (first (filter #(= "study.parts/bracket" (:symbol %)) (:dependencies snapshot))))))
    (is (not (re-find #"UNRELATED" (pr-str snapshot)))))
  (let [document (-> (configured {:scope "selected" :roles {"code" "target"}})
                     (assoc-in [:blocks 4 :source] "(study.parts/bracket 2 3) (study.parts/helper 3)"))]
    (is (= ["study.parts/bracket"] (mapv :symbol (:dependencies (context/build document "prompt" [document parts] {})))))
    (is (empty? (:dependencies (context/build (assoc-in document [:blocks 3 :context-settings] (pr-str {:dependencies "none"}))
                                              "prompt" [document parts] {}))))))

(deftest instructions-and-role-enforcement
  (let [document (assoc (configured {:scope "selected" :roles {"code" "target" "note" "reference"}})
                        :instructions "Use cm here." :instructions-mode "extend")
        request (gen/request-input document "prompt" "request" [] [document parts] {:instructions "Default to mm."})
        snapshot (gen/snapshot request)]
    (is (= ["Default to mm." "Use cm here."] (mapv :text (:instructions snapshot))))
    (is (= ["Use cm here."] (mapv :text (:instructions (context/build (assoc document :instructions-mode "replace") "prompt" [document] {:instructions "Default to mm."})))))
    (doseq [[target kind before] [["note" "prose" "Keep"] ["secret" "code" "secret"] ["prompt" "prose" "Make"] ["lib" "code" "height"]]]
      (is (try (gen/output-plan request {:summary "bad" :edits [{:action "patch" :target target :kind kind :before before :after "x"}]}) false
               (catch #?(:clj Exception :cljs :default) _ true))))
    (is (= 1 (count (:edits (gen/output-plan request {:summary "ok" :edits [{:action "patch" :target "code" :kind "code" :before "width height" :after "width 4"}]})))))
    (let [tx (doc/entity-tx document) restored (doc/from-entity (assoc (last tx) :document/blocks (vec (butlast tx))))]
      (is (= (:instructions document) (:instructions restored)))
      (is (= (context/settings (get-in document [:blocks 3])) (context/settings (get-in restored [:blocks 3])))))))

(deftest parse-is-safe-and-tolerates-in-progress-code
  (is (:warning (context/parse-code "#=(throw (Exception. \"must not run\"))")))
  (is (= 1 (count (:forms (context/parse-code "(def size 2) (unfinished")))))
  (is (nil? (:warning (context/parse-code "#js [1 2]"))))
  (let [d (get-in (context/index-document {:namespace "constants" :blocks [{:id "c" :kind "code" :source "(def answer \"42\")"}]})
                  [:definitions "constants/answer"])]
    (is (= "" (:doc d)))
    (is (= "(def answer \"42\")" (:signature d))))
  (is (context/valid-settings? (pr-str {:scope "selected" :roles {"x" "reference"}})))
  (is (not (context/valid-settings? (pr-str {:roles {"x" "admin"}}))))
  (is (not (context/valid-settings? "#=(slurp \"secret\")"))))

(deftest local-bindings-and-quoted-data-are-not-dependencies
  (let [document (-> (configured {:scope "selected" :roles {"code" "target"}})
                     (assoc-in [:blocks 4 :source] "(fn [width a b] (+ width a b)) (let [{:keys [width]} {:width 3}] width) 'width \"width\""))]
    (is (empty? (:dependencies (context/build document "prompt" [document parts] {}))))))

(ns clj-manifold3d.journal-generation-test
  (:require [clj-manifold3d.journal.generation :as gen]
            [clj-manifold3d.journal.document :as doc]
            [clj-manifold3d.journal.namespace :as ns-form]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(def document {:namespace "test.ai" :title "AI" :revision 0
               :ns-source (ns-form/declaration "test.ai")
               :blocks [{:id "before" :kind "code" :source "(def radius 2)"}
                        {:id "prompt" :kind "prose" :source "Make a sphere"}
                        {:id "later" :kind "code" :source "(never-send-this)"}]})

(deftest model-choice-is-request-data
  (let [request (gen/request-input document "prompt" "request" [] [document] {:codex-model "provider/model-v2"})]
    (is (= "provider/model-v2" (:model request)))
    (is (= request (gen/from-entity (gen/entity request))))
    (is (= "actual-version" (:resolved-model (gen/from-entity (gen/entity (assoc request :resolved-model "actual-version")))))))
  (is (= "" (:model (gen/request-input document "prompt" "default"))))
  (doseq [model [nil "" "gpt-version" "provider/model:version"]] (is (gen/valid-model? model)))
  (doseq [model [42 {} "--model x" "invalid\nmodel" (apply str (repeat 129 "x"))]] (is (not (gen/valid-model? model)))))

(deftest bounded-prompt-context
  (let [request (gen/request-input document "prompt" "request")]
    (is (= "Make a sphere" (:prompt request)))
    (is (= "" (:context request)) "No duplicated preceding source can leak excluded panels")
    (is (not (re-find #"never-send-this" (:context request))))
    (is (= ["namespace-test_dai" "before" "prompt" "later"] (mapv :id (:panels (gen/snapshot request))))
        "The editable snapshot includes existing panels below the prompt")
    (is (= request (gen/from-entity (gen/entity request)))))
  (is (nil? (gen/request-input document "before" "request")))
  (is (nil? (gen/request-input document "absent" "request")))
  (is (not (gen/active? {:status "complete"})))
  (is (gen/active? {:status "running"})))

(deftest output-placement-and-replay
  (let [document (update document :blocks gen/insert-after "prompt"
                         [{:id "request" :kind "thinking" :source "Progress is not code"}])
        request (assoc (gen/request-input document "prompt" "request") :status "complete")
        panels (gen/output-plan request {:summary "Created a sphere"
                                        :edits [{:action "insert" :target nil :kind "prose" :before nil :after "A sphere"}
                                                {:action "insert" :target nil :kind "code" :before nil :after "(m/sphere radius)"}]})
        next (gen/apply-output document request panels)]
    (is (= ["before" "prompt" "request" "request-0" "request-1" "later"] (mapv :id (:blocks next))))
    (is (= ["request"] (:applied-requests next)))
    (is (nil? (gen/apply-output next request panels)) "Polling/reload never duplicates results")
    (is (nil? (gen/apply-output (assoc document :blocks []) request panels)) "Deleting an anchor prevents late insertion")
    (is (nil? (gen/apply-output document (assoc request :status "cancelled") panels)))
    (is (re-find #";; Progress is not code" (doc/source document)))
    (is (every? false? (map :hidden (filter :prompt-id (:blocks next)))))))

(deftest validate-structured-panels
  (doseq [bad [nil {} {:edits []} {:summary "Bad" :edits [{:action "insert" :kind "shell" :source "bad"}]}
               {:summary "Bad" :edits [{:action "insert" :kind "code" :source 42}]}
               {:summary "Bad" :edits [{:action "update" :target "unknown" :kind "code" :source "42"}]}
               {:summary "Bad" :edits [{:action "update" :target "prompt" :kind "prose" :source "Rewrite user"}]}
               {:summary "Bad" :edits [{:action "update" :target "before" :kind "prose" :source "Change type"}]}
               {:summary "Bad" :edits [{:action "update" :target "before" :kind "code" :source "1"}
                                       {:action "update" :target "before" :kind "code" :source "2"}]}]]
    (is (try (gen/output-plan (gen/request-input document "prompt" "request") bad) false
             (catch #?(:clj Exception :cljs :default) _ true)))))

(defn with-anchor [document id]
  (update document :blocks gen/insert-after "prompt" [{:id id :kind "thinking" :source "Thinking"}]))

(deftest namespace-creation-requires-explicit-permission
  (let [document (assoc-in document [:blocks 1 :context-settings] "{:allow-create-namespaces? true}")
        request (assoc (gen/request-input document "prompt" "request") :status "complete")
        output {:summary "Parts" :edits [{:action "create" :target "parts.bracket" :kind "prose" :before nil :after "# Bracket"}
                                         {:action "create" :target "parts.bracket" :kind "code" :before nil :after "(def size 3)"}]}
        [created] (gen/created-documents request output)]
    (is (= "parts.bracket" (:namespace created)))
    (is (= ["request-0" "request-1"] (mapv :id (:blocks created))))
    (is (= ["# Bracket" "(def size 3)"] (mapv :source (:blocks created))))
    (is (= "applied" (:generation-status (get-in (gen/apply-output (with-anchor document "request") request output) [:blocks 2]))))
    (is (= "conflict" (:generation-status (get-in (gen/apply-output (with-anchor document "request") request output [document created]) [:blocks 2]))))
    (doseq [target ["../escape" "test.ai" "clj-manifold3d.core" "clojure.core"]]
      (is (try (gen/output-plan request (assoc-in output [:edits 0 :target] target)) false
               (catch #?(:clj Exception :cljs :default) _ true))))
    (is (try (gen/output-plan (gen/request-input (assoc-in document [:blocks 1 :context-settings] "{}") "prompt" "no") output) false
             (catch #?(:clj Exception :cljs :default) _ true)))))

(deftest patch-diagnostics-identify-the-sequential-source
  (let [request (gen/request-input document "prompt" "request")
        edits [{:action "patch" :target "before" :kind "code" :before "radius 2" :after "radius 3"}
               {:action "patch" :target "before" :kind "code" :before "radius 2" :after "radius 4"}]
        data (try (gen/output-plan request {:summary "" :edits edits}) nil
                  (catch #?(:clj Exception :cljs :default) e (ex-data e)))]
    (is (= :patch-match (:type data)))
    (is (= :missing (:reason data)))
    (is (= 1 (:edit-index data)))
    (is (= "before" (:target data)))
    (is (= "(def radius 3)" (:source data))))
  (is (= :ambiguous (try (gen/patch-source "a a" {:before "a" :after "b"})
                         (catch #?(:clj Exception :cljs :default) e (:reason (ex-data e)))))))

(deftest revise-add-and-mix-with-stable-identities
  (let [original (update document :blocks #(mapv (fn [b] (assoc b :hidden true)) %))
        request (assoc (gen/request-input original "prompt" "request") :status "complete")
        plan {:summary "Updated the radius; added an example."
              :edits [{:action "patch" :target "before" :kind "code" :before "radius 2" :after "radius 3"}
                      {:action "insert" :target nil :kind "code" :before nil :after "(m/sphere radius)"}]}
        next (gen/apply-output (with-anchor original "request") request plan)
        blocks (into {} (map (juxt :id identity) (:blocks next)))]
    (is (= ["before" "prompt" "request" "request-1" "later"] (mapv :id (:blocks next))))
    (is (= "(def radius 3)" (:source (blocks "before"))))
    (is (true? (:hidden (blocks "before"))) "Visibility survives in-place edits")
    (is (= "prompt" (:prompt-id (blocks "before"))))
    (is (= "(never-send-this)" (:source (blocks "later"))) "Unrelated work is untouched")
    (is (= "applied" (:generation-status (blocks "request"))))
    (is (re-find #"Updated 1, added 1" (:generation-message (blocks "request"))))
    (is (nil? (gen/apply-output next request plan)))
    (let [revised (update next :blocks #(mapv (fn [b] (if (= "prompt" (:id b)) (assoc b :source "Make it bigger") b)) %))
          followup (gen/request-input revised "prompt" "again" [(assoc request :created 1)])]
      (is (= "Make a sphere" (:previous-prompt followup)))
      (is (= "Make it bigger" (:prompt followup)))
      (is (= "(def radius 3)" (:source (first (filter #(= "before" (:id %)) (:panels (gen/snapshot followup))))))))))

(deftest conflicts-are-atomic-and-persistent
  (let [request (assoc (gen/request-input document "prompt" "request") :status "complete")
        plan {:summary "Revise and add"
              :edits [{:action "patch" :target "before" :kind "code" :before "radius 2" :after "radius 3"}
                      {:action "insert" :target nil :kind "code" :before nil :after "(m/sphere radius)"}]}]
    (doseq [changed [(update document :blocks #(mapv (fn [b] (if (= "before" (:id b)) (assoc b :source "My manual edit") b)) %))
                     (update document :blocks #(vec (remove (fn [b] (= "before" (:id b))) %)))
                     (update document :blocks #(mapv (fn [b] (if (= "prompt" (:id b)) (assoc b :source "New prompt") b)) %))]]
      (let [next (gen/apply-output (with-anchor changed "request") request plan)
            anchor (first (filter #(= "request" (:id %)) (:blocks next)))]
        (is (= "conflict" (:generation-status anchor)))
        (is (= (:blocks changed) (vec (remove #(= "request" (:id %)) (:blocks next)))))
        (is (= ["request"] (:applied-requests next)))
        (is (nil? (gen/apply-output next request plan)))))))

(deftest no-op-and-new-topic-do-not-rewrite-existing-panels
  (let [request (assoc (gen/request-input document "prompt" "request") :status "complete")]
    (doseq [edits [[] [{:action "insert" :target nil :kind "prose" :before nil :after "An additional idea"}]]]
      (let [next (gen/apply-output (with-anchor document "request") request {:summary "Done" :edits edits})]
        (is (= (:blocks document) (filterv #(#{"before" "prompt" "later"} (:id %)) (:blocks next))))))))

(deftest literal-deltas-compose-without-rewriting-surroundings
  (let [source ";; keep exactly\n(def radius 2)\n(def color :red)\n;; unchanged tail 🚀\n"
        document (assoc-in document [:blocks 0 :source] source)
        request (assoc (gen/request-input document "prompt" "request") :status "complete")
        edits [{:action "patch" :target "before" :kind "code" :before "radius 2" :after "radius 3"}
               {:action "patch" :target "before" :kind "code" :before ":red" :after ":blue"}
               {:action "patch" :target "before" :kind "code" :before "radius 3" :after "radius 4"}
               {:action "insert" :target nil :kind "prose" :before nil :after "New panel"}]
        result (gen/apply-output (with-anchor document "request") request {:summary "Done" :edits edits})]
    (is (= ";; keep exactly\n(def radius 4)\n(def color :blue)\n;; unchanged tail 🚀\n"
           (:source (first (:blocks result)))))
    (is (= "New panel" (:source (first (filter #(= "request-3" (:id %)) (:blocks result))))))
    (is (= "" (gen/patch-source "only" {:before "only" :after ""})))
    (is (= "new" (gen/patch-source "" {:before "" :after "new"})))
    (is (= "a\nnew\nb" (gen/patch-source "a\nb" {:before "a\n" :after "a\nnew\n"})))
    (doseq [[source before] [["a a" "a"] ["aaa" "aa"] ["abc" "missing"] ["abc" ""]]]
      (is (try (gen/patch-source source {:before before :after "x"}) false
               (catch #?(:clj Exception :cljs :default) _ true))))
    (doseq [edit [{:action "patch" :target "unknown" :kind "code" :before "x" :after "y"}
                 {:action "patch" :target "prompt" :kind "prose" :before "Make" :after "Build"}
                 {:action "patch" :target "before" :kind "prose" :before "radius 2" :after "radius 3"}
                 {:action "patch" :target "before" :kind "code" :before "absent" :after "x"}]]
      (is (try (gen/output-plan request {:summary "" :edits [edit]}) false
               (catch #?(:clj Exception :cljs :default) _ true))))))

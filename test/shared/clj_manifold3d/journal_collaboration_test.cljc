(ns clj-manifold3d.journal-collaboration-test
  (:require [clj-manifold3d.journal.collaboration :as c]
            [clj-manifold3d.journal.document :as doc]
            [clj-manifold3d.journal.generation :as gen]
            [clj-manifold3d.journal.namespace :as ns-form]
            [clj-manifold3d.journal.stream :as stream]
            #?(:clj [clojure.test :refer [deftest is]] :cljs [cljs.test :refer-macros [deftest is]])))

(def document {:namespace "test.scratch" :title "Scratch" :ns-source (ns-form/declaration "test.scratch")
               :blocks [{:id "prompt" :kind "prose" :source "Create a cube"}
                        {:id "request" :kind "thinking" :source "Working"}
                        {:id "code" :kind "code" :source ";; keep\n(m/cube 1 1 1)"}
                        {:id "note" :kind "prose" :source "Other user's note"}]})
(def request (assoc (gen/request-input document "prompt" "request") :collaborative? true :status "running"))
(def patch {:action "patch" :target "code" :kind "code" :before "m/cube" :after "missing-fn"})

(deftest repairs-append-to-current-source
  (let [first-plan (gen/output-plan request {:summary "Try" :edits [patch]})
        first-doc (first (c/changes request c/empty-plan first-plan [document]))
        other-edit (assoc-in first-doc [:blocks 3 :source] "A concurrent note edit")
        repair (assoc patch :before "missing-fn" :after "m/cube")
        final-plan (gen/output-plan request {:summary "Fixed" :edits [patch repair]})
        result (first (c/changes request first-plan final-plan [other-edit]))]
    (is (= ";; keep\n(missing-fn 1 1 1)" (get-in first-doc [:blocks 2 :source])))
    (is (= ";; keep\n(m/cube 1 1 1)" (get-in result [:blocks 2 :source])))
    (is (= "A concurrent note edit" (get-in result [:blocks 3 :source])))
    (is (c/extends? (:edits first-plan) (:edits final-plan)))
    (is (not (c/extends? (:edits first-plan) [])))
    (is (not (c/extends? (:edits first-plan) [repair])))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (c/changes request first-plan final-plan [(assoc-in first-doc [:blocks 2 :source] "User changed owned code")])))))

(deftest new-panels-are-patchable-by-stable-id
  (let [insert {:action "insert" :target nil :kind "code" :before nil :after "(missing-fn 2 3 4)"}
        initial (gen/output-plan request {:summary "Add" :edits [insert]})
        id (:id (first (:edits initial)))
        plan (gen/output-plan request {:summary "Fix" :edits [insert (assoc patch :target id :before "missing-fn" :after "m/cube")]})
        before (first (c/changes request c/empty-plan initial [document]))
        after (first (c/changes request initial plan [before]))
        final (gen/apply-output document (assoc request :status "complete") plan)]
    (is (= id (:id (first (:edits plan)))))
    (is (= "(m/cube 2 3 4)" (:source (first (filter #(= id (:id %)) (:blocks after))))))
    (is (= "(m/cube 2 3 4)" (:source (first (filter #(= id (:id %)) (:blocks final))))))
    (is (= 5 (count (:blocks after))))
    (is ((c/locked-ids request plan) id))
    (is (empty? (c/locked-ids (assoc request :status "cancelled") plan)))))

(deftest created-namespace-panels-and-imports-can-be-repaired
  (let [r (assoc request :snapshot (pr-str (assoc (gen/snapshot request) :allow-create-namespaces? true)))
        create {:action "create" :target "test.part" :kind "code" :before nil :after "(def part (missing-fn 2 3 4))"}
        initial (gen/output-plan r {:summary "Add" :edits [create]})
        id (:id (first (:edits initial)))
        plan (gen/output-plan r {:summary "Fix" :edits [create (assoc patch :target id :before "missing-fn" :after "m/cube")
                                                           {:action "patch" :target (ns-form/header-id "test.part") :kind "namespace"
                                                            :before "[clj-manifold3d.core :as m]" :after "[clj-manifold3d.core :as m]\n            [clj-manifold3d.math :as math]"}]})
        result (first (gen/created-documents r plan))]
    (is (= id (get-in result [:blocks 0 :id])))
    (is (= "(def part (m/cube 2 3 4))" (get-in result [:blocks 0 :source])))
    (is (re-find #"math :as math" (:ns-source result)))))

(deftest merging-unrelated-local-edits-preserves-them
  (let [local (-> document (assoc-in [:blocks 3 :source] "Local note") (assoc-in [:blocks 2 :hidden] true))
        remote (-> document (assoc-in [:blocks 2 :source] "Agent code") (assoc :revision 3))
        merged (c/merge-remote document local remote #{"code"})]
    (is (= "Agent code" (get-in merged [:blocks 2 :source])))
    (is (= "Local note" (get-in merged [:blocks 3 :source])))
    (is (get-in merged [:blocks 2 :hidden]))
    (is (= 3 (:revision merged)))
    (is (not (c/conflicting-edits? document local remote #{"code"})))
    (is (c/conflicting-edits? document local (assoc-in remote [:blocks 3 :source] "Remote note") #{"code"}))))

(deftest merging-keeps-local-insertion-position
  (let [extra {:id "local-new" :kind "prose" :source "Added during repair"}
        local (update document :blocks #(vec (concat (take 2 %) [extra] (drop 2 %))))
        remote (assoc-in document [:blocks 2 :source] "Agent code")
        merged (c/merge-remote document local remote #{"code"})]
    (is (= ["prompt" "request" "local-new" "code" "note"] (mapv :id (:blocks merged))))))

(deftest scratchpad-retains-execution-order
  (let [d (update document :blocks into (mapv #(hash-map :id (str "code-" %) :kind "code" :source "42") (range 12)))
        r (gen/request-input d "prompt" "request")
        plan (gen/output-plan r {:summary "Add" :edits [{:action "insert" :target nil :kind "code" :before nil :after "(def added 1)"}]})
        ids (mapv :id (c/panels r plan))
        original (mapv :id (:panels (gen/snapshot r)))]
    (is (= (gen/insert-after (mapv #(hash-map :id %) original) "prompt" [{:id "request-0"}])
           (mapv #(hash-map :id %) ids)))))

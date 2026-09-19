(ns clj-manifold3d.journal-state-test
  (:require [cljs.test :as test :refer-macros [deftest is run-tests]]
            [clj-manifold3d.journal-document-test]
            [clj-manifold3d.journal-generation-test]
            [clj-manifold3d.journal.state :as state]
            [clj-manifold3d.journal.document :as doc]
            [clj-manifold3d.journal.schema :as schema]
            [datascript.core :as d]))

(deftest facts-drive-subscriptions
  (d/reset-conn! state/conn (d/empty-db schema/schema))
  (state/initialize! {:documents [(doc/new-document "test.part" "Part")]
                      :workspace {:vim false :active-pane "one"
                                  :panes [{:id "one" :document "test.part" :width 1}]}})
  (let [seen (atom [])
        unsubscribe (state/subscribe!
                     '[:find ?v . :where [?e :workspace/id "default"] [?e :workspace/vim? ?v]]
                     #(swap! seen conj %))]
    (is (= [false] @seen) "Scalar false is a result, not an absent subscription")
    (state/workspace! {:ui/save-status "Saving"})
    (is (= [false] @seen) "Unrelated transactions do not redraw consumers")
    (state/workspace! {:workspace/vim? true})
    (is (= [false true] @seen))
    (unsubscribe)
    (state/workspace! {:workspace/vim? false})
    (is (= [false true] @seen)))
  (state/split! "one")
  (is (= 2 (count (state/panes))))
  (is (= #{"test.part"} (set (map :document (:panes (state/workspace-data))))))
  (let [id (state/add-block! "test.part" "not-in-this-document" "code")]
    (state/source! id "(m/sphere 4)")
    (is (= "(m/sphere 4)" (:source (last (:blocks (state/document "test.part"))))))
    (state/move-block! "test.part" id -1)
    (is (= id (:id (second (:blocks (state/document "test.part"))))))
    (state/remove-block! "test.part" id)
    (is (= 2 (count (:blocks (state/document "test.part")))))))

(defmethod test/report [::test/default :end-run-tests] [result]
  (set! (.-exitCode js/process) (if (test/successful? result) 0 1)))

(deftest exact-atomic-code-splits
  (doseq [offset [0 4 9]]
    (d/reset-conn! state/conn (d/empty-db schema/schema))
    (let [document {:namespace "test.split" :title "Split" :revision 0
                    :blocks [{:id "code" :kind "code" :source "(a)\n\n(b)\n" :hidden false}
                             {:id "note" :kind "prose" :source "Keep this note" :hidden false}]}]
      (state/put-document! document)
      (state/transact! [{:result/id "code" :result/status "ready"}])
      (let [transactions (atom 0)]
        (d/listen! state/conn ::split (fn [_] (swap! transactions inc)))
        (let [id (state/split-block! "test.split" "code" offset)
              blocks (:blocks (state/document "test.split"))]
          (is (= 1 @transactions))
          (is (= ["code" id "note"] (mapv :id blocks)))
          (is (= "(a)\n\n(b)\n" (apply str (map :source (take 2 blocks)))))
          (is (= offset (count (:source (first blocks)))))
          (is (= "stale" (:result/status (state/pull '[*] [:result/id "code"])))))
        (d/unlisten! state/conn ::split)))
    (is (nil? (state/split-block! "test.split" "note" 2)))
    (is (nil? (state/split-block! "test.split" "missing" 0)))))
(defn main [] (run-tests 'clj-manifold3d.journal-document-test 'clj-manifold3d.journal-generation-test 'clj-manifold3d.journal-state-test))

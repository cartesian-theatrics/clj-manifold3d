(ns clj-manifold3d.journal-browser-store-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [clj-manifold3d.journal.browser-store :as store]
            [clj-manifold3d.journal.document :as doc]))

(deftest curated-seed-and-backup
  (let [snapshot (store/seed)]
    (is (= store/example-namespaces (set (map :namespace (:documents snapshot)))))
    (is (= "journal.castle-night" (get-in snapshot [:workspace :panes 0 :document])))
    (is (= snapshot (first (store/save-workspace snapshot (:workspace snapshot)))))
    (is (= (:documents snapshot)
           (:documents (store/read-backup (store/backup (:documents snapshot) (:workspace snapshot))))))
    (is (thrown? #?(:clj Exception :cljs js/Error) (store/read-backup {:version 999})))))

(deftest atomic-revisions-and-identities
  (let [initial (store/seed) a (first (:documents initial))
        b (assoc (doc/new-document "workshop.new" "New") :create-only true)
        [saved result] (store/save-documents initial [(assoc a :title "Edited") b])]
    (is (= [1 0] (mapv :revision result)))
    (is (= 4 (count (:documents saved))))
    (is (not-any? :create-only result))
    (is (thrown? #?(:clj Exception :cljs js/Error) (store/save-documents saved [a b])))
    (is (= 4 (count (:documents saved))) "Failed batches leave snapshots unchanged")
    (is (thrown? #?(:clj Exception :cljs js/Error) (store/save-documents saved [b])))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (store/save-documents initial [(assoc b :blocks (:blocks a))])))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (store/save-documents initial [a a])))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (store/save-workspace saved {:panes [{:id "p" :width 1 :document "absent"}] :active-pane "p"})))))

(ns clj-manifold3d.journal-state-test
  (:require [cljs.test :as test :refer-macros [deftest is run-tests]]
            [clj-manifold3d.journal-namespace-test]
            [clj-manifold3d.journal-verification-test]
            [clj-manifold3d.journal-collaboration-test]
            [clj-manifold3d.journal-document-test]
            [clj-manifold3d.journal-browser-store-test]
            [clj-manifold3d.journal-generation-test]
            [clj-manifold3d.journal-stream-test]
            [clj-manifold3d.journal-context-test]
            [clj-manifold3d.journal.state :as state]
            [clj-manifold3d.journal.document :as doc]
            [clj-manifold3d.journal.schema :as schema]
            [datascript.core :as d]))

(deftest viewer-presentation-is-reactive-per-pane-state
  (d/reset-conn! state/conn (d/empty-db schema/schema))
  (let [id (pr-str ["pane" "block"]) seen (atom [])
        unsubscribe (state/subscribe! '[:find ?mode . :in $ ?id :where [?d :display/id ?id] [?d :display/mode ?mode]]
                                      #(swap! seen conj %) id)]
    (state/transact! [{:display/id id :display/mode "inline" :display/message ""}])
    (state/transact! [{:display/id id :display/mode "pip"}])
    (is (= "pip" (:display/mode (state/pull '[*] [:display/id id]))))
    (is (= [nil "inline" "pip"] @seen))
    (unsubscribe)))

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
(deftest panel-visibility-and-deletion
  (d/reset-conn! state/conn (d/empty-db schema/schema))
  (state/initialize! {:documents [(doc/new-document "test.panels" "Panels")
                                  (doc/new-document "test.other" "Other")]
                      :workspace {:active-pane "one" :panes [{:id "one" :document "test.panels" :width 1}]}})
  (let [[prose code] (:blocks (state/document "test.panels"))
        id (:id code) seen (atom [])
        unsubscribe (state/subscribe!
                     '[:find ?hidden . :in $ ?id :where [?b :block/id ?id] [?b :block/hidden? ?hidden]]
                     #(swap! seen conj %) id)]
    (state/toggle-block! "test.panels" id)
    (is (:hidden (last (:blocks (state/document "test.panels")))))
    (state/toggle-block! "test.panels" id)
    (is (= [false true false] @seen))
    (unsubscribe)
    (is (nil? (state/remove-block! "test.other" id)) "Cannot delete another namespace's panel")
    (is (nil? (state/toggle-block! "test.other" id)))
    (state/transact! [{:result/id id :result/status "ready"}])
    (state/workspace! {:ui/active-block id})
    (is (= (:id prose) (state/remove-block! "test.panels" id)))
    (is (nil? (state/pull '[*] [:result/id id])) "Delete the evaluation result as well")
    (is (= (:id prose) (:ui/active-block (state/workspace))))
    (state/remove-block! "test.panels" (:id prose))
    (let [[empty-panel :as panels] (:blocks (state/document "test.panels"))]
      (is (= 1 (count panels)))
      (is (= "prose" (:kind empty-panel)))
      (is (= "" (:source empty-panel)))
      (is (not= (:id prose) (:id empty-panel)))
      (is (nil? (state/remove-block! "test.panels" (:id prose)))))))

(deftest deleting-thinking-prevents-late-output
  (d/reset-conn! state/conn (d/empty-db schema/schema))
  (let [document (doc/new-document "test.thinking" "Thinking")]
    (state/put-document! document)
    (let [request (state/begin-request! "test.thinking" (:id (first (:blocks document))))]
      (state/toggle-block! "test.thinking" (:id request))
      (is (:hidden (second (:blocks (state/document "test.thinking")))))
      (state/remove-block! "test.thinking" (:id request))
      (is (not (state/accept-request! (assoc request :status "complete" :output "[]")))))
    (is (= (:blocks document) (:blocks (state/document "test.thinking"))))))

(deftest revisions-update-entities-and-invalidate-results
  (d/reset-conn! state/conn (d/empty-db schema/schema))
  (let [document (doc/new-document "test.revise" "Revise")
        [prompt code] (:blocks document)]
    (state/put-document! document)
    (state/transact! [{:result/id (:id code) :result/status "ready"}])
    (let [request (state/begin-request! "test.revise" (:id prompt))
          response (assoc request :status "complete"
                          :output (js/JSON.stringify
                                   (clj->js {:summary "Changed the cube"
                                             :edits [{:action "patch" :target (:id code)
                                                      :kind "code" :before "2 2 2 true" :after "4 4 4"}]})))]
      (is (state/accept-request! response))
      (is (= 3 (count (:blocks (state/document "test.revise")))))
      (is (= "(m/cube 4 4 4)" (:block/source (state/pull '[*] [:block/id (:id code)]))))
      (is (= "stale" (:result/status (state/pull '[*] [:result/id (:id code)]))))
      (is (= "applied" (:block/generation-status (state/pull '[*] [:block/id (:id request)]))))
      (is (not (state/accept-request! response)))
      (let [saved (state/document "test.revise")]
        (d/reset-conn! state/conn (d/empty-db schema/schema))
        (state/put-document! saved)
        (is (= saved (state/document "test.revise")))))))

(deftest streaming-is-not-document-mutation
  (d/reset-conn! state/conn (d/empty-db schema/schema))
  (let [document (doc/new-document "test.stream" "Stream")]
    (state/put-document! document)
    (let [request (state/begin-request! "test.stream" (:id (first (:blocks document))))
          before (state/document "test.stream")
          pending (assoc request :status "running" :preview "[{\"id\":\"draft\",\"action\":\"insert\",\"kind\":\"code\",\"after\":\"(m/cu\"}]")]
      (state/accept-request! pending)
      (is (= before (state/document "test.stream")))
      (is (= "(m/cu" (:after (first (state/drafts "test.stream")))))
      (state/accept-request! (assoc pending :status "cancelled"))
      (is (empty? (state/drafts "test.stream")))
      (is (= before (state/document "test.stream"))))))

(deftest in-place-drafts-respect-current-source
  (d/reset-conn! state/conn (d/empty-db schema/schema))
  (let [document (doc/new-document "test.overlay" "Overlay")
        [prompt code] (:blocks document)]
    (state/put-document! document)
    (let [request (state/begin-request! "test.overlay" (:id prompt))
          pending (assoc request :status "running"
                         :preview (js/JSON.stringify
                                   (clj->js [{:action "patch" :target (:id code)
                                              :kind "code" :before "2 2 2" :after "3 3 3"}
                                             {:action "patch" :target (:id code)
                                              :kind "code" :before "true" :after "false"}])))]
      (state/accept-request! pending)
      (is (= "(m/cube 3 3 3 false)" (:source (get (state/in-place-drafts "test.overlay") (:id code)))))
      (is (= 2 (count (:patches (get (state/in-place-drafts "test.overlay") (:id code))))))
      (is (= (:source code) (:block/source (state/pull '[*] [:block/id (:id code)]))))
      (state/source! (:id code) "My edit")
      (is (= (:source code) (:block/source (state/pull '[*] [:block/id (:id code)]))) "A concurrent user cannot overwrite owned code")
      (state/source! (:id code) (:source code))
      (state/source! (:id prompt) "Changed prompt")
      (is (seq (state/in-place-drafts "test.overlay")) "A note edit does not roll the agent's scratchpad back")
      (state/source! (:id prompt) (:source prompt))
      (state/accept-request! (assoc pending :status "cancelled"))
      (is (empty? (state/in-place-drafts "test.overlay"))))))

(deftest completed-session-polls-preserve-subsequent-manual-edits
  (d/reset-conn! state/conn (d/empty-db schema/schema))
  (let [document (doc/new-document "test.takeover" "Takeover")
        [prompt code] (:blocks document)]
    (state/put-document! document)
    (state/accept-document! (assoc document :revision 0) #{})
    (let [request (assoc (state/begin-request! "test.takeover" (:id prompt))
                         :collaborative? true :status "running" :updated 1
                         :working-plan "{\"summary\":\"\",\"edits\":[]}")
          remote (assoc (state/document "test.takeover") :revision 1)
          finished (assoc request :status "cancelled" :updated 2 :documents [remote])]
      (state/accept-request! request)
      (state/accept-request! finished)
      (state/source! (:id code) "(m/sphere 5)")
      (state/accept-request! finished)
      (is (= "(m/sphere 5)" (:block/source (state/pull '[*] [:block/id (:id code)]))))
      (state/accept-request! (assoc request :documents [remote]))
      (is (nil? (state/owner (:id code))) "An out-of-order active response cannot reclaim a released lock")
      (let [sent (state/document "test.takeover")]
        (state/source! (:id code) "(m/sphere 6)")
        (state/accept-document! (assoc sent :revision 2) #{} sent)
        (is (= "(m/sphere 6)" (:block/source (state/pull '[*] [:block/id (:id code)])))
            "Acknowledging a save must preserve edits typed while it was in flight")))))

(deftest inspected-requests-freeze-model-selection
  (d/reset-conn! state/conn (d/empty-db schema/schema))
  (let [document (doc/new-document "test.models" "Models") prompt-id (:id (first (:blocks document)))]
    (state/put-document! document)
    (state/workspace! {:workspace/codex-model "model-a"})
    (let [prepared (state/request-input "test.models" prompt-id "request")]
      (is (= "model-a" (:model prepared)))
      (is (= "model-a" (:codex-model (state/workspace-data))))
      (state/workspace! {:workspace/codex-model "model-b"})
      (is (thrown? js/Error (state/begin-request! "test.models" prompt-id prepared)))
      (is (= "model-a" (:model prepared))))))

(deftest inspected-requests-freeze-selected-context
  (d/reset-conn! state/conn (d/empty-db schema/schema))
  (let [document (doc/new-document "test.inspect" "Inspect")
        [prompt code] (:blocks document)]
    (state/put-document! document)
    (state/workspace! {:workspace/instructions "Use mm."})
    (state/context-settings! (:id prompt) {:scope "selected" :roles {(:id code) "reference"} :dependencies "none"})
    (let [prepared (state/request-input "test.inspect" (:id prompt) "request")]
      (is (= "reference" (:role (first (filter #(= (:id code) (:id %)) (:panels (clj-manifold3d.journal.generation/snapshot prepared)))))))
      (state/source! (:id code) "(m/sphere 4)")
      (is (thrown? js/Error (state/begin-request! "test.inspect" (:id prompt) prepared))))
    (is (= 2 (count (:blocks (state/document "test.inspect")))))
    (let [prepared (state/request-input "test.inspect" (:id prompt) "next")]
      (is (= prepared (state/begin-request! "test.inspect" (:id prompt) prepared))))))

(deftest undo-deletion-restores-identity-without-restoring-stale-results
  (d/reset-conn! state/conn (d/empty-db schema/schema))
  (let [document (doc/new-document "test.undo" "Undo") [prompt code] (:blocks document)]
    (state/put-document! document)
    (state/transact! [{:result/id (:id code) :result/status "ready" :result/asset "blob:expired"}])
    (state/remove-block! "test.undo" (:id code))
    (state/source! (:id prompt) "A new note")
    (is (= (:id code) (state/undo-delete! "test.undo")))
    (is (= ["A new note" (:source code)] (mapv :source (:blocks (state/document "test.undo")))))
    (is (nil? (state/pull '[*] [:result/id (:id code)])))
    (let [request (state/begin-request! "test.undo" (:id prompt))]
      (state/remove-block! "test.undo" (:id request))
      (state/undo-delete! "test.undo")
      (is (not (state/accept-request! (assoc request :status "complete" :output "[]"))))
      (is (= 3 (count (:blocks (state/document "test.undo"))))))))

(deftest verified-results-hydrate-without-overwriting-manual-or-conflicting-results
  (d/reset-conn! state/conn (d/empty-db schema/schema))
  (let [document (doc/new-document "test.verified" "Verified")
        [prompt code] (:blocks document)
        encode #(js/JSON.stringify (clj->js %))]
    (state/put-document! document)
    (let [request (state/begin-request! "test.verified" (:id prompt))
          response (assoc request :status "complete"
                          :output (encode {:summary "Checked" :edits [{:action "patch" :target (:id code)
                                                                      :kind "code" :before "2 2 2" :after "3 3 3"}]}))]
      (state/accept-request! response)
      (let [tested (state/document "test.verified")
            run {:status "passed" :namespace "test.verified" :ns-source (:ns-source tested) :full? true
                 :sources [{:id (:id code) :source "(m/cube 3 3 3 true)"}]
                 :results [{:block (:id code) :kind "model" :description "volume 27" :output "checked\n"
                            :asset "/api/codex/requests/example/artifacts/example"}]}
            verified (assoc response :verified-results (encode {:status "passed" :runs [run]}))]
        (state/accept-verified! verified)
        (is (= "ready" (:result/status (state/pull '[*] [:result/id (:id code)]))))
        (is (= "checked\n" (:result/output (state/pull '[*] [:result/id (:id code)]))))
        (state/transact! [{:result/id (:id code) :result/description "Manual result"}])
        (state/accept-verified! verified)
        (is (= "Manual result" (:result/description (state/pull '[*] [:result/id (:id code)]))))
        (state/transact! [[:db/retractEntity [:result/id (:id code)]]])
        (state/source! (:id code) "(m/sphere 4)")
        (state/accept-verified! verified)
        (is (nil? (state/pull '[*] [:result/id (:id code)])))
        (state/source! (:id code) "(m/cube 3 3 3 true)")
        (state/transact! [{:block/id (:id request) :block/generation-status "conflict"}])
        (state/accept-verified! verified)
        (is (nil? (state/pull '[*] [:result/id (:id code)])))
        (state/transact! [{:block/id (:id request) :block/generation-status "applied"}])
        (state/accept-verified! verified)
        (is (= "volume 27" (:result/description (state/pull '[*] [:result/id (:id code)]))))))))

(deftest generated-namespaces-share-one-datascript-transaction
  (d/reset-conn! state/conn (d/empty-db schema/schema))
  (let [document (doc/new-document "test.create" "Create") [prompt code] (:blocks document)]
    (state/put-document! document)
    (state/context-settings! (:id prompt) {:allow-create-namespaces? true})
    (let [request (state/begin-request! "test.create" (:id prompt))
          output {:summary "Parts" :edits [{:action "create" :target "test.part" :kind "code" :before nil :after "(def part 42)"}
                                           {:action "patch" :target (:id code) :kind "code" :before "2 2 2" :after "3 3 3"}]}
          response (assoc request :status "complete" :output (js/JSON.stringify (clj->js output)))]
      (is (state/accept-request! response))
      (is (= "(def part 42)" (get-in (state/document "test.part") [:blocks 0 :source])))
      (is (= "(m/cube 3 3 3 true)" (:block/source (state/pull '[*] [:block/id (:id code)]))))
      (is (not (state/accept-request! response)))
      (is (= 2 (count (state/documents)))))))

(deftest import-replaces-panels-and-keeps-save-baseline
  (d/reset-conn! state/conn (d/empty-db schema/schema))
  (let [original (doc/new-document "test.backup" "Backup")
        changed (assoc original :blocks [(doc/block "code" "42")])]
    (state/put-document! original)
    (state/transact! [{:document/id "test.backup" :document/saved-content "baseline"}])
    (state/import-documents! [changed])
    (is (= (mapv :id (:blocks changed)) (mapv :id (:blocks (state/document "test.backup")))))
    (is (= "baseline" (:document/saved-content (state/pull '[*] [:document/id "test.backup"]))))
    (doseq [b (:blocks original)] (is (nil? (state/pull '[*] [:block/id (:id b)]))))))

(defn main [] (run-tests 'clj-manifold3d.journal-browser-store-test 'clj-manifold3d.journal-document-test 'clj-manifold3d.journal-generation-test 'clj-manifold3d.journal-stream-test 'clj-manifold3d.journal-context-test 'clj-manifold3d.journal-namespace-test 'clj-manifold3d.journal-verification-test 'clj-manifold3d.journal-collaboration-test 'clj-manifold3d.journal-state-test))

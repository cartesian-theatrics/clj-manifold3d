(ns clj-manifold3d.journal-namespace-test
  (:require [clj-manifold3d.journal.namespace :as ns-form]
            [clj-manifold3d.journal.document :as doc]
            [clj-manifold3d.journal.context :as context]
            [clj-manifold3d.journal.generation :as gen]
            [clj-manifold3d.journal.api-catalog :as api]
            [clojure.string :as str]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(deftest visible-headers-and-verbatim-migration
  (let [body ";; 🦆 keep this comment\r\n(require '[parts.base :as p]) ; keep inline\n\n(def part  (p/cube 2))\n"
        old {:namespace "study.test" :title "Study" :blocks [{:id "c" :kind "code" :source body :hidden true}]}
        migrated (ns-form/migrate old)]
    (is (= ";; 🦆 keep this comment\r\n ; keep inline\n\n(def part  (p/cube 2))\n" (get-in migrated [:blocks 0 :source])))
    (is (str/includes? (:ns-source migrated) "[parts.base :as p]"))
    (is (= (dissoc (first (:blocks old)) :source) (dissoc (first (:blocks migrated)) :source)))
    (is (= migrated (ns-form/migrate migrated)))
    (is (str/starts-with? (doc/source migrated) (:ns-source migrated))))
  (let [fresh (doc/new-document "study.fresh" "Fresh")
        tx (doc/entity-tx fresh) restored (doc/from-entity (assoc (last tx) :document/blocks (vec (butlast tx))))]
    (is (= "(ns study.fresh\n  (:require [clj-manifold3d.core :as m]))" (:ns-source fresh)))
    (is (= (:ns-source fresh) (:ns-source restored))))
  (is (not= (ns-form/header-id "a.b") (ns-form/header-id "a_db")))
  (doseq [d (doc/examples)]
    (is (nil? (ns-form/declaration-error (:namespace d) (:ns-source d))))
    (is (every? #(not (ns-form/imports-in-body? (:source %))) (filter #(= "code" (:kind %)) (:blocks d))))))

(deftest imports-are-explicit-and-confined-to-header
  (is (nil? (ns-form/declaration-error "a.b" "(ns a.b (:require [clj-manifold3d.math :refer [pi]]))")))
  (doseq [source ["" "(ns" "(ns wrong)" "(ns a.b) (def x 1)" "#=(throw (Exception.))"]]
    (is (string? (ns-form/declaration-error "a.b" source))))
  (doseq [source ["(require '[x :as y])" "(ns x)" "(do (clojure.core/require 'x))" "(in-ns 'x)"]]
    (is (ns-form/imports-in-body? source)))
  (is (not (ns-form/imports-in-body? "'(require 'x) (comment (require 'y)) (def text \"(ns x)\")")))
  (is (= {} (get-in (context/index-document {:namespace "x" :blocks []}) [:imports :aliases])))
  (let [source "(ns a.b\n  ;; Keep comment\n  (:require [x :as p])) ; tail"
        next (ns-form/add-requires "a.b" source ['[y :as q]])]
    (is (= "(ns a.b\n  ;; Keep comment\n  (:require [x :as p]\n            [y :as q])) ; tail" next))
    (is (= next (ns-form/add-requires "a.b" next ['[y :as q]]))))
  (is (= "(ns a.b\n  (:require [x :as p]))" (ns-form/add-requires "a.b" "(ns a.b)" ['[x :as p]])))
  (let [old {:namespace "study.undo" :title "Undo" :blocks [{:id "old" :kind "code" :source "(require '[p :as p])\np/x"}]}
        removed (ns-form/migrate (doc/delete-panel old "old"))
        restored (doc/undo-delete removed)]
    (is (not (str/includes? (:ns-source removed) "[p :as p]")))
    (is (str/includes? (:ns-source restored) "[p :as p]"))
    (is (= "\np/x" (get-in restored [:blocks 0 :source])))))

(deftest namespace-deltas-are-atomic-and-respect-context
  (let [document (assoc (doc/new-document "study.delta" "Delta") :blocks
                        [{:id "prompt" :kind "prose" :source "Use textures"}
                         {:id "request" :kind "thinking" :source ""}])
        header (ns-form/header-id "study.delta")
        request (assoc (gen/request-input document "prompt" "request") :status "complete")
        edit {:action "patch" :target header :kind "namespace" :before "[clj-manifold3d.core :as m]"
              :after "[clj-manifold3d.core :as m]\n            [clj-manifold3d.texture :as texture]"}
        plan {:summary "Imports and code" :edits [edit {:action "insert" :target nil :kind "code" :before nil :after "(texture/bake (m/cube 2 2 2))"}]}
        next (gen/apply-output document request plan)]
    (is (str/includes? (:ns-source next) "[clj-manifold3d.texture :as texture]"))
    (is (= ["prompt" "request" "request-1"] (mapv :id (:blocks next))))
    (is (= "namespace" (:kind (first (:panels (gen/snapshot request))))))
    (let [changed (assoc document :ns-source "(ns study.delta)")
          conflict (gen/apply-output changed request plan)]
      (is (= (:ns-source changed) (:ns-source conflict)))
      (is (= 2 (count (:blocks conflict))))
      (is (= "conflict" (get-in conflict [:blocks 1 :generation-status]))))
    (doseq [role ["reference" "exclude"]]
      (let [locked (assoc-in document [:blocks 0 :context-settings] (pr-str {:scope "selected" :roles {header role}}))]
        (is (try (gen/output-plan (gen/request-input locked "prompt" "request") plan) false
                 (catch #?(:clj Exception :cljs :default) _ true)))))
    (doseq [bad [(assoc edit :after "[clj-manifold3d.texture :as texture")
                 (assoc edit :action "insert" :target nil :before nil :after "(ns study.delta)")
                 {:action "insert" :target nil :before nil :kind "code" :after "(require '[x :as x])"}]]
      (is (try (gen/output-plan request {:summary "bad" :edits [bad]}) false
               (catch #?(:clj Exception :cljs :default) _ true))))))

(deftest read-only-library-discovery
  (let [indexes (into {} (for [[namespace source]
                               [["clj-manifold3d.core" "(ns clj-manifold3d.core (:require [clj-manifold3d.model :as native])) (def texture native/texture) (defn- secret [] nil) (defn export-model [x path] nil)"]
                                ["clj-manifold3d.model" "(defn texture \"Map a texture on a Model.\" [model image & options] model)"]
                                ["private.notes" "(def secret \"DO_NOT_SEND\")"]]]
                            [namespace (context/index-document {:namespace namespace :blocks [{:id namespace :kind "code" :source source}]})]))
        catalog (api/build indexes)
        search (api/call catalog "journal_api_search" {:query "textures"})
        result (api/call catalog "journal_api_read" {:symbol "clj-manifold3d.core/texture" :include_source true})]
    (is (= ["clj-manifold3d.core/texture"] (mapv :symbol (:matches search))))
    (is (= "clj-manifold3d.model/texture" (get-in result [:function :defined-by])))
    (is (str/includes? (get-in result [:function :signature]) "model image & options"))
    (is (str/includes? (get-in result [:function :source]) "Map a texture"))
    (is (nil? (get-in (api/call catalog "journal_api_read" {:symbol "clj-manifold3d.core/texture" :include_source false}) [:function :source])))
    (doseq [symbol ["private.notes/secret" "clj-manifold3d.core/secret" "clj-manifold3d.core/export-model" "../../etc/passwd"]]
      (is (:error (api/call catalog "journal_api_read" {:symbol symbol :include_source true}))))
    (is (seq (:examples (api/call catalog "journal_api_examples" {:topic "flag UV"}))))
    (is (not (str/includes? (pr-str catalog) "DO_NOT_SEND")))
    (doseq [[tool args] [["shell" {:query "ls"}] ["journal_api_search" {:query "x" :path "/etc"}]
                        ["journal_api_read" {:symbol "clj-manifold3d.core/texture" :include_source "true"}]]]
      (is (try (api/call catalog tool args) false (catch #?(:clj Exception :cljs :default) _ true))))))

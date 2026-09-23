(ns clj-manifold3d.journal-verification-test
  (:require [clj-manifold3d.journal.verification :as v]
            [clj-manifold3d.journal.document :as doc]
            [clj-manifold3d.journal.generation :as gen]
            [clojure.string :as str]
            #?(:clj [clojure.test :refer [deftest is]] :cljs [cljs.test :refer-macros [deftest is]])))

(def main-doc
  {:namespace "checks.main" :title "Checks" :ns-source "(ns checks.main (:require [checks.parts :as p]))"
   :blocks [{:id "prompt" :kind "prose" :source "Make it larger"
             :context-settings "{:scope \"selected\" :roles {\"code\" \"target\" \"excluded\" \"exclude\"}}"}
            {:id "code" :kind "code" :source "(p/part 2)"}
            {:id "excluded" :kind "code" :source "(def secret \"DO_NOT_EVALUATE\")"}]})
(def parts-doc
  {:namespace "checks.parts" :title "Parts" :ns-source "(ns checks.parts)"
   :blocks [{:id "parts" :kind "code" :source "(defn- helper [x] (* x x))\n(defn part [x] (helper x))\n(def secret \"UNRELATED\")"}]})

(deftest freeze-evaluates-only-authorized-context
  (let [documents [main-doc parts-doc (doc/new-document "private.other" "Private")]
        r (gen/request-input main-doc "prompt" "request" [] documents {})
        frozen (v/freeze r documents)
        plan {:summary "Larger" :edits [{:action "patch" :target "code" :kind "code" :before "2" :after "3"}]}
        bundle (v/candidate r frozen plan)
        text (pr-str bundle)]
    (is (= ["checks.main"] (:namespaces bundle)))
    (is (= ["checks.main" "checks.parts"] (mapv :namespace (:documents bundle))))
    (is (str/includes? text "(p/part 3)"))
    (is (str/includes? text "(defn- helper"))
    (is (not (str/includes? text "DO_NOT_EVALUATE")))
    (is (not (str/includes? text "UNRELATED")))
    (is (not (str/includes? text "private.other")))
    (is (= "(p/part 2)" (get-in main-doc [:blocks 1 :source])))
    (is (nil? (v/candidate r frozen {:summary "Note" :edits []}))))
  (let [document (assoc-in main-doc [:blocks 0 :context-settings]
                           "{:scope \"selected\" :roles {\"code\" \"target\"} :dependencies \"none\"}")
        r (gen/request-input document "prompt" "request" [] [document parts-doc] {})
        frozen (v/freeze r [document parts-doc])]
    (is (= [] (get-in frozen [:dependencies 0 :blocks])) "Unused requires are stubs, not a way to expose excluded definitions")))

(deftest only-exact-tested-sources-can-display-results
  (let [d (doc/new-document "checks.render" "Render") code (last (:blocks d))
        run {:namespace (:namespace d) :ns-source (:ns-source d) :status "passed" :full? true
             :sources [(select-keys code [:id :source])]}]
    (is (v/matching-run? d run))
    (is (not (v/matching-run? (assoc d :ns-source "(ns checks.render)") run)))
    (is (not (v/matching-run? (assoc-in d [:blocks 1 :source] "42") run)))
    (is (not (v/matching-run? (update d :blocks conj (doc/block "code" "42")) run)))
    (is (not (v/matching-run? d (assoc run :status "failed"))))
    (is (v/matching-run? (assoc-in d [:blocks 1 :hidden] true) run))))

(deftest diagnostics-dont-send-assets-or-source-snapshots
  (let [d (v/diagnostic {:status "failed" :runs [{:namespace "x" :status "failed" :message "bad function"
                                                 :sources [{:source "large implementation"}] :ns-source "(ns x)"
                                                 :results [{:block "c" :output "hello" :asset-base64 "BINARY" :polygons [[1 2]]}]}]})]
    (is (= "hello" (get-in d [:runs 0 :results 0 :output])))
    (is (= "bad function" (get-in d [:runs 0 :message])))
    (is (not (str/includes? (pr-str d) "BINARY")))
    (is (not (str/includes? (pr-str d) "large implementation")))))

(ns clj-manifold3d.journal-stream-test
  (:require [clj-manifold3d.journal.stream :as stream]
            [clj-manifold3d.journal.generation :as gen]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(def request (gen/request-input {:namespace "test.stream"
                                 :blocks [{:id "prompt" :kind "prose" :source "Revise"}
                                          {:id "code" :kind "code" :source ";; keep\n(old)\n(tail)"}]}
                                "prompt" "request"))
(def prefix "{\"summary\":\"Hi\",\"edits\":[{\"action\":\"patch\",\"target\":\"code\",\"kind\":\"code\",\"before\":\"old\",\"after\":\"")
(def insert-prefix "{\"edits\":[{\"action\":\"insert\",\"target\":null,\"kind\":\"prose\",\"before\":null,\"after\":\"")

(deftest complete-deltas-not-partial-patches
  (is (= [] (stream/preview request (str prefix "new"))))
  (is (= [] (stream/preview request (str prefix "new\""))) "Wait for the operation's closing brace")
  (let [edits (stream/preview request (str prefix "new\"}"))]
    (is (= [{:action "patch" :target "code" :kind "code" :before "old" :after "new"}] edits))
    (is (= ";; keep\n(new)\n(tail)" (get (gen/patched-sources request edits) "code"))))
  (is (= [] (stream/preview request "{\"edits\":[{\"action\":\"pat")))
  (doseq [target ["foreign" "prompt"]]
    (is (= [] (stream/preview request (str "{\"edits\":[{\"action\":\"patch\",\"target\":\"" target
                                               "\",\"kind\":\"code\",\"before\":\"old\",\"after\":\"new\"}"))))))

(deftest split-escapes-and-json-punctuation
  (doseq [[encoded expected] [["a\\" "a"] ["a\\n" "a\n"] ["\\\"hello\\\"" "\"hello\""]
                              ["\\u03" ""] ["\\u03bb" "λ"] ["{a} [b]" "{a} [b]"]
                              ["\\uD83D" ""] ["\\uD83D\\uDE" ""] ["\\uD83D\\uDE80" "🚀"]
                              ["\\\\n" "\\n"]]]
    (is (= expected (:after (first (stream/preview request (str insert-prefix encoded)))))))
  (is (= [] (stream/preview request (str insert-prefix "bad\\q"))))
  (is (= [] (stream/preview request (str insert-prefix "bad\n"))))
  (is (= "ready" (:after (first (stream/preview request (str insert-prefix "ready\"")))))
      "Closing an insert string must not temporarily hide its preview")
  (is (= [] (stream/preview request (str insert-prefix "ok\"}],}garbage")))))

(deftest every-character-boundary-is-safe
  (let [first-edit (str prefix "new\"}")
        second-edit ",{\"action\":\"patch\",\"target\":\"code\",\"kind\":\"code\",\"before\":\"new\",\"after\":\"newer\"}"
        text (str first-edit second-edit ",{\"action\":\"insert\",\"target\":null,\"kind\":\"prose\",\"before\":null,\"after\":\"Next\"}]}")]
    (doseq [i (range (inc (count text)))]
      (let [drafts (stream/preview request (subs text 0 i))
            source (get (gen/patched-sources request drafts) "code")]
        (is (<= (count drafts) 3))
        (is (= (cond (< i (count first-edit)) ";; keep\n(old)\n(tail)"
                     (< i (+ (count first-edit) (count second-edit))) ";; keep\n(new)\n(tail)"
                     :else ";; keep\n(newer)\n(tail)") source))))
    (is (= ["new" "newer" "Next"] (mapv :after (stream/preview request text))))
    (is (= "request-2" (:id (last (stream/preview request text)))))
    (is (= 1 (count (stream/preview request (str first-edit
                     ",{\"action\":\"patch\",\"target\":\"code\",\"kind\":\"code\",\"before\":\"missing\",\"after\":\"x\"}"))))
        "Invalid later operations do not erase earlier valid previews")))

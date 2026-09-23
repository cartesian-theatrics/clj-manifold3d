(ns clj-manifold3d.journal.example-format
  "Repair the old data-printer layout without replacing edited example code."
  (:require [clj-manifold3d.journal.document :as doc]))

(defn- tokens [source]
  ;; Ignore layout only. Keep strings, comments, character literals, token
  ;; boundaries and collection order intact; reading forms would lose some of
  ;; these distinctions (and would expand anonymous functions).
  (re-seq #"\"(?:\\[\s\S]|[^\"\\])*\"|;[^\r\n]*|\\(?:[A-Za-z0-9]+|[\s\S])|[()\[\]{}]|[^\s,()\[\]{}\";\\]+"
          source))

(defn updates
  "Source-only patches for old, otherwise unchanged bundled code panels.
  Never restores deleted panels or changes prose, panel settings or layout."
  [documents]
  (let [examples (into {} (map (juxt :namespace identity))
                       (filter #(doc/curated-namespaces (:namespace %)) (doc/examples)))]
    (vec
     (for [document documents
           :let [example (get examples (:namespace document))
                 blocks (into {} (map (juxt :id identity)) (:blocks example))]
           block (:blocks document)
           :let [source (:source block) fresh (:source (get blocks (:id block)))]
           :when (and (= "code" (:kind block)) fresh (not= source fresh)
                      (re-find #"(?m)^\(defn?\s*\n" source)
                      (= (tokens source) (tokens fresh)))]
       {:id (:id block) :source fresh}))))

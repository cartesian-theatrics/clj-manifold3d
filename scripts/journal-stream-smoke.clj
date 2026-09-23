;; Opt-in smoke test: makes one request using your signed-in Codex account.
;; clojure -Sdeps '{:deps {io.replikativ/datahike {:mvn/version "0.7.1616"}}}' -M scripts/journal-stream-smoke.clj
(require '[clj-manifold3d.journal.codex :as codex]
         '[clj-manifold3d.journal.codex-transport :as transport]
         '[clj-manifold3d.journal.stream :as stream]
         '[clj-manifold3d.journal.generation :as gen]
         '[clojure.java.io :as io]
         '[clojure.data.json :as json])

(let [dir (java.nio.file.Files/createTempDirectory "journal-stream-smoke-"
                                                (make-array java.nio.file.attribute.FileAttribute 0))
      request (gen/request-input
               {:namespace "smoke.stream"
                :ns-source "(ns smoke.stream (:require [clj-manifold3d.core :as m]))"
                :blocks [{:id "prompt" :kind "prose"
                          :source "In the existing part panel, change only width from 2 to 5 and color from red to blue. Preserve everything else byte-for-byte. Add one short prose panel explaining the changes. Use separate minimal patches for width and color."}
                         {:id "part" :kind "code"
                          :source ";; Keep this comment exactly.\n(def width 2)\n(def color [1 0 0 1])\n\n(-> (m/cube width 3 4) (m/color color))\n;; Keep this tail exactly.\n"}]}
               "prompt" (str (random-uuid)))
      process (.start (doto (ProcessBuilder. ^java.util.List (codex/command (io/file ".") dir))
                        (.directory (.toFile dir))
                        (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)))
      seen (atom [])
      timeout (future (Thread/sleep 90000) (.destroyForcibly process))]
  (try
    (let [text (transport/run!
                process dir (System/getenv "JOURNAL_CODEX_MODEL") (codex/prompt request)
                (json/read-str (slurp "resources/journal/codex-output.schema.json"))
                (fn [text]
                  (let [drafts (stream/preview request text)]
                    (when (seq drafts)
                      (let [lengths (mapv #(count (:after %)) drafts)]
                        (swap! seen conj lengths)
                        (println :draft-lengths lengths))))))
          plan (gen/output-plan request (json/read-str text :key-fn keyword))]
      (assert (> (count (distinct @seen)) 1) "Expected multiple growing previews")
      (let [patches (filter #(= "patch" (:action %)) (:edits plan))
            source (get (gen/patched-sources request (:edits plan)) "part")]
        (assert (<= 2 (count patches)) "Expected separate small patches, not a full-panel replacement")
        (assert (every? #(< (count (:before %)) 60) patches) "Patches should contain only local context")
        (assert (= ";; Keep this comment exactly.\n(def width 5)\n(def color [0 0 1 1])\n\n(-> (m/cube width 3 4) (m/color color))\n;; Keep this tail exactly.\n" source))
        (assert (some #(= "insert" (:action %)) (:edits plan)))
      (println :distinct-preview-sizes (count (distinct @seen)) :edits (:edits plan))))
    (finally
      (future-cancel timeout)
      (.destroyForcibly process)
      (.waitFor process)
      (.delete (.toFile dir)))))
(shutdown-agents)
(System/exit 0)

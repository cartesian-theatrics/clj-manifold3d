;; Opt-in: one real Codex request, using the signed-in account. No user documents
;; are read or changed. Saves only a test artifact under target/.
;; clojure -Sdeps '{:deps {io.replikativ/datahike {:mvn/version "0.7.1616"}}}' -M scripts/journal-api-smoke.clj
(require '[clj-manifold3d.journal.codex :as codex]
         '[clj-manifold3d.journal.codex-transport :as transport]
         '[clj-manifold3d.journal.api-catalog :as api]
         '[clj-manifold3d.journal.context :as context]
         '[clj-manifold3d.journal.document :as doc]
         '[clj-manifold3d.journal.generation :as gen]
         '[clj-manifold3d.journal.verification :as verification]
         '[clj-manifold3d.journal.evaluator :as evaluator]
         '[clj-manifold3d.journal.collaboration :as collaboration]
         '[clojure.java.io :as io]
         '[clojure.data.json :as json]
         '[clojure.string :as str])

(let [dir (java.nio.file.Files/createTempDirectory "journal-api-smoke-" (make-array java.nio.file.attribute.FileAttribute 0))
      document (assoc (doc/new-document "smoke.discovery" "Discovery") :blocks
                      [{:id "prompt" :kind "prose" :source "Create a simple scene demonstrating textures, lighting and animations"}
                       {:id "code" :kind "code" :source "(m/cube 2 3 4)"}])
      request (gen/request-input document "prompt" (str (random-uuid)))
      indexes (into {} (for [name ["core" "texture" "animation" "model" "mesh-io" "math"]
                            :let [namespace (str "clj-manifold3d." name)
                                  file (if (= name "math") "src/cljc/clj_manifold3d/math.cljc"
                                           (str "src/cljs/clj_manifold3d/" (str/replace name "-" "_") ".cljs"))]]
                        [namespace (context/index-document {:namespace namespace :blocks [{:id name :kind "code" :source (slurp file)}]})]))
      catalog (api/build indexes)
      lookups (atom [])
      checks (atom [])
      working (atom collaboration/empty-plan)
      frozen (verification/freeze request [document])
      process (.start (doto (ProcessBuilder. ^java.util.List (transport/command "codex"))
                        (.directory (.toFile dir)) (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)))
      timeout (future (Thread/sleep 180000) (.destroyForcibly process))]
  (try
    (let [text (transport/run! process dir (System/getenv "JOURNAL_CODEX_MODEL") (codex/prompt request)
                              (json/read-str (slurp "resources/journal/codex-output.schema.json"))
                              (fn [_])
                              (fn [text attempt]
                                (try
                                  (let [plan (gen/output-plan request (update (json/read-str text :key-fn keyword) :edits #(into (:edits @working) %)))
                                        _ (reset! working plan)
                                        report (evaluator/check! (:id request) (io/file ".")
                                                                 (verification/candidate request frozen plan) (constantly true))]
                                    (swap! checks conj (verification/diagnostic report))
                                    (println :evaluation (:status report))
                                    (when-not (= "passed" (:status report))
                                      (throw (ex-info "Runtime check failed" {:type :runtime-verification :report (verification/diagnostic report)}))))
                                  nil
                                  (catch Exception e
                                    (if (< attempt 2)
                                      (codex/scratch-repair-input (assoc request :working-plan (json/write-str @working)) e)
                                      (throw e)))))
                              {:tools api/tool-specs
                               :on-tool (fn [tool arguments]
                                          (assert (< (count @lookups) 32))
                                          (println :lookup tool arguments)
                                          (let [result (api/call catalog tool arguments)]
                                            (swap! lookups conj {:tool tool :arguments arguments :result result})
                                            {:success (nil? (:error result))
                                             :contentItems [{:type "inputText" :text (json/write-str result)}]}))})
          plan @working]
      (assert (seq @lookups) "Must discover APIs not referenced in the existing code")
      (assert (some #(= "code" (:kind %)) (:edits plan)) "Must produce code, not ask the user for documentation")
      (assert (some #(= "namespace" (:kind %)) (:edits plan)) "New imports must update the visible header")
      (assert (not (re-find #"(?i)please expand|expand the signatures" text)))
      (assert (= "passed" (:status (last @checks))) "Generated code must execute and export successfully")
      (io/make-parents "target/journal-api-smoke.json")
      (spit "target/journal-api-smoke.json" (json/write-str {:request request :document document :plan plan :lookups @lookups :checks @checks}))
      (println :passed :lookups (count @lookups) :edits (count (:edits plan)) :summary (:summary plan)))
    (finally (future-cancel timeout) (.destroyForcibly process) (.waitFor process) (.delete (.toFile dir)))))
(shutdown-agents)
(System/exit 0)

(ns clj-manifold3d.journal.evaluator
  "Disposable SCI/WASM checks. Only OS process handles live outside Datahike."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.util.concurrent TimeUnit]
           [java.lang ProcessBuilder$Redirect ProcessHandle]))

(defonce processes (atom {}))
(defn kill! [id]
  (when-let [^Process p (get @processes id)]
    (doseq [child (iterator-seq (.iterator (.descendants p)))] (.destroyForcibly ^ProcessHandle child))
    (.destroyForcibly p)))

(defn check! [id root bundle active?]
  (let [command [(or (System/getenv "JOURNAL_NODE_BIN") "node")
                 (str (io/file root "scripts/journal-verify.cjs"))]
        process (try (.start (doto (ProcessBuilder. ^java.util.List command)
                               (.directory (io/file root)) (.redirectError ProcessBuilder$Redirect/DISCARD)))
                     (catch Exception e (throw (ex-info "Cannot start the journal verifier. Put Node on PATH or set JOURNAL_NODE_BIN; install Playwright Chromium." {:type :verification-unavailable} e))))]
    (swap! processes assoc id process)
    (try
      (when-not (active?) (throw (ex-info "Request stopped." {})))
      (with-open [w (io/writer (.getOutputStream process))] (.write w (json/write-str bundle)))
      ;; Drain concurrently so a bounded result cannot fill the OS pipe.
      (let [output (future (with-open [s (.getInputStream process)]
                             (String. (.readNBytes s 32000001) java.nio.charset.StandardCharsets/UTF_8)))]
        (try
          (when-not (.waitFor process 60 TimeUnit/SECONDS)
            (throw (ex-info "Journal verification exceeded its 60-second budget." {:type :verification-timeout})))
          (let [text @output]
            (when (> (count text) 32000000) (throw (ex-info "Verification output exceeded its budget." {:type :verification-timeout})))
            (let [report (json/read-str text :key-fn keyword)]
              (when (= "unavailable" (:status report))
                (throw (ex-info (:message report) {:type :verification-unavailable})))
              report))
          (finally (future-cancel output))))
      (finally (kill! id) (swap! processes dissoc id)))))

(ns deploy
  "Build the library-only release; publishing is handled by the tested CI workflow."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn build-jar
  ([] (build-jar (str/trim (slurp "version.txt"))))
  ([version]
   (when-not (= version (str/trim (slurp "version.txt")))
     (throw (ex-info "Update version.txt and pom.xml before building" {:version version})))
   (let [{:keys [exit out err]} (shell/sh "python3" "scripts/build-release.py")]
     (print out)
     (when-not (zero? exit)
       (throw (ex-info "Release packaging failed" {:exit exit :stderr err})))
     (str "target/clj-manifold3d-" version ".jar"))))

(defn -main [& _]
  (build-jar)
  (println "Publish with manifold/.github/workflows/clj-library-release.yml after CI validation."))

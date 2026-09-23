(ns clj-manifold3d.journal.projection
  (:require [clj-manifold3d.journal.document :as doc]
            [clojure.java.io :as io])
  (:import [java.nio.file Files StandardCopyOption]))

(defn materialize! [data-root document]
  (let [file (io/file data-root "documents" (doc/namespace-path (:namespace document)))]
    (io/make-parents file)
    (let [tmp (Files/createTempFile (.toPath (.getParentFile file)) ".journal-" ".clj"
                                   (make-array java.nio.file.attribute.FileAttribute 0))]
      (spit (.toFile tmp) (doc/source document))
      (Files/move tmp (.toPath file) (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))))
  document)

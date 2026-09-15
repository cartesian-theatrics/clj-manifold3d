(ns clj-manifold3d.animation-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [clj-manifold3d.animation :as animation])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]))

(defn- glb-json [path]
  (let [bytes (Files/readAllBytes (.toPath (java.io.File. path)))
        buffer (doto (ByteBuffer/wrap bytes)
                 (.order ByteOrder/LITTLE_ENDIAN))
        magic (.getInt buffer)
        version (.getInt buffer)
        total-length (.getInt buffer)
        json-length (.getInt buffer)
        chunk-type (.getInt buffer)
        json-bytes (byte-array json-length)]
    (.get buffer json-bytes)
    {:magic magic
     :version version
     :total-length total-length
     :chunk-type chunk-type
     :json (json/read-str (String. json-bytes StandardCharsets/UTF_8))}))

(deftest writes-pivot-animation-glb
  (let [file (java.io.File/createTempFile "clj-manifold3d-pivot-" ".glb")
        path (.getPath file)]
    (.deleteOnExit file)
    (animation/write-pivot-arm-glb path)
    (let [{:keys [magic version total-length chunk-type json]} (glb-json path)]
      (is (= 0x46546c67 magic))
      (is (= 2 version))
      (is (= total-length (.length file)))
      (is (= 0x4e4f534a chunk-type))
      (is (= "2.0" (get-in json ["asset" "version"])))
      (is (= "Pivot" (get-in json ["animations" 0 "name"])))
      (is (= "rotation" (get-in json ["animations" 0 "channels" 0 "target" "path"])))
      (is (= [2] (get-in json ["nodes" 1 "children"])))
      (is (= [0 1] (get-in json ["scenes" 0 "nodes"]))))))

(deftest rejects-invalid-scenes
  (is (thrown? Exception (animation/scene {})))
  (is (thrown? Exception
               (animation/scene {:parts [{:id :part}]})))
  (is (thrown? Exception
               (animation/scene
                {:nodes [{:id :pivot :children [:missing]}]})))
  (is (thrown? Exception
               (animation/scene
                {:nodes [{:id :pivot}]
                 :animations [{:channels [{:node :pivot
                                           :path :rotation
                                           :track []}]}]})))
  (is (thrown? Exception
               (animation/scene
                {:nodes [{:id :pivot}]
                 :animations [{:node :pivot :track []}]}))))

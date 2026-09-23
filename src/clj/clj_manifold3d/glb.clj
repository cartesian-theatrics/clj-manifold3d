(ns clj-manifold3d.glb
  "Binary glTF documents. Edits retain original buffers and unmodified fields."
  (:refer-clojure :exclude [accessor])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clj-manifold3d.glb-assets :as assets])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files]
           [java.nio.charset StandardCharsets]
           [java.util Arrays]))

(defn document? [x] (= ::document (:model/type x)))
(defn document [gltf binary] {:model/type ::document :gltf gltf :binary binary})
(defn- fail [message data] (throw (ex-info message data)))
(defn align4 [n] (+ n (mod (- n) 4)))
(defn concat-bytes [a b]
  (let [out (byte-array (+ (alength ^bytes a) (alength ^bytes b)))]
    (System/arraycopy a 0 out 0 (alength ^bytes a))
    (System/arraycopy b 0 out (alength ^bytes a) (alength ^bytes b)) out))
(defn pad [data fill]
  (let [n (alength ^bytes data) out (Arrays/copyOf ^bytes data (int (align4 n)))]
    (Arrays/fill out n (alength out) (byte fill)) out))

(defn read-glb
  "Read a GLB filename or bytes. Embedded, single-buffer glTF 2.0 only."
  [source]
  (let [data (if (bytes? source) source (Files/readAllBytes (.toPath (io/file source))))
        n (alength ^bytes data)]
    (when (< n 20) (fail "Truncated GLB header" {}))
    (let [b (doto (ByteBuffer/wrap data) (.order ByteOrder/LITTLE_ENDIAN))]
      (when-not (and (= 0x46546c67 (.getInt b)) (= 2 (.getInt b)) (= n (.getInt b)))
        (fail "Invalid GLB 2.0 header or length" {}))
      (loop [doc nil binary nil]
        (if (.hasRemaining b)
          (do
            (when (< (.remaining b) 8) (fail "Truncated GLB chunk header" {}))
            (let [length (.getInt b) kind (.getInt b)]
              (when (or (neg? length) (not (zero? (mod length 4))) (> length (.remaining b)))
                (fail "Invalid GLB chunk length" {:length length}))
              (let [chunk (byte-array length)]
                (.get b chunk)
                (case kind
                  0x4e4f534a (do (when doc (fail "Duplicate JSON chunk" {}))
                                 (recur (json/read-str (String. chunk StandardCharsets/UTF_8)) binary))
                  0x004e4942 (do (when (or (nil? doc) binary) (fail "Unexpected BIN chunk" {}))
                                 (recur doc chunk))
                  (fail "Unsupported GLB chunk" {:type kind})))))
          (let [buffers (get doc "buffers" []) binary (or binary (byte-array 0))]
            (when-not (and (= "2.0" (get-in doc ["asset" "version"]))
                           (<= (count buffers) 1)
                           (not-any? #(contains? % "uri") buffers)
                           (<= (get (first buffers) "byteLength" 0) (alength ^bytes binary))
                           (not-any? #(contains? % "uri") (get doc "images" [])))
              (fail "Expected glTF 2.0 with embedded buffers and images" {}))
            (document doc binary)))))))

(defn write-glb
  "Write a document, or return bytes when filename is nil."
  [{:keys [gltf binary]} filename]
  (let [binary (pad binary 0)
        bin-length (alength ^bytes binary)
        gltf (cond-> (into {} (remove (fn [[_ v]] (and (vector? v) (empty? v))) gltf))
               (pos? bin-length) (assoc "buffers" [(assoc (first (get gltf "buffers")) "byteLength" bin-length)])
               (zero? bin-length) (dissoc "buffers"))
        j (pad (.getBytes (json/write-str gltf) StandardCharsets/UTF_8) 32)
        size (+ 20 (alength ^bytes j) (if (pos? bin-length) (+ 8 bin-length) 0))
        b (doto (ByteBuffer/allocate size) (.order ByteOrder/LITTLE_ENDIAN)
            (.putInt 0x46546c67) (.putInt 2) (.putInt size)
            (.putInt (alength ^bytes j)) (.putInt 0x4e4f534a) (.put ^bytes j))
        _ (when (pos? bin-length) (.putInt b bin-length) (.putInt b 0x004e4942) (.put b ^bytes binary))
        data (.array b)]
    (if filename (do (with-open [out (io/output-stream filename)] (.write out data)) filename) data)))

(def ^:private widths {"SCALAR" 1 "VEC2" 2 "VEC3" 3 "VEC4" 4 "MAT2" 4 "MAT3" 9 "MAT4" 16})
(def ^:private sizes {5120 1 5121 1 5122 2 5123 2 5125 4 5126 4})
(defn accessor
  "Decode an accessor into vectors (SCALAR also returns one-element vectors).
  Supports interleaving, normalized integer attributes, and sparse accessors."
  [{:keys [gltf binary] :as doc} index]
  (let [a (get-in gltf ["accessors" index]) type (get a "componentType")
        size (sizes type) width (widths (get a "type")) n (get a "count")
        matrix? (.startsWith ^String (get a "type" "") "MAT")
        dim (when (and matrix? width) (int (Math/sqrt (double width))))
        column-size (when (and dim size) (align4 (* dim size)))
        element-size (if column-size (* dim column-size) (* (or width 0) (or size 0)))]
    (when-not (and size width (integer? n) (<= 0 n)) (fail "Invalid accessor" {:index index}))
    (letfn [(decode [view-id byte-offset count component components stride offsets normalized?]
              (let [view (get-in gltf ["bufferViews" view-id])
                    start (+ (get view "byteOffset" 0) byte-offset)
                    b (doto (ByteBuffer/wrap binary) (.order ByteOrder/LITTLE_ENDIAN))
                    end (+ start (if (zero? count) 0 (+ (* (dec count) stride) (last offsets) (sizes component))))]
                (when (or (nil? view) (not= 0 (get view "buffer" 0)) (neg? byte-offset)
                          (neg? start) (< stride (+ (last offsets) (sizes component)))
                          (> end (+ (get view "byteOffset" 0) (get view "byteLength" 0)))
                          (> end (alength ^bytes binary))) (fail "Accessor exceeds buffer view" {:index index}))
                (mapv (fn [i]
                        (mapv (fn [offset]
                                (let [p (int (+ start (* i stride) offset))
                                      v (case component
                                          5120 (.get b p) 5121 (bit-and 255 (.get b p))
                                          5122 (.getShort b p) 5123 (bit-and 65535 (.getShort b p))
                                          5125 (Integer/toUnsignedLong (.getInt b p)) 5126 (.getFloat b p))]
                                  (if normalized?
                                    (case component 5120 (max -1.0 (/ v 127.0)) 5121 (/ v 255.0)
                                          5122 (max -1.0 (/ v 32767.0)) 5123 (/ v 65535.0) v)
                                    v))) offsets)) (range count))))]
      (let [offsets (mapv #(if matrix? (+ (* (quot % dim) column-size) (* (mod % dim) size)) (* % size)) (range width))
            values (if-let [v (get a "bufferView")]
                     (decode v (get a "byteOffset" 0) n type width
                             (get-in gltf ["bufferViews" v "byteStride"] element-size) offsets (get a "normalized" false))
                     (vec (repeat n (vec (repeat width 0)))))]
        (if-let [s (get a "sparse")]
          (let [c (get s "count") i (get s "indices") v (get s "values") it (get i "componentType")
                _ (when-not (and (integer? c) (<= 0 c n) (#{5121 5123 5125} it))
                    (fail "Invalid sparse accessor" {:index index}))
                ids (mapv first (decode (get i "bufferView") (get i "byteOffset" 0) c it 1 (sizes it) [0] false))
                rows (decode (get v "bufferView") (get v "byteOffset" 0) c type width element-size offsets (get a "normalized" false))]
            (when-not (and (every? #(< -1 % n) ids) (or (< c 2) (apply < ids))) (fail "Invalid sparse indices" {:index index}))
            (reduce (fn [out [i row]] (assoc out i row)) values (map vector ids rows))) values)))))

(defn append-document
  "Append embedded assets while retaining all core glTF references."
  [a b]
  (let [binary (pad (:binary a) 0)]
    (document (assets/append-gltf (:gltf a) (:gltf b) (alength ^bytes binary))
              (concat-bytes binary (:binary b)))))

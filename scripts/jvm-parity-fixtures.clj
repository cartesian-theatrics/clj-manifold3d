(require '[clj-manifold3d.core :as m]
         '[clojure.data.json :as json]
         '[clojure.java.io :as io])
(load-file "test/shared/clj_manifold3d/parity_models.cljc")

(defn snapshot [shape]
  (let [mesh (m/get-mesh-gl shape) width (.numProp mesh)
        rows (mapv vec (partition width (.toFloatArray (.vertProperties mesh))))
        indices (vec (.toIntArray (.triVerts mesh)))
        uv-area (if (< width 5) nil
                  (reduce + (for [[a b c] (partition 3 indices)
                                  :let [[_ _ _ au av] (rows a)
                                        [_ _ _ bu bv] (rows b)
                                        [_ _ _ cu cv] (rows c)]]
                              (/ (Math/abs (- (* (- bu au) (- cv av)) (* (- bv av) (- cu au)))) 2))))]
    {:properties (m/get-properties shape) :genus (.genus shape)
     :triangles (.numTri shape) :property-width width :uv-area uv-area
     :min (reduce #(mapv min %1 %2) (map #(subvec % 0 3) rows))
     :max (reduce #(mapv max %1 %2) (map #(subvec % 0 3) rows))}))

(def directory (io/file "public/fixtures"))
(.mkdirs directory)
(spit (io/file directory "parity.json")
      (json/write-str (into {} (map (fn [[label shape]] [label (snapshot shape)]))
                           (clj-manifold3d.parity-models/models))))

(doseq [[name type samples]
        [["depth8.png" java.awt.image.BufferedImage/TYPE_BYTE_GRAY
          [[0 19 41] [51 127 180] [193 211 255]]]
         ["depth16.png" java.awt.image.BufferedImage/TYPE_USHORT_GRAY
          [[0 4001 8001] [12345 31234 45678] [56789 60001 65535]]]]]
  (let [image (java.awt.image.BufferedImage. 3 3 type) raster (.getRaster image)]
    (doseq [y (range 3) x (range 3)] (.setSample raster x y 0 (int (get-in samples [y x]))))
    (javax.imageio.ImageIO/write image "png" (io/file directory name))))
(io/copy (io/file "resources/fonts/Cinzel-Regular.ttf") (io/file directory "font.ttf"))
(println "Generated JVM geometry references, depth images, and font fixture")

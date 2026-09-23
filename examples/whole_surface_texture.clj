(ns whole-surface-texture
  "Whole-solid textures, including a differently textured boolean cavity."
  (:require [clj-manifold3d.core :as m])
  (:import [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream]
           [javax.imageio ImageIO]))

(defn checker-image
  "Generate PNG bytes for a checker; no external image file needed."
  [light dark]
  (let [image (BufferedImage. 128 128 BufferedImage/TYPE_INT_ARGB)]
    (doseq [x (range 128) y (range 128)]
      (.setRGB image x y (unchecked-int (if (even? (+ (quot x 64) (quot y 64))) light dark))))
    (with-open [out (ByteArrayOutputStream.)]
      (ImageIO/write image "png" out)
      (.toByteArray out))))

(defn build []
  (let [tiles (checker-image 0xff185fcb 0xfff7f7e8)
        inside (checker-image 0xffdc4a18 0xffffd03f)
        sphere (-> (m/sphere 5 64) (m/texture-all tiles :size [2 2]))
        torus (-> (m/revolve (m/translate (m/circle 1.5 32) [3.5 0]) 64)
                  (m/texture-all tiles :size [2 2])
                  (m/translate [13 0 0]))
        cavity (-> (m/cube 9 9 9 true)
                   (m/texture-all tiles :size [2 2])
                   (m/difference (-> (m/sphere 5 48)
                                     (m/translate [0 -3 3])
                                     (m/texture-all inside :size [1.5 1.5])))
                   (m/translate [-13 0 0]))]
    (m/union sphere torus cavity)))

(comment
  (m/export-model (build) "target/whole-surface-texture.glb"))

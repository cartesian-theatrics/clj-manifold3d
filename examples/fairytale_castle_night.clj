(ns fairytale-castle-night
  "Ten-second nighttime tableau. Geometry, stone UVs, lights, camera and all
  particle motion are authored in Clojure and embedded in an animated GLB.
  The optional web preview adds renderer-only bloom and water reflections."
  (:require [clj-manifold3d.core :as m]
            [clj-manifold3d.texture :as texture]
            [clj-manifold3d.math :as math]
            [fairytale-castle :as castle]
            [clojure.java.io :as io]))

(def duration 10.0)
(defn noise [n] (let [v (* 43758.5453 (math/sin (* 12.9898 n)))] (- v (math/floor v))))
(defn stone-image
  "Seamless staggered limestone courses, with per-block color and fine grain.
  PNG is generated deterministically; all faces use native repeating box UVs."
  []
  (texture/image 512 512
    (fn [x y]
      (let [row (quot y 64) xx (mod (+ x (if (odd? row) 64 0)) 512)
            column (quot xx 128) u (mod xx 128) v (mod y 64)
            edge (min u (- 127 u) v (- 63 v))
            mortar (< edge 2)
            grain (* 8 (- (noise (+ x (* y 512))) 0.5))
            tone (+ (if mortar 150 (+ 215 (* 23 (- (noise (+ column (* row 4))) 0.5))))
                    grain (if (and (not mortar) (< edge 4)) -13 0))
            rgb (mapv #(/ (int (max 0 (min 255 (+ tone %)))) 255.0) [5 1 -9])]
        (conj rgb 1)))))

(defn facing
  "Quaternion orienting local -Z toward a target, with world +Z as camera-up."
  [position target]
  (let [[x y z] (mapv - target position)
        yaw (- (math/atan2 x y)) pitch (math/atan2 (math/hypot x y) (- z))
        sx (math/sin (/ pitch 2)) cx (math/cos (/ pitch 2))
        sz (math/sin (/ yaw 2)) cz (math/cos (/ yaw 2))]
    [(* sx cz) (* sx sz) (* cx sz) (* cx cz)]))
(defn frame [t position scale]
  {:time t :translation position :rotation [0 0 0 1] :scale (vec (repeat 3 scale))})
(defn channels
  ([id track] (channels id track [:translation :scale]))
  ([id track paths] (mapv (fn [path] {:node id :path path :track track}) paths)))
(defn glow [color strength]
  {:color (conj color 1) :emissive color :emissive-strength strength :roughness 0.4})
(defn solid-node [id geometry material]
  {:id id :name (name id) :geometry geometry :material material})
(defn compact [shapes]
  (m/as-original (apply m/union shapes)))
(defn animate-particle [node track]
  {:node (merge node (select-keys (first track) [:translation :scale]))
   :channels (channels (:id node) track)})
(defn particle-group [particles]
  {:nodes (mapv :node particles) :channels (vec (mapcat :channels particles))})

(defn blend [a b t] (+ a (* t (- b a))))
(defn smooth-step [lo hi x]
  (let [t (max 0.0 (min 1.0 (/ (- x lo) (- hi lo))))] (* t t (- 3 (* 2 t)))))
(defn land-noise [x y]
  (let [ix (math/floor x) iy (math/floor y)
        u (smooth-step 0 1 (- x ix)) v (smooth-step 0 1 (- y iy))
        sample #(noise (+ %1 (* 157 %2)))]
    (blend (blend (sample ix iy) (sample (inc ix) iy) u)
           (blend (sample ix (inc iy)) (sample (inc ix) (inc iy)) u) v)))
(def peaks
  ;; X, Y, elevation, and the two footprint radii. Overlap creates saddles.
  [[-315 285 175 155 160] [-210 315 210 130 155] [-112 250 130 115 125]
   [15 350 170 155 150] [145 285 190 130 150] [290 325 225 155 170]])
(defn terrain-height [x y]
  (let [n (land-noise (/ x 32) (/ y 32))
        ridge (- 1 (math/abs (- (* 2 (land-noise (/ x 15) (/ y 15))) 1)))
        hill (fn [cx cy rx ry]
               (max 0 (- 1 (math/hypot (/ (- x cx) rx) (/ (- y cy) ry)))))
        banks (* 48 (max (hill -160 62 125 165) (hill 166 68 120 165)))
        mountains (reduce max 0 (for [[cx cy h rx ry] peaks]
                                  (* h (math/pow (hill cx cy rx ry) 1.25))))
        height (+ -14 (* (+ banks mountains) (+ 0.78 (* 0.22 ridge)))
                  (* 9 n (smooth-step 0 14 (+ banks mountains))))
        ;; Grade a real landing into the bank; the road sits just above it.
        road (* (smooth-step 96 108 x) (- 1 (smooth-step 220 238 x))
                (- 1 (smooth-step 7 17 (math/abs (+ y 24)))))]
    (blend height 3.25 road)))
(defn land-surface [height]
  ;; Positive heights above a buried base produce a closed, valid m/surface.
  (-> (m/surface (mapv (fn [y] (mapv #(+ 24 (height % y)) (range -480 481 4)))
                       (range -180 781 4)) 4.0)
      (m/translate [-480 -180 -24])))
(defn terrain []
  (let [land (land-surface terrain-height)
        [low alpine] (m/split land (land-surface #(+ 35 (* 14 (land-noise (/ %1 43) (/ %2 43))))))
        [rock snow] (m/split alpine (land-surface #(+ 106 (* 24 (land-noise (/ %1 29) (/ %2 29))))))
        [grass beach] (m/split-by-plane low [0 0 1] -2)
        pine (compact (for [[z r h] [[0 2.2 6] [2.8 1.75 5.5] [5.5 1.2 4.8]]]
                        (m/translate (m/cylinder h r 0 10) [0 0 z])))
        trees (m/with-spatial-index land (fn [index]
                (vec (for [i (range 380)
                           :let [x (* (if (even? i) -1 1) (+ 91 (* 185 (noise (+ i 44)))))
                                 y (+ -46 (* 254 (noise (+ i 144))))
                                 s (+ 0.65 (* 1.35 (noise (+ i 299))))
                                 z (terrain-height x y)]
                           :when (and (< 0 z 58) (not (and (< 96 x 241) (< (math/abs (+ y 24)) 16))))
                           :let [hit (m/ray-cast index [x y 300] [0 0 -1])]
                           :when hit]
                       (assoc (solid-node (keyword (str "pine-" i)) pine {:color [0.035 0.085 0.052 1] :roughness 1})
                              :translation [x y (- (nth (:position hit) 2) 0.12)] :scale [s s s])))))]
    (into (mapv (fn [[id geometry color]] (solid-node id geometry {:color color :roughness 1}))
                [[:foothills grass [0.075 0.15 0.095 1]] [:shoreline beach [0.24 0.22 0.17 1]]
                 [:mountain-rock rock [0.24 0.29 0.36 1]] [:snowcaps snow [0.72 0.81 0.89 1]]
                 [:bridge-approach (castle/block 62 7 0.3 182 -24 3.25) [0.46 0.41 0.32 1]]])
          (cons (solid-node :lake (castle/block 1200 1200 0.3 0 0 -6.8)
                            {:color [0.012 0.052 0.085 1] :metalness 0.65 :roughness 0.15}) trees))))

(defn star-field []
  (let [star (m/sphere 0.15 6)]
    (solid-node :stars
                (compact (for [i (range 290)]
                           (-> star (m/scale (vec (repeat 3 (+ 0.6 (* 1.3 (noise i))))))
                               (m/translate [(- (* 640 (noise (+ i 891))) 320)
                                             320 (+ 40 (* 170 (noise (+ i 495))))]))))
                (glow [0.48 0.65 1] 3))))

(defn fireworks [spark]
  (let [streak (m/scale spark [0.65 0.65 4.5])
        ;; Center, start time, color.
        bursts [[[-68 26 103] 1.1 [0.12 0.65 1]]
                [[53 45 115] 3.0 [1 0.16 0.13]]
                [[-40 55 140] 5.3 [1 0.58 0.16]]
                [[74 38 92] 7.1 [0.23 1 0.56]]]
        particles
        (for [[burst [center start color]] (map-indexed vector bursts) i (range 80)
              :let [azimuth (* 2 math/pi (noise (+ i (* burst 81))))
                    zz (- (* 2 (noise (+ i 198 (* burst 119)))) 1)
                    radial (math/sqrt (- 1 (* zz zz)))
                    speed (+ 11 (* 7 (noise (+ i 478))))
                    velocity [(* speed radial (math/cos azimuth)) (* speed radial (math/sin azimuth)) (* speed zz)]
                    id (keyword (str "firework-" burst "-" i))
                    end (min 9.95 (+ start 2.5))
                    trajectory (fn [dt] (mapv + center (mapv #(* dt %) velocity) [0 0 (* -3.0 dt dt)]))
                    track (vec (concat [(frame 0 center 0.001) (frame start center 0.001)]
                                       (for [j (range 1 13) :let [dt (* (/ j 12) (- end start))]]
                                         (frame (+ start dt) (trajectory dt) (* 0.65 (- 1 (/ j 13)))))
                                       [(frame (+ end 0.01) (trajectory (- end start)) 0.001)
                                        (frame duration center 0.001)]))]]
          (animate-particle (assoc (solid-node id streak (glow color 10))
                                  :rotation (facing velocity [0 0 0])) track))]
    (particle-group particles)))

(defn arc-point [u]
  [(* -97 (math/cos (* math/pi u))) -3 (+ 67 (* 90 (math/sin (* math/pi u))))])
(defn sparkle-arc [spark]
  (let [times (mapv #(/ % 8.0) (range 81))
        progress #(max 0 (min 1 (/ (- % 1.5) 7)))
        comet-track (mapv #(frame % (arc-point (progress %)) (if (<= 1.5 % 8.5) 1.6 0.001)) times)
        tail (for [i (range 140)
                   :let [u (/ i 139) born (+ 1.5 (* u 7))
                         p (mapv + (arc-point u) [(* 1.3 (- (noise i) 0.5)) 0 (* 1.5 (- (noise (+ i 82)) 0.5))])
                         id (keyword (str "sparkle-" i))
                         track [(frame 0 p 0.001) (frame born p 0.001) (frame (+ born 0.04) p 0.6)
                                (frame (min 9.96 (+ born 1.1)) (mapv + p [0 0 -1]) 0.15)
                                (frame (min 9.98 (+ born 1.4)) (mapv + p [0 0 -2]) 0.001)
                                (frame 10 p 0.001)]]]
               (animate-particle (solid-node id spark (glow [1 0.78 0.36] 9)) track))]
    (particle-group (cons (animate-particle (solid-node :comet spark (glow [1 0.87 0.52] 14)) comet-track)
                          tail))))

(defn lighting []
  ;; Directional key/fill provide the architectural illumination. Keep local
  ;; accents restrained: F3D 2.3's range attenuation makes the previous
  ;; hundreds/thousands-of-candela rig clip badly despite looking OK in Three.
  [{:id :moonlight :light {:type :directional :color [0.40 0.54 1] :intensity 0.45}
    :rotation (facing [80 50 140] [0 0 25])}
   {:id :soft-front :light {:type :directional :color [0.65 0.72 1] :intensity 0.55}
    :rotation (facing [0 -150 65] [0 0 35])}
   {:id :warm-front :light {:type :directional :color [1 0.63 0.29] :intensity 1.1}
    :rotation (facing [30 -100 90] [0 0 30])}
   {:id :warm-left :light {:type :point :color [1 0.66 0.35] :intensity 1.2 :range 100} :translation [-41 -32 15]}
   {:id :warm-right :light {:type :point :color [1 0.66 0.35] :intensity 1.2 :range 100} :translation [41 -32 15]}
   {:id :spire-wash :light {:type :spot :color [1 0.68 0.34] :intensity 6 :range 130 :inner-cone 0.2 :outer-cone 0.7}
    :translation [0 -37 26] :rotation (facing [0 -37 26] [0 4 68])}])

(defn assembly []
  (let [stone (stone-image)
        castle (castle/assembly {:water? false :bridge-spans 9})
        masonry {:stone [16 [0.85 0.78 0.65 1] 0.9], :trim [8 [1 0.91 0.76 1] 0.8]}
        castle-nodes (for [{:keys [id] :as node} (:nodes castle)]
                       (if-let [[tile color roughness] (masonry id)]
                         (-> node (update :geometry m/texture-all stone :size [tile tile])
                             (assoc :material {:color color :roughness roughness}))
                         (cond-> node (= id :glass) (assoc :material (glow [1 0.45 0.09] 3)))))
        spark (m/sphere 0.55 8)
        fireworks (fireworks spark) arc (sparkle-arc spark)
        ;; A gentle dolly preserves the centered, head-on view throughout the clip.
        camera-track (vec (for [i (range 41)
                                :let [t (/ i 4.0) p [0 (+ -300 (* 6 (math/sin (* 2 math/pi (/ t duration))))) 78]]]
                            (assoc (frame t p 1) :rotation (facing p [0 0 53]))))]
    (m/scene
      {:name "A wish over the castle"
       :extras {"duration" duration "waterHeight" -6.5 "camera" "cinematic-camera"
                "note" "Lights, camera, stone UVs and particles are portable glTF. Reflection and bloom are viewer effects."}
       :nodes (vec (concat castle-nodes (terrain) [(star-field)] (lighting)
                           [(merge {:id :cinematic-camera :name "cinematic-camera"
                                    :camera {:yfov 0.72 :znear 2 :zfar 1500}}
                                   (select-keys (first camera-track) [:translation :rotation]))]
                           (:nodes fireworks) (:nodes arc)))
       :animations [{:name "Ten-second celebration"
                     :channels (vec (concat (:channels fireworks) (:channels arc)
                                           (channels :cinematic-camera camera-track [:translation :rotation])))}]})))

(defn -main [& [filename]]
  (let [filename (or filename "target/fairytale-castle-night.glb") started (System/nanoTime)
        scene (assembly)]
    (io/make-parents filename)
    (println "Windows:" (castle/check-window-visibility! scene))
    (m/export-scene scene filename)
    (println "Exported" filename "nodes:" (count (:nodes scene)) "seconds:" (/ (- (System/nanoTime) started) 1e9)))
  (shutdown-agents))

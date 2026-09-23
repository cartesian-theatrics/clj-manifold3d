(ns fairytale-castle
  "Procedural castle inspired by the architecture in Stefan_3D_AI's reference:
  https://x.com/Stefan_3D_AI/status/2102471841046786153
  All geometry is built with clj-manifold3d; no imported meshes or Blender.
  Run: clojure -M:clj-dev -m fairytale-castle target/fairytale-castle.glb"
  (:require [clj-manifold3d.core :as m]
            [clj-manifold3d.math :as math]
            [clojure.java.io :as io]))

(def palette
  {:stone {:color [0.72 0.62 0.47 1] :roughness 0.85}
   :trim {:color [0.92 0.84 0.67 1] :roughness 0.7}
   :roof {:color [0.025 0.10 0.20 1] :roughness 0.38 :metalness 0.2}
   :roof-ridge {:color [0.06 0.19 0.30 1] :roughness 0.48}
   :gold {:color [0.85 0.51 0.12 1] :metalness 0.65 :roughness 0.28}
   :glass {:color [0.95 0.47 0.11 1] :metalness 0.2 :roughness 0.25}
   :shadow {:color [0.035 0.052 0.066 1] :roughness 0.8}
   :foundation {:color [0.30 0.34 0.32 1] :roughness 1}
   :grass {:color [0.12 0.23 0.15 1] :roughness 1}
   :water {:color [0.025 0.16 0.24 1] :metalness 0.35 :roughness 0.18}})

(defn block [w d h x y z]
  (m/translate (m/cube w d h) [(- x (/ w 2)) (- y (/ d 2)) z]))
(defn drum [r h z] (m/translate (m/cylinder h r r 48) [0 0 z]))
(defn part [style geometry] {:style style :geometry geometry})
(defn move-parts [parts xyz]
  (mapv #(update % :geometry m/translate xyz) parts))
(defn turn-parts [parts angle]
  (mapv #(update % :geometry m/rotate [0 0 angle]) parts))

(defn arch-section
  "Pointed arch with quadratic shoulders, or a semicircular bridge arch."
  [w h pointed?]
  (let [r (/ w 2)
        top (if pointed?
              (let [side (for [i (range 13)
                               :let [t (/ i 12) s (- 1 t)]]
                           [(+ (* s s r) (* 2 s t r 0.85))
                            (+ (* s s h 0.57) (* 2 s t h 0.85) (* t t h))])]
                (concat side (map (fn [[x z]] [(- x) z]) (reverse (butlast side)))))
              (for [i (range 25) :let [a (* math/pi (/ i 24))]]
                [(* r (math/cos a)) (+ (- h r) (* r (math/sin a)))]))]
    (m/cross-section (vec (concat [[(- r) 0] [r 0]] top)))))

(defn arch [w h depth]
  (-> (arch-section w h true) (m/extrude depth) (m/rotate [90 0 0])))
(defn arch-rim [w h border depth]
  (-> (m/difference (arch-section w h true)
                    (m/translate (arch-section (- w (* 2 border)) (- h (* 2 border)) true) [0 border]))
      (m/extrude depth) (m/rotate [90 0 0])))

(defn window
  "Recessed amber pane, carved stone surround and a fine gold mullion."
  [w h x y z]
  (move-parts
    [(part :opening (m/translate (arch w h 2.0) [0 0.1 0]))
     (part :shadow (arch w h 0.12))
     (part :glass (-> (arch (* w 0.72) (* h 0.86) 0.13) (m/translate [0 -0.02 (* h 0.05)])))
     (part :trim (-> (arch-rim (+ w 0.30) (+ h 0.24) 0.18 0.28) (m/translate [0 -0.48 -0.12])))
     (part :gold (block 0.075 0.16 (* h 0.8) 0 -0.22 (* h 0.06)))
     (part :gold (block (* w 0.72) 0.16 0.075 0 -0.22 (* h 0.38)))]
    [x y z]))

(defn pennant [z length]
  [(part :gold (drum 0.065 (+ length 0.7) z))
   (part :gold (m/translate (m/sphere 0.2 12) [0 0 (+ z length 0.7)]))
   (part :roof (-> (m/cross-section [[0 0] [length -0.28] [(* 0.70 length) 0.30] [0 0.65]])
                   (m/extrude 0.08) (m/rotate [90 0 0])
                   (m/translate [0 0 (+ z length -0.05)])))])

(defn conical-roof [radius height z flag?]
  (let [r (* radius 1.18)]
    (concat
      [(part :trim (m/translate (m/cylinder 0.8 radius (* radius 1.08) 48) [0 0 (- z 0.8)]))
       (part :roof (m/translate (m/cylinder height r 0.04 64) [0 0 z]))
       (part :gold (drum (+ r 0.04) 0.13 z))]
      ;; Actual geometric tile courses, not painted lines.
      (for [i (range 1 17) :let [t (/ i 18) rr (* r (- 1 t)) zz (+ z (* height t))]]
        (part :roof-ridge (m/translate (m/cylinder 0.10 (+ rr 0.07) (+ rr 0.045) 64) [0 0 zz])))
      [(part :gold (m/translate (m/sphere 0.19 12) [0 0 (+ z height)]))]
      (when flag? (pennant (+ z height) 1.9)))))

(defn tower
  "Complete round tower; windows are boolean recesses in the wall, on all sides."
  [{:keys [x y radius height roof-height flag?] :or {flag? false}}]
  (let [levels (if (> height 40) [12 (- height 18) (- height 6)] [7 (- height 6)])
        w (min 1.45 (* radius 0.32)) h (min 4.4 (* radius 1.1))
        placements (for [z levels angle (range 0 360 60)] [z angle])
        cuts (for [[z angle] placements]
               (-> (arch w h 1.2) (m/translate [0 (+ (- radius) 0.55) z]) (m/rotate [0 0 angle])))
        wall (apply m/difference (drum radius height 0) cuts)]
    (move-parts
      (concat
        [(part :stone wall)
         (part :trim (drum (* radius 1.12) 0.7 0))
         (part :trim (drum (* radius 1.06) 0.36 1.2))
         (part :trim (drum (* radius 1.08) 0.45 (- height 2)))
         (part :trim (drum (* radius 1.10) 0.4 (- height 0.5)))]
        (for [z (range 3 (- height 2) 2.4)]
          (part :stone (drum (+ radius 0.035) 0.06 z)))
        (mapcat (fn [[z angle]] (turn-parts (window w h 0 (+ (- radius) 0.54) z) angle)) placements)
        (for [angle (range 0 360 20)]
          (part :trim (-> (block 0.5 0.5 0.65 0 (- radius) (- height 1.65)) (m/rotate [0 0 angle]))))
        (conical-roof radius roof-height height flag?))
      [x y 4])))

(defn battlements [width depth height x y z]
  (concat
    [(part :trim (block (+ width 0.4) (+ depth 0.4) 0.45 x y (+ z height)))
     (part :trim (block (+ width 0.8) (+ depth 0.8) 0.35 x y (+ z height -0.7)))]
    (for [xx (range (+ x (- (/ width 2)) 0.6) (+ x (/ width 2)) 1.6)
          yy [(- y (/ depth 2)) (+ y (/ depth 2))]]
      (part :trim (block 0.9 0.8 1.1 xx yy (+ z height 0.4))))))

(defn hall [w d h x y z & {:keys [window-xs]}]
  (let [xs (or window-xs (range (+ (- (/ w 2)) 2.7) (- (/ w 2) 1.5) 3.2))
        zs (range 6 (- h 3) 9)
        cuts (for [xx xs zz zs]
               (m/translate (arch 1.25 3.5 0.95) [xx (+ (- (/ d 2)) 0.45) zz]))]
    (move-parts
      (concat
        [(part :stone (apply m/difference (block w d h 0 0 0) cuts))]
        (for [zz (range 2 h 5.5)] (part :trim (block (+ w 0.25) (+ d 0.25) 0.19 0 0 zz)))
        (mapcat (fn [[xx zz]] (window 1.25 3.5 xx (+ (- (/ d 2)) 0.44) zz)) (for [xx xs zz zs] [xx zz]))
        (battlements w d h 0 0 0))
      [x y z])))

(defn gable [w d h x y z]
  (-> (m/cross-section [[(- (/ w 2)) 0] [(/ w 2) 0] [0 h]])
      (m/extrude d) (m/rotate [90 0 0]) (m/translate [x (+ y (/ d 2)) z])))

(defn gatehouse []
  (let [body (m/union (block 18 12 24 0 -26 4) (gable 18 12 13 0 -26 28))
        opening (m/translate (arch 8.5 13.5 15) [0 -19 3.9])]
    (concat
      [(part :stone (m/difference body opening))
       (part :trim (m/translate (arch-rim 9.6 14.2 0.33 0.65) [0 -32.1 4]))
       (part :trim (m/translate (arch-rim 10.6 15 0.25 0.35) [0 -32.2 3.8]))
       (part :roof (m/difference (gable 20 13 14 0 -26 28) (gable 18.8 14 13.8 0 -26 27.5)))
       (part :gold (m/difference (gable 19 0.3 13.5 0 -32.7 28) (gable 18.55 0.5 13.3 0 -32.7 27.98)))]
      (mapcat #(window 1.5 6 % -32.01 21) [-2.2 0 2.2])
      ;; Circular rose window and eight radial tracery bars.
      [(part :gold (-> (m/difference (m/circle 1.5 48) (m/circle 1.23 48))
                       (m/extrude 0.2) (m/rotate [90 0 0]) (m/translate [0 -32.15 32])))
       (part :glass (-> (m/cylinder 0.14 1.23 1.23 48) (m/rotate [90 0 0]) (m/translate [0 -32.1 32])))]
      (for [angle (range 0 180 30)]
        (part :gold (-> (block 0.08 0.21 2.4 0 0 -1.2) (m/rotate [0 angle 0]) (m/translate [0 -32.3 32])))))))

(defn curtain [x]
  (concat
    ;; Do not put the innermost window behind the gatehouse turret.
    (hall 28 5 14 x -24 4 :window-xs (filter #(> (math/abs (double (+ x %))) 13) (range -11.3 12.5 3.2)))
    (for [xx (map #(+ x %) [-11 -5.5 0 5.5 11])]
      (part :trim (block 0.6 0.8 12.8 xx -26.8 4.4)))))

(defn bridge
  ([] (bridge 4))
  ([spans]
   (let [centers (take spans (iterate #(+ % 12) 48))
         length (+ 4 (* 12 spans)) center (+ 39 (/ length 2)) end (+ 39 length)
         cuts (for [x centers]
                (-> (arch-section 9.6 6.6 false) (m/extrude 12) (m/rotate [90 0 0])
                    (m/translate [x -18 -5.9])))]
     (concat
       [(part :stone (apply m/difference (block length 9 9 center -24 -6) cuts))
        (part :trim (block (inc length) 9.5 0.55 center -24 3))]
       (for [y [-28.3 -19.7]] (part :trim (block length 0.6 1.7 center y 3.5)))
       (for [x centers y [-28.6 -19.4] i (range 15)
             :let [a (* math/pi (/ (+ i 0.5) 15))]]
         (part :trim (-> (block 0.72 0.5 0.6 0 0 -0.3)
                        (m/rotate [0 (- 90 (* a (/ 180 math/pi))) 0])
                        (m/translate [(+ x (* 5.05 (math/cos a))) y (+ -4.1 (* 5.05 (math/sin a)))]))))
       (mapcat
         (fn [[x y]]
           [(part :gold (m/translate (m/cylinder 3 0.12 0.08 12) [x y 5.2]))
            (part :glass (m/translate (m/sphere 0.35 12) [x y 8.2]))
            (part :roof (m/translate (m/cylinder 0.5 0.48 0 8) [x y 8.55]))])
         (for [x (range 41 (inc end) 10) y [-28.3 -19.7]] [x y]))))))

(def tower-layout
  [{:x -39 :y -22 :radius 5.5 :height 22 :roof-height 15 :flag? true}
   {:x 39 :y -22 :radius 5.5 :height 22 :roof-height 15 :flag? true}
   {:x -9.2 :y -30 :radius 2.8 :height 23 :roof-height 11}
   {:x 9.2 :y -30 :radius 2.8 :height 23 :roof-height 11}
   {:x -23 :y -10 :radius 5.2 :height 33 :roof-height 16}
   {:x 23 :y -10 :radius 5.2 :height 33 :roof-height 16}
   {:x -14 :y 1 :radius 5.6 :height 53 :roof-height 20 :flag? true}
   {:x 14 :y 1 :radius 5 :height 59 :roof-height 21}
   {:x -24 :y 16 :radius 4.2 :height 35 :roof-height 15}
   {:x 24 :y 16 :radius 4.2 :height 38 :roof-height 15}
   {:x -8 :y 18 :radius 3.5 :height 62 :roof-height 17}
   {:x 4 :y 12 :radius 3.7 :height 78 :roof-height 21 :flag? true}])

(defn castle-parts []
  (vec
    (concat
      [(part :foundation (-> (m/cylinder 7 1 0.98 64) (m/scale [63 46 1]) (m/translate [0 0 -6.5])))
       (part :stone (-> (m/cylinder 0.7 1 1 96) (m/scale [61 44 1]) (m/translate [0 0 0.5])))
       (part :trim (-> (m/cylinder 0.35 1 1 96) (m/scale [60 43 1]) (m/translate [0 0 1.2])))
       (part :stone (-> (m/cylinder 2.5 1 1 96) (m/scale [59 42 1]) (m/translate [0 0 1.5])))]
      (hall 28 24 39 0 0 4)
      (hall 18 16 15 0 3 43)
      [(part :roof (gable 21 20 17 0 3 58))]
      (curtain -23) (curtain 23)
      (hall 5 36 15 -33 0 4) (hall 5 36 15 33 0 4)
      (hall 60 5 14 0 19 4)
      (gatehouse)
      (mapcat tower tower-layout)
      ;; Small roof pinnacles flank the upper gallery.
      (mapcat (fn [x]
                (move-parts (concat [(part :stone (drum 0.9 5 0))]
                                    (conical-roof 0.9 5 5 false)) [x -12.5 43])) [-11 -4 4 11]))))

(defn material-nodes
  "Finish masonry only after all windows and decorations have been assembled.
  :opening is construction geometry, never exported. Clearing the final walls
  and trim prevents later-added stone courses/pilasters from filling windows."
  [parts]
  (let [groups (group-by :style parts)
        openings (when (seq (:opening groups)) (apply m/union (map :geometry (:opening groups))))]
    (mapv (fn [[style pieces]]
            (let [merged (apply m/union (map :geometry pieces))
                  cleared (if (and openings (#{:stone :trim} style)) (m/difference merged openings) merged)
                  ;; A single material has no per-piece appearance to preserve.
                  geometry (m/as-original cleared)]
              (when-not (= :NoError (m/status geometry))
                (throw (ex-info "Invalid castle geometry" {:style style})))
              {:id style :name (name style) :geometry geometry :material (palette style)}))
          (sort-by key (dissoc groups :opening)))))

(defn assembly
  "Return a scene of real, individually valid solids grouped by material.
  :bridge? adds the side bridge (:bridge-spans defaults to 4); :water? adds a presentation plinth.
  This is a display assembly, not a single fused printable solid."
  ([] (assembly {}))
  ([{:keys [bridge? bridge-spans water?] :or {bridge? true bridge-spans 4 water? true}}]
   (let [parts (concat (castle-parts) (when bridge? (bridge bridge-spans))
                       (when water?
                         [(part :water (-> (m/cylinder 0.4 1 1 96) (m/scale [102 66 1]) (m/translate [17 0 -6.5])))]))]
     (m/scene
       {:name "Fairytale castle — procedural Manifold architecture"
        :nodes (material-nodes parts)}))))

(defn check-window-visibility!
  "Ray-check representative exposed windows against the finished assembly,
  including pilasters/course heights. Architectural foreground towers may
  naturally hide more distant windows; these probes start just outside walls."
  [scene]
  (let [indices (into {} (for [{:keys [id geometry]} (:nodes scene)
                               :when (and geometry (#{:stone :trim :shadow :gold :glass :roof :roof-ridge} id))]
                           [id (m/spatial-index geometry)]))
        probes (concat
                 (for [x [-39 39] dx [-0.32 0.32] z [11.83 12.2]] [(+ x dx) -29 z])
                 (for [x [-2.2 0 2.2]] [(+ x 0.3) -34 23.5])
                 (for [x [-6.3 -3.1 0.1 3.3 6.5]] [(+ x 0.3) -7 50.2])
                 (for [side [-23 23] x [-11.3 -8.1 -4.9 -1.7 1.5 4.7 7.9 11.1]
                       :when (> (math/abs (double (+ side x))) 13)]
                   [(+ side x 0.3) -29 11.2]))]
    (try
      (doseq [p probes]
        (let [hits (sort-by :distance (keep (fn [[id index]]
                                             (when-let [hit (m/ray-cast index p [0 1 0] :max-distance 4)]
                                               (assoc hit :material id))) indices))]
          (when-not (= :glass (:material (first hits)))
            (throw (ex-info "Exterior window is occluded" {:probe p :hits hits})))))
      {:checked (count probes) :occluded 0}
      (finally (doseq [[_ index] indices] (.close ^java.lang.AutoCloseable index))))))

(defn -main [& [filename]]
  (let [filename (or filename "target/fairytale-castle.glb")
        started (System/nanoTime)
        scene (assembly)]
    (io/make-parents filename)
    (println "Windows:" (check-window-visibility! scene))
    (m/export-scene scene filename)
    (println "Exported" filename "in" (/ (- (System/nanoTime) started) 1e9) "seconds")
    (println "Materials:" (count (:nodes scene))))
  (shutdown-agents))

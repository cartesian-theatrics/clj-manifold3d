(ns raptor-3
  "Photo-based Raptor 3 SN1 display assembly, authored entirely in Manifold.
  Millimetres in the geometry helpers; the exported scene is Y-up, in metres.
  Run: clojure -M:clj-dev -m raptor-3 target/raptor-3.glb
  See raptor_3.md for references, options, and the limits of this reconstruction."
  (:require [clj-manifold3d.core :as m]
            [clj-manifold3d.math :as math]
            [clj-manifold3d.texture :as texture]
            [clojure.java.io :as io])
  (:import [org.bytedeco.javacpp PointerScope]))

(def ^:dynamic *segments* 128)
(def tau (* 2 math/pi))
(defn degrees [radians] (* radians (/ 180 math/pi)))
(defn radians [degrees] (* degrees (/ math/pi 180)))
(def palette
  {:jacket {:color [0.018 0.025 0.032 1] :metalness 0.72 :roughness 0.46}
   :cast {:color [0.085 0.079 0.072 1] :metalness 0.82 :roughness 0.43}
   :steel {:color [0.48 0.51 0.53 1] :metalness 0.92 :roughness 0.27}
   :machined {:color [0.67 0.69 0.71 1] :metalness 0.95 :roughness 0.22}
   :weld {:color [0.25 0.23 0.20 1] :metalness 0.85 :roughness 0.38}
   :liner {:color [0.24 0.18 0.13 1] :metalness 0.82 :roughness 0.48}
   :black {:color [0.016 0.021 0.026 1] :metalness 0.25 :roughness 0.62}
   :gold {:color [0.52 0.37 0.15 1] :metalness 0.83 :roughness 0.3}
   :blue {:color [0.024 0.19 0.32 1] :metalness 0.65 :roughness 0.3}
   :white {:color [0.82 0.85 0.83 1] :roughness 0.7 :metalness 0.1}
   :pallet {:color [0.60 0.64 0.66 1] :metalness 0.7 :roughness 0.48}})

(defn v+ [a b] (mapv + a b))
(defn v- [a b] (mapv - a b))
(defn v* [a s] (mapv #(* % s) a))
(defn dot [a b] (reduce + (map * a b)))
(defn cross [[a b c] [x y z]] [(- (* b z) (* c y)) (- (* c x) (* a z)) (- (* a y) (* b x))])
(defn norm [a] (math/sqrt (dot a a)))
(defn unit [a] (v* a (/ 1.0 (norm a))))
(defn polar [r a z] [(* r (math/cos a)) (* r (math/sin a)) z])
(defn part [label style geometry] {:name label :style style :geometry geometry})
(defn shift [parts xyz] (mapv #(update % :geometry m/translate xyz) parts))
(defn orient
  "Aim local +Z along direction, then place its origin."
  [geometry origin direction]
  (let [[x y z] (unit direction)]
    (-> geometry
        (m/rotate [0 (degrees (math/atan2 (math/sqrt (+ (* x x) (* y y))) z))
                   (degrees (math/atan2 y x))])
        (m/translate origin))))
(defn cylinder [r h z] (m/translate (m/cylinder h r r *segments*) [0 0 z]))
(defn box [w d h p] (m/translate (m/cube w d h true) p))
(defn lathe [profile] (m/revolve (m/cross-section (vec profile)) *segments*))
(defn annulus [outer inner height]
  (lathe [[inner 0] [outer 0] [outer height] [inner height]]))
(defn torus [radius wire z]
  (lathe (for [i (range 24) :let [a (* tau (/ i 24.0))]]
           [(+ radius (* wire (math/cos a))) (+ z (* wire (math/sin a)))])))

(defn bezier [p0 p1 p2 p3 samples]
  (mapv (fn [i]
          (let [t (/ i (double samples)) s (- 1 t)]
            (mapv (fn [a b c d] (+ (* s s s a) (* 3 s s t b) (* 3 s t t c) (* t t t d)))
                  p0 p1 p2 p3))) (range (inc samples))))

(defn fair-curve
  "Catmull–Rom interpolation through pipe centreline stations."
  [points]
  (let [ps (vec (concat [(first points)] points [(last points)]))]
    (vec (concat
          (mapcat (fn [[a b c d]]
                    (butlast (bezier b (v+ b (v* (v- c a) (/ 1.0 6)))
                                        (v- c (v* (v- d b) (/ 1.0 6))) c 10)))
                  (partition 4 1 ps)) [(last points)]))))

(defn tube
  "Closed swept solid with parallel-transport frames, without elbow seams."
  [radius points]
  (let [ps (vec points) n (count ps) sides (max 12 (quot *segments* 4))
        tangents (mapv (fn [i] (unit (v- (ps (min (dec n) (inc i))) (ps (max 0 (dec i)))))) (range n))
        frames (reduce (fn [out t]
                         (let [prev (if (seq out) (first (peek out))
                                        (unit (cross t (if (< (math/abs (double (last t))) 0.9) [0 0 1] [0 1 0]))))
                               u (unit (v- prev (v* t (dot prev t))))]
                           (conj out [u (cross t u)]))) [] tangents)
        vertices (vec (mapcat (fn [p [u v]]
                               (for [j (range sides) :let [a (* tau (/ j sides))]]
                                 (v+ p (v+ (v* u (* radius (math/cos a))) (v* v (* radius (math/sin a))))))) ps frames))
        faces (concat [(vec (reverse (range sides)))]
                      (mapcat (fn [i]
                                (mapcat (fn [j]
                                          (let [k (mod (inc j) sides) a (+ (* i sides) j) b (+ (* i sides) k)
                                                c (+ (* (inc i) sides) k) d (+ (* (inc i) sides) j)]
                                            [[a b c] [a c d]])) (range sides))) (range (dec n)))
                      [(vec (range (* (dec n) sides) (* n sides)))])]
    (m/polyhedron vertices faces)))

(defn pipe [label style radius stations] (part label style (tube radius (fair-curve stations))))
(defn rod [radius a b] (orient (m/cylinder (norm (v- b a)) radius radius 24) a (v- b a)))

(defn bolt-circle [label radius z n size]
  (mapcat (fn [i]
            (let [p (polar radius (* tau (/ i n)) z)]
              [(part (str label " washer " i) :steel (m/translate (m/cylinder (* size 0.22) (* size 1.34) (* size 1.34) 24) p))
               (part (str label " hex " i) :machined
                     (m/translate (m/cylinder (* size 0.85) size size 6) (v+ p [0 0 (* size 0.22)])))])) (range n)))

(defn flange [label origin direction outer bore]
  (mapv #(update % :geometry orient origin direction)
        (concat [(part (str label " flange") :steel (annulus outer bore 20))
                 (part (str label " seal") :black (annulus (+ bore 8) bore 22))]
                (bolt-circle label (- outer 14) 20 12 6))))

(def bell-profile
  ;; Radius/height stations traced from the SN1 silhouette, not nozzle design data.
  (vec (concat (bezier [650 0] [616 400] [456 934] [312 1190] 64)
               (rest (bezier [312 1190] [265 1275] [184 1360] [184 1420] 30))
               (rest (bezier [184 1420] [184 1490] [245 1510] [245 1600] 24)))))

(defn nozzle []
  (let [inner (mapv (fn [[r z]] [(- r 10) z]) bell-profile)]
    (concat
     [(part "Continuous hollow nozzle jacket" :jacket (lathe (concat bell-profile (reverse inner))))
      (part "Warm inner nozzle wall" :liner
            (lathe (concat inner (reverse (map (fn [[r z]] [(- r 1.5) z]) inner)))))
      (part "Rolled nozzle exit lip" :steel (torus 646 5 4))
      (part "Lower cooling collector" :jacket (torus 325 24 1182))
      (part "Throat cooling collector" :cast (torus 208 34 1430))
      (part "Chamber lower jacket" :cast (cylinder 245 220 1580))
      (part "Chamber upper shoulder" :cast
            (lathe [[0 1770] [245 1770] [252 1840] [280 1870] [280 1930] [0 1930]]))
      (part "Chamber jacket lower weld" :weld (torus 247 4 1600))
      (part "Chamber manifold" :jacket (torus 253 24 1745))
      (part "Injector flange bottom" :cast (cylinder 300 34 1910))
      (part "Injector flange gasket" :black (cylinder 299 4 1944))
      (part "Injector flange top" :steel (cylinder 300 22 1948))
      (part "Injector head dome" :cast
            (lathe [[0 1970] [276 1970] [276 1990] [262 2030] [240 2050] [0 2050]]))]
     ;; Narrow raised circumferential manufacturing seams.
     (for [z [1050 1073] :let [r (ffirst (sort-by #(math/abs (- (second %) z)) bell-profile))]]
       (part "Nozzle circumferential seam" :jacket (torus r 2.2 z)))
     (bolt-circle "Injector perimeter" 280 1970 36 8)
     ;; Geometric white SN1 stencil follows the bell's conical surface.
     [(part "SN1 nozzle marking" :white
            (m/intersection
             (-> (m/cross-section [[-42 0] [42 0] [42 18] [14 18] [14 150]
                                   [-18 150] [-40 128] [-28 114] [-10 130] [-10 18] [-42 18]])
                 (m/extrude 700) (m/rotate [90 0 0]) (m/translate [0 0 840]))
             (lathe (concat (map (fn [[r z]] [(+ r 1.4) z]) bell-profile)
                            (reverse (map (fn [[r z]] [(+ r 0.3) z]) bell-profile))))))])))

(defn powerhead []
  (concat
   ;; A compact asymmetric pair of cast pump bodies and a shared support bridge.
   [(part "Powerhead bridge" :jacket
          (m/hull (cylinder 310 85 2025) (m/translate (cylinder 187 85 2025) [365 18 0])))
    (part "Bridge joint shadow" :black
          (m/hull (cylinder 310 3 2064) (m/translate (cylinder 187 3 2064) [365 18 0])))
    (part "Main pump housing" :cast
          (lathe [[0 2080] [210 2080] [238 2140] [235 2310] [200 2370] [165 2390] [0 2390]]))
    (part "Upper pump crown" :cast (m/translate (m/sphere 175 64) [-35 25 2440]))
    (part "Main pump machined split" :steel (torus 238 9 2270))
    (part "Offset turbine casing" :cast
          (m/translate (lathe [[0 1440] [75 1440] [110 1490] [114 1580] [125 1660]
                              [112 1710] [139 1780] [129 1830] [156 1890] [170 1970]
                              [170 2120] [150 2160] [0 2160]]) [365 18 0]))
    (part "Offset turbine upper volute" :cast (m/translate (torus 120 62 2210) [365 18 0]))
    (part "Offset inlet neck" :steel (m/translate (cylinder 88 110 2230) [365 18 0]))
    (part "Blue inlet shipping cap" :blue (m/translate (cylinder 82 10 2340) [365 18 0]))
    (part "Upper feed neck" :steel (m/translate (cylinder 110 200 2510) [-35 25 0]))
    (part "Upper feed neck weld" :weld (m/translate (torus 111 4 2630) [-35 25 0]))
    (part "Upper feed dark cap" :black (m/translate (cylinder 106 14 2710) [-35 25 0]))
    (part "Upper attachment flange" :machined (m/translate (annulus 171 104 24) [-35 25 2510]))]
   (shift (bolt-circle "Upper flange" 150 2534 16 10) [-35 25 0])
   (shift (bolt-circle "Offset pump" 160 2120 18 7) [365 18 0])
   (for [z [1575 1715 1830 1950]]
     (part "Turbine casing weld" :weld (m/translate (torus (case z 1575 114 1715 114 1830 130 1950 168) 3 z) [365 18 0])))
   ;; Cast vertical webs, port bosses, and front access covers.
   (for [a (range 0 360 60)]
     (part "Pump casing stiffener" :cast
           (-> (box 26 64 172 [215 0 2390]) (m/rotate [0 0 a]))))
   (mapcat (fn [[x z r]]
             (concat (flange "Service cover" [x -225 z] [0 -1 0] r (- r 22))
                     [(part "Service cover face" :cast (orient (m/cylinder 8 (- r 21) (- r 21) 40) [x -245 z] [0 -1 0]))]))
           [[-90 2310 43] [70 2330 35]])
   (for [a [30 150 270]]
     (part "Thrust attachment lug" :steel
           (-> (m/difference (m/hull (box 54 60 100 [163 0 2470])
                                             (orient (m/cylinder 60 39 39 40 true) [163 0 2520] [0 1 0]))
                                (orient (m/cylinder 100 19 19 32 true) [163 0 2520] [0 1 0]))
               (m/rotate [0 0 a]))))))

(defn plumbing []
  (concat
   [(pipe "Sweeping main coolant feed" :cast 62
          [[390 -80 2220] [292 -230 2230] [35 -300 2220] [-225 -308 2110]
           [-285 -302 1850] [-270 -283 1570] [-207 -224 1440] [0 -209 1430]])
    (pipe "Turbine return elbow" :cast 76
          [[365 18 1590] [350 0 1430] [285 -18 1368] [204 -20 1370]])
    (pipe "Rear oxidizer riser" :steel 47
          [[-160 150 1850] [-279 153 2050] [-290 147 2350] [-233 109 2480] [-90 70 2490]])
    (pipe "Lower collector transfer" :steel 15
          [[80 -315 1182] [260 -270 1210] [290 -260 1530] [244 -257 1850] [130 -230 2040]])
    (pipe "Chamber sensing line" :steel 9
          [[-80 -242 1745] [-130 -284 1690] [-194 -288 1720] [-215 -270 1890] [-198 -260 2040]])
    (pipe "Powerhead pressure tube" :steel 10
          [[92 -225 2300] [157 -268 2270] [155 -290 2160] [120 -315 2100]])
    (pipe "Rear return line" :cast 26
          [[365 160 2230] [360 232 2290] [100 260 2270] [32 247 2110]])]
   (flange "Main feed coupling" [275 -234 2228] [-1 -0.12 0] 86 62)
   (flange "Return coupling" [230 -18 1370] [-1 0 0] 96 73)
   (flange "Rear riser coupling" [-284 150 2285] [0 0 1] 66 47)
   ;; Small instrumentation block on the visible side, including fittings.
   [(part "Valve manifold block" :steel (box 104 76 98 [88 -255 2112]))
    (part "Valve manifold lid" :machined (box 112 10 104 [88 -298 2112]))
    (part "Valve actuator" :steel (orient (m/cylinder 100 29 29 40) [88 -258 2030] [0 0 -1]))]
   (for [x [48 128] z [2076 2148]]
     (part "Valve lid screw" :machined (orient (m/cylinder 9 8 8 6) [x -308 z] [0 -1 0])))
   (for [x [52 89 126]]
     (part "Valve tube union" :gold (m/translate (m/cylinder 22 13 13 6) [x -253 2161])))
   (for [[x y z] [[260 -268 1240] [282 -260 1490] [245 -258 1810] [-216 -270 1890]]]
     (part "Instrument tube clamp" :steel (m/translate (annulus 20 15 11) [x y z])))
   (for [i (range 3)]
     (pipe "Short valve capillary" :steel 5
           [[(+ 52 (* i 37)) -253 2180] [(+ 52 (* i 37)) -267 (+ 2220 (* i 18))]
            [(+ 55 (* i 30)) -239 (+ 2270 (* i 10))]]))
   ;; Ribbed protective sheath, restrained to the small external harness.
   [(pipe "Sensor harness" :black 9 [[-185 200 2340] [-262 203 2180] [-266 202 2020] [-211 185 1830]])]
   (for [z (range 2040 2180 9)]
     (part "Harness corrugation" :black (m/translate (torus 10 2 z) [-267 202 0])))))

(defn stand []
  (concat
   ;; Hollow pallet runners with open fork pockets.
   (for [x [-570 0 570]]
     (part "Transport pallet hollow runner" :pallet
           (m/difference (box 210 1480 110 [x 0 -130]) (box 180 1482 82 [x 0 -130]))))
   [(part "Transport pallet deck" :pallet (box 1540 1480 24 [0 0 -63]))
    (part "Transport collar" :steel (m/translate (annulus 363 337 32) [0 0 1140]))]
   (for [a [45 135 225 315]]
     (let [angle (radians a)]
       (part "Nozzle padded support" :black (box 110 110 48 (polar 588 angle -27)))))
   (mapcat (fn [i]
             (let [a (+ (* tau (/ i 3)) 0.3) low (polar 790 a -42) high (polar 354 a 1146)
                   delta (v- high low) direction (unit delta) mid (v+ low (v* delta 0.47))]
               [(part "Transport brace" :steel (rod 11 low high))
                (part "Brace turnbuckle" :machined (orient (annulus 20 13 230) mid direction))
                (part "Pallet anchor shoe" :steel (box 84 70 24 low))
                (part "Collar lifting eye" :gold
                      (orient (annulus 28 15 13) high [0 -1 0]))])) (range 3))))

(defn engine-parts [] (vec (concat (nozzle) (powerhead) (plumbing))))

(defn finish-image
  "Deterministic, tileable fine manufacturing grain, embedded in the GLB."
  [brushed?]
  (texture/image
   256 256
   (fn [x y]
     (let [u (* tau (/ x 256.0)) v (* tau (/ y 256.0))
           grain (* (math/sin (+ (* 43 u) (* 37 v)))
                    (math/sin (- (* 71 u) (* 53 v))))
           brush (if brushed? (* 0.035 (math/sin (+ (* 91 v) (* 0.7 (math/sin (* 3 u)))))) 0)
           c (+ 0.85 (* 0.10 grain) brush)]
       [c c c 1]))))

(defn parts-scene
  "Validate and finish existing parts, so journal stages can reuse their solids."
  [engine fixture]
     (let [engine (vec engine) fixture (vec fixture)
           finishes {:jacket (finish-image true) :cast (finish-image false)}
           parts (into engine fixture)
           nodes (mapv (fn [i {:keys [name style geometry]}]
                         (when-not (and (= :NoError (m/status geometry))
                                        (pos? (:volume (m/get-properties geometry))))
                           (throw (ex-info "Invalid Raptor part" {:name name :index i :status (m/status geometry)})))
                         {:id (keyword (str "part-" i)) :name (str name " / " i)
                          :geometry (cond-> (m/calculate-normals geometry 0 38)
                                      (finishes style) (m/texture-all (finishes style) :size [120 120]))
                          :material (palette style)}) (range) parts)
           ids (mapv :id nodes)]
       (m/scene
        {:name "Raptor 3 SN1 — photo-based display reconstruction"
         :nodes (into [{:id :root :name "Raptor 3 / metres / Y-up"
                        :rotation [(- (math/sqrt 0.5)) 0 0 (math/sqrt 0.5)]
                        :scale [0.001 0.001 0.001] :children [:engine :transport]
                        :extras {"reference" "Raptor 3 SN1, August 2024 photographs"
                                 "fidelity" "Approximate exterior; not engineering CAD"}}
                       {:id :engine :name "Engine" :children (subvec ids 0 (count engine))}
                       {:id :transport :name "Removable transport fixture" :children (subvec ids (count engine))}]
                      nodes)})))

(defn assembly
  "Build individually closed solids with named parts and PBR materials.
  :quality is :draft or :high; :stand? adds the removable transport fixture."
  ([] (assembly {}))
  ([{:keys [quality stand?] :or {quality :high stand? true}}]
   (when-not (#{:draft :high} quality)
     (throw (ex-info "Quality must be :draft or :high" {:quality quality})))
   (binding [*segments* (if (= quality :draft) 64 160)]
     (parts-scene (engine-parts) (if stand? (stand) [])))))

(defn -main [& [filename quality fixture]]
  (when (and fixture (not= fixture "no-stand"))
    (throw (ex-info "Third argument must be no-stand or omitted" {:argument fixture})))
  (with-open [_ (PointerScope.)]
    (let [filename (or filename "target/raptor-3.glb") started (System/nanoTime)
          scene (assembly {:quality (keyword (or quality "high")) :stand? (not= fixture "no-stand")})]
      (io/make-parents filename)
      (m/export-scene scene filename)
      (println "Exported" filename "with" (- (count (:nodes scene)) 3) "validated parts in"
               (format "%.1f" (/ (- (System/nanoTime) started) 1e9)) "seconds")))
  (shutdown-agents))

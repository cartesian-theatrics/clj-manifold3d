(ns clj-manifold3d.scene-features
  "Shared authored glTF lights, cameras and emissive materials.")

(defn- finite? [x]
  (and (number? x) #?(:clj (Double/isFinite (double x)) :cljs (js/Number.isFinite x))))
(defn- rgb? [x] (and (vector? x) (= 3 (count x)) (every? #(and (finite? %) (<= 0 % 1)) x)))
(defn- fail! [message value] (throw (ex-info message {:value value})))

(defn validate-node! [{:keys [light camera]}]
  (when light
    (let [{:keys [type color intensity range inner-cone outer-cone]
           :or {color [1 1 1] intensity 1 inner-cone 0 outer-cone 0.7853981633974483}} light]
      (when-not (and (map? light) (every? #{:type :color :intensity :range :inner-cone :outer-cone :name} (keys light))
                     (#{:point :spot :directional} type) (rgb? color)
                     (finite? intensity) (<= 0 intensity)
                     (or (nil? range) (and (not= type :directional) (finite? range) (pos? range)))
                     (or (= type :spot) (not-any? #(contains? light %) [:inner-cone :outer-cone]))
                     (finite? inner-cone) (finite? outer-cone) (<= 0 inner-cone) (< inner-cone outer-cone)
                     (<= outer-cone 1.5707963267948966)
                     (or (nil? (:name light)) (string? (:name light))))
        (fail! "Invalid punctual light (cone angles are radians)" light))))
  (when camera
    (let [{:keys [type yfov znear zfar aspect-ratio] :or {type :perspective}} camera]
      (when-not (and (map? camera) (every? #{:type :yfov :znear :zfar :aspect-ratio} (keys camera))
                     (= type :perspective) (finite? yfov) (< 0 yfov 3.141592653589793)
                     (finite? znear) (pos? znear)
                     (or (nil? zfar) (and (finite? zfar) (> zfar znear)))
                     (or (nil? aspect-ratio) (and (finite? aspect-ratio) (pos? aspect-ratio))))
        (fail! "Invalid perspective camera (yfov is radians)" camera)))))

(defn validate-material! [material]
  (when material
    (when-not (and (map? material)
                   (every? #{:color :roughness :metalness :emissive :emissive-strength} (keys material))
                   (or (nil? (:color material))
                       (and (vector? (:color material)) (= 4 (count (:color material)))
                            (every? #(and (finite? %) (<= 0 % 1)) (:color material))))
                   (every? #(and (finite? %) (<= 0 % 1)) (vals (select-keys material [:roughness :metalness])))
                   (or (nil? (:emissive material)) (rgb? (:emissive material)))
                   (or (not (contains? material :emissive-strength))
                       (and (rgb? (:emissive material)) (finite? (:emissive-strength material))
                            (<= 0 (:emissive-strength material)))))
      (fail! "Invalid material: color, roughness, metalness, emissive RGB, emissive-strength" material))))

(defn- use-extension [gltf extension]
  (update gltf "extensionsUsed" #(vec (distinct (conj (vec %) extension)))))

(defn apply-material [gltf material]
  (validate-material! material)
  (if-not material gltf
    (cond->
      (update gltf "materials"
              (fn [materials]
                (mapv (fn [m]
                        (cond-> (update m "pbrMetallicRoughness" merge
                                        (cond-> {}
                                          (:color material) (assoc "baseColorFactor" (:color material))
                                          (contains? material :roughness) (assoc "roughnessFactor" (:roughness material))
                                          (contains? material :metalness) (assoc "metallicFactor" (:metalness material))))
                          (and (:color material) (< (nth (:color material) 3) 1)) (assoc "alphaMode" "BLEND")
                          (:emissive material) (assoc "emissiveFactor" (:emissive material))
                          (contains? material :emissive-strength)
                          (assoc-in ["extensions" "KHR_materials_emissive_strength" "emissiveStrength"] (:emissive-strength material))))
                      materials)))
      (contains? material :emissive-strength) (use-extension "KHR_materials_emissive_strength"))))

(defn decorate-nodes [gltf nodes]
  (reduce
    (fn [doc [index {:keys [light camera] :as node}]]
      (validate-node! node)
      (cond-> doc
        light
        (as-> d (let [lights-path ["extensions" "KHR_lights_punctual" "lights"]
                      light-index (count (get-in d lights-path))
                      value (cond-> {"type" (name (:type light)) "color" (get light :color [1 1 1])
                                     "intensity" (get light :intensity 1)}
                              (:name light) (assoc "name" (:name light))
                              (:range light) (assoc "range" (:range light))
                              (= :spot (:type light)) (assoc "spot" {"innerConeAngle" (get light :inner-cone 0)
                                                                     "outerConeAngle" (get light :outer-cone 0.7853981633974483)}))]
                  (-> d (update-in lights-path (fnil conj []) value)
                      (assoc-in ["nodes" index "extensions" "KHR_lights_punctual" "light"] light-index)
                      (use-extension "KHR_lights_punctual"))))
        camera
        (as-> d (let [camera-index (count (get d "cameras"))
                      perspective (cond-> {"yfov" (:yfov camera) "znear" (:znear camera)}
                                    (:zfar camera) (assoc "zfar" (:zfar camera))
                                    (:aspect-ratio camera) (assoc "aspectRatio" (:aspect-ratio camera)))]
                  (-> d (update "cameras" (fnil conj []) {"type" "perspective" "perspective" perspective})
                      (assoc-in ["nodes" index "camera"] camera-index))))))
    gltf (map-indexed vector nodes)))

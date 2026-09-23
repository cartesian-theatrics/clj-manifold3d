(ns clj-manifold3d.glb-assets
  "Pure glTF asset reference remapping, shared by both scene exporters."
  (:require [clojure.walk :as walk]
            [clojure.string :as str]))

(def ^:private tables ["bufferViews" "accessors" "images" "samplers" "textures" "materials" "meshes" "cameras" "nodes" "skins" "animations"])
(defn append-gltf
  "Append embedded core glTF assets, remapping all core references. Existing
  animation clips survive. Extensions with unknown index semantics are rejected."
  [ad bd base]
  (let [extensions (set (concat (get bd "extensionsUsed") (get bd "extensionsRequired")))]
    (when (seq (remove #{"KHR_materials_unlit" "KHR_texture_transform" "KHR_materials_emissive_strength" "KHR_lights_punctual"} extensions))
      (throw (ex-info "Cannot append extensions with unknown reference semantics" {:extensions extensions})))
    (let [light-path ["extensions" "KHR_lights_punctual" "lights"]
          light-offset (count (get-in ad light-path))
          offsets (zipmap tables (map #(count (get ad % [])) tables))
          shift (fn [obj key table] (if (contains? obj key) (update obj key + (offsets table)) obj))
          refs (fn [xs table] (mapv #(+ % (offsets table)) xs))
          attributes (fn [attrs] (into {} (map (fn [[k v]] [k (+ v (offsets "accessors"))]) attrs)))
          material (fn [m] (walk/postwalk
                             (fn [x] (if (map? x)
                                       (into {} (map (fn [[k v]]
                                                       [k (if (and (string? k) (str/ends-with? k "Texture") (map? v) (contains? v "index"))
                                                            (update v "index" + (offsets "textures")) v)]) x)) x)) m))
          convert {"bufferViews" #(-> % (assoc "buffer" 0) (update "byteOffset" (fnil + 0) base))
                   "accessors" #(cond-> (shift % "bufferView" "bufferViews")
                                  (get % "sparse") (update "sparse" (fn [s] (-> s (update "indices" shift "bufferView" "bufferViews") (update "values" shift "bufferView" "bufferViews")))))
                   "images" #(shift % "bufferView" "bufferViews")
                   "textures" #(-> % (shift "source" "images") (shift "sampler" "samplers"))
                   "materials" material
                   "meshes" #(update % "primitives" (fn [ps] (mapv (fn [p] (cond-> (-> p (update "attributes" attributes) (shift "indices" "accessors") (shift "material" "materials"))
                                                                             (get p "targets") (update "targets" (fn [ts] (mapv attributes ts))))) ps)))
                   "nodes" #(cond-> (-> % (shift "mesh" "meshes") (shift "camera" "cameras") (shift "skin" "skins"))
                              (some? (get-in % ["extensions" "KHR_lights_punctual" "light"]))
                              (update-in ["extensions" "KHR_lights_punctual" "light"] + light-offset)
                              (get % "children") (update "children" refs "nodes"))
                   "skins" #(-> % (update "joints" refs "nodes") (shift "skeleton" "nodes") (shift "inverseBindMatrices" "accessors"))
                   "animations" #(-> % (update "samplers" (fn [ss] (mapv (fn [s] (-> s (shift "input" "accessors") (shift "output" "accessors"))) ss)))
                                      (update "channels" (fn [cs] (mapv (fn [c] (update c "target" shift "node" "nodes")) cs))))}
          out (reduce (fn [d table] (if (seq (get bd table)) (update d table (fnil into []) (mapv (get convert table identity) (get bd table))) d)) ad tables)
          scene-index (get ad "scene" 0)
          out (if (seq (get out "scenes")) out (assoc out "scenes" [{"nodes" []}] "scene" 0))
          roots (get-in bd ["scenes" (get bd "scene" 0) "nodes"] [])
          out (update-in out ["scenes" scene-index "nodes"] (fnil into []) (refs roots "nodes"))
          out (reduce (fn [d k] (let [v (vec (distinct (concat (get ad k) (get bd k))))] (if (seq v) (assoc d k v) d))) out ["extensionsUsed" "extensionsRequired"])]
      (if (seq (get-in bd light-path)) (update-in out light-path (fnil into []) (get-in bd light-path)) out))))

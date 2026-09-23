(ns clj-manifold3d.model-options)

(defn- finite? [x] (and (number? x) #?(:clj (Double/isFinite (double x)) :cljs (js/Number.isFinite x))))
(defn- tuple! [label n xs]
  (when-not (and (sequential? xs) (= n (count xs)) (every? finite? xs))
    (throw (ex-info (str label " requires " n " finite numbers") {:value xs})))
  (vec xs))

(defn export-options [{:keys [tile-size] :or {tile-size 16} :as options}]
  (when-not (and (every? #{:tile-size} (keys options))
                 (integer? tile-size) (<= 2 tile-size 256))
    (throw (ex-info "Model export accepts only :tile-size, an integer from 2 to 256" {:options options})))
  tile-size)

(defn texture-options
  "Validate once for both bindings. Return native camelCase configuration."
  [{:keys [mapping name origin normal u-direction size pixel-size uv-rect opacity axes scale offset
           seam-angle padding pack? depth-map depth-scale depth-offset depth-fade depth-boundary] :as options}]
  (when-let [unknown (seq (remove #{:mapping :name :origin :normal :u-direction :size :pixel-size :uv-rect
                                   :opacity :axes :scale :offset :seam-angle :padding :pack?
                                   :depth-map :depth-scale :depth-offset :depth-fade :depth-boundary}
                                 (keys options)))]
    (throw (ex-info "Unknown texture options" {:options unknown})))
  (let [mapping (or mapping :geodesic) opacity (or opacity 1)
        rect (tuple! :uv-rect 4 (or uv-rect [0 0 1 1]))
        boundary (or depth-boundary :fade)
        scale (tuple! :scale 2 (if (number? scale) [scale scale] (or scale [1 1])))
        axes (mapv #(get {:x 0 :y 1 :z 2} % %) (or axes [:x :z]))
        pixel (or pixel-size 0)]
    (when-not (and (#{:geodesic :planar :box :unwrap} mapping) (finite? opacity) (<= 0 opacity 1)
                   (< (rect 0) (rect 2)) (< (rect 1) (rect 3)) (#{:fade :step} boundary)
                   (= 2 (count axes)) (every? #{0 1 2} axes) (apply not= axes)
                   (or (nil? name) (string? name))
                   (finite? pixel) (if (some? pixel-size) (pos? pixel) true))
      (throw (ex-info "Invalid texture mapping, opacity, UV rectangle, axes, or pixel size" {})))
    (when (and (nil? depth-map) (some #(contains? options %) [:depth-scale :depth-offset :depth-fade :depth-boundary]))
      (throw (ex-info "Depth options require :depth-map" {})))
    (when (and (not= mapping :geodesic) depth-map) (throw (ex-info "Depth requires :geodesic mapping" {})))
    (when (and (not= mapping :unwrap) (some #(contains? options %) [:seam-angle :padding :pack?]))
      (throw (ex-info "Atlas options require :unwrap mapping" {})))
    (when-let [unsupported
               (seq (filter #(contains? options %)
                            (case mapping
                              :box [:normal :u-direction :axes :pixel-size :uv-rect]
                              :unwrap [:origin :normal :u-direction :size :axes :offset :pixel-size :uv-rect]
                              [])))]
      (throw (ex-info "Options do not apply to this whole-surface mapping" {:mapping mapping :options unsupported})))
    (when (and (= mapping :box) (some zero? scale))
      (throw (ex-info "Box mapping requires nonzero scale" {})))
    (when (= mapping :unwrap)
      (when-not (and (apply = scale) (pos? (first scale))
                     (or (nil? seam-angle) (and (finite? seam-angle) (<= 0 seam-angle 180)))
                     (or (nil? padding) (and (finite? padding) (<= 0 padding) (< padding 0.5)))
                     (or (not (contains? options :pack?)) (boolean? pack?)))
        (throw (ex-info "Unwrap requires positive uniform scale, seam-angle [0,180], padding [0,0.5), and boolean pack?" {}))))
    (let [size (tuple! :size 2 (if (= mapping :geodesic) size (or size [1 1])))
          _ (when-not (every? pos? size) (throw (ex-info "Texture size must be positive" {})))
          depth-scale (if (nil? depth-scale) 1 depth-scale)
          depth-offset (or depth-offset 0)]
      (when-not (and (finite? depth-scale) (finite? depth-offset)
                     (or (nil? depth-fade) (and (finite? depth-fade) (<= 0 depth-fade))))
        (throw (ex-info "Depth scale, offset and fade must be finite" {})))
      (cond-> {"mapping" (clojure.core/name mapping) "name" (or name "") "opacity" opacity
               "origin" (tuple! :origin 3 (if (= mapping :geodesic) origin (or origin [0 0 0])))
               "normal" (tuple! :normal 3 (or normal [0 0 0]))
               "uDirection" (tuple! :u-direction 3 (or u-direction [1 0 0]))
               "size" size "pixelSize" pixel "uvRect" rect "axes" axes
               "scale" (tuple! :scale 2 scale) "offset" (tuple! :offset 2 (or offset [0 0]))
               "depthScale" depth-scale "depthOffset" depth-offset
               "depthFade" (if (nil? depth-fade) -1 depth-fade) "depthBoundary" (clojure.core/name boundary)}
        (= mapping :unwrap)
        (assoc "seamAngle" (or seam-angle 45) "padding" (or padding 0.01)
               "pack" (if (contains? options :pack?) pack? true))
        (sequential? depth-map)
        (merge (let [height (count depth-map) width (count (first depth-map))]
                 (when-not (and (<= 2 height) (<= 2 width) (<= (* width height) 2000000)
                                (every? #(and (sequential? %) (= width (count %))) depth-map)
                                (every? finite? (mapcat identity depth-map)))
                   (throw (ex-info "Depth requires a rectangular finite grid of at least 2x2" {})))
                 {"depthWidth" width "depthHeight" height "depthValues" (vec (mapcat identity depth-map))}))
        (and (some? depth-map) (not (sequential? depth-map))) (assoc "depthImage" depth-map)))))

(defn whole-surface-options
  "Default to box projection; never silently accept a local decal mapping."
  [options]
  (let [options (merge {:mapping :box} options)]
    (when-not (#{:box :unwrap :planar} (:mapping options))
      (throw (ex-info "texture-all requires :box, :unwrap or :planar mapping" {:options options})))
    options))

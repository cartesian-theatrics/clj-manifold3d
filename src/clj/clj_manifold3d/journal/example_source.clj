(ns clj-manifold3d.journal.example-source
  "Build editable, staged journals from canonical examples without evaluating them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [zprint.core :as zprint]
            [clojure.walk :as walk]
            [clj-manifold3d.flag-example :as flag]
            [clj-manifold3d.journal.namespace :as ns-form]))

(defn- parsed [source]
  (let [{:keys [forms warning]} (ns-form/parse-code source)]
    (when warning (throw (ex-info "Cannot read journal example" {:warning warning})))
    forms))

(defn- example [file] (parsed (slurp (io/file "examples" file))))
(defn- code [form]
  ;; These are executable forms, not data: keep def/let bodies and keyword
  ;; arguments in normal Clojure layout. Original source sections stay intact.
  (zprint/zprint-str form {:width 88 :map {:comma? false :sort? false}}))
(defn- section [forms start end]
  (let [names (mapv #(second (:form %)) forms)
        a (.indexOf names start) b (.indexOf names end)]
    (when-not (<= 0 a b) (throw (ex-info "Missing example section" {:start start :end end})))
    (str/join "\n\n" (map :source (subvec forms a b)))))
(defn- named [forms name]
  (or (some #(when (= name (second (:form %))) %) forms)
      (throw (ex-info "Missing example definition" {:name name}))))
(defn- joined [& sources] (str/join "\n\n" sources))
(defn- definitions [bindings names]
  (let [values (into {} (map vec (partition 2 bindings)))]
    (str/join "\n\n" (map #(code (list 'def % (get values %))) names))))
(defn- document [namespace title specs introduction stages]
  (let [prefix (str/replace namespace "." "-")]
  {:namespace namespace :title title :revision 0
   :ns-source (ns-form/declaration namespace specs)
   :blocks (vec (cons {:id (str prefix "-intro") :kind "prose" :source introduction :hidden false}
                     (mapcat (fn [[id prose source]]
                               [{:id (str prefix "-" id "-notes") :kind "prose" :source prose :hidden false}
                                {:id (str prefix "-" id) :kind "code" :source source :hidden false}]) stages)))}))

(def imports ['[clj-manifold3d.core :as m] '[clj-manifold3d.math :as math]])
(defn- parts-view [expression] (str "(m/scene {:nodes (material-nodes " expression ")})"))
(defn- scene-view [nodes channels]
  (code (list 'm/scene (cond-> {:nodes nodes}
                        channels (assoc :animations [{:name "Assembly study" :channels channels}])))))

(defn castle-documents []
  (let [a (example "fairytale_castle.clj") n (example "fairytale_castle_night.clj")
        ;; Promote the canonical assembly's local bindings into editable stages.
        assembly-let (last (:form (named n 'assembly)))
        bindings (second assembly-let) values (into {} (map vec (partition 2 bindings)))
        final-scene (walk/postwalk-replace
                     {'(terrain) 'landscape '(star-field) 'stars '(lighting) 'lights
                      'fireworks 'bursts 'arc 'arc-particles}
                     (last assembly-let))]
    [(document "journal.castle-architecture" "Castle · architecture" imports
       "# Castle architecture\n\nBuild the vocabulary from a single arch to the complete castle. Every code panel returns geometry: run a panel to inspect that part, or Run page to keep the whole construction story visible. The night scene imports this namespace and reuses the finished architecture. Functions come from examples/fairytale_castle.clj."
       [["arch" "## Begin with an arch\n\nA cross-section is a real 2D result. Quadratic shoulders make the pointed profile; the same construction also makes the bridge's round arches."
         (joined (section a 'palette 'arch) "(arch-section 4 7 true)")]
        ["window" "## Cut a window into masonry\n\nExtrude the profile, add a recessed pane and surround, then cut the opening through the finished stone and trim. Rotate the wall to inspect the recess."
         (joined (section a 'arch 'pennant) (:source (named a 'material-nodes))
                 (parts-view "(concat [(part :stone (block 6 2 9 0 0 0))]\n                         (window 2 5 0 -0.95 2))"))]
        ["roof" "## Stack a tiled roof and pennant\n\nTapered cylinders form the roof; narrow raised courses catch the light."
         (joined (section a 'pennant 'tower) (parts-view "(conical-roof 4 12 0 true)"))]
        ["tower" "## Assemble one tower\n\nRepeat recessed windows around the wall, then add stone courses, cornices and the roof."
         (joined (:source (named a 'tower))
                 (parts-view "(tower {:x 0 :y 0 :radius 5.5 :height 22\n                              :roof-height 15 :flag? true})"))]
        ["hall" "## A hall with battlements\n\nThe rectangular building uses the same window vocabulary. Decorations are cut after assembly so windows remain open."
         (joined (section a 'battlements 'gable) (parts-view "(hall 28 24 39 0 0 4)"))]
        ["gatehouse" "## Join the gatehouse and curtain walls\n\nThe central passage is a boolean cut. The gable, rose window and walls establish the front elevation."
         (joined (section a 'gable 'bridge)
                 (parts-view "(concat (gatehouse) (curtain -23) (curtain 23))"))]
        ["bridge" "## Nine spans reach the bank\n\nRound arch cuts, individual voussoirs, parapets and lamps make the bridge. These are the same nine spans used by the night scene."
         (joined (:source (named a 'bridge)) (parts-view "(bridge 9)"))]
        ["architecture" "## Bring the architecture together\n\nPlace the towers, halls and gatehouse on their foundations and attach the bridge. Keep this assembled value: the night journal imports it without rebuilding the castle."
         (joined (section a 'tower-layout 'material-nodes) (:source (named a 'assembly))
                 "(def architecture (assembly {:water? false :bridge-spans 9}))\n\narchitecture")]])
     (document "journal.castle-night" "Castle · night scene"
       (into imports ['[clj-manifold3d.texture :as texture] '[journal.castle-architecture :as castle]])
       "# A wish over the castle\n\nA visual construction journal: stone becomes a castle, surfaces become mountains, and animated particles bring the night to life. Every code panel leaves a model below it. Run page evaluates the stages in order; the full landscape can take a minute or more. Edit Castle · architecture beside this document to change the buildings. Bloom and water reflections belong to the standalone cinematic viewer, not the exported GLB."
       [["stone" "## Make the stone material\n\nGenerate a seamless limestone PNG and wrap a small masonry sample. No image files or network requests are needed."
         (joined (section n 'duration 'facing) (definitions bindings '[stone])
                 "(-> (m/cube 24 12 18)\n    (m/texture-all stone :size [16 16]))")]
        ["castle" "## Dress the castle and bridge\n\nReuse the architecture from the neighboring journal. Apply repeating stone to walls and trim, and warm emission to the window panes."
         (joined (section n 'glow 'compact) "(def castle castle/architecture)"
                 (definitions bindings '[masonry])
                 (code (list 'def 'castle-nodes (list 'vec (values 'castle-nodes))))
                 "(m/scene {:nodes castle-nodes})")]
        ["landform" "## Raise a landscape from a height field\n\nOverlapping peaks create saddles and ridges. Inspect the raw closed surface before assigning elevation bands. A graded corridor will meet the bridge."
         (joined (:source (named n 'compact)) (section n 'blend 'terrain)
                 "(def landform (land-surface terrain-height))\n\nlandform")]
        ["terrain" "## Add rock, snow, shoreline and trees\n\nSplit that same landform into elevation bands. Native ray queries seat the pines on the surface. Now set the textured castle into its landscape."
         (joined (:source (named n 'terrain))
                 "(def landscape (terrain landform))\n\n(def stage-nodes (vec (concat castle-nodes landscape)))\n\n(m/scene {:nodes stage-nodes})")]
        ["stars" "## Put stars behind the mountains\n\nOne compact mesh holds the distant star field. Inspect the sky on its own here; the final scene will place it behind the mountains."
         (joined (:source (named n 'star-field))
                 "(def stars (star-field))\n\n(m/scene {:nodes [stars]})")]
        ["fireworks" "## Animate the fireworks\n\nShared particle geometry moves along keyframed trajectories—no boolean rebuilding per frame. Play the preview to watch the four bursts."
         (joined (section n 'facing 'glow) (section n 'animate-particle 'blend)
                 (:source (named n 'fireworks)) (definitions bindings '[spark])
                 "(def bursts (fireworks spark))"
                 (scene-view '(:nodes bursts) '(:channels bursts)))]
        ["arc" "## Draw a traveling sparkle arc\n\nA moving comet leaves a fading trail. Inspect the isolated effect here, then combine it with the fireworks in the final scene. The castle and terrain do not need rebuilding."
         (joined (section n 'arc-point 'lighting) "(def arc-particles (sparkle-arc spark))"
                 (scene-view '(:nodes arc-particles) '(:channels arc-particles)))]
        ["lights" "## Illuminate the castle\n\nInspect the architecture under six authored lights instead of the viewer's default illumination. Restrained moonlight and warm front fill preserve the stone's contrast."
         (joined (:source (named n 'lighting)) "(def lights (lighting))"
                 (scene-view '(vec (concat castle-nodes lights)) nil))]
        ["camera" "## Frame the final animated scene\n\nA gentle camera dolly keeps the head-on composition. The scene below reuses all previous stages; download it as one textured, lit and animated GLB."
         (joined (definitions bindings '[camera-track])
                 (code (list 'defn 'assembly [] final-scene)) "(assembly)")]])]))

(defn flag-document []
  (let [bindings (second (:form (last (parsed flag/source))))
        values (into {} (map vec (partition 2 bindings)))
        ;; Same surface walk, first without displacement, then with cloth depth.
        surface (values 'surface)
        texture-form (last surface)
        flat-texture (apply list (take-while #(not= :depth-map %) texture-form))
        flat-surface (apply list (concat (butlast surface) [flat-texture]))]
    (document "journal.flag-uv" "American flag · surface UV"
      (into imports ['[clj-manifold3d.texture :as texture]])
      "# Draw, bake, wrap, ripple\n\nBuild an American flag as colored geometry, bake it, and walk the image onto a sphere. Each code panel returns a model. The last two views compare ordinary surface UV mapping with a cloth-like displacement."
      [["stripes" "## Thirteen stripes\n\nStart with a white slab and seven red stripes. Everything is real, colored Manifold geometry."
        (joined (definitions bindings '[red white blue stripe-height canton-width canton-height stripes])
                "(def striped-cloth\n  (apply m/union (m/color (m/cube 19 10 0.1) white) stripes))\n\nstriped-cloth")]
       ["star" "## One five-pointed star\n\nAlternate the outer and inner radii to make the profile, then extrude it into a white solid."
        (joined (definitions bindings '[star]) "star")]
       ["flag" "## Fifty stars on the canton\n\nArrange nine alternating rows, add the blue canton, and combine them with the stripes."
        (joined (definitions bindings '[stars flag]) "flag")]
       ["bake" "## Bake the colored geometry\n\nThe flat board below carries the baked image, not the original fifty star meshes. A neutral margin is included for mapping onto the sphere."
        (joined (definitions bindings '[image])
                "(-> (m/cube 23.75 12.5 0.1)\n    (m/texture image :mapping :planar\n      :axes [:x :y] :scale [0.042105263157894736 0.08]))")]
       ["wrap" "## Walk the flag onto the sphere\n\nThe native halfedge walk follows surface distance. This stage has UVs but no depth displacement, making it a useful baseline."
        (joined (code (list 'def 'wrapped flat-surface)) "wrapped")]
       ["wave" "## Lift the flag into a cloth wave\n\nA numeric depth grid holds the pole edge steady and increases the ripple toward the free edge. Depth is relative to the surface normal; :step supplies boundary walls. Compare this to the smooth sphere above."
        (joined (definitions bindings '[wave-columns wave-rows wave-depth flag-depth surface]) "surface")]])))

(defmacro embedded-castle-documents [] (castle-documents))
(defmacro embedded-flag-document [] (flag-document))

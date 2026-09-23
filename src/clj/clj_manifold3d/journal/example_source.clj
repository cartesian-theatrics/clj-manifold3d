(ns clj-manifold3d.journal.example-source
  "Seed editable journals from canonical example source; never load/evaluate it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-manifold3d.journal.namespace :as ns-form]))

(defn- sections [file ranges]
  (let [{:keys [forms warning]} (ns-form/parse-code (slurp (io/file "examples" file)))
        names (mapv #(second (:form %)) forms)]
    (when warning (throw (ex-info "Cannot read castle example" {:file file :warning warning})))
    (mapv (fn [[start end]]
            (let [a (.indexOf names start) b (.indexOf names end)]
              (when-not (<= 0 a b) (throw (ex-info "Missing example section" {:file file :start start :end end})))
              (str (str/join "\n\n" (map :source (subvec forms a b))) "\n\nnil"))) ranges)))

(defn- document [namespace title specs entries]
  {:namespace namespace :title title :revision 0
   :ns-source (ns-form/declaration namespace specs)
   :blocks (mapv (fn [i [kind source]]
                   {:id (str (str/replace namespace "." "-") "-" i)
                    :kind kind :source source :hidden false}) (range) entries)})

(defn castle-documents []
  (let [[shapes buildings assembly] (sections "fairytale_castle.clj"
                                            '[[palette tower] [tower bridge] [bridge check-window-visibility!]])
        [materials terrain particles scene] (sections "fairytale_castle_night.clj"
                                                      '[[duration blend] [blend star-field] [star-field lighting] [lighting -main]])
        imports ['[clj-manifold3d.core :as m] '[clj-manifold3d.math :as math]]]
    [(document "journal.castle-architecture" "Castle · architecture" imports
       [["prose" "# Castle architecture\n\nThe building vocabulary used by **Castle · night scene**. These are the actual modeling functions, not a prebuilt GLB or an entrypoint. Edit roofs, windows or towers here; the night scene requires this document as an ordinary namespace.\n\nThe source is seeded from `examples/fairytale_castle.clj`. Your edits stay in your journal and are never reset on restart."]
        ["prose" "## Stone, arches and windows\n\nOpenings remain construction geometry until the finished masonry is cut, so later decorations cannot fill the windows."]
        ["code" shapes]
        ["prose" "## Towers and halls"] ["code" buildings]
        ["prose" "## A bridge to land\n\nThe night scene requests nine spans. `material-nodes` consolidates construction provenance only after grouping by material."]
        ["code" assembly]])
     (document "journal.castle-night" "Castle · night scene"
       (into imports ['[clj-manifold3d.texture :as texture] '[journal.castle-architecture :as castle]])
       [["prose" "# A wish over the castle\n\nA complete, editable scene: textured stone, recessed windows, a bridge reaching land, surface-built mountains and snowcaps, trees, six lights, fireworks and a ten-second animation. **Run page** evaluates everything; the first full build is substantial and may take a minute or more.\n\nThis document requires **Castle · architecture**. Both documents contain the implementation, and both are ordinary namespaces. Source: `examples/fairytale_castle_night.clj`. The standalone cinematic preview additionally supplies bloom and water reflections; those are renderer effects, not GLB geometry."]
        ["prose" "## A repeating stone material\n\nThe PNG is generated from a pixel function—no JVM image classes or external files."] ["code" materials]
        ["prose" "## Shape the landscape\n\nEdit `peaks` and `terrain-height`. `m/surface` creates closed solids; native BVH ray queries seat the trees, while a level cut supports the bridge approach."] ["code" terrain]
        ["prose" "## Fireworks and the sparkle trail\n\nReusable particle geometry follows keyframed translation and scale; there is no per-frame boolean rebuilding."] ["code" particles]
        ["prose" "## Light and assemble\n\nThe scene owns its materials, lights, camera and animation. The viewer starts at the authored camera; orbit or pop it out to explore."] ["code" scene]
        ["prose" "## Render the scene\n\nChange the functions above, then run this panel. Download the result as a textured, animated GLB."]
        ["code" "(assembly)"]])]))

(defmacro embedded-castle-documents [] (castle-documents))

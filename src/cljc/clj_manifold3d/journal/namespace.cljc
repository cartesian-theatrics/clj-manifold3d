(ns clj-manifold3d.journal.namespace
  "Explicit namespace declarations and a one-time, non-evaluating import migration."
  (:require [clojure.string :as str]
            [edamame.core :as edamame]))

(def parse-options
  (edamame/normalize-opts
   {:all true :read-eval false :read-cond :allow :features #{:cljs} :readers {'js identity}
    :auto-resolve (fn [alias] (if (= alias :current) 'journal.current (symbol (str alias))))}))

(defn parse-code [source]
  (let [r (edamame/source-reader (or source ""))]
    (loop [forms []]
      (let [result (try {:next (edamame/parse-next+string r parse-options)}
                        (catch #?(:clj Exception :cljs :default) e {:warning (ex-message e)}))]
        (cond (:warning result) {:forms forms :warning (:warning result)}
              (= ::edamame/eof (first (:next result))) {:forms forms}
              :else (recur (conj forms {:form (first (:next result)) :source (second (:next result))})))))))

(defn declaration
  ([namespace] (declaration namespace ['[clj-manifold3d.core :as m]]))
  ([namespace specs]
   (str "(ns " namespace
        (when (seq specs) (str "\n  (:require " (str/join "\n            " (map pr-str (distinct specs))) ")")) ")")))

(defn header-id [namespace]
  ;; Escape underscores first so a.b and a_db never share result/context IDs.
  (str "namespace-" (-> (or namespace "") (str/replace "_" "__") (str/replace "." "_d"))))
(defn header [{:keys [namespace ns-source]}]
  (when ns-source {:id (header-id namespace) :kind "namespace" :source ns-source :hidden false}))
(defn panels [document] (into (cond-> [] (:ns-source document) (conj (header document))) (:blocks document)))

(defn declaration-error [namespace source]
  (let [{:keys [forms warning]} (parse-code source) f (:form (first forms))]
    (cond
      warning (str "Incomplete namespace declaration: " warning)
      (not (and (= 1 (count forms)) (seq? f) (= 'ns (first f))))
      "The namespace header must contain exactly one (ns …) form."
      (not= (symbol namespace) (second f))
      (str "Keep the document namespace " namespace "; edit its :require entries here.")
      (some #(and (seq? %) (not (#{:require :refer-clojure :import} (first %)))) (drop 2 f))
      "Unsupported namespace clause.")))

(defn assert-declaration! [namespace source]
  (when-let [message (declaration-error namespace source)]
    (throw (ex-info message {:type :namespace-declaration :namespace namespace :status 400})))
  source)

(def import-ops '#{require clojure.core/require cljs.core/require use clojure.core/use
                   import clojure.core/import ns clojure.core/ns in-ns clojure.core/in-ns})
(defn import-form? [f] (and (seq? f) (import-ops (first f))))
(defn imports-in-body? [source]
  (letfn [(walk [f]
            (cond (and (seq? f) (#{'quote 'comment} (first f))) false
                  (import-form? f) true
                  (coll? f) (boolean (some walk f))
                  :else false))]
    (boolean (some (comp walk :form) (:forms (parse-code source))))))

(defn assert-body! [source]
  (when (imports-in-body? source)
    (throw (ex-info "Put imports in the document's ns header (:require / :import), not in a code panel."
                    {:type :namespace-declaration :status 400})))
  source)

(defn unquote-form [f] (if (and (seq? f) (= 'quote (first f))) (second f) f))
(defn require-specs [form]
  (when (seq? form)
    (case (str (first form))
      ("require" "clojure.core/require" "cljs.core/require") (map unquote-form (rest form))
      "ns" (mapcat rest (filter #(and (seq? %) (= :require (first %))) (drop 2 form)))
      [])))

(defn- offset [source row col]
  (+ (reduce + (map #(inc (count %)) (take (dec row) (str/split source #"\n" -1)))) (dec col)))

(defn hoist-requires
  "Extract literal top-level requires without reprinting remaining forms or
  comments. Never evaluates forms or guesses incomplete imports."
  [source]
  (let [forms (:forms (parse-code source))
        imports (filter #(and (seq? (:form %)) (#{'require 'clojure.core/require 'cljs.core/require} (first (:form %)))) forms)
        specs (vec (mapcat (comp require-specs :form) imports))]
    (when-not (every? #(or (symbol? %) (and (vector? %) (symbol? (first %)))) specs)
      (throw (ex-info "Cannot migrate a computed require; move it into the ns header manually." {})))
    {:specs specs
     :source (reduce (fn [s {:keys [form]}]
                       (let [{:keys [row col end-row end-col]} (meta form)
                             start (offset source row col) end (offset source end-row end-col)]
                         (str (subs s 0 start) (subs s end)))) source (reverse imports))}))

(def previous-imports
  ['[clj-manifold3d.core :as m] '[clj-manifold3d.texture :as texture]
   '[clj-manifold3d.animation :as animation] '[clj-manifold3d.math :as math]])

(defn add-requires
  "Append literal libspecs without reformatting an existing namespace header."
  [namespace source specs]
  (assert-declaration! namespace source)
  (let [form (:form (first (:forms (parse-code source))))
        specs (remove (set (require-specs form)) specs)
        clause (first (filter #(and (seq? %) (= :require (first %))) (drop 2 form)))
        {:keys [end-row end-col]} (meta (or clause form))
        at (dec (offset source end-row end-col))]
    (if (empty? specs) source
      (str (subs source 0 at)
           (if clause (str "\n            " (str/join "\n            " (map pr-str specs)))
               (str "\n  (:require " (str/join "\n            " (map pr-str specs)) ")"))
           (subs source at)))))

(defn restore-panel
  "Undo can restore a panel deleted before the namespace-header migration.
  Hoist its literal requires at restoration time, not into an unused namespace."
  [document panel]
  (if (= "code" (:kind panel))
    (let [{:keys [source specs]} (hoist-requires (:source panel))]
      [(cond-> document (seq specs) (update :ns-source #(add-requires (:namespace document) % specs)))
       (assoc panel :source source)])
    [document panel]))

(defn migrate [document]
  (if (:ns-source document) document
    (let [entries (mapv #(when (= "code" (:kind %)) (hoist-requires (:source %))) (:blocks document))
          specs (vec (distinct (concat previous-imports (mapcat :specs entries))))]
      (assoc document :ns-source (declaration (:namespace document) specs)
             :blocks (mapv (fn [b entry] (if entry (assoc b :source (:source entry)) b)) (:blocks document) entries)))))

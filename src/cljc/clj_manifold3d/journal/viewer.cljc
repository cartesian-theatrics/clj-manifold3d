(ns clj-manifold3d.journal.viewer
  "Serializable viewer preferences and model-unit measurements."
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])))

(defn finite? [n]
  (and (number? n) #?(:clj (Double/isFinite (double n)) :cljs (js/Number.isFinite n))))
(defn point? [p] (and (vector? p) (= 3 (count p)) (every? finite? p)))
(defn camera? [{:keys [position target near far]}]
  (and (point? position) (point? target) (not= position target)
       (finite? near) (finite? far) (< 0 near far)))
(defn settings [s] (merge {:grid true} (when s (edn/read-string s))))
(defn valid-settings? [s]
  (try
    (let [v (settings s)]
      (and (boolean? (:grid v)) (or (nil? (:camera v)) (camera? (:camera v)))))
    (catch #?(:clj Exception :cljs :default) _ false)))
(defn distance [[a b]]
  (when (and (point? a) (point? b))
    (#?(:clj Math/sqrt :cljs js/Math.sqrt) (reduce + (map #(let [d (- %1 %2)] (* d d)) a b)))))

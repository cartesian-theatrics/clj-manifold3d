(ns clj-manifold3d.fixtures
  (:require [clj-manifold3d.runtime :as rt]))

(defonce data (atom {}))
(defn path [name] (str (if (exists? js/require) "public/fixtures/" "/fixtures/") name))
(defn prepare! []
  (-> (js/Promise.all (to-array (map #(rt/read-bytes (path %)) ["parity.json" "depth8.png" "depth16.png" "font.ttf"])))
      (.then (fn [values]
               (reset! data {:parity (js->clj (js/JSON.parse (.decode (js/TextDecoder.) (aget values 0))) :keywordize-keys true)
                             :depth8 (aget values 1) :depth16 (aget values 2) :font (aget values 3)})))))

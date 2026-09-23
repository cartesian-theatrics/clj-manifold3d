(ns clj-manifold3d.journal.stream
  "Validate complete patch operations in a streamed JSON plan. Incomplete
  new-panel text may be previewed, but a partial patch is never applied."
  (:require [clojure.string :as str]
            [clj-manifold3d.journal.generation :as gen]))

(defn- invalid [] (throw (ex-info "Invalid JSON prefix" {})))
(defn- whitespace [s i]
  (loop [i i] (if (and (< i (count s)) (#{" " "\n" "\r" "\t"} (subs s i (inc i))))
               (recur (inc i)) i)))

(defn- decoded [parts]
  (let [s (apply str parts)
        last-code (when (seq s) #?(:clj (int (.charAt ^String s (dec (count s))))
                                    :cljs (.charCodeAt s (dec (count s)))))]
    ;; Do not expose half of a UTF-16 surrogate pair while an escape is split.
    (if (and last-code (<= 0xD800 last-code 0xDBFF)) (subs s 0 (dec (count s))) s)))

(defn- string-prefix [s start]
  (loop [i (inc start) parts []]
    (if (>= i (count s)) {:value (decoded parts) :end i :complete? false}
      (let [c (subs s i (inc i))]
        (cond
          (= c "\"") {:value (decoded parts) :end (inc i) :complete? true}
          (= c "\\")
          (if (>= (inc i) (count s)) {:value (decoded parts) :end (count s) :complete? false}
            (let [e (subs s (inc i) (+ i 2))]
              (if (= e "u")
                (if (> (+ i 6) (count s)) {:value (decoded parts) :end (count s) :complete? false}
                  (let [hex (subs s (+ i 2) (+ i 6))]
                    (when-not (re-matches #"[0-9a-fA-F]{4}" hex) (invalid))
                    (recur (+ i 6) (conj parts #?(:clj (str (char (Integer/parseInt hex 16)))
                                                  :cljs (js/String.fromCharCode (js/parseInt hex 16)))))))
                (if-let [decoded (get {"\"" "\"" "\\" "\\" "/" "/" "n" "\n" "r" "\r"
                                      "t" "\t" "b" "\b" "f" "\f"} e)]
                  (recur (+ i 2) (conj parts decoded)) (invalid)))))
          (re-find #"[\x00-\x1f]" c) (invalid)
          :else (recur (inc i) (conj parts c)))))))

(declare value-prefix)
(defn- collection-prefix [s start object? depth]
  (let [close (if object? "}" "]") initial (if object? {} [])]
    (loop [i (whitespace s (inc start)) value initial after-comma? false]
      (cond
        (>= i (count s)) {:value value :end i :complete? false}
        (= close (subs s i (inc i)))
        (if after-comma? (invalid) {:value value :end (inc i) :complete? true})
        :else
        (let [key-result (when object?
                           (when-not (= "\"" (subs s i (inc i))) (invalid))
                           (string-prefix s i))
              key (:value key-result) colon (when object? (whitespace s (:end key-result)))]
          (if (and object? (or (not (:complete? key-result)) (>= colon (count s))))
            {:value value :end (count s) :complete? false}
            (let [_ (when (and object? (not= ":" (subs s colon (inc colon)))) (invalid))
                  _ (when (and object? (contains? value key)) (invalid))
                  result (value-prefix s (if object? (inc colon) i) (inc depth))
                  value' (if (contains? result :value)
                           (if object? (assoc value key (:value result))
                               (with-meta (conj value (:value result))
                                 {:complete-count (+ (count value) (if (:complete? result) 1 0))})) value)
                  end (whitespace s (:end result))]
              (if-not (:complete? result)
                {:value value' :end end :complete? false
                 :partial-path (into [(if object? key (count value))] (:partial-path result))}
                (cond
                  (>= end (count s)) {:value value' :end end :complete? false}
                  (= close (subs s end (inc end))) {:value value' :end (inc end) :complete? true}
                  (= "," (subs s end (inc end))) (recur (whitespace s (inc end)) value' true)
                  :else (invalid))))))))))

(defn- value-prefix [s start depth]
  (when (> depth 8) (invalid))
  (let [i (whitespace s start)]
    (if (>= i (count s)) {:end i :complete? false}
      (case (subs s i (inc i))
        "\"" (string-prefix s i)
        "{" (collection-prefix s i true depth)
        "[" (collection-prefix s i false depth)
        "n" (let [tail (subs s i)]
              (cond (str/starts-with? tail "null") {:value nil :end (+ i 4) :complete? true}
                    (str/starts-with? "null" tail) {:end (count s) :complete? false}
                    :else (invalid)))
        (invalid)))))

(defn preview
  "Return the valid sequential prefix of operations. Only insert.after may
  be unfinished; patches wait for their closing brace, then apply atomically."
  ([request text] (preview request text [] false))
  ([request text prefix complete-only?]
  (try
    (let [{:keys [value partial-path end]} (value-prefix text 0 0)
          edits (get value "edits")
          _ (when (< (whitespace text end) (count text)) (invalid))]
      (if-not (vector? edits) prefix
        (loop [i 0 accepted (vec prefix)]
          (if (>= i (min 100 (count edits))) accepted
            (let [raw (get edits i)
                  eligible? (or (< i (or (:complete-count (meta edits)) 0))
                                (and (not complete-only?) (#{"insert" "create"} (get raw "action"))
                                     (string? (get raw "after"))
                                     (or (= ["edits" i "after"] partial-path)
                                         (= ["edits" i] partial-path))))
                  next (when (and eligible? (map? raw))
                         (try
                           (:edits (gen/output-plan request
                                     {:summary "" :edits (conj accepted (into {} (map (fn [[k v]] [(keyword k) v]) raw)))}))
                           (catch #?(:clj Exception :cljs :default) _ nil)))]
              (if next (recur (inc i) next) accepted))))))
    (catch #?(:clj Exception :cljs :default) _ prefix))))

(ns clj-manifold3d.journal.context
  "Pure, non-evaluating context selection and namespace dependency discovery."
  (:require [clojure.string :as str]
            [clj-manifold3d.journal.namespace :as ns-form]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])))

(def defaults {:scope "document" :roles {} :dependencies "signatures" :expanded [] :allow-create-namespaces? false})
(defn settings [panel]
  (merge defaults (when-let [s (not-empty (:context-settings panel))] (edn/read-string s))))
(defn valid-settings? [s]
  (try
    (let [{:keys [scope roles dependencies expanded allow-create-namespaces?]} (settings {:context-settings s})]
      (and (#{"document" "section" "selected"} scope)
           (boolean? allow-create-namespaces?)
           (map? roles) (<= (count roles) 500)
           (every? (fn [[id role]] (and (string? id) (#{"target" "reference" "exclude"} role))) roles)
           (#{"none" "signatures" "implementations"} dependencies)
           (vector? expanded) (<= (count expanded) 100) (every? string? expanded)))
    (catch #?(:clj Exception :cljs :default) _ false)))

(defn heading-level [panel]
  (when (= "prose" (:kind panel))
    (some-> (re-find #"(?m)^ {0,3}(#{1,6})\s+" (:source panel)) second count)))

(defn section-ids [blocks prompt-id]
  (let [i (or (first (keep-indexed #(when (= prompt-id (:id %2)) %1) blocks)) 0)
        start (or (last (keep-indexed #(when (heading-level %2) %1) (take (inc i) blocks))) 0)
        level (or (heading-level (get blocks start)) 6)
        end (or (first (keep-indexed #(when (and (> %1 i) (heading-level %2) (<= (heading-level %2) level)) %1) blocks))
                (count blocks))]
    (set (map :id (subvec (vec blocks) start end)))))

(defn panel-roles [document prompt-id]
  (let [blocks (ns-form/panels document) prompt (first (filter #(= prompt-id (:id %)) blocks))
        {:keys [scope roles]} (settings prompt) section (section-ids blocks prompt-id)]
    (into {} (for [p blocks :when (#{"code" "prose" "namespace"} (:kind p))]
               [(:id p) (cond (= prompt-id (:id p)) "prompt"
                              (contains? roles (:id p)) (get roles (:id p))
                              (= "namespace" (:kind p)) "target"
                              (= scope "document") "target"
                              (and (= scope "section") (section (:id p))) "target"
                              :else "exclude")]))))

(def parse-code ns-form/parse-code)
(defn- imports [forms]
  (reduce (fn [m spec]
            (if-not (and (vector? spec) (symbol? (first spec))) m
              (let [lib (str (first spec)) opts (into {} (map vec (partition 2 (rest spec))))]
                (-> m
                    (update :aliases #(cond-> % (:as opts) (assoc (str (:as opts)) lib)))
                    (update :refers into (for [s (when (vector? (:refer opts)) (:refer opts))]
                                           [(str (get (:rename opts) s s)) (str lib "/" s)]))))))
          {:aliases {} :refers {}} (mapcat (comp ns-form/require-specs :form) forms)))

(defn- definition [namespace panel {:keys [form source]}]
  (when (and (seq? form) (#{'def 'defonce 'defn 'defn- 'defmacro} (first form)) (symbol? (second form)))
    (let [[op sym & body] form function? (#{'defn 'defn- 'defmacro} op)
          docstring (when (and (string? (first body)) (or function? (next body))) (first body))
          body (if docstring (next body) body) attrs (when (and function? (map? (first body))) (first body))
          body (if attrs (next body) body)
          arglists (when function? (if (vector? (first body)) [(first body)] (keep #(when (seq? %) (first %)) body)))]
      {:symbol (str namespace "/" sym) :name (str sym) :namespace namespace :panel (:id panel)
       :private? (boolean (or (= op 'defn-) (:private (meta sym)) (:private attrs)))
       :doc (or docstring (:doc (meta sym)) (:doc attrs) "")
       :signature (if function? (str "(" op " " sym " " (str/join " " (map pr-str arglists)) ")")
                      (str "(" op " " sym
                           (when (and (= 1 (count body)) (or (number? (first body)) (keyword? (first body))
                                                            (string? (first body)) (boolean? (first body))))
                             (str " " (pr-str (first body)))) ")"))
       :source source :alias (when (and (not function?) (symbol? (first body))) (str (first body)))})))

(defn- binding-symbols [form]
  (cond (symbol? form) (if (= '& form) #{} #{(symbol (name form))})
        (vector? form) (reduce into #{} (map binding-symbols form))
        (map? form) (reduce (fn [out [k v]]
                             (into out (cond (#{:keys :syms :strs} k) (set (map #(symbol (name %)) v))
                                             (= :as k) (binding-symbols v)
                                             (= :or k) #{}
                                             :else (binding-symbols k)))) #{} form)
        :else #{}))

(defn- symbols-in
  ;; Conservative static references. Quoted data, strings and comments are not
  ;; dependencies. Macro-generated references cannot be inferred without eval.
  ([form] (symbols-in form #{}))
  ([form bound]
   (cond (symbol? form) (when-not (bound form) [form])
         (and (seq? form) (= 'quote (first form))) []
         (and (seq? form) (#{'defn 'defn- 'defmacro 'fn 'fn*} (first form)))
         (let [body (rest form) named? (symbol? (first body))
               bound (cond-> bound named? (conj (first body)))
               body (if named? (next body) body)
               body (drop-while #(or (string? %) (map? %)) body)
               arities (if (vector? (first body)) [body] body)]
           (mapcat (fn [[params & exprs]] (mapcat #(symbols-in % (into bound (binding-symbols params))) exprs)) arities))
         (and (seq? form) (#{'let 'let* 'loop 'loop* 'with-open} (first form)) (vector? (second form)))
         (let [[names refs] (reduce (fn [[names refs] [binding value]]
                                     [(into names (binding-symbols binding)) (into refs (symbols-in value names))])
                                   [bound []] (partition 2 (second form)))]
           (concat refs (mapcat #(symbols-in % names) (drop 2 form))))
         (and (seq? form) (#{'def 'defonce} (first form))) (mapcat #(symbols-in % bound) (drop 2 form))
         (coll? form) (mapcat #(symbols-in % bound) form)
         :else [])))

(defn index-document [document]
  (let [parsed (for [p (ns-form/panels document) :when (#{"code" "namespace"} (:kind p))]
                 (assoc (parse-code (:source p)) :panel p))
        forms (mapcat :forms parsed)]
    {:namespace (:namespace document) :imports (imports forms)
     :definitions (into {} (for [{:keys [panel forms]} parsed entry forms
                                 :let [d (definition (:namespace document) panel entry)] :when d]
                             [(:symbol d) d]))}))

(defn dependencies [documents document panels config]
  (if (= "none" (:dependencies config)) {:items [] :warnings []}
    (let [indexes (merge (:library config) (into {} (map (juxt :namespace identity) (map index-document documents))))
          defs (apply merge (map :definitions (vals indexes)))
          current (:namespace document)
          explicit-exclusions (set (keep (fn [[id role]] (when (= role "exclude") id)) (:roles config)))
          included (set (map :id panels))
          resolve-ref (fn [ns sym]
                        (let [{:keys [aliases refers]} (:imports (get indexes ns))
                              qualifier (namespace sym)]
                          (if qualifier (str (get aliases qualifier qualifier) "/" (name sym))
                              (get refers (str sym) (str ns "/" sym)))))
          roots (for [p panels :when (= "code" (:kind p))
                      {:keys [form]} (:forms (parse-code (:source p))) sym (symbols-in form)]
                  [current sym (str "Referenced by panel " (:id p))])]
      (loop [todo (seq roots) seen #{} items []]
        (if (or (empty? todo) (>= (count items) 100))
          {:items items
           :warnings (vec (concat (when (seq todo) ["Dependency limit (100 definitions) reached; narrow the context."])
                                  (for [p panels :when (= "code" (:kind p))
                                        :let [w (:warning (parse-code (:source p)))] :when w]
                                    (str "Panel " (:id p) " has incomplete/unreadable code; dependencies may be missing: " w))))}
          (let [[ns sym reason] (first todo) qname (resolve-ref ns sym) d (get defs qname)
                allowed? (and d (not (seen qname))
                              (not (explicit-exclusions (:panel d)))
                              (not (and (= current (:namespace d)) (included (:panel d))))
                              (or (= ns (:namespace d)) (not (:private? d))))
                full? (or (= "implementations" (:dependencies config)) (some #{qname} (:expanded config)))
                entry (when allowed? (assoc (select-keys d [:symbol :namespace :panel :signature :doc])
                                            :mode (if full? "implementation" "signature") :reason reason
                                            :source (if full? (:source d) (:signature d))))
                more (when allowed?
                       (for [s (if full? (mapcat (comp symbols-in :form) (:forms (parse-code (:source d))))
                                   (when (:alias d) [(symbol (:alias d))]))]
                         [(:namespace d) s (str "Used by " qname)]))]
            (recur (concat (rest todo) more) (conj seen qname) (cond-> items entry (conj entry)))))))))

(defn build [document prompt-id documents workspace]
  (let [prompt (first (filter #(= prompt-id (:id %)) (:blocks document))) config (settings prompt)
        roles (panel-roles document prompt-id)
        panels (vec (for [p (ns-form/panels document) :let [role (get roles (:id p))]
                          :when (and role (not= "exclude" role))]
                      (assoc (select-keys p [:id :kind :source :prompt-id]) :role role
                             :reason (cond (= role "prompt") "Active prompt"
                                           (contains? (:roles config) (:id p)) "Explicit panel selection"
                                           (= "namespace" (:kind p)) "Document namespace and imports"
                                           :else (str (:scope config) " scope")))))
        deps (dependencies documents document panels (assoc config :library (:library workspace)))
        instructions (cond-> []
                       (and (not= "replace" (:instructions-mode document)) (not (str/blank? (:instructions workspace))))
                       (conj {:scope "workspace" :text (:instructions workspace)})
                       (not (str/blank? (:instructions document)))
                       (conj {:scope "document" :text (:instructions document)}))]
    {:namespace (:namespace document) :scope (:scope config) :panels panels
     :allow-create-namespaces? (:allow-create-namespaces? config)
     :existing-namespaces (when (:allow-create-namespaces? config) (vec (sort (map :namespace documents))))
     :instructions instructions :dependencies (:items deps) :warnings (:warnings deps)}))

(ns clj-manifold3d.runtime
  "One asynchronous WASM initialization; synchronous geometry thereafter.
  All foreign property names cross an explicit string-keyed Closure boundary."
  (:require [goog.object :as gobj]))

(defonce ^:private state (atom {}))
(def ^:dynamic *resources* nil)
(defn- track [value]
  (when *resources*
    (doseq [object (if (array? value) (array-seq value) [value])]
      (when (and (some? object) (not (or (number? object) (string? object) (boolean? object)))
                 (gobj/get object "delete") (gobj/get object "isDeleted"))
        (swap! *resources* conj object))))
  value)

(defn get-property [object key] (gobj/get object key))
(defn call [object method & args]
  (let [f (gobj/get object method)]
    (when-not (fn? f)
      (throw (ex-info (str "Missing native method: " method) {:method method})))
    (track (.apply f object (to-array args)))))

(defn module []
  (or (:module @state)
      (throw (ex-info "WASM is not initialized; await (core/init!) before modeling" {}))))

(defn native [method & args] (apply call (module) method args))
(defn static [class-name method & args]
  (apply call (gobj/get (module) class-name) method args))
(defn construct [class-name & args]
  (track (js/Reflect.construct (gobj/get (module) class-name) (to-array args))))

(defn init!
  "Return a Promise of the initialized module (concurrent calls share it).
  :factory accepts an Emscripten factory; defaults to global ManifoldModule.
  :wasm-url or :wasm-binary selects the fresh WASM artifact. :module may supply
  an already instantiated module. Failed initialization is retryable."
  ([] (init! {}))
  ([{:keys [factory wasm-url wasm-binary module] :as options}]
   (or (:loading @state)
       (when-let [m (:module @state)] (js/Promise.resolve m))
       (let [factory (or factory (gobj/get js/globalThis "ManifoldModule"))
             config (js-obj)
             _ (when wasm-url (gobj/set config "locateFile" (fn [_ _] wasm-url)))
             _ (when wasm-binary (gobj/set config "wasmBinary" wasm-binary))
             promise (-> (js/Promise.resolve nil)
                         (.then (fn [_]
                                  (cond module module
                                        (fn? factory) (factory config)
                                        :else (throw (ex-info "Provide :factory or load the fresh public/wasm/manifold.js first" {})))))
                         (.then (fn [m]
                                  (call m "setup")
                                  (swap! state assoc :module m)
                                  m))
                         (.catch (fn [error]
                                   (reset! state {})
                                   (throw error))))]
         (swap! state assoc :loading promise)
         promise))))

(defn instance-of? [class-name value]
  (boolean (and (:module @state) value
                (instance? (gobj/get (:module @state) class-name) value))))

(defn dispose!
  "Release owned WASM handles. Idempotent; never releases input operands implicitly."
  [& objects]
  (doseq [object objects]
    (when (and object (gobj/get object "delete")
               (not (call object "isDeleted")))
      (call object "delete"))))

(defn with-disposal
  "Run synchronous f and release every handle created through these bindings,
  even on exceptions. Return data, not WASM handles, from the scope."
  [f]
  (binding [*resources* (atom [])]
    (try (f)
         (finally (apply dispose! (reverse @*resources*))))))

(defn read-bytes
  "Promise of Uint8Array, from bytes, a Node filename, or a browser URL."
  [source]
  (cond
    (instance? js/Uint8Array source) (js/Promise.resolve source)
    (instance? js/ArrayBuffer source) (js/Promise.resolve (js/Uint8Array. source))
    (string? source)
    (if (and (exists? js/require) (not (re-find #"^(https?:|data:)" source)))
      (-> (js/Promise.resolve nil)
          (.then (fn [_] (call (js/require "node:fs") "readFileSync" source))))
      (-> (js/fetch source)
          (.then (fn [response]
                   (when-not (.-ok response)
                     (throw (ex-info "Unable to load asset" {:source source :status (.-status response)})))
                   (.arrayBuffer response)))
          (.then #(js/Uint8Array. %))))
    :else (js/Promise.reject (ex-info "Expected image bytes, filename, or URL" {:source source}))))

(defn write-bytes!
  "Write a Node file, download in a browser, or return bytes when filename is nil."
  [filename bytes]
  (cond
    (nil? filename) bytes
    (exists? js/require) (do (call (js/require "node:fs") "writeFileSync" filename bytes) filename)
    (exists? js/document)
    (let [url (js/URL.createObjectURL (js/Blob. #js [bytes] #js {:type "model/gltf-binary"}))
          anchor (.createElement js/document "a")]
      (set! (.-href anchor) url)
      (set! (.-download anchor) filename)
      (.click anchor)
      (js/setTimeout #(js/URL.revokeObjectURL url) 1000)
      filename)
    :else (throw (ex-info "No file output available; pass nil to obtain bytes" {}))))

(defonce ^:private file-sequence (atom 0))
(defn with-native-file
  "Load bytes into WASM's private filesystem for synchronous f; always unlink.
  Returns a Promise, even for in-memory byte input."
  [source f]
  (-> (read-bytes source)
      (.then (fn [bytes]
               (let [fs (get-property (module) "FS") path (str "/clj-asset-" (swap! file-sequence inc))]
                 (call fs "writeFile" path bytes)
                 (try (f path) (finally (call fs "unlink" path))))))))

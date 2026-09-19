(ns clj-manifold3d.try-it.app
  (:refer-clojure :exclude [run!])
  (:require [clj-manifold3d.try-it.examples :as examples]
            [goog.object :as gobj]))

(defonce state (atom {:id 0 :ready? false :busy? false :auto-run? true :example "loft"}))
(defn- el [id] (.getElementById js/document id))
(defn- text! [id value] (set! (.-textContent (el id)) value))
(defn- disabled! [id value] (set! (.-disabled (el id)) value))
(defn- viewer [method & args]
  (let [object (gobj/get js/window "manifoldViewer")]
    (.apply (gobj/get object method) object (to-array args))))
(defn- editor [method & args]
  (let [object (gobj/get js/window "manifoldEditor")]
    (.apply (gobj/get object method) object (to-array args))))
(defn- status! [label kind]
  (text! "model-state" label)
  (.setAttribute (el "model-state") "data-state" kind))
(defn- output! [text error?]
  (text! "output" text)
  (.toggle (.-classList (el "output")) "error" error?))
(defn- loading! [value label]
  (set! (.-hidden (el "loading")) (not value))
  (text! "loading-text" label))
(defn- number-text [number] (.toLocaleString number "en-US" #js {:maximumFractionDigits 1}))
(defn- clear-timer! []
  (when-let [timer (:timer @state)] (js/clearTimeout timer))
  (swap! state dissoc :timer))
(defn- storage-get [key]
  (try (.getItem js/localStorage key) (catch :default _ nil)))
(defn- storage-set! [key value]
  (try (.setItem js/localStorage key value) (catch :default _ nil)))
(defn- save-code! []
  (storage-set! (str "manifold.try-it.code." (:example @state)) (editor "getValue"))
  (text! "save-status" "Saved in this browser"))
(defn- finish! []
  (clear-timer!)
  (swap! state assoc :busy? false)
  (disabled! "run" (not (:ready? @state)))
  (disabled! "stop" true)
  (loading! false ""))
(defn- fail! [message output]
  (finish!)
  (status! (if (:buffer @state) "ERROR · LAST GOOD MODEL" "ERROR") "error")
  (output! (str output (when (seq output) "\n") message) true))

(declare run! start-worker! cancel!)

(defn- display-result! [data]
  (let [id (gobj/get data "id") buffer (gobj/get data "buffer")]
    (-> (viewer "load" buffer)
        (.then (fn [info]
                 (when (and info (= id (:id @state)))
                   (let [kind (gobj/get data "kind") scene? (= kind "scene")
                         description (if scene?
                                       (str (gobj/get data "nodes") " nodes · " (gobj/get data "animations") " animation(s)")
                                       (str (number-text (gobj/get data "triangles")) " triangles · "
                                            (number-text (gobj/get data "volume")) " units³"))
                         elapsed (number-text (gobj/get data "elapsed"))]
                     (swap! state assoc :buffer buffer :playing? true)
                     (finish!)
                     (status! "MODEL READY" "ready")
                     (text! "stats" description)
                     (text! "engine-status" (str "WASM · " elapsed " ms"))
                     (output! (str (gobj/get data "output") "✓ " (if scene? "Scene" "Manifold") " built in " elapsed " ms.\n"
                                   (if (= kind "section") "CrossSection preview: extruded 1 unit."
                                       (str description ". GLB loaded in viewer."))) false)
                     (disabled! "download" false)
                     (set! (.-hidden (el "play")) (zero? (gobj/get info "animations")))
                     (.setAttribute (el "play") "aria-pressed" "true")
                     (text! "play" "Pause")))))
        (.catch (fn [error] (when (= id (:id @state)) (fail! (str "Viewer: " (.-message error)) "")))))))

(defn- start-worker! []
  (when-let [worker (:worker @state)] (.terminate worker))
  (swap! state assoc :ready? false)
  (disabled! "run" true)
  (text! "engine-status" "Loading WASM…")
  (let [worker (js/Worker. "/try-it/worker/worker.js")]
    (swap! state assoc :worker worker)
    (set! (.-onmessage worker)
          (fn [event]
            (let [data (.-data event) type (gobj/get data "type")]
              (case type
                "ready" (do (clear-timer!)
                            (swap! state assoc :ready? true)
                            (disabled! "run" false)
                            (text! "engine-status" "WASM ready")
                            (when (:auto-run? @state)
                              (swap! state assoc :auto-run? false)
                              (run!)))
                "fatal" (do (.terminate worker) (fail! (gobj/get data "message") ""))
                (when (= (gobj/get data "id") (:id @state))
                  (case type
                    "started" (text! "engine-status" "WASM · evaluating")
                    "result" (display-result! data)
                    "error" (fail! (str (when-let [line (gobj/get data "line")]
                                          (str "Line " line ", column " (gobj/get data "column") ": "))
                                        (gobj/get data "message")) (gobj/get data "output"))
                    nil))))))
    (set! (.-onerror worker)
          (fn [event]
            (.preventDefault event)
            (.terminate worker)
            (swap! state assoc :ready? false)
            (fail! (str "Worker failed: " (.-message event) ". Reload the page to restart.") "")))
    (swap! state assoc :timer
           (js/setTimeout (fn [] (.terminate worker) (fail! "Engine startup timed out. Check the local WASM build, then reload." "")) 30000))))

(defn- cancel! [message]
  (clear-timer!)
  (swap! state update :id inc)
  (viewer "cancelLoad")
  (finish!)
  (status! "STOPPED" "idle")
  (output! message false)
  (start-worker!))

(defn- run! []
  (when (and (:ready? @state) (not (:busy? @state)))
    (save-code!)
    (let [{:keys [id worker]} (swap! state #(-> % (update :id inc) (assoc :busy? true)))]
      (disabled! "run" true) (disabled! "stop" false)
      (status! "BUILDING" "building")
      (loading! (nil? (:buffer @state)) "Building geometry")
      (output! "Evaluating in a fresh worker context…" false)
      (swap! state assoc :timer (js/setTimeout #(cancel! "Stopped after 30 seconds. Reduce the model complexity and try again.") 30000))
      (.postMessage worker (js-obj "id" id "code" (editor "getValue"))))))

(defn- load-example! [id reset?]
  (let [{:keys [title description code previous-codes]} (examples/example id)
        saved (examples/upgrade-code id (storage-get (str "manifold.try-it.code." id)))]
    (when (:busy? @state) (cancel! "Stopped to switch examples."))
    (swap! state assoc :example id)
    (storage-set! "manifold.try-it.example" id)
    (set! (.-value (el "example")) id)
    ;; Only upgrade an unchanged stock example, never overwrite a user's edits.
    (editor "setValue" (if (or reset? (nil? saved) (some #{saved} previous-codes)) code saved))
    (text! "model-title" title)
    (text! "model-description" description)
    (save-code!)
    (if (:ready? @state) (run!) (swap! state assoc :auto-run? true))))

(defn- download! []
  (when-let [buffer (:buffer @state)]
    (let [url (js/URL.createObjectURL (js/Blob. #js [buffer] #js {:type "model/gltf-binary"}))
          anchor (.createElement js/document "a")]
      (set! (.-href anchor) url)
      (set! (.-download anchor) (str "manifold-" (:example @state) ".glb"))
      (.click anchor)
      (js/setTimeout #(js/URL.revokeObjectURL url) 1000))))

(defn- toggle! [id method]
  (let [button (el id) on? (not= "true" (.getAttribute button "aria-pressed"))]
    (.setAttribute button "aria-pressed" (str on?))
    (viewer method on?)
    (when (= id "play") (text! "play" (if on? "Pause" "Play")))))

(defn main []
  (gobj/set js/window "manifoldEditor"
            ((gobj/get js/window "createManifoldEditor") (el "code")
             (js-obj "onChange" save-code! "onRun" run!)))
  (.addEventListener (el "run") "click" run!)
  (.addEventListener (el "stop") "click" #(cancel! "Stopped. The last good model is unchanged."))
  (.addEventListener (el "download") "click" download!)
  (.addEventListener (el "fit") "click" #(viewer "fit"))
  (.addEventListener (el "wireframe") "click" #(toggle! "wireframe" "setWireframe"))
  (.addEventListener (el "grid") "click" #(toggle! "grid" "setGrid"))
  (.addEventListener (el "play") "click" #(toggle! "play" "setPlaying"))
  (.addEventListener (el "example") "change" #(load-example! (.-value (el "example")) false))
  (.addEventListener (el "reset-code") "click" #(load-example! (:example @state) true))
  (let [saved (storage-get "manifold.try-it.example")]
    (load-example! (if (examples/example saved) saved "loft") false))
  (start-worker!)
  (.addEventListener js/window "beforeunload"
                     #(do (editor "destroy")
                          (when-let [worker (:worker @state)] (.terminate worker)))))

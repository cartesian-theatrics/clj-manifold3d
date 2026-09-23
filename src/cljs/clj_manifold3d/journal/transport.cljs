(ns clj-manifold3d.journal.transport
  (:require [clj-manifold3d.journal.browser-store :as store]
            [cljs.reader :as reader]
            [goog.object :as gobj]))

(defn static? []
  (= "static" (.getAttribute (.-documentElement js/document) "data-journal-mode")))

(defn asset-url [path] (.-href (js/URL. path (.-baseURI js/document))))

(defn- transact-browser! [f]
  (js/Promise.
   (fn [resolve reject]
     ;; Each read/modify/write shares one IndexedDB transaction, including the
     ;; revision check. Concurrent tabs cannot silently overwrite document edits.
     (let [request (.open js/indexedDB (str "modeling-journal:" (asset-url "./")) 1)]
       (set! (.-onupgradeneeded request)
             (fn [_] (.createObjectStore (.-result request) "snapshots")))
       (set! (.-onerror request) #(reject (.-error request)))
       (set! (.-onblocked request) #(reject (js/Error. "Close other journal tabs to upgrade browser storage.")))
       (set! (.-onsuccess request)
             (fn [_]
               (let [db (.-result request)
                     tx (.transaction db #js ["snapshots"] "readwrite")
                     object-store (.objectStore tx "snapshots")
                     read (.get object-store "current")
                     result (volatile! nil) failure (volatile! nil)]
                 (set! (.-oncomplete tx) (fn [_] (.close db) (resolve @result)))
                 (set! (.-onabort tx)
                       (fn [_] (.close db) (reject (or @failure (.-error tx) (js/Error. "Browser storage transaction failed.")))))
                 (set! (.-onsuccess read)
                       (fn [_]
                         (try
                           (let [snapshot (if-let [s (.-result read)] (reader/read-string s) (store/seed))
                                 [next value] (f snapshot)]
                             (vreset! result value)
                             (.put object-store (pr-str next) "current"))
                           (catch :default e (vreset! failure e) (.abort tx))))))))))))

(defn- browser-api [method path data]
  (case [method path]
    ["GET" "/api/state"] (transact-browser! #(vector % %))
    ["PUT" "/api/documents-batch"] (transact-browser! #(store/save-documents % data))
    ["PUT" "/api/workspace"] (transact-browser! #(store/save-workspace % data))
    (js/Promise.reject (js/Error. "AI prompting requires the local server-backed journal; it is unavailable on this static site."))))

(defn api [method path data]
  (if (static?) (browser-api method path data)
    (-> (js/fetch path (clj->js (cond-> {:method method :headers {"Content-Type" "application/json"}}
                                 data (assoc :body (js/JSON.stringify (clj->js data))))))
        (.then (fn [response]
                 (-> (.json response)
                     (.then (fn [body]
                              (if (.-ok response) (js->clj body :keywordize-keys true)
                                  (throw (js/Error. (gobj/get body "error"))))))))))))

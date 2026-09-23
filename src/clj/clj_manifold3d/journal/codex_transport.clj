(ns clj-manifold3d.journal.codex-transport
  "One ephemeral app-server thread per request. Only public agent-message
  deltas reach the preview callback; private reasoning is discarded. Registered
  read-only API tool calls go to a separate, bounded host callback."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(def disabled-features
  ["shell_tool" "unified_exec" "multi_agent" "multi_agent_v2" "apps" "hooks" "plugins" "tool_suggest"
   "browser_use" "browser_use_external" "in_app_browser" "computer_use" "image_generation" "view_image" "skill_search"])

(defn command [binary]
  (into [binary "app-server" "--listen" "stdio://"
         "-c" "mcp_servers={}" "-c" "hooks={}" "-c" "plugins={}" "-c" "notify=[]"
         "-c" "web_search=\"disabled\"" "-c" "approval_policy=\"never\""
         "-c" "sandbox_mode=\"read-only\""]
        (mapcat #(vector "--disable" %) disabled-features)))

(defn- isolated? [config]
  (and (empty? (:mcp_servers config)) (empty? (:plugins config))
       (every? #(false? (get-in config [:features (keyword %)])) disabled-features)
       (= "disabled" (:web_search config)) (empty? (:notify config))))

(defn list-models!
  "Read the installed app-server catalog without creating a thread or turn.
  The caller owns the process lifetime and deadline."
  [^Process process]
  (with-open [writer (io/writer (.getOutputStream process))
              reader (io/reader (.getInputStream process))]
    (let [send! (fn [m] (.write writer (str (json/write-str m) "\n")) (.flush writer))
          page! (fn [cursor] (send! {:id 2 :method "model/list"
                                     :params (cond-> {:limit 100 :includeHidden false}
                                               cursor (assoc :cursor cursor))}))]
      (send! {:id 1 :method "initialize" :params {:clientInfo {:name "modeling-journal" :version "1"}}})
      (loop [models [] cursors #{} bytes 0]
        (let [line (.readLine ^java.io.BufferedReader reader)]
          (when-not line (throw (ex-info "Codex model discovery stopped or timed out." {})))
          (when (> (+ bytes (count line)) 1000000) (throw (ex-info "Codex model catalog exceeded its size limit." {})))
          (let [{:keys [id method result error]} (json/read-str line :key-fn keyword)]
            (cond
              (and method id) (do (send! {:id id :error {:code -32601 :message "Model discovery is read-only."}})
                                  (recur models cursors (+ bytes (count line))))
              error (throw (ex-info (or (:message error) "Model discovery failed.") {}))
              (= id 1) (do (send! {:method "initialized"}) (page! nil) (recur models cursors (+ bytes (count line))))
              (= id 2)
              (let [next (into models (remove :hidden (:data result))) cursor (:nextCursor result)]
                (when (or (> (count next) 500) (cursors cursor) (> (count cursors) 20))
                  (throw (ex-info "Invalid model catalog pagination." {})))
                (if cursor (do (page! cursor) (recur next (conj cursors cursor) (+ bytes (count line))))
                    (mapv #(select-keys % [:id :model :displayName :description :isDefault]) next)))
              :else (recur models cursors (+ bytes (count line))))))))))

(defn run!
  ([process cwd model prompt schema on-preview]
   (run! process cwd model prompt schema on-preview (fn [_ _] nil)))
  ([process cwd model prompt schema on-preview repair]
   (run! process cwd model prompt schema on-preview repair {}))
  ([process cwd model prompt schema on-preview repair {:keys [tools on-tool on-model]}]
  (with-open [writer (io/writer (.getOutputStream ^Process process))
              reader (io/reader (.getInputStream ^Process process))]
    (let [send! (fn [message] (.write writer (str (json/write-str message) "\n")) (.flush writer))
          turn! (fn [thread-id attempt input]
                  (send! {:id (+ 4 attempt) :method "turn/start"
                          :params {:threadId thread-id :input [{:type "text" :text input}]
                                   :outputSchema schema}}))]
      (send! {:id 1 :method "initialize"
              :params {:clientInfo {:name "modeling-journal" :version "1"}
                       :capabilities {:experimentalApi true}}})
      (loop [text "" item-id nil final nil bytes 0 published-at 0 thread-id nil attempt 0]
        (when-let [line (.readLine ^java.io.BufferedReader reader)]
          (let [bytes (+ bytes (count line))
                _ (when (> bytes 4000000) (throw (ex-info "Codex stream exceeded the size limit." {})))
                {:keys [id method params result error]} (json/read-str line :key-fn keyword)
                ;; JSON-RPC request IDs are independent in each direction.
                ;; A server tool call with id 3 is not our thread/start reply.
                thread-id (if (and (nil? method) (= id 3)) (get-in result [:thread :id]) thread-id)
                item (:item params)
                agent? (and (= "agentMessage" (:type item)) (not= "commentary" (:phase item)))
                start? (and (= method "item/started") agent?)
                delta? (and (= method "item/agentMessage/delta") (= item-id (:itemId params)) (some? item-id))
                completed? (and (= method "item/completed") agent?)
                text (cond start? "" delta? (str text (:delta params)) completed? (:text item) :else text)
                item-id (if (or start? completed?) (:id item) item-id)
                final (if completed? text final)
                now (System/currentTimeMillis)
                publish? (or completed? (and delta? (>= (- now published-at) 180)))]
            (when (> (count text) 1100000) (throw (ex-info "Codex response exceeded the size limit." {})))
            (cond
              (and (= method "item/tool/call") (some? id) on-tool
                   (= thread-id (:threadId params))
                   (some #(= (:name %) (:tool params)) tools))
              (send! {:id id :result (on-tool (:tool params) (:arguments params))})
              ;; No shell, approvals, arbitrary integrations, or unknown tools.
              (and method (some? id)) (send! {:id id :error {:code -32601 :message "Only the journal's read-only API lookup tools are available."}})
              error (throw (ex-info (or (:message error) "Codex protocol request failed") {}))
              (= id 1) (do (send! {:method "initialized"})
                           (send! {:id 2 :method "config/read" :params {:includeLayers false}}))
              (= id 2) (do
                         (when-not (isolated? (:config result))
                           (throw (ex-info "Codex did not disable external integrations; refusing to start." {})))
                         (send! {:id 3 :method "thread/start"
                                 :params (cond-> {:cwd (str cwd) :ephemeral true :approvalPolicy "never"
                                                  :sandbox "read-only" :environments []
                                                  :developerInstructions "Use the journal API lookup tools to discover supported functions as needed, then answer with the requested JSON edit plan. No other tools, files, external context, or private reasoning."
                                                  :config {"web_search" "disabled"}}
                                           (seq tools) (assoc :dynamicTools tools)
                                           model (assoc :model model))}))
              (= id 3) (do (when (and on-model (:model result)) (on-model (:model result)))
                           (turn! thread-id attempt prompt)))
            (when publish? (on-preview text))
            (if (= method "turn/completed")
              (if (and (= "completed" (get-in params [:turn :status])) final)
                (if-let [correction (repair final attempt)]
                  (do
                    (when (>= attempt 2) (throw (ex-info "Patch repair limit exceeded." {})))
                    (turn! thread-id (inc attempt) correction)
                    (recur "" nil nil bytes 0 thread-id (inc attempt)))
                  final)
                (throw (ex-info (or (get-in params [:turn :error :message]) "Codex stopped without a complete response.") {})))
              (recur text item-id final bytes (if publish? now published-at) thread-id attempt)))))))))

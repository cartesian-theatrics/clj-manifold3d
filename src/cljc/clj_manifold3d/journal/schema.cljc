(ns clj-manifold3d.journal.schema)

;; One typed schema; DataScript omits scalar type declarations.
(def attributes
  {:document/id [:db.type/string :identity]
   :document/namespace [:db.type/string :identity]
   :document/title [:db.type/string]
   :document/revision [:db.type/long]
   :document/saved-content [:db.type/string]
   :document/blocks [:db.type/ref :many]
   :document/applied-requests [:db.type/string :many]
   :block/id [:db.type/string :identity]
   :block/kind [:db.type/string]
   :block/order [:db.type/long]
   :block/source [:db.type/string]
   :block/hidden? [:db.type/boolean]
   :workspace/id [:db.type/string :identity]
   :workspace/active-pane [:db.type/string]
   :workspace/vim? [:db.type/boolean]
   :pane/id [:db.type/string :identity]
   :pane/document [:db.type/ref]
   :pane/order [:db.type/long]
   :pane/width [:db.type/double]
   ;; Transient browser facts use the same vocabulary, never persisted results.
   :ui/engine [:db.type/string]
   :ui/save-status [:db.type/string]
   :ui/active-block [:db.type/string]
   :ui/saved-layout [:db.type/string]
   :ui/request-id [:db.type/string]
   :ui/request-document [:db.type/string]
   :ui/dialog [:db.type/string]
   :ui/draft-namespace [:db.type/string]
   :ui/draft-title [:db.type/string]
   :ui/dialog-error [:db.type/string]
   :evaluation/namespace [:db.type/string :identity]
   :evaluation/history [:db.type/string]
   :result/id [:db.type/string :identity]
   :result/status [:db.type/string]
   :result/description [:db.type/string]
   :result/output [:db.type/string]
   :result/asset [:db.type/string]
   :result/polygons [:db.type/string]
   :result/wireframe? [:db.type/boolean]
   :result/paused? [:db.type/boolean]
   :result/kind [:db.type/string]
   :request/id [:db.type/string :identity]
   :request/namespace [:db.type/string]
   :request/prompt-block [:db.type/string]
   :request/prompt [:db.type/string]
   :request/context [:db.type/string]
   :request/status [:db.type/string]
   :request/progress [:db.type/string]
   :request/output [:db.type/string]
   :request/error [:db.type/string]
   :request/created [:db.type/long]
   :request/updated [:db.type/long]
   :request/poll-error [:db.type/string]})

(def attribute-tx
  (mapv (fn [[ident [type option]]]
          (cond-> {:db/ident ident :db/valueType type
                   :db/cardinality (if (= option :many) :db.cardinality/many :db.cardinality/one)}
            (= option :identity) (assoc :db/unique :db.unique/identity))) attributes))

(def schema
  (into {} (map (fn [{:db/keys [ident valueType] :as attr}]
                  [ident (cond-> (dissoc attr :db/ident)
                           (not= valueType :db.type/ref) (dissoc :db/valueType))])
                attribute-tx)))

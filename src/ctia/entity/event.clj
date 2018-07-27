(ns ctia.entity.event
  (:require
   [ctia.stores.es.mapping :as em]
   [ctia.store :refer [IEventStore]]
   [clj-momo.lib.es
    [document :as d]
    [schemas :refer [ESConnState SliceProperties]]
    [slice :refer [get-slice-props]]]
   [ctia.lib.pagination :refer [list-response-schema]]
   [ctia.stores.es.crud :as crud]
   [ctim.events.schemas :as event-schemas]
   [schema.core :as s]))

(def event-mapping
  {"event"
   {:dynamic false
    :properties
    {:owner em/token
     :groups em/token
     :timestamp em/ts
     :entity {:enabled false
              :type "object"}
     :id em/token
     :http-params {:enabled false
                   :type "object"}
     :type em/token
     :fields {:enabled false
              :type "object"}
     :judgement_id em/token}}})

(s/defschema UpdateMap
  "a map converted from an Update Triple for ES Compat"
  {:field s/Keyword
   :action s/Str
   :change {s/Any s/Any}})

(s/defn update-triple->map :- UpdateMap
  [[field action change] :- event-schemas/UpdateTriple]
  {:field field
   :action action
   :change change})

(s/defn transform-fields [e :- event-schemas/Event]
  "for ES compat, transform field vector of an event to a map"
  (if-let [fields (:fields e)]
    (assoc e :fields (map update-triple->map fields)) e))

(defn attach-bulk-fields [index event]
  (assoc event
         :_type "event"
         :_id (:id event)
         :_index index))

(s/defn index-produce
  "produce an event to an aliased index"
  [state :- ESConnState
   slice-props :- SliceProperties
   events :- [event-schemas/Event]]
  (d/bulk-create-doc (:conn state)
                     (->> events
                          (map transform-fields)
                          (map (partial attach-bulk-fields (:name slice-props))))
                     (get-in state [:props :refresh] false)))

(s/defn simple-produce
  "produce an event to an index"
  [state :- ESConnState
   events :- [event-schemas/Event]]
  (d/bulk-create-doc (:conn state)
                     (->> events
                          (map transform-fields)
                          (map (partial attach-bulk-fields (:index state))))
                     (get-in state [:props :refresh] false)))

(s/defn produce
  ([state :- ESConnState
    slice-props :- SliceProperties
    events :- [event-schemas/Event]]
   (index-produce state slice-props events))

  ([state :- ESConnState
    events :- [event-schemas/Event]]
   (simple-produce state events)))

(s/defn handle-create :- [event-schemas/Event]
  "produce an event to ES"
  [state :- ESConnState
   events :- [event-schemas/Event]]

  (if (-> state :slicing :strategy)
    (let [slice-props (get-slice-props (:timestamp (first events)) state)]
      (produce state slice-props events))
    (produce state events))
  events)

(def ^:private handle-list-raw (crud/handle-find :event event-schemas/Event))

(s/defn handle-list :- (list-response-schema event-schemas/Event)
  [state :- ESConnState
   filter-map :- {s/Any s/Any}
   ident
   params]
  (handle-list-raw state
                   filter-map
                   ident
                   params))

(defrecord EventStore [state]
  IEventStore
  (create-events [this new-events]
    (handle-create state new-events))
  (list-events [this filter-map ident params]
    (handle-list state filter-map ident params)))

(def event-entity
  {:schema {s/Any s/Any}
   :new-schema {s/Any s/Any}
   :stored-schema {s/Any s/Any}
   :partial-schema {s/Any s/Any}
   :partial-stored-schema {s/Any s/Any}
   :partial-list-schema {s/Any s/Any}
   :no-api? true
   :no-bulk? true
   :entity :event
   :plural :events
   :es-store ->EventStore
   :es-mapping event-mapping})

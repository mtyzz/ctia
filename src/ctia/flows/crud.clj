(ns ctia.flows.crud
  "This namespace handle all necessary flows for creating, updating and deleting entities."
  (:import java.util.UUID)
  (:require [clojure.tools.logging :as log]
            [ctia.auth :as auth]
            [ctia.domain.id :as id]
            [ctia.flows.hooks :as h]
            [ctia.events.obj-to-event :refer [to-create-event
                                              to-update-event
                                              to-delete-event]]))

(defn find-id
  "Lookup an ID in a given entity.  Parse it, because it might be a
   URL, and return the short form ID.  Returns nil if the ID could not
   be found."
  [{id :id}]
  (when (seq id)
    (id/str->short-id id)))

(defn make-id
  [entity-type]
  (str (name entity-type) "-" (UUID/randomUUID)))

(defn- handle-flow
  [& {:keys [flow-type
             entity-type
             entity
             prev-entity
             identity
             realize-fn
             store-fn
             create-event-fn]}]
  (let [login (auth/login identity)
        entity-id (or (find-id prev-entity)
                      (when (auth/capable? identity :specify-id)
                        (find-id entity))
                      (make-id entity-type))
        realized (h/apply-hooks :entity (case flow-type
                                          :create (realize-fn entity entity-id login)
                                          :update (realize-fn entity entity-id login prev-entity)
                                          :delete entity)
                                :prev-entity prev-entity
                                :hook-type (case flow-type
                                             :create :before-create
                                             :update :before-update
                                             :delete :before-delete)
                                :read-only? (= flow-type :delete))
        event (try
                (if (= :update flow-type)
                  (create-event-fn realized prev-entity)
                  (create-event-fn realized))
                (catch Throwable e
                  (log/error "Could not create event" e)
                  (throw (ex-info "Could not create event"
                                  {:flow-type flow-type
                                   :login login
                                   :entity realized
                                   :prev-entity prev-entity}))))
        _ (h/apply-event-hooks event)
        result (if (= :delete flow-type)
                 (store-fn entity-id)
                 (store-fn realized))]
    (h/apply-hooks :entity realized
                   :prev-entity prev-entity
                   :hook-type (case flow-type
                                :create :after-create
                                :update :after-update
                                :delete :after-delete)
                   :read-only? true)
    result))

(defn create-flow
  "This function centralizes the create workflow.
  It is helpful to easily add new hooks name

  To be noted:
    - `:before-create` hooks can modify the entity stored.
    - `:after-create` hooks are read only"
  [& {:keys [entity-type
             realize-fn
             store-fn
             identity
             entity]}]
  (handle-flow :flow-type :create
               :entity-type entity-type
               :entity entity
               :identity identity
               :realize-fn realize-fn
               :store-fn store-fn
               :create-event-fn to-create-event))

(defn update-flow
  "This function centralize the update workflow.
  It is helpful to easily add new hooks name

  To be noted:
    - `:before-update` hooks can modify the entity stored.
    - `:after-update` hooks are read only"
  [& {:keys [entity-type
             get-fn
             realize-fn
             update-fn
             entity-id
             identity
             entity]}]
  (let [prev-entity (get-fn entity-id)]
    (handle-flow :flow-type :update
                 :entity-type entity-type
                 :entity entity
                 :prev-entity prev-entity
                 :identity identity
                 :realize-fn realize-fn
                 :store-fn update-fn
                 :create-event-fn to-update-event)))

(defn delete-flow
  "This function centralize the deletion workflow.
  It is helpful to easily add new hooks name

  To be noted:
    - the flow get the entity from the store to be used by hooks.
    - `:before-delete` hooks can modify the entity stored.
    - `:after-delete` hooks are read only"
  [& {:keys [entity-type
             get-fn
             delete-fn
             entity-id
             identity]}]
  (let [entity (get-fn entity-id)]
    (handle-flow :flow-type :delete
                 :entity-type entity-type
                 :entity entity
                 :prev-entity entity
                 :identity identity
                 :store-fn delete-fn
                 :create-event-fn to-delete-event)))
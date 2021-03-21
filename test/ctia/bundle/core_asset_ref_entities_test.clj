(ns ctia.bundle.core-asset-ref-entities-test
  "Additonal tests for entities with AssetRef field.

  For Assets, (unlike other entities) it was decided not to use Relationship
  objects to define association with other entities. Specifically, AssetMap and
  AssetProperties are linked to Assets via their :asset-ref fields.

  These tests are to ensure that such a relationship is observed when these
  types of objects when they created via Bundle Import"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures join-fixtures]]
   [clojure.walk :as walk]
   [ctia.auth.threatgrid :refer [map->Identity]]
   [ctia.bulk.core :as bulk]
   [ctia.bundle.core :as bundle]
   [ctia.store :as store]
   [ctia.test-helpers.auth :as auth]
   [ctia.test-helpers.core :as th]
   [ctia.test-helpers.fake-whoami-service :as whoami-helpers]
   [ctim.examples.bundles :refer [bundle-maximal]]
   [puppetlabs.trapperkeeper.app :as app]))

(defn- set-transient-asset-refs [m]
  (walk/prewalk #(if (and (map? %)
                          (contains? % :asset_ref))
                   (assoc % :asset_ref "transient:asset-1")
                   %) m))

(def bundle-ents
  "Sample Bundle Map for testing."
  (-> bundle-maximal
      (select-keys
       [:assets :asset_mappings :asset_properties ])
      th/deep-dissoc-entity-ids
      ;; in order to test association of Asset to AssetMappings/AssetProperties
      set-transient-asset-refs))

(deftest bulk-for-asset-related-entities
  (testing "delay creation of :asset-mapping and :asset-properties, until all
  transient IDs for :asset are resolved"
    (th/fixture-ctia-with-app
     (fn [app]
       (let [services (app/service-graph app)
             login (map->Identity {:login  "foouser"
                                   :groups ["foogroup"]})]
         (testing "Passing a Bundle with Assets with transient IDs, should skip
                   the creation of AssetMappings and AssetProperties (initially)"
          (with-redefs [bulk/gen-bulk-from-fn
                        (fn [_ bulk _ _ _ _]
                          (is (empty?
                               (select-keys
                                bulk [:asset_mappings
                                      :asset_properties])))
                          ;; Doesn't make sence to continue from here; once we
                          ;; asserted that asset-mapping and asset-properties
                          ;; won't be explicitly created (until we create Assets
                          ;; with non-transient IDs), we achieved the goal of
                          ;; this test.
                          (throw (Exception. "stopped intentionally")))]
            (is (thrown-with-msg?
                 Exception #"stopped intentionally"
                 (bundle/import-bundle
                  bundle-ents
                  nil         ;; external-key-prefixes
                  login
                  services))))))))))

(use-fixtures :once
  (join-fixtures
   [whoami-helpers/fixture-server]))

(deftest asset-refs-test
  (th/fixture-ctia-with-app
   (fn [app]
     (th/set-capabilities! app "foouser" ["foogroup"] "user" auth/all-capabilities)
     (testing "every AssetMapping and AssetProperties have proper
                 non-transient :asset-ref"
       (let [services          (app/service-graph app)
             login             (map->Identity {:login  "foouser"
                                               :groups ["foogroup"]})
             {:keys [results]} (bundle/import-bundle
                                bundle-ents
                                nil         ;; external-key-prefixes
                                login
                                services)
             get-records       (fn [store]
                                 (let [{{:keys [get-store]} :StoreService} services]
                                   (-> (get-store store)
                                       (store/list-records
                                        {:query "*"}
                                        {:login  "foouser"
                                         :groups ["foogroup"]} {})
                                       :data)))
             asset-refs        (->> results
                                  (filter #(-> % :result (= "created")))
                                  (filter #(-> % :type (= :asset)))
                                  (map :id)
                                  set)]
         (doseq [store [:asset-mapping :asset-properties]]
           (is (every?
                (partial contains? asset-refs)
                (->> store get-records (map :asset_ref))))))))))

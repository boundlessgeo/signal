;; Copyright 2016-2017 Boundless, http://boundlessgeo.com
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;; http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns signal.components.database
  (:require [signal.db.conn :as db]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clojure.set :refer [rename-keys]]
            [yesql.core :as ysql]
            [clojure.java.jdbc :as clj-jdbc]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]
            [signal.specs.processor]))


;;;;;;;;;;;;;;;;;SQL;;;;;;;;;;;;;;
(ysql/defqueries "sql/notification.sql" {:connection db/db-spec})
(ysql/defqueries "sql/processor.sql" {:connection db/db-spec})

;;;;;;;;;;;SANITIZERS;;;;;;;;
(defn sanitize-timestamps [v]
  (dissoc v :updated_at :deleted_at))

(defn sanitize-user [u]
  (dissoc (sanitize-timestamps u) :password :created_at))

(defn- sanitize [processor]
  (dissoc processor :created_at :updated_at :deleted_at))

(defn- processor-entity->map [t]
  (-> t
      (assoc :id (.toString (:id t)))
      (assoc :created_at (.toString (:created_at t)))
      (assoc :updated_at (.toString (:updated_at t)))))

(defn- row-fn
  "Modifies the row result while the ResultSet is open. This method
  is required for accessing methods that are only available while the
  PG ResultSet is open"
  [row]
  (-> row
      (assoc :input-ids (vec (.getArray (:input_ids row))))))

(def result->map
  {:result-set-fn doall
   :row-fn        row-fn
   :identifiers   clojure.string/lower-case})

;;;;;;;;;;;;UTILS;;;;;;;;;;;;;;;;;;
(deftype StringArray [items]
  clj-jdbc/ISQLParameter
  (set-parameter [_ stmt ix]
    (let [as-array (into-array Object items)
          jdbc-array (.createArrayOf (.getConnection stmt) "text" as-array)]
      (.setArray stmt ix jdbc-array))))

(deftype UUIDArray [items]
  clj-jdbc/ISQLParameter
  (set-parameter [_ stmt ix]
    (let [as-array (into-array Object items)
          jdbc-array (.createArrayOf (.getConnection stmt) "UUID" as-array)]
      (.setArray stmt ix jdbc-array))))

(deftype UUIDArray [items]
  clj-jdbc/ISQLParameter)

(defn sqluuid->str [row col-name]
  (if-let [r (col-name row)]
    (assoc row col-name (if (instance? java.util.UUID r) (.toString r) r))
    row))

(defn sqlarray->vec [row col-name]
  (if-let [r (col-name row)]
    (assoc row col-name (vec (.getArray r)))
    row))

(extend-type java.sql.Timestamp
  clojure.data.json/JSONWriter
  (-write [date out]
    (clojure.data.json/-write (str date) out)))

(extend-type java.util.UUID
  clojure.data.json/JSONWriter
  (-write [uuid out]
    (clojure.data.json/-write (str uuid) out)))

(extend-type org.postgresql.util.PGobject
  clj-jdbc/IResultSetReadColumn
  (result-set-read-column [val rsmeta idx]
    (let [colType (.getColumnTypeName rsmeta idx)]
      (if (contains? #{"json" "jsonb"} colType)
        (json/read-str (.getValue val) :key-fn clojure.core/keyword)
        val))))

;;;;;;;;;;;;NOTIFICATION;;;;;;;;;;;
(defn- create-message [message-type info]
  (let [info-str (json/write-str info)]
    (insert-message<!
     {:type message-type :info info-str})))

(defn find-message-by-id [id]
  (find-message-by-id-query {:id id}))

(defn create-notifications
  "Adds a notification to the queue"
  [recipients message-type info]
  (let [message (create-message message-type info)
        id (:id message)]
    (map #(sanitize-timestamps
           (insert-notification<!
            {:recipient  %
             :message_id id})) recipients)))

(defn create-notification
  [recipient message-type info]
  (sanitize-timestamps
   (insert-notification<!
    {:recipient  recipient
     :message_id (:id (create-message message-type info))})))

(defn unsent
  "List of all the unsent notifications"
  []
  (map sanitize-timestamps (unsent-notifications-list)))

(defn undelivered
  []
  (map sanitize-timestamps (undelivered-notifications-list)))

(defn notifications []
  (map sanitize-timestamps (notifications-list)))

(defn find-notif-by-id [id]
  (some-> (find-notification-by-id-query {:id id})
          first
          sanitize-timestamps))

(defn find-message-by-id [id]
  (some-> (find-message-by-id-query {:id id})
          first))

(defn mark-as-sent [notif-id]
  (mark-as-sent! {:id notif-id}))

(defn mark-as-delivered [notif-id]
  (mark-as-delivered! {:id notif-id}))

;;;;;;;;;;;;TRIGGERS;;;;;;;;;;;
(defn processors
  "Returns all the active processors"
  []
  (log/debug "Fetching all active processors from db")
  (map processor-entity->map (processor-list-query)))

(defn processor-by-id
  "Find processor by identifier"
  [id]
  (log/debugf "Finding processors with id %s from db" id)
  (some-> (find-by-id-query {:id (java.util.UUID/fromString id)})
          first
          processor-entity->map))

(defn map->processor-entity
  [trg]
  (-> (assoc trg :definition (json/write-str (:definition trg)))
      (rename-keys {:input-ids :input_ids})))

(defn create-processor
  "Creates a processor definition"
  [t]
  (log/debug "Validating processor against spec"
             (do
               (let [entity (map->processor-entity t)
                     new-processor (insert-processor<!
                                     (assoc entity :input_ids (->StringArray (:input_ids entity))))]
                 (processor-entity->map (assoc t :id (:id new-processor)
                                               :created_at (:created_at new-processor)
                                               :updated_at (:updated_at new-processor)))))))

(s/fdef create-processor
        :args (s/cat :t :signal.specs.processor/processor-spec)
        :ret :signal.specs.processor/processor-spec)

(st/instrument)

(defn modify-processor
  "Update processor"
  [id t]
  (let [entity (map->processor-entity (assoc t :id (java.util.UUID/fromString id)))
        updated-processor (update-processor<!
                           (assoc entity
                                  :stores
                                  (->StringArray (:stores t))))]
    (processor-entity->map (assoc t :id (:id updated-processor)
                                  :created_at (:created_at updated-processor)
                                  :updated_at (:updated_at updated-processor)))))

(defn delete-processor
  "Delete processor"
  [id]
  (delete-processor! {:id (java.util.UUID/fromString id)}))

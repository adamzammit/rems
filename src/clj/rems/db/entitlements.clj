(ns rems.db.entitlements
  "Creating and fetching entitlements."
  (:require [clj-http.client :as http]
            [clj-time.core :as time]
            [clojure.set :refer [union]]
            [clojure.tools.logging :as log]
            [mount.core :as mount]
            [rems.common.application-util :as application-util]
            [rems.auth.util :refer [throw-forbidden]]
            [rems.config :refer [env]]
            [rems.db.applications :as applications]
            [rems.db.core :as db]
            [rems.db.csv :as csv]
            [rems.db.outbox :as outbox]
            [rems.db.users :as users]
            [rems.json :as json]
            [rems.roles :refer [has-roles?]]
            [rems.scheduler :as scheduler]
            [rems.util :refer [getx-user-id]]))

;; TODO move Entitlement schema here from rems.api?

(defn- entitlement-to-api [{:keys [resid catappid start end mail userid]}]
  {:resource resid
   :user (users/get-user userid)
   :application-id catappid
   :start start
   :end end
   :mail mail})

(defn get-entitlements-for-api [user-or-nil resource-or-nil expired?]
  (mapv entitlement-to-api
        (db/get-entitlements {:user (if (has-roles? :handler :owner :organization-owner :reporter)
                                      user-or-nil
                                      (getx-user-id))
                              :resource-ext-id resource-or-nil
                              :is-active? (not expired?)})))

(defn- entitlement-to-permissions-api [{:keys [resid catappid start end mail userid]}]
  {:ga4gh_visa_v1 {:type "ControlledAccessGrants"
                   :value (str "" resid)
                   :source "https://ga4gh.org/duri/no_org"
                   :by "rems"             ;; TODO Get approver from application events
                   :asserted 1568699331}}) ;; TODO Real timestamp

(defn get-entitlements-for-permissions-api [user-or-nil resource-or-nil expired?]
  (mapv entitlement-to-permissions-api
        (db/get-entitlements {:user (if (has-roles? :handler :owner :organization-owner :reporter)
                                      user-or-nil
                                      (getx-user-id))
                              :resource-ext-id resource-or-nil
                              :is-active? (not expired?)})))

(defn get-entitlements-for-export
  "Returns a CSV string representing entitlements"
  []
  (when-not (has-roles? :handler)
    (throw-forbidden))
  (let [ents (db/get-entitlements)]
    (csv/entitlements-to-csv ents)))

(defn- post-entitlements! [{:keys [entitlements action] :as params}]
  (when-let [target (get-in env [:entitlements-target action])]
    (let [payload (for [e entitlements]
                    {:application (:catappid e)
                     :resource (:resid e)
                     :user (:userid e)
                     :mail (:mail e)})
          json-payload (json/generate-string payload)]
      (log/infof "Posting entitlements to %s:" target payload)
      (let [response (try
                       (http/post target
                                  {:throw-exceptions false
                                   :body json-payload
                                   :content-type :json
                                   :socket-timeout 2500
                                   :conn-timeout 2500})
                       (catch Exception e
                         (log/error "POST failed" e)
                         {:status "exception"}))
            status (:status response)]
        (when-not (= 200 status)
          (log/warnf "Entitlement post failed: %s", response)
          (str "failed: " status))))))

;; TODO argh adding these everywhere sucks
(defn- fix-entry-from-db [entry]
  (update-in entry [:outbox/entitlement-post :action] keyword))

(defn process-outbox! []
  (doseq [entry (mapv fix-entry-from-db
                      (outbox/get-entries {:type :entitlement-post :due-now? true}))]
    ;; TODO could send multiple entitlements at once instead of one outbox entry at a time
    (if-let [error (post-entitlements! (:outbox/entitlement-post entry))]
      (let [entry (outbox/attempt-failed! entry error)]
        (when (not (:outbox/next-attempt entry))
          (log/warn "all attempts to send entitlement post id " (:outbox/id entry) "failed")))
      (outbox/attempt-succeeded! (:outbox/id entry)))))

(mount/defstate entitlement-poller
  :start (scheduler/start! process-outbox! (.toStandardDuration (time/seconds 10)))
  :stop (scheduler/stop! entitlement-poller))

(defn- add-to-outbox! [action entitlements]
  (outbox/put! {:outbox/type :entitlement-post
                :outbox/deadline (time/plus (time/now) (time/days 1)) ;; hardcoded for now
                :outbox/entitlement-post {:action action
                                          :entitlements entitlements}}))

(defn- grant-entitlements! [application-id user-id resource-ids]
  (log/info "granting entitlements on application" application-id "to" user-id "resources" resource-ids)
  (doseq [resource-id (sort resource-ids)]
    (db/add-entitlement! {:application application-id
                          :user user-id
                          :resource resource-id})
    ;; TODO could generate only one outbox entry per application. Currently one per user-resource pair.
    (add-to-outbox! :add (db/get-entitlements {:application application-id :user user-id :resource resource-id}))))

(defn- revoke-entitlements! [application-id user-id resource-ids]
  (log/info "revoking entitlements on application" application-id "to" user-id "resources" resource-ids)
  (doseq [resource-id (sort resource-ids)]
    (db/end-entitlements! {:application application-id
                           :user user-id
                           :resource resource-id})
    (add-to-outbox! :remove (db/get-entitlements {:application application-id :user user-id :resource resource-id}))))

(defn- get-entitlements-by-user [application-id]
  (->> (db/get-entitlements {:application application-id :is-active? true})
       (group-by :userid)
       (map (fn [[userid rows]]
              [userid (set (map :resourceid rows))]))
       (into {})))

(defn- update-entitlements-for-application
  "If the given application is approved, licenses accepted etc. add an entitlement to the db
  and call the entitlement REST callback (if defined). Likewise if a resource is removed, member left etc.
  then we end the entitlement and call the REST callback."
  [application]
  (let [application-id (:application/id application)
        current-members (set (map :userid (application-util/applicant-and-members application)))
        past-members (set (map :userid (:application/past-members application)))
        application-state (:application/state application)
        application-resources (->> application
                                   :application/resources
                                   (map :resource/id)
                                   set)
        application-entitlements (get-entitlements-by-user application-id)
        is-entitled? (fn [userid resource-id]
                       (and (= :application.state/approved application-state)
                            (contains? current-members userid)
                            (application-util/accepted-licenses? application userid)
                            (contains? application-resources resource-id)))
        entitlements-by-user (fn [userid] (or (application-entitlements userid) #{}))
        entitlements-to-add (->> (for [userid (union current-members past-members)
                                       :let [current-resource-ids (entitlements-by-user userid)]
                                       resource-id application-resources
                                       :when (is-entitled? userid resource-id)
                                       :when (not (contains? current-resource-ids resource-id))]
                                   {userid #{resource-id}})
                                 (apply merge-with union))
        entitlements-to-remove (->> (for [userid (union current-members past-members)
                                          :let [resource-ids (entitlements-by-user userid)]
                                          resource-id resource-ids
                                          :when (not (is-entitled? userid resource-id))]
                                      {userid #{resource-id}})
                                    (apply merge-with union))
        members-to-update (keys (merge entitlements-to-add entitlements-to-remove))]
    (when (seq members-to-update)
      (log/info "updating entitlements on application" application-id)
      (doseq [[userid resource-ids] entitlements-to-add]
        (grant-entitlements! application-id userid resource-ids))
      (doseq [[userid resource-ids] entitlements-to-remove]
        (revoke-entitlements! application-id userid resource-ids)))))

(defn update-entitlements-for-event [event]
  ;; performance improvement: filter events which may affect entitlements
  (when (contains? #{:application.event/approved
                     :application.event/closed
                     :application.event/licenses-accepted
                     :application.event/member-removed
                     :application.event/resources-changed
                     :application.event/revoked}
                   (:event/type event))
    (let [application (applications/get-unrestricted-application (:application/id event))]
      (update-entitlements-for-application application))))

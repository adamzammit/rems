(ns rems.migrations.convert-to-dynamic-applications
  (:require [clojure.java.jdbc :as jdbc]
            [rems.db.applications :as applications]
            [rems.db.core :refer [*db*]]
            [rems.db.workflow :as workflow]
            [rems.db.workflow-actors :as actors])
  (:import [java.util UUID]))

(defn migrate-catalogue-items! [workflow-id]
  (jdbc/execute! *db* ["update catalogue_item set wfid = ?" workflow-id]))

(defn migrate-application! [application-id workflow-id]
  (let [read-user (or (first (actors/get-by-role application-id "approver"))
                      ;; auto-approved workflows do not have an approver,
                      ;; so the applicant is the only one who can see the application
                      (:applicantuserid (applications/get-application-state application-id)))
        form (applications/get-form-for read-user application-id)
        application (:application form)
        workflow (workflow/get-workflow workflow-id)
        comment-requests-by-commenter (atom {})]
    (assert (= "workflow/dynamic" (get-in workflow [:workflow :type])))

    ;; use the dynamic workflow
    (jdbc/execute! *db* ["update catalogue_item_application set wfid = ? where id = ?" (:id workflow) (:id application)])
    ;; delete old events
    (jdbc/execute! *db* ["delete from application_event where appid = ?" (:id application)])

    (applications/add-application-created-event! {:application-id (:id application)
                                                  :catalogue-item-ids (->> (:catalogue-items application)
                                                                           (map :id))
                                                  :time (:start application)
                                                  :actor (:applicantuserid application)})
    (applications/add-dynamic-event! {:event/type :application.event/draft-saved
                                      :event/time (:start application)
                                      :event/actor (:applicantuserid application)
                                      :application/id (:id application)
                                      :application/field-values (->> (:items form)
                                                                     (map (fn [item]
                                                                            [(:id item) (:value item)]))
                                                                     (into {}))
                                      :application/accepted-licenses (->> (:licenses form)
                                                                          (filter :approved)
                                                                          (map :id)
                                                                          set)})
    (doseq [event (:events application)]
      (case (:event event)
        "save" nil ; skip - the save-draft event is produced separately
        "apply" (applications/add-dynamic-event! {:event/type :application.event/submitted
                                                  :event/time (:time event)
                                                  :event/actor (:userid event)
                                                  :application/id (:id application)})
        "reject" (applications/add-dynamic-event! {:event/type :application.event/rejected
                                                   :event/time (:time event)
                                                   :event/actor (:userid event)
                                                   :application/id (:id application)
                                                   :application/comment (:comment event)})
        "approve" (applications/add-dynamic-event! {:event/type :application.event/approved
                                                    :event/time (:time event)
                                                    :event/actor (:userid event)
                                                    :application/id (:id application)
                                                    :application/comment (:comment event)})
        "autoapprove" (applications/add-dynamic-event! {:event/type :application.event/approved
                                                        :event/time (:time event)
                                                        :event/actor (:userid event)
                                                        :application/id (:id application)
                                                        :application/comment ""})
        "return" (applications/add-dynamic-event! {:event/type :application.event/returned
                                                   :event/time (:time event)
                                                   :event/actor (:userid event)
                                                   :application/id (:id application)
                                                   :application/comment (:comment event)})
        "review" (applications/add-dynamic-event! {:event/type :application.event/commented
                                                   :event/time (:time event)
                                                   :event/actor (:userid event)
                                                   :application/id (:id application)
                                                   ;; TODO: request-id doesn't make much sense for these old applications - make it optional?
                                                   :application/request-id (UUID. 0 0)
                                                   :application/comment (:comment event)})
        "review-request" (applications/add-dynamic-event! {:event/type :application.event/comment-requested
                                                           :event/time (:time event)
                                                           ;; TODO: it's not known that who made the review request; can we guess the approver?
                                                           :event/actor (:userid event)
                                                           :application/id (:id application)
                                                           :application/request-id (do
                                                                                     (let [request-id (UUID/randomUUID)]
                                                                                       (swap! comment-requests-by-commenter
                                                                                              assoc (:userid event) request-id)
                                                                                       request-id))
                                                           :application/commenters [(:userid event)]
                                                           :application/comment (:comment event)})
        "third-party-review" (applications/add-dynamic-event! {:event/type :application.event/commented
                                                               :event/time (:time event)
                                                               :event/actor (:userid event)
                                                               :application/id (:id application)
                                                               :application/request-id (get @comment-requests-by-commenter (:userid event))
                                                               :application/comment (:comment event)})
        "withdraw" (assert false "withdraw not implemented") ; TODO: migrate "withdraw"
        "close" (assert false "close not implemented"))))) ; TODO: migrate "close"

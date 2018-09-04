(ns rems.administration.resource
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [rems.administration.components :refer [text-field]]
            [rems.autocomplete :as autocomplete]
            [rems.collapsible :as collapsible]
            [rems.text :refer [text localize-item]]
            [rems.util :refer [dispatch! fetch post!]]))

(defn- valid-request? [request]
  (and (not (str/blank? (:prefix request)))
       (not (str/blank? (:resid request)))))

(defn- build-request [form]
  (let [request {:prefix (:prefix form)
                 :resid (:resid form)
                 :licenses (map :id (:licenses form))}]
    (when (valid-request? request)
      request)))

(defn- create-resource [form]
  (post! "/api/resources/create" {:params (build-request form)
                                  :handler (fn [resp]
                                             (dispatch! "#/administration"))}))

(rf/reg-event-fx
  ::create-resource
  (fn [db [_ request]]
    (create-resource request)
    {}))

(rf/reg-event-db
  ::reset-create-resource
  (fn [db _]
    (assoc db ::form {:licenses #{}})))

(rf/reg-sub
  ::form
  (fn [db _]
    (::form db)))

(rf/reg-event-db
  ::set-form-field
  (fn [db [_ keys value]]
    (assoc-in db (concat [::form] keys) value)))

(rf/reg-sub
  ::selected-licenses
  (fn [db _]
    (get-in db [::form :licenses])))

(rf/reg-event-db
  ::select-license
  (fn [db [_ license]]
    (update-in db [::form :licenses] conj license)))

(rf/reg-event-db
  ::deselect-license
  (fn [db [_ license]]
    (update-in db [::form :licenses] disj license)))


; available licenses

(rf/reg-sub
  ::available-licenses
  (fn [db _]
    (::available-licenses db)))

(rf/reg-event-db
  ::set-available-licenses
  (fn [db [_ licenses]]
    (assoc db ::available-licenses licenses)))

(defn- fetch-licenses []
  (fetch "/api/licenses?active=true"
         {:handler #(rf/dispatch [::set-available-licenses %])}))


;;;; UI ;;;;

(def ^:private context {:get-form ::form
                        :update-form ::set-form-field})

(defn- resource-prefix-field []
  [text-field context {:keys [:prefix]
                       :label (text :t.create-resource/prefix)
                       :placeholder (text :t.create-resource/prefix-placeholder)}])

(defn- resource-id-field []
  [text-field context {:keys [:resid]
                       :label (text :t.create-resource/resid)
                       :placeholder (text :t.create-resource/resid-placeholder)}])

(defn- resource-licenses-field []
  (let [available-licenses @(rf/subscribe [::available-licenses])
        selected-licenses @(rf/subscribe [::selected-licenses])]
    [:div.form-group
     [:label (text :t.create-resource/licenses-selection)]
     [autocomplete/component
      {:value (->> selected-licenses
                   (map localize-item)
                   (sort-by :id))
       :items (map localize-item available-licenses)
       :value->text #(:title %2)
       :item->key :id
       :item->text :title
       :item->value identity
       :search-fields [:title]
       :add-fn #(rf/dispatch [::select-license %])
       :remove-fn #(rf/dispatch [::deselect-license %])}]]))

(defn- save-resource-button []
  (let [form @(rf/subscribe [::form])]
    [:button.btn.btn-primary
     {:on-click #(rf/dispatch [::create-resource form])
      :disabled (not (build-request form))}
     (text :t.administration/save)]))

(defn- cancel-button []
  [:button.btn.btn-secondary
   {:on-click #(dispatch! "/#/administration")}
   (text :t.administration/cancel)])

(defn create-resource-page []
  ; FIXME: fetch available licenses the same way as fetch-form-items to fix https://github.com/CSCfi/rems/issues/646
  (fetch-licenses)
  (fn []
    [collapsible/component
     {:id "create-resource"
      :title (text :t.navigation/create-resource)
      :always [:div
               [resource-prefix-field]
               [resource-id-field]
               [resource-licenses-field]

               [:div.col.commands
                [cancel-button]
                [save-resource-button]]]}]))

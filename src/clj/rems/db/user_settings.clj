(ns rems.db.user-settings
  (:require [clojure.string :as str]
            [medley.core :refer [map-keys]]
            [rems.config :refer [env]]
            [rems.db.core :as db]
            [rems.json :as json]
            [schema.coerce :as coerce]
            [schema.core :as s]))

(defn- default-settings []
  {:language (:default-language env)
   :email nil})

(s/defschema UserSettings
  {:language s/Keyword
   :email (s/maybe s/Str)})

(s/defschema PartialUserSettings
  (map-keys s/optional-key UserSettings))

(def ^:private coerce-partial-settings
  (coerce/coercer! PartialUserSettings json/coercion-matcher))

(defn- parse-settings [settings]
  (when settings
    (coerce-partial-settings (json/parse-string settings))))

(defn get-user-settings [user]
  (merge (default-settings)
         (when user
           (parse-settings (:settings (db/get-user-settings {:user user}))))))

;; regex from https://developer.mozilla.org/en-US/docs/Web/HTML/Element/input/email#Validation
(def ^:private email-regex #"[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*")

(defn validate-settings [{:keys [language email] :as settings}]
  (into {} [(when (contains? (set (:languages env)) language)
              {:language language})
            (when (and email (re-matches email-regex email))
              {:email email})
            (when (and (contains? settings :email)
                       (str/blank? email))
              ;; clear custom email to use identity provider's email instead
              {:email nil})]))

(defn update-user-settings! [user new-settings]
  (assert user "User missing!")
  (let [old-settings (get-user-settings user)
        validated (validate-settings new-settings)]
    (db/update-user-settings!
     {:user user
      :settings (json/generate-string
                 (merge old-settings validated))})
    {:success (some? validated)}))

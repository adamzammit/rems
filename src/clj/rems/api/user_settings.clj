(ns rems.api.user-settings
  (:require [compojure.api.sweet :refer :all]
            [rems.api.schema :as schema]
            [rems.config :refer [env]]
            [rems.db.user-settings :as user-settings]
            [rems.util :refer [getx-user-id get-user-id]]
            [ring.util.http-response :refer :all]
            [schema.core :as s])
  (:import (org.joda.time DateTime)))

(def GetUserSettings user-settings/UserSettings)

(s/defschema UpdateUserSettings
  {(s/optional-key :language) s/Keyword
   (s/optional-key :notification-email) (s/maybe s/Str)})

(s/defschema GenerateEGAApiKeyResponse
  {:success s/Bool
   (s/optional-key :api-key-expiration-date) DateTime})

(def user-settings-api
  (context "/user-settings" []
    :tags ["user-settings"]

    (GET "/" []
      :summary "Get user settings"
      :roles #{:logged-in}
      :return GetUserSettings
      (ok (user-settings/get-user-settings (get-user-id))))

    (PUT "/" []
      :summary "Update user settings"
      :roles #{:logged-in}
      :body [settings UpdateUserSettings]
      :return schema/SuccessResponse
      (ok (user-settings/update-user-settings! (getx-user-id) settings)))

    (when (:enable-ega env)
      (POST "/generate-ega-api-key" [:as request] ; NB: binding syntax
        :summary "Generates a new EGA API-key for the user."
        :roles #{:handler}
        :return GenerateEGAApiKeyResponse
        (let [access-token (get-in request [:session :access-token])]
          (ok (user-settings/generate-ega-api-key! (get-user-id) access-token)))))))

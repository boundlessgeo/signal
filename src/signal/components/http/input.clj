(ns signal.components.http.input
  (:require [signal.components.http.intercept :as intercept]
            [signal.components.http.response :as response]
            [signal.components.input-manager :as input-manager-api]))

(defn http-get-all-inputs
  [input-comp _]
  (response/ok (input-manager-api/all input-comp)))

(defn http-get-input
  [input-comp request]
  (let [input (input-manager-api/find-by-id input-comp (get-in request [:path-params :id]))]
    (response/ok input)))

(defn http-put-inputs
  [input-comp request]
  (do
    (input-manager-api/add-polling-input
      input-comp (:json-params request))
    (response/ok "success")))

(defn http-post-inputs
  [input-comp request]
  (response/ok (input-manager-api/create input-comp (:json-params request))))

(defn http-delete-inputs
  [input-comp request]
  (let [id (get-in request [:path-params :id])]
      (if (input-manager-api/delete input-comp id)
        (response/ok "success")
        (response/error "could not delete input: " id))))

(defn routes
  "Makes routes for the current inputs"
  [input-comp]
  #{["/api/inputs" :get
     (conj intercept/common-interceptors
           (partial http-get-all-inputs input-comp))
     :route-name :get-inputs]
    ["/api/inputs/:id" :get
     (conj intercept/common-interceptors
           (partial http-get-input input-comp))
     :route-name :get-input]
    ["/api/inputs/:id" :put
     (conj intercept/common-interceptors
           (partial http-put-inputs input-comp))
     :route-name :put-input]
    ["/api/inputs" :post
     (conj intercept/common-interceptors
           (partial http-post-inputs input-comp))
     :route-name :post-input]
    ["/api/inputs/:id" :delete
     (conj intercept/common-interceptors
           (partial http-delete-inputs input-comp))
     :route-name :delete-input]})

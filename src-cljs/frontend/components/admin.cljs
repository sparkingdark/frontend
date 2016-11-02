(ns frontend.components.admin
  (:require [ankha.core :as ankha]
            [cljs.core.async :as async :refer [<! >! alts! chan close! sliding-buffer]]
            [clojure.string :as str]
            [frontend.async :refer [navigate! raise!]]
            [frontend.components.builds-table :as builds-table]
            [frontend.components.common :as common]
            [frontend.components.pieces.button :as button]
            [frontend.components.pieces.dropdown :as dropdown]
            [frontend.components.pieces.table :as table]
            [frontend.components.pieces.tabs :as tabs]
            [frontend.components.pieces.spinner :refer [spinner]]
            [frontend.config :as config]
            [frontend.datetime :as datetime]
            [frontend.routes :as routes]
            [frontend.state :as state]
            [frontend.utils :as utils :include-macros true]
            [inflections.core :refer [pluralize]]
            [om.core :as om :include-macros true])
  (:require-macros [frontend.utils :refer [html]]))

(defn build-state [app owner]
  (reify
    om/IDisplayName (display-name [_] "Admin Build State")
    om/IRender
    (render [_]
      (let [build-state (get-in app state/build-state-path)]
        (html
         [:section {:style {:padding-left "10px"}}
          [:a {:href "/api/v1/admin/build-state" :target "_blank"} "View raw"]
          " / "
          [:a {:href "javascript:void(0)" :on-click #(raise! owner [:refresh-admin-build-state-clicked])} "Refresh"]
          (if-not build-state
            (spinner)
            [:code (om/build ankha/inspector build-state)])])))))

(defn switch [app owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div.container-fluid
        [:div.row-fluid
         [:div.span9
          [:p "Switch user"]
          [:form.form-inline {:method "post", :action "/admin/switch-user"}
           [:input.input-medium {:name "login", :type "text"}]
           (when-not (config/enterprise?)
             [:div.col-lg-3
              [:label
               [:input {:type "checkbox"
                        :name "bitbucket"}]
               "Bitbucket"]])
           [:input {:value (utils/csrf-token)
                    :name "CSRFToken",
                    :type "hidden"}]
           [:div.col-lg-9
            (button/button {:on-click (fn [event]
                                        ;; a higher level handler will stop all form submissions
                                        ;;
                                        ;; see frontend.components.app/app*
                                        (.stopPropagation event))
                            :kind :primary}
                           "Switch user")]]]]]))))

(defn current-seat-usage [active-users total-seats]
  [:span
   "There " (if (= 1 active-users) "is" "are") " currently "
   [:b (pluralize active-users "active user")]
   " out of "
   [:b (pluralize total-seats "licensed user")]
   "."])

(defn overview [app owner]
  (reify
    om/IDisplayName (display-name [_] "Admin Build State")
    om/IRender
    (render [_]
      (html
        [:section {:style {:padding-left "10px"}}
         [:h1 "Active Users"]
         [:p (current-seat-usage (get-in app (conj state/license-path :seat_usage))
                                 (get-in app (conj state/license-path :seats)))
          " You can deactivate users in "
          [:a {:href "/admin/users"} "user settings."]]]))))

(defn builders [builders owner]
  (reify
    om/IDisplayName (display-name [_] "Admin Build State")
    om/IRender
    (render [_]
      (html
        [:section {:style {:padding-left "0"}}
         [:header {:style {:padding-top "10px"}}
          [:a {:href "/api/v1/admin/build-state-summary" :target "_blank"} "View raw"]
          " / "
          [:a {:href "javascript:void(0)" :on-click #(raise! owner [:refresh-admin-fleet-state-clicked])} "Refresh"]]
         (if-not builders
           (spinner)
           (if-not (seq builders)
             "No available masters."
             (om/build table/table
                       {:rows builders
                        :key-fn :instance_id
                        :columns [{:header "Instance ID"
                                   :cell-fn :instance_id}

                                  {:header "Instance Type"
                                   :cell-fn :ec2_instance_type}

                                  {:header "Boot Time"
                                   :cell-fn (comp datetime/long-datetime :boot_time)}

                                  {:header "Busy Containers"
                                   :cell-fn :busy}

                                  {:header "State"
                                   :cell-fn :state}]})))]))))

(defn admin-builds-table [data owner {:keys [tab]}]
  (reify
    om/IRender
    (render [_]
      (html
        [:div
         [:header {:style {:padding-top "10px" :padding-bottom "10px"}}
          [:a
           {:href (case tab
                    :running-builds "/admin/running-builds"
                    :queued-builds "/admin/queued-builds")}
           "See more"]
          " / "
          [:a {:on-click #(raise! owner [:refresh-admin-build-list {:tab tab}])} "Refresh"]
          (if (nil? (:builds data))
            (spinner))]
         (om/build builds-table/builds-table data
                   {:opts {:show-actions? true
                           :show-parallelism? true
                           :show-branch? false
                           :show-log? false}})]))))

(defn fleet-state [app owner]
  (reify
    om/IRender
    (render [_]
      (let [fleet-state (->> (get-in app state/fleet-state-path)
                             (filter #(-> % :boot_time not-empty)) ;; remove corrupt entries
                             (remove #(-> % :builder_tags (= ["os:none"])))
                             (sort-by :instance_id))
            summary-counts (get-in app state/build-system-summary-path)
            current-tab (or (get-in app [:navigation-data :tab]) :builders)]
        (html
          [:div {:style {:padding-left "10px"}}
           [:h1 "Fleet State"]
           [:div
            (if-not summary-counts
              (spinner)
              (let [container-totals (->> fleet-state
                                          (map #(select-keys % [:free :busy :total]))
                                          (apply merge-with +))
                    queued-builds (+ (get-in summary-counts [:usage_queue :builds])
                                     (get-in summary-counts [:run_queue :builds]))
                    queue-container-count (+ (get-in summary-counts [:usage_queue :containers])
                                             (get-in summary-counts [:run_queue :containers]))]
                [:div
                 [:div "capacity"
                  [:ul [:li "total containers: " (:total container-totals)]]]
                 [:div "in use"
                  [:ul
                   [:li "running builds: " (get-in summary-counts [:running :builds])]
                   [:li "containers in use: " (:busy container-totals)]]]
                 [:div "queued"
                  [:ul
                   [:li "queued builds: " queued-builds]
                   [:li "containers requested by queued builds: " queue-container-count]]]]))
            (om/build tabs/tab-row {:tabs [{:name :builders
                                            :label "Builders"}
                                           {:name :running-builds
                                            :label "Running Builds"}
                                           {:name :queued-builds
                                            :label "Queued Builds"}]
                                    :selected-tab-name current-tab
                                    :on-tab-click #(navigate! owner (routes/v1-admin-fleet-state-path {:_fragment (name %)}))})
            (if (#{:running-builds :queued-builds} current-tab)
              (om/build admin-builds-table
                        {:builds (:recent-builds app)
                         :projects (get-in app state/projects-path)}
                        {:opts {:tab current-tab}})
              (om/build builders fleet-state))]])))))

(defn license [app owner]
  (reify
    om/IDisplayName (display-name [_] "License Info")
    om/IRender
    (render [_]
      (html
        [:section {:style {:padding-left "10px"}}
         [:h1 "License Info"]
         (let [license (get-in app state/license-path)]
           (if-not license
             (spinner)
             (list
              [:p "License Type: " [:b (:type license)]]
              [:p "License Status: Term (" [:b (:expiry_status license)] "), Seats ("
               [:b (:seat_status license)] ": "
               (get-in app (conj state/license-path :seat_usage)) "/"
               (get-in app (conj state/license-path :seats)) ")"]
              [:p "Expiry date: " [:b (datetime/medium-date (:expiry_date license))]])))]))))

(defn relevant-scope
  [admin-scopes]
  (or (some (set admin-scopes) ["write-settings" "read-settings"])
      "none"))

(defn user-table [{:keys [users current-user]} owner]
  (let [scope-labels {"write-settings" "Admin"
                      "read-settings" "Read-only Admin"
                      "none" "Normal"}]
    (reify
      om/IDisplayName (display-name [_] "License Info")
      om/IRender
      (render [_]
        (html
         [:div.user-table
          (om/build table/table
                    {:rows users
                     :key-fn :login
                     :columns [{:header "GitHub ID"
                                :cell-fn
                                #(html
                                  [:a {:href (routes/v1-dashboard-path {:vcs_type "github" :org (:login %) })} (:login %)])}

                               {:header "Name"
                                :cell-fn :name}

                               {:header "Permissions"
                                :type :shrink
                                :cell-fn
                                (fn [user]
                                  (let [show-suspend-unsuspend? (and (#{"all" "write-settings"} (:admin current-user))
                                                                     (not= (:login current-user) (:login user)))
                                        scope (-> user :admin_scopes relevant-scope)
                                        dropdown-options (cond->> (keys scope-labels)
                                                           (not= "read-settings" scope) (remove #{"read-settings"}))]
                                    (html
                                     (if-not show-suspend-unsuspend?
                                       (-> user :admin_scopes relevant-scope scope-labels)

                                       [:div.permissions-editor
                                        (dropdown/dropdown
                                         {:on-change #(raise! owner [:set-admin-scope
                                                                     {:login (:login user)
                                                                      :scope %}])
                                          :value scope
                                          :options (for [opt dropdown-options]
                                                     [opt (scope-labels opt)])})

                                        (let [action (if (:suspended user) :unsuspend-user :suspend-user)]
                                          [:span.suspend-button
                                           (button/button {:on-click #(raise! owner [action (select-keys user [:login])])}
                                                          (case action
                                                            :suspend-user "Suspend"
                                                            :unsuspend-user "Activate"))])]))))}]})])))))

(defn users [app owner]
  (reify
    om/IDisplayName (display-name [_] "User Admin")

    om/IRender
    (render [_]
      (let [all-users (:all-users app)
            active-users (filter #(and (pos? (:sign_in_count %))
                                       (not (:suspended %)))
                                 all-users)
            suspended-users (filter #(and (pos? (:sign_in_count %))
                                          (:suspended %))
                                    all-users)
            suspended-new-users (filter #(and (zero? (:sign_in_count %))
                                              (:suspended %))
                                        all-users)
            inactive-users (filter #(and (zero? (:sign_in_count %))
                                         (not (:suspended %)))
                                   all-users)
            current-user (:current-user app)
            num-licensed-users (get-in app (conj state/license-path :seats))
            num-active-users (get-in app (conj state/license-path :seat_usage))]
        (html
         [:div.users {:style {:padding-left "10px"}}
          [:h1 "Users"]

          [:div.card.detailed
           [:h3 "Active"]
           [:div.details (current-seat-usage num-active-users num-licensed-users)]
           (when (not-empty active-users)
             (om/build user-table {:users active-users :current-user current-user}))]

          [:div.card.detailed
           [:h3 "Suspended"]
           [:div.details "Suspended users are prevented from logging in and do not count towards the number your license allows."]
           (when (not-empty suspended-users)
             (om/build user-table {:users suspended-users :current-user current-user}))]

          ;;Don't show this section if there are no suspended new users to show
          (when (not-empty suspended-new-users)
            [:div.card.detailed
             [:h3 "Suspended New Users"]
             [:div.details "Suspended new users require an admin to unsuspend them before they can log on and do not count towards the number your license allows."]
             (om/build user-table {:users suspended-new-users :current-user current-user})])

          [:div.card.detailed
           [:h3 "Inactive Users"]
           [:div.details "Inactive users have never logged on and also do not count towards your license limits."]
           (when (not-empty inactive-users)
             (om/build user-table {:users inactive-users :current-user current-user}))]])))))

(defn boolean-setting-entry [item owner]
  (reify
    om/IRender
    (render [_]
      (html [:div.details
             [:div.btn-group
              [:button.btn.btn-default
               (if-let [value (:value item)]
                 {:class "active"}
                 {:on-click #(raise! owner [:system-setting-changed
                                            (assoc item :value true)])})
               "true"]
              [:button.btn.btn-default
               (if-let [value (:value item)]
                 {:on-click #(raise! owner [:system-setting-changed
                                            (assoc item :value false)])}
                 {:class "active"})
               "false"]]
             (when (:updating item) (spinner))]))))

(defn get-input-box-type [display-type]
  (case display-type
    "text" :input.form-control
    "textarea" :textarea.form-control))

(defn- setting-entry [item owner type]
  (reify
    om/IRender
    (render [_]
      (let [input-type (get-input-box-type (or (:display_type item) "text"))
            field-ref (str (:name item) "-input")
            get-field-value #(some->> field-ref
                                      (om/get-node owner)
                                      .-value
                                      type)]
        (html
         [:div.form-group.details
          [input-type
           {:type "text"
            :default-value (:value item)
            :ref field-ref}]
          (when-let [error-message (:error item)]
            (om/build common/flashes (str error-message ". ")))
          (button/button {:on-click #(raise! owner [:system-setting-changed
                                                    (assoc item
                                                           :value (get-field-value))])
                          :kind :primary}
                         (if-not (:updating item)
                           "Save"
                           (spinner)))])))))

(defn number-setting-entry [item owner]
  (setting-entry item owner js/Number))

(defn text-setting-entry [item owner]
  (setting-entry item owner js/String))

(defn system-setting [item owner]
  (reify
    om/IRender
    (render [_]
      (html [:div.card.detailed
             [:h4 (:human_name item)]
             [:div.details (:description item)]
             (om/build (case (:value_type item)
                         "Boolean" boolean-setting-entry
                         "Number" number-setting-entry
                         "String" text-setting-entry)
                       item)]))))

(defn system-settings [app owner]
  (reify
    om/IRender
    (render [_]
      (html [:div
             [:h1 "System Settings"]
             [:p "Low level settings for tweaking the behavior of the system."]
             [:div (om/build-all system-setting
                                 (get-in app state/system-settings-path))]]))))

(defn admin-settings [app owner]
  (reify
    om/IRender
    (render [_]
      (let [subpage (:admin-settings-subpage app)]
        (html
         [:div#admin-settings
          [:div.admin-settings-inner
           [:div#subpage
            (case subpage
              :fleet-state (om/build fleet-state app)
              :license (om/build license app)
              :users (om/build users app)
              :system-settings (om/build system-settings app)
              (om/build overview app))]]])))))

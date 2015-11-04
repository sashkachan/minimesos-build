(ns minimesos-pipeline.plugin
  (:require [clojure.core.async :refer [go-loop <!]]
            [clojure.tools.logging :as log]))

(defonce agents (atom {}))

(defn bootstrap-agents
  "Starts thread that fetches updates from lambdacd events channel and updates agents. 
  Every step has respective agent. If no agent exists for the step, create and update agents map."
  [ctx]
  (let [error-mode :continue
        error-handler (fn [failed-agent ^Exception exception]
                        (log/error (.getMessage exception)))
        steps[:step-result-updated :step-finished]
        agents (reset! agents (into {} (map #(vector % (agent {})) steps)))        
        ch (go-loop []
             (let [publ (:event-publisher ctx)
                   {:keys [topic payload]} (<! publ)
                   topic-agent (get agents topic)]
               (log/info "Received payload update: " topic payload)
               (send-off topic-agent assoc :topic topic :payload payload))
             (recur))]
    agents))

(defn on-step
  "Subscribe to specific step event."
  [step f]
  (let [nUUID (java.util.UUID/randomUUID)
        candidate-agent (get @agents step)]
    (if (nil? candidate-agent)
      (throw (Exception. (str "Agent does not exist for step " step)))
      (add-watch candidate-agent nUUID f))
    nUUID))

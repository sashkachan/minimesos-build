(ns minimesos-pipeline.pipeline
  (:use [lambdacd.steps.control-flow]
        [minimesos-pipeline.steps])
  (:require
    [lambdacd.steps.manualtrigger :as manualtrigger]
    [lambdacd.steps.git :as git]
    [clojure.tools.logging :as log]
    [lambdacd.steps.shell :as shell]
    [lambdacd.steps.support :as support]))

(def minimesos-repo "https://github.com/ContainerSolutions/minimesos.git")

(defn wait-for-repo [_ ctx]
  (let [wait-result (git/wait-with-details ctx minimesos-repo "master")]
    (assoc wait-result :revision (:revision wait-result) :step-name "Github trigger")))

(defn build [{cwd :cwd} ctx]
  (assoc (shell/bash ctx cwd
                     "sh -c './gradlew build -x test -x buildDockerImage'")
         :step-name "build"))

(defn trigger-readthedocs [{cwd :cwd} ctx]
  (assoc (shell/bash ctx cwd  "curl -X POST 'https://readthedocs.org/build/minimesos'") :step-name "trigger-readthedocs"))

(defn trigger-jitpack [cd ctx]
  (let [revision (:revision cd)        
        _ (log/info "Triggering jitpack, revision: " revision )
        build-log (slurp (format "https://jitpack.io/com/github/ContainerSolutions/minimesos/%s/build.log" revision))
        jp-success? (not= nil?
                          (re-find #"BUILD SUCCESS" build-log))]
    (log/info "Jitpack response: " jp-success?)
    {:build-log build-log
     :step-name "trigger-jitpack"
     :status (if jp-success? :success :fail)}))

;; trigger jitpack
;; trigger readthedocs
;; update website

(defn ^{:display-type :container} with-repo [& steps]
  (fn [args ctx]
    (log/info "With-repo args: " args)
    (if (nil? (:revision args))
      (if (not= nil (:pr-id args))
        (let [f (git/with-pull-request ctx minimesos-repo (:pr-id args) steps)]
          (f (assoc args :step-name "with-pull-request") ctx))
        (git/checkout-and-execute minimesos-repo "master" args ctx steps :branch))
      (git/checkout-and-execute minimesos-repo (:revision args) args ctx steps))))

(def pipeline
  `((either
     manualtrigger/wait-for-manual-trigger
     manualtrigger/wait-for-pr
     wait-for-repo)
    (with-repo
      build
      (in-parallel
       trigger-jitpack
       trigger-readthedocs))))

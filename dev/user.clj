(ns user
  (:require [clojure.java.io :as io]
            [clojure.java.javadoc :refer (javadoc)]
            [clojure.pprint :as pprint :refer (pprint)]
            [clojure.repl :refer :all]
            [clojure.tools.logging :as log]
            [clojure.tools.namespace.repl :refer (refresh refresh-all)]
            [clojure.tools.trace :refer (trace-ns)]
            [com.stuartsierra.component :as component]
            [daedal.git :refer :all]
            [io.pedestal.service.http.jetty])
  (:refer-clojure :exclude [methods])
  (:import [java.io ByteArrayInputStream]
           [org.eclipse.jgit.lib
            AbbreviatedObjectId
            AnyObjectId
            CommitBuilder
            Constants
            FileMode
            ObjectDatabase
            ObjectId
            ObjectInserter
            ObjectReader
            PersonIdent
            Ref
            RefDatabase
            RefUpdate
            Repository
            RepositoryBuilder
            TreeFormatter]

;;; Development-time components

(defn create-jetty-server
  [port]
  (let [server         (Server. port)
        [repo db]      (mem-repo)
        repo-resolver  (reify org.eclipse.jgit.transport.resolver.RepositoryResolver
                         (open [this req name]
                           (log/debug {:method :repository-resolver/open
                                       :name name})
                           repo))
        upload-factory (proxy [org.eclipse.jgit.transport.resolver.UploadPackFactory] []
                         (create [req repo]
                           (log/debug {:method :upload-pack-factory/create
                                       :request req
                                       :repo repo})
                           (UploadPack. repo)))
        servlet        (doto (GitServlet.)
                         (.setRepositoryResolver repo-resolver)
                         (.setUploadPackFactory upload-factory))
        context        (doto (ServletContextHandler.)
                         (.setContextPath "/")
                         (.addServlet (ServletHolder. servlet) "/*"))]
    (.setHandler server context)
    server))

(defn jetty-server
  "Returns a Lifecycle wrapper around embedded Jetty for development."
  [port]
  (let [server (atom nil)]
    (reify component/Lifecycle
      (start [_]
        (when-not @server
          (log/info :STARTING "jetty" :port port)
          (reset! server
                  (create-jetty-server port))
          (.start @server)
          (log/info :STARTED "jetty" :port port)))
      (stop [_]
        (when @server
          (log/info :STOPPING "jetty" :port port)
          (.stop @server)
          (reset! server nil)
          (log/info :STOPPED "jetty" :port port))))))

(def dev-system-components [:jetty])

;; Do not create directly; use dev-system function
;; options is only here so we can look at it later if we want to.
(defrecord DevSystem [jetty options]
  component/Lifecycle
  (start [this]
    (component/start-system this dev-system-components))
  (stop [this]
    (component/stop-system this dev-system-components)))

(defn dev-system
  "Returns a complete system in :dev mode for development at the REPL.
  Options are key-value pairs from:

      :port        Web server port, default is 9900"
  [& {:keys [port]
      :or {port        9900}
      :as options}]
  (let [jetty           (jetty-server app port)]
    ;; TODO: If we start to have dependencies, make use of component/using
    (map->DevSystem {:jetty   jetty
                     :options options})))

;;; Development system lifecycle

(defn init
  "Initializes the development system."
  [& options]
  (alter-var-root #'system-instance/system (constantly (apply dev-system options))))

;; If desired change this to a vector of options that will get passed
;; to dev-system on (reset)
(def default-options [])

(defn go
  "Launches the development system. Ensure it is initialized first."
  [& options]
  (let [options (or options default-options)]
    (when-not system-instance/system (apply init options)))
  (component/start system-instance/system)
  (set! *print-length* 20)
  :started)

(defn stop
  "Shuts down the development system and destroy all its state."
  []
  (when system-instance/system
    (component/stop system-instance/system)
    (alter-var-root #'system-instance/system (constantly nil)))
  :stopped)

(defn reset
  "Stops the currently-running system, reload any code that has changed,
  and restart the system."
  []
  (stop)
  (refresh :after 'user/go))

;;; Utility methods

(defn methods
  [^Class c]
  (->> c .getMethods (map str) (into [])))

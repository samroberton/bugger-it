(ns bugger-it.core
  (:import
   [com.sun.jdi Bootstrap ReferenceType VirtualMachineManager VirtualMachine]
   [com.sun.jdi.event
    AccessWatchpointEvent BreakpointEvent ClassPrepareEvent ClassUnloadEvent
    ExceptionEvent Event EventSet MethodEntryEvent MethodExitEvent
    ModificationWatchpointEvent MonitorContendedEnteredEvent
    MonitorContendedEnterEvent MonitorWaitedEvent MonitorWaitEvent StepEvent
    ThreadDeathEvent ThreadStartEvent VMDeathEvent VMDisconnectEvent
    VMStartEvent]
   [com.sun.jdi.request ExceptionRequest EventRequestManager EventRequest]
   [java.util.concurrent Executors ExecutorService]))


(defn- handle-event
  "Calls the handler function which was supplied when the EventRequest for this
  even was installed, supplying all the available event information as function
  arguments."
  [^Event event]
  (let [handler-fn (.getProperty (.request event) :handler)
        arg-accessors
        (condp instance? event
          AccessWatchpointEvent        [#(.thread ^AccessWatchpointEvent %)
                                        #(.field ^AccessWatchpointEvent %)
                                        #(.object ^AccessWatchpointEvent %)
                                        #(.valueCurrent ^AccessWatchpointEvent %)]
          BreakpointEvent              [#(.thread ^BreakpointEvent %)]
          ClassPrepareEvent            [#(.thread ^ClassPrepareEvent %)
                                        #(.referenceType ^ClassPrepareEvent %)]
          ClassUnloadEvent             [#(.className ^ClassUnloadEvent %)
                                        #(.classSignature ^ClassUnloadEvent %)]
          ExceptionEvent               [#(.thread ^ExceptionEvent %)
                                        #(.exception ^ExceptionEvent %)
                                        #(.catchLocation ^ExceptionEvent %)]
          MethodEntryEvent             [#(.thread ^MethodEntryEvent %)
                                        #(.method ^MethodEntryEvent %)]
          MethodExitEvent              [#(.thread ^MethodExitEvent %)
                                        #(.method ^MethodExitEvent %)
                                        #(.returnValue ^MethodExitEvent %)]
          ModificationWatchpointEvent  [#(.thread ^ModificationWatchpointEvent %)
                                        #(.field ^ModificationWatchpointEvent %)
                                        #(.object ^ModificationWatchpointEvent %)
                                        #(.valueCurrent ^ModificationWatchpointEvent %)
                                        #(.valueToBe ^ModificationWatchpointEvent %)]
          MonitorContendedEnteredEvent [#(.thread ^MonitorContendedEnteredEvent %)
                                        #(.monitor ^MonitorContendedEnteredEvent %)]
          MonitorContendedEnterEvent   [#(.thread ^MonitorContendedEnterEvent %)
                                        #(.monitor ^MonitorContendedEnterEvent %)]
          MonitorWaitedEvent           [#(.thread ^MonitorWaitedEvent %)
                                        #(.monitor ^MonitorWaitedEvent %)
                                        #(.timedout ^MonitorWaitedEvent %)]
          MonitorWaitEvent             [#(.thread ^MonitorWaitEvent %)
                                        #(.monitor ^MonitorWaitEvent %)
                                        #(.timeout ^MonitorWaitEvent %)]
          StepEvent                    [#(.thread ^StepEvent %)]
          ThreadDeathEvent             [#(.thread ^ThreadDeathEvent %)]
          ThreadStartEvent             [#(.thread ^ThreadStartEvent %)]
          VMDeathEvent                 []
          VMDisconnectEvent            []
          VMStartEvent                 [#(.thread ^VMStartEvent %)])
        args (map #(apply % [event]) arg-accessors)]
    (apply handler-fn args)))

(defn- handle-event-set
  "Calls handle-event for each event in the event-set. If the event-set
  indicates that the thread or VM is suspended, and all the handlers return
  non-false/non-nil, calls EventSet#resume()."
  [^EventSet event-set]
  (let [handler-results (doall (map handle-event event-set))
        suspended? (not= EventRequest/SUSPEND_NONE (.suspendPolicy event-set))]
    (when (and suspended? (every? identity handler-results))
      (.resume event-set))))

(defrecord Debuggee [^VirtualMachine vm connected monitor-thread])

(defn- ^Debuggee debug-vm
  "Creates a thread to poll for events sent by the given VM, and returns a
  'Debuggee' record to represent the VM."
  [^VirtualMachine vm name]
  (let [connected (atom true)
        executor (Executors/newSingleThreadExecutor)
        monitor-thread (Thread.
                        (fn []
                          (loop []
                            (when @connected
                              (let [event-set (-> vm .eventQueue .remove)
                                    first-event (-> event-set .eventIterator .next)]
                                (when (instance? VMDisconnectEvent first-event)
                                  (reset! connected false))
                                (.execute executor #(handle-event-set event-set)))
                              (recur))))
                        (str "Debug VM event thread " name))]
    (.start monitor-thread)
    (->Debuggee vm connected monitor-thread)))

(defn ^Debuggee connect-to-vm
  "Connects as a debugger to the (already running) VM at hostname:port."
  [hostname port]
  (let [connectors (.attachingConnectors (Bootstrap/virtualMachineManager))
        connector (first (filter #(= "com.sun.jdi.SocketAttach" (.name %)) connectors))
        args (.defaultArguments connector)]
    (.setValue (get args "hostname") hostname)
    (.setValue (get args "port") port)
    (debug-vm (.attach connector args) (str hostname ":" port))))

(defn- configure-event-request
  ":suspend may be :vm, :thread or nil."
  [^EventRequest request handler-fn
   {:keys [suspend enabled thread invoke-on-count]
    :or {suspend :thread, enabled true, thread nil, invoke-on-count nil}}]
  (doto request
    (.setSuspendPolicy (case suspend
                         :vm     EventRequest/SUSPEND_ALL
                         :thread EventRequest/SUSPEND_EVENT_THREAD
                         :none   EventRequest/SUSPEND_NONE))
    (#(when invoke-on-count
        (.addCountFilter % invoke-on-count)))
    (.putProperty :handler handler-fn)
    (#(when enabled
        (.enable %)))))

(defn- as-java-class-name
  "For example:
    (as-java-class-name 'clojure.main/eval-opt)
     -> \"clojure.main$eval_opt\"
    (as-java-class-name #'clojure.main/eval-opt)
     -> \"clojure.main$eval_opt\"
    (as-java-class-name clojure.lang.Keyword)
     -> \"clojure.lang.Keyword\""
  [arg]
  (cond (string? arg)
        arg
        (class? arg)
        (.getName arg)
        (var? arg)
        (str (namespace-munge (:ns (meta arg))) "$" (munge (:name (meta arg))))
        (instance? clojure.lang.Named arg)
        (str (namespace-munge (namespace arg)) "$" (munge (name arg)))))

(defn- as-coll
  [args]
  (cond (not args) nil
        (coll? args) args
        :else [args]))

(defn- get-locations
  "Returns a map from ClassLoader to the first Location of the given line number
   in the Java class for the given var (since the class may have been loaded by
   multiple ClassLoaders)."
  [debuggee class-name line-number]
  (into {} (filter second
                   (map #(vector (.classLoader %)
                                 (first (.locationsOfLine % line-number)))
                        (.classesByName (:vm debuggee) class-name)))))

(defn break-at-point
  "handler-fn will be invoked with [thread] when the breakpoint is hit."
  ([debuggee handler-fn location {:keys [thread] :or {thread nil} :as options}]
     (doto (-> (:vm debuggee)
               .eventRequestManager
               (.createBreakpointRequest location))
       (#(when thread
           (.addThreadFilter % thread)))
       (configure-event-request handler-fn options)))
  ([debuggee handler-fn class-name line-number options]
     (doall
      (for [[_ location] (get-locations debuggee class-name line-number)]
        (break-at-point debuggee handler-fn location options)))))

(defn break-on-exception
  "handler-fn will be invoked with [thread exception catch-location] when the
  breakpoint is hit.

  exclude-thrown-by-class is a regexp-like string which may start or end with *:
  see ExceptionRequest#addClassExclusionFilter(String). It may be a vector to
  specify multiple such strings.

  only-thrown-by-class may be a regexp-like string (same) or a ReferenceType. It
  may be a vector of them (intermixed, if desired).

  Returns a seq: if exception-class-name is specified, you'll get one breakpoint
  for each ClassLoader which has loaded the given class."
  [debuggee handler-fn
   {:keys [exception-class-name exclude-thrown-by-class only-thrown-by-class
           only-thrown-by-instance only-thrown-in-thread]
    :as options}]
  (doall
   (for [type (if exception-class-name
                (.classesByName (:vm debuggee) exception-class-name)
                [nil])]
     (doto (-> (:vm debuggee)
               .eventRequestManager
               (.createExceptionRequest type true true))
       (#(doseq [class (as-coll exclude-thrown-by-class)]
           (.addClassExclusionFilter ^ExceptionRequest % class)))
       (#(doseq [class (as-coll only-thrown-by-class)]
           (if (string? class)
             (.addClassFilter ^ExceptionRequest % ^String class)
             (.addClassFilter ^ExceptionRequest % ^ReferenceType class))))
       (#(when only-thrown-by-instance
           (.addInstanceFilter ^ExceptionRequest % only-thrown-by-instance)))
       (#(when only-thrown-in-thread
           (.addThreadFilter ^ExceptionRequest % only-thrown-in-thread)))
       (configure-event-request handler-fn options)))))

(defn break-after-step
  "handler-fn will be invoked with [thread] when the breakpoint is hit.

  step-size is :line or :min, step-depth is :into, :over or :out."
  [debuggee handler-fn thread {:keys [step-size step-depth]
                               :or {step-size :min, step-depth :over}
                               :as options}]
  (let [size  (case step-size
                :min  StepRequest/STEP_MIN
                :line StepRequest/STEP_LINE)
        depth (case step-depth
                :into StepRequest/STEP_INTO
                :over StepRequest/STEP_OVER
                :out  StepRequest/STEP_OUT)]
    (doto (-> (:vm debuggee)
              .eventRequestManager
              (.createStepRequest thread size depth))
      (configure-event-request handler-fn options))))

(defn break-on-method-enter
  "handler-fn will be invoked with [thread method] when the breakpoint is hit.

  Returns a single MethodEntryEventRequest."
  [debuggee handler-fn class-name
   {:keys [exclude-class only-class instance thread] :as options}]
  (doto (-> (:vm debuggee) .eventRequestManager .createMethodEntryRequest)
    (#(doseq [class (as-coll exclude-class)]
        (.addClassExclusionFilter % class)))
    (#(doseq [class (as-coll only-class)]
        (if (string? class)
          (.addClassFilter % ^String class)
          (.addClassFilter % ^ReferenceType class))))
    (#(when instance
        (.addInstanceFilter % instance)))
    (#(when thread
        (.addThreadFilter % thread)))
    (configure-event-request handler-fn options)))

(defn break-on-method-exit
  "handler-fn will be invoked with [thread method return-value] when the
  breakpoint is hit.

  Returns a single MethodExitEventRequest."
  [debuggee handler-fn class-name
   {:keys [exclude-class only-class instance thread] :as options}]
  (doto (-> (:vm debuggee) .eventRequestManager .createMethodEntryRequest)
    (#(doseq [class (as-coll exclude-class)]
        (.addClassExclusionFilter % class)))
    (#(doseq [class (as-coll only-class)]
        (if (string? class)
          (.addClassFilter % ^String class)
          (.addClassFilter % ^ReferenceType class))))
    (#(when instance
        (.addInstanceFilter % instance)))
    (#(when thread
        (.addThreadFilter % thread)))
    (configure-event-request handler-fn options)))

(defn- get-fields
  [debuggee class-name field-name]
  (for [class (.classesByName (:vm debuggee) class-name)]
    (.fieldByName class field-name)))

(defn break-on-read
  "handler-fn will be invoked with [thread field object current-value] when the
  breakpoint is hit."
  [debuggee handler-fn class-name field-name
   {:keys [exclude-access-by-class only-access-by-class only-access-by-instance
           thread] :as options}]
  (doall
   (for [[_ field] (get-fields debuggee class-name field-name)]
     (doto (-> (:vm debuggee)
               .eventRequestManager
               (.createAccessWatchpointRequest field))
       (#(doseq [class (as-coll exclude-access-by-class)]
        (.addClassExclusionFilter % class)))
    (#(doseq [class (as-coll only-access-by-class)]
        (if (string? class)
          (.addClassFilter % ^String class)
          (.addClassFilter % ^ReferenceType class))))
    (#(when only-access-by-instance
        (.addInstanceFilter % only-access-by-instance)))
    (#(when thread
        (.addThreadFilter % thread)))
    (configure-event-request handler-fn options)))))

(defn break-on-write
  "handler-fn will be invoked with [thread field object current-value new-value]
  when the breakpoint is hit."
  [debuggee handler-fn class-name field-name
   {:keys [exclude-writes-by-class only-writes-by-class only-writes-by-instance
           thread] :as options}]
  (doall
   (for [[_ field] (get-fields debuggee class-name field-name)]
     (doto (-> (:vm debuggee)
               .eventRequestManager
               (.createModificationWatchpointRequest field))
       (#(doseq [class (as-coll exclude-writes-by-class)]
           (.addClassExclusionFilter % class)))
       (#(doseq [class (as-coll only-writes-by-class)]
           (if (string? class)
             (.addClassFilter % ^String class)
             (.addClassFilter % ^ReferenceType class))))
       (#(when only-writes-by-instance
           (.addInstanceFilter % only-writes-by-instance)))
       (#(when thread
           (.addThreadFilter % thread)))
       (configure-event-request handler-fn options)))))

(defn break-on-class-prepare
  "handler-fn will be invoked with [thread reference-type] when the breakpoint
  is hit.

  only-source-file is a string regexp-like pattern which can start or end with
  *, and you can specify more than one (in a vector). See
  ClassPrepareRequest#addSourceNameFilter(String)."
  [debuggee handler-fn {:keys [exclude-class only-class only-source-file]
                        :as options}]
  (doto (-> (:vm debuggee) .eventRequestManager .createClassPrepareRequest)
    (#(doseq [class (as-coll exclude-class)]
        (.addClassExclusionFilter % class)))
    (#(doseq [class (as-coll only-class)]
        (if (string? class)
          (.addClassFilter % ^String class)
          (.addClassFilter % ^ReferenceType class))))
    (#(when only-source-file
        (.addSourceNameFilter % only-source-file)))
    (configure-event-request handler-fn options)))

(defn break-on-class-unload
  "handler-fn will be invoked with [thread class-signature] when the breakpoint
  is hit."
  [debuggee handler-fn {:keys [exclude-class only-class] :as options}]
  (doto (-> (:vm debuggee) .eventRequestManager .createClassUnloadRequest)
    (#(doseq [class (as-coll exclude-class)]
        (.addClassExclusionFilter % class)))
    (#(doseq [class (as-coll only-class)]
        (.addClassFilter % ^String class)))
    (configure-event-request handler-fn options)))

(defn break-on-monitor-contended
  "handler-fn will be invoked with [thread monitor] when the breakpoint is hit."
  [debuggee handler-fn {:keys [exclude-class only-class instance thread]
                        :as options}]
  (doto (-> (:vm debuggee)
            .eventRequestManager
            (.createMonitorContendedEnterRequest))
    (#(doseq [class (as-coll exclude-class)]
        (.addClassExclusionFilter % class)))
    (#(doseq [class (as-coll only-class)]
        (if (string? class)
          (.addClassFilter % ^String class)
          (.addClassFilter % ^ReferenceType class))))
    (#(when instance
        (.addInstanceFilter % instance)))
    (#(when thread
        (.addThreadFilter % thread)))
    (configure-event-request handler-fn options)))

(defn break-after-monitor-contended
  "handler-fn will be invoked with [thread monitor] when the breakpoint is hit."
  [debuggee handler-fn {:keys [exclude-class only-class instance thread]
                        :as options}]
  (doto (-> (:vm debuggee)
            .eventRequestManager
            (.createMonitorContendedEnteredRequest))
    (#(doseq [class (as-coll exclude-class)]
        (.addClassExclusionFilter % class)))
    (#(doseq [class (as-coll only-class)]
        (if (string? class)
          (.addClassFilter % ^String class)
          (.addClassFilter % ^ReferenceType class))))
    (#(when instance
        (.addInstanceFilter % instance)))
    (#(when thread
        (.addThreadFilter % thread)))
    (configure-event-request handler-fn options)))

(defn break-on-monitor-wait
  "handler-fn will be invoked with [thread monitor timeout] when the breakpoint
  is hit."
  [debuggee handler-fn {:keys [exclude-class only-class instance thread]
                        :as options}]
  (doto (-> (:vm debuggee)
            .eventRequestManager
            .createMonitorWaitRequest)
    (#(doseq [class (as-coll exclude-class)]
        (.addClassExclusionFilter % class)))
    (#(doseq [class (as-coll only-class)]
        (if (string? class)
          (.addClassFilter % ^String class)
          (.addClassFilter % ^ReferenceType class))))
    (#(when instance
        (.addInstanceFilter % instance)))
    (#(when thread
        (.addThreadFilter % thread)))
    (configure-event-request handler-fn options)))

(defn break-after-monitor-wait
  "handler-fn will be invoked with [thread monitor timedout] when the breakpoint
  is hit.

  :exclude-class and :only-class refer to the class of the monitor object.
  :instance refers (as elsewhere) to the current 'this' in the executing
  thread."
  [debuggee handler-fn {:keys [exclude-class only-class instance thread]
                        :as options}]
  (doto (-> (:vm debuggee)
            .eventRequestManager
            .createMonitorWaitedRequest)
    (#(doseq [class (as-coll exclude-class)]
        (.addClassExclusionFilter % class)))
    (#(doseq [class (as-coll only-class)]
        (if (string? class)
          (.addClassFilter % ^String class)
          (.addClassFilter % ^ReferenceType class))))
    (#(when instance
        (.addInstanceFilter % instance)))
    (#(when thread
        (.addThreadFilter % thread)))
    (configure-event-request handler-fn options)))

(defn break-on-thread-start
  "handler-fn will be invoked with [thread] when the breakpoint is hit."
  [debuggee handler-fn {:keys [thread] :as options}]
  (doto (-> (:vm debuggee) .eventRequestManager .createThreadStartRequest)
    (#(when thread
        (.addThreadFilter % thread)))
    (configure-event-request handler-fn options)))

(defn break-on-thread-death
  "handler-fn will be invoked with [thread] when the breakpoint is hit."
  [debuggee handler-fn {:keys [thread] :as options}]
  (doto (-> (:vm debuggee) .eventRequestManager .createThreadDeathRequest)
    (#(when thread
        (.addThreadFilter % thread)))
    (configure-event-request handler-fn options)))

(defn break-on-vm-death
  "handler-fn will be invoked with no args when the breakpoint is hit. The VM
  will still be alive at this point -- if requested, it will be suspended."
  [debuggee handler-fn options]
  (doto (-> (:vm debuggee) .eventRequestManager .createVMDeathRequest)
    (configure-event-request handler-fn options)))


(defn delete-all-breakpoints
  [debuggee]
  (-> (:vm debuggee) .eventRequestManager .deleteAllBreakpoints))

(defn delete-all-exception-breakpoints
  [debuggee]
  (let [erm (.eventRequestManager (:vm debuggee))]
    (.deleteEventRequests erm (.exceptionRequests erm))))

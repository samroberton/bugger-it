(ns bugger-it.repl
  "Utility functions for debugging with the REPL."
  (:require
   [bugger-it.core :as bugger]
   [bugger-it.inspect :as inspect])
  (:import
   [com.sun.jdi LocalVariable ObjectReference]))


(defn arguments
  "Gets (with inspect/remote-value) the arguments in the identified frame (by
   default, frame zero) as a seq of variable values. Only works when thread is
   suspended."
  ([thread]
     (arguments thread 0))
  ([thread frame-number]
     (map inspect/remote-value (.getArgumentValues (.frame thread frame-number)))))

(defn visible-variables
  "Gets (with inspect/remote-value) the visible variables in the identified
   frame (by default, frame zero) as a map of variable name to value. Only works
   when thread is suspended."
  ([thread]
     (visible-variables thread 0))
  ([thread frame-number]
     (let [frame (.frame thread frame-number)]
       (map (fn [^LocalVariable var]
              [(.name var) (inspect/remote-value (.getValue frame var))])
            (.visibleVariables frame)))))

(defn suspend-and-save-at-point
  "Installs a breakpoint which suspends the thread when hit and leaves it
   suspended. Returns an atom holding a vector; when the breakpoint is hit, the
   thread which hits it will be conj'd into the vector."
  [debuggee class-name line-number]
  (let [threads (atom [])
        handler-fn (fn [thread]
                     (swap! threads conj thread)
                     (println "Breakpoint at" (str class-name ":" line-number)
                              "in thread" (str "T" (.uniqueID thread))))]
    (bugger/break-at-point debuggee handler-fn class-name line-number
                           {:suspend :thread})
    threads))

(defn suspend-and-save-on-exception
  "Installs a breakpoint which suspends the thread whenever there is an
   exception, caught or uncaught. Returns an atom holding a vector; when an
   exception is raised, a map of with keys :thread, :exception and
   :catch-location will be conj'd into the vector."
  [debuggee]
  (let [events (atom [])
        handler-fn (fn [t e cl]
                     (swap! events conj
                            {:thread t :exception e :catch-location cl})
                     (println (.getName (class e))  "in thread"
                              (str "T" (.uniqueID t))))]
    (bugger/break-on-exception debuggee handler-fn {:suspend :thread})
    events))

(defn trace-fn
  [debuggee f]
  (let [c (bugger/as-java-class-name f)
        depth (atom 0)
        entry-handler (fn [thread method]
                        (println
                         (str "T" (.uniqueID thread) " -> "
                              (apply str (repeat (- (swap! depth inc) 1) "  "))
                              (-> method .location .declaringType .name)
                              "#" (.name method)
                              (pr-str (arguments thread))))
                        :resume)
        exit-handler  (fn [thread method return-val]
                        (println
                         (str "T" (.uniqueID thread) " <- "
                              (apply str (repeat (swap! depth dec) "  "))
                              (-> method .location .declaringType .name)
                              "#" (.name method) "(..): "
                              (inspect/remote-value return-val)))
                        :resume)]
    [(bugger/break-on-method-enter debuggee entry-handler {:only-class c})
     (bugger/break-on-method-exit debuggee exit-handler {:only-class c})]))


;; annoying: invoking a method resumes the thread, which makes stack frames invalid
#_(defn- invoke-remote-method
  [thread object-ref method-name & args]
  (let [method (first (.methodsByName (.referenceType object-ref) method-name))]
    (.invokeMethod object-ref thread method (vec args) ObjectReference/INVOKE_SINGLE_THREADED)))

#_(def d (bugger/connect-to-vm "localhost" 48220))
#_(def b (suspend-and-save-at-point d 'bugger-it-examples.core/inspect-me 10))
#_(visible-variables (first @b))

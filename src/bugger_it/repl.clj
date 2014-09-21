(ns bugger-it.repl
  "Utility functions for debugging with the REPL."
  (:require
   [bugger-it.core :as bugger]
   [bugger-it.inspect :as inspect])
  (:import
   [com.sun.jdi LocalVariable ObjectReference]))


(defn suspend-and-save-at-point
  [debuggee class-name line-number]
  (let [threads (atom [])
        handler-fn (fn [thread]
                     (swap! threads conj thread)
                     (println "Breakpoint hit in thread " thread "; "
                              (count @threads) " threads currently suspended "
                              "on this breakpoint"))]
    (bugger/break-at-point debuggee handler-fn class-name line-number {:suspend :thread})
    threads))

(defn arguments
  ([thread]
     (arguments thread 0))
  ([thread frame-number]
     (map inspect/remote-value (.getArgumentValues (.frame thread frame-number)))))

(defn visible-variables
  ([thread]
     (visible-variables thread 0))
  ([thread frame-number]
     (let [frame (.frame thread frame-number)]
       (map (fn [^LocalVariable var]
              [(.name var) (inspect/remote-value (.getValue frame var))])
            (.visibleVariables frame)))))


;; annoying: invoking a method resumes the thread, which makes stack frames invalid
#_(defn- invoke-remote-method
  [thread object-ref method-name & args]
  (let [method (first (.methodsByName (.referenceType object-ref) method-name))]
    (.invokeMethod object-ref thread method (vec args) ObjectReference/INVOKE_SINGLE_THREADED)))

#_(def d (bugger/connect-to-vm "localhost" 48220))
#_(def b (suspend-and-save-at-point d 'bugger-it-examples.core/inspect-me 10))
#_(visible-variables (first @b))

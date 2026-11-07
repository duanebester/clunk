(ns clunk.log
  "clunk's logging, with nothing to install. On the JVM it goes to the
   System.Logger named \"clunk\", which java.util.logging handles unless a
   bridge such as slf4j-jdk-platform-logging routes it elsewhere. On Node,
   debug messages go to util.debuglog, so NODE_DEBUG=clunk shows them, and
   errors to console.error."
  #?(:clj  (:import (java.lang System$Logger System$Logger$Level))
     :cljs (:require ["util" :as util])))

#?(:clj  (def ^:private ^System$Logger logger (System/getLogger "clunk"))
   :cljs (def ^:private debuglog (util/debuglog "clunk")))

(defn debug
  "Logs msg at debug level, followed by x unless it's nil: an exception's
   stack trace, or anything else as pr-str prints it."
  ([msg] (debug msg nil))
  ([msg x]
   #?(:clj  (when (.isLoggable logger System$Logger$Level/DEBUG)
              (if (instance? Throwable x)
                (.log logger System$Logger$Level/DEBUG ^String msg ^Throwable x)
                (.log logger System$Logger$Level/DEBUG ^String (cond-> msg (some? x) (str " " (pr-str x))))))
      :cljs (when (.-enabled debuglog)
              (if (some? x) (debuglog msg (if (instance? js/Error x) x (pr-str x))) (debuglog msg))))))

(defn error
  "Logs msg and the exception e at error level."
  [msg e]
  #?(:clj  (.log logger System$Logger$Level/ERROR ^String msg ^Throwable e)
     :cljs (js/console.error (str "clunk: " msg) e)))

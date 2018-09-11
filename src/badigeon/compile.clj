(ns badigeon.compile
  (:refer-clojure :exclude [compile])
  (:require
   [clojure.string :as str]
   [badigeon.io.alpha :as io]
   [badigeon.classpath :as classpath]
   )
  (:import
   java.io.File
   java.net.URI
   java.net.URL
   java.net.URLClassLoader
   java.nio.file.Files
   java.nio.file.Path
   java.nio.file.Paths
   java.nio.file.attribute.FileAttribute
   ))


(set! *warn-on-reflection* true)


(defn- do-compile
  [namespaces compile-path compiler-options]
  (let [^Path compile-path (io/path compile-path)]
    (io/create-directories compile-path)
    (binding [*compile-path*     (str compile-path)
              *compiler-options* (or compiler-options *compiler-options*)]
      (doseq [namespace namespaces]
        (clojure.core/compile namespace)))))


(defn classpath->paths
  [classpath]
  (map
    io/path
    (-> classpath
      (str/trim)
      (str/split (re-pattern File/pathSeparator)))))


(defn paths->urls
  [paths]
  (->> paths
    (map #(.toUri ^Path %))
    (map #(.toURL ^URI %))))


(defn -main
  [namespaces compile-path compiler-options]
  (let [namespaces       (read-string namespaces)
        compiler-options (read-string compiler-options)]
    (do-compile namespaces compile-path compiler-options)
    (clojure.core/shutdown-agents)))


(defn compile
  "AOT compile one or several Clojure namespace(s). Dependencies of the compiled namespaces are
  always AOT compiled too. Namespaces are loaded while beeing compiled so beware of side effects.
  - namespaces: A symbol or a collection of symbols naming one or several Clojure namespaces.
  - compile-path: The path to the directory where .class files are emitted. Default to \"target/classes\".
  - compiler-options: A map with the same format than clojure.core/*compiler-options*."
  ([namespaces]
   (compile namespaces nil nil nil))
  ([namespaces compile-path classpath compiler-options]
   (let [compile-path   (or compile-path "target/classes")
         compile-path   (io/path compile-path)
         ;; We must ensure early that the compile-path exists otherwise the Clojure Compiler has issues compiling classes / loading classes. I'm not sure why exactly
         _              (io/create-directories compile-path)
         classpath      (or classpath (classpath/get-classpath))
         classpath-urls (->> classpath classpath->paths paths->urls (into-array URL))
         classloader    (URLClassLoader. classpath-urls (.getParent (ClassLoader/getSystemClassLoader)))
         main-class     (.loadClass classloader "clojure.main")
         main-method    (.getMethod main-class "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
         t              (Thread.
                          (fn []
                            (.setContextClassLoader (Thread/currentThread) classloader)
                            (.invoke
                              main-method
                              nil
                              (into-array
                                Object
                                [(into-array String
                                   ["--main"
                                    "badigeon.compile"
                                    (pr-str namespaces)
                                    (str compile-path)
                                    (pr-str compiler-options)])]))))]
     (.start t)
     (.join t)
     (.close classloader))))


(set! *warn-on-reflection* false)


(comment


  (compile '[badigeon.main] {:compile-path "target/classes"
                             :compiler-options {:elide-meta [:doc :file :line :added]}})

  )


;; Cleaning non project classes: https://dev.clojure.org/jira/browse/CLJ-322

;; Cleaning non project classes is not supported by badigeon because:
;; Most of the time, libraries should be shipped without AOT. In the rare case when a library must be shipped AOT (let's say we don't want to ship the sources), directories can be removed programmatically, between build tasks. Shipping an application with AOT is a more common use case. In this case, AOT compiling dependencies is not an issue.

;; Compiling is done in a separate classloader because
;; - clojure.core/compile recursively compiles a namespace and its dependencies, unless the dependencies are already loaded. :reload-all does not help. Removing the AOT compiled files and recompiling results in a strange result: Source files are not reloaded, no .class file is produced. Using a separate classloader simulates a :reload-all for compile.

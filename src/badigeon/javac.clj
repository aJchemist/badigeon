(ns badigeon.javac
  (:require
   [clojure.java.io :as jio]
   [badigeon.io.alpha :as io]
   [badigeon.classpath :as classpath]
   )
  (:import
   java.io.ByteArrayOutputStream
   java.nio.file.FileSystemLoopException
   java.nio.file.FileVisitOption
   java.nio.file.FileVisitResult
   java.nio.file.FileVisitor
   java.nio.file.Files
   java.nio.file.NoSuchFileException
   java.nio.file.Path
   java.nio.file.Paths
   java.nio.file.attribute.BasicFileAttributes
   java.nio.file.attribute.FileAttribute
   java.util.EnumSet
   javax.tools.JavaCompiler
   javax.tools.ToolProvider
   ))


(set! *warn-on-reflection* true)


(defn- make-file-visitor
  [compiler source-dir compile-dir visitor-fn]
  (reify FileVisitor
    (postVisitDirectory [_ dir exception]
      FileVisitResult/CONTINUE)
    (preVisitDirectory [_ dir attrs]
      FileVisitResult/CONTINUE)
    (visitFile [_ path attrs]
      (visitor-fn compiler source-dir compile-dir path attrs)
      FileVisitResult/CONTINUE)
    (visitFileFailed [_ file exception]
      (case (.getName ^Class exception)
        "java.nio.file.FileSystemLoopException" FileVisitResult/SKIP_SUBTREE
        "java.nio.file.NoSuchFileException"     FileVisitResult/SKIP_SUBTREE
        (throw exception)))))


(defn is-java-file?
  [path ^BasicFileAttributes attrs]
  (and (.isRegularFile attrs) (.endsWith (str path) ".java")))


(def ^{:dynamic true
       :private true}
  *java-paths* nil)


(defn- visit-path [compiler source-dir compile-dir path attrs]
  (when (is-java-file? path attrs)
    (set! *java-paths* (conj! *java-paths* path))))


(defn- javac-command
  [classpath compile-path paths opts]
  (into `["-cp" ~classpath ~@opts "-d" ~(str compile-path)]
    (map str paths)))


(defn success? [ret] (zero? ret))


(defn- javac*
  "Return an integer, 0 for success; nonzero otherwise."
  [^JavaCompiler compiler java-source-paths compile-dir classpath javac-options]
  (let [compile-dir (io/path compile-dir)]
    (io/create-directories compile-dir)
    (binding [*java-paths* (transient [])]
      (doseq [source-dir java-source-paths
              :let [source-dir (io/path source-dir)]]
        (Files/walkFileTree
          source-dir
          (EnumSet/of FileVisitOption/FOLLOW_LINKS)
          Integer/MAX_VALUE
          (make-file-visitor compiler source-dir compile-dir visit-path)))
      (let [java-paths (persistent! *java-paths*)]
        (when (seq java-paths)
          (let [javac-command (javac-command classpath compile-dir java-paths javac-options)]
            (.run compiler nil (System/out) (System/err) (into-array String javac-command))))))))


(defn javac
  "Compiles java source files found in the \"source-dir\" directory.
  - java-source-paths: The paths of a directory containing java source files.
  - compile-path: The path to the directory where .class file are emitted.
  - classpath: The concatenated string of classpath to be passed to javac \"-cp\" argument.
  - javac-options: A vector of the options to be used when invoking the javac command."
  ([java-source-paths]
   (javac java-source-paths nil nil nil))
  ([java-source-paths compile-path classpath javac-options]
   (let [compiler     (ToolProvider/getSystemJavaCompiler)
         compile-path (or compile-path "target/classes")
         classpath    (or classpath (classpath/get-classpath))]
     (when (nil? compiler)
       (throw (ex-info "Java compiler not found" {})))
     (javac* compiler java-source-paths compile-path classpath javac-options))))


(set! *warn-on-reflection* false)


(comment
  (javac
    ["src-java"]
    "target/classes"
    nil
    ["-target" "1.6" "-source" "1.6" "-Xlint:-options"])
  )

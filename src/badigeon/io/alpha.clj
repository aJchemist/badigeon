(ns badigeon.io.alpha
  (:require
   [clojure.java.io :as jio]
   [clojure.stacktrace :as stacktrace]
   )
  (:import
   java.net.URI
   java.io.File
   java.nio.file.FileSystem
   java.nio.file.FileSystems
   java.nio.file.FileVisitOption
   java.nio.file.FileVisitResult
   java.nio.file.FileVisitor
   java.nio.file.Files
   java.nio.file.LinkOption
   java.nio.file.Path
   java.nio.file.Paths
   java.nio.file.SimpleFileVisitor
   java.nio.file.StandardCopyOption
   java.nio.file.StandardOpenOption
   java.nio.file.attribute.FileAttribute
   java.nio.file.attribute.FileTime
   java.nio.file.attribute.PosixFilePermission
   java.nio.file.attribute.PosixFilePermissions
   ))


(set! *warn-on-reflection* true)


(defprotocol IPath
  (^Path path [x]))


(def ^:dynamic ^"[Ljava.nio.file.LinkOption;" *no-follow* (LinkOption/values))


(def ^"[Ljava.nio.file.LinkOption;" default-link-options (make-array LinkOption 0))


(defn exists? [^Path path] (Files/exists path *no-follow*))


(defn executable? [^Path path] (Files/isExecutable path))


(defn readable? [^Path path] (Files/isReadable path))


(defn writable? [^Path path] (Files/isWritable path))


(defn file?
  [^Path path]
  (Files/isRegularFile path *no-follow*))


(defn directory?
  [^Path path]
  (Files/isDirectory path *no-follow*))


(defn relativize-path
  [^Path root-path ^Path path]
  (if (.equals root-path path)
    (if-let [parent-path (.getParent path)]
      (.. parent-path (relativize path) (normalize))
      path)
    (.. root-path (relativize path) (normalize))))


(defn create-directories
  [^Path path]
  (Files/createDirectories path (make-array FileAttribute 0)))


(defn mkparents
  [^Path path]
  (when-let [parent (.getParent path)]
    (Files/createDirectories parent (make-array FileAttribute 0))))


(defn same-directory?
  [^Path path1 ^Path path2]
  (let [normalized-path1 (.. path1 toAbsolutePath normalize)
        normalized-path2 (.. path2 toAbsolutePath normalize)]
    (.equals normalized-path2 normalized-path1)))


(defn is-parent-path?
  [^Path path1 ^Path path2]
  (let [normalized-path1 (.. path1 toAbsolutePath normalize)
        normalized-path2 (.. path2 toAbsolutePath normalize)]
    (and
      (not (.equals normalized-path2 normalized-path1))
      (.startsWith normalized-path2 normalized-path1))))


(def ^"[Ljava.nio.file.StandardOpenOption;" open-opts
  (into-array StandardOpenOption [StandardOpenOption/CREATE]))


(def ^"[Ljava.nio.file.StandardCopyOption;" copy-opts
  (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))


;; * file operation


(defn copy!
  ([src dest path]
   (copy! src dest path nil))
  ([^Path src ^Path dest ^String path {:keys [^FileTime time ^java.util.Set mode]}]
   (let [^Path target (doto (.resolve dest path) (mkparents))]
     (Files/copy src target copy-opts)
     (when time (Files/setLastModifiedTime target time))
     (when mode (Files/setPosixFilePermissions target mode)))))


(defn write!
  [^Path dest ^String path writer-fn]
  (let [^Path target (doto (.resolve dest path) (mkparents))]
    (with-open [os (Files/newOutputStream target open-opts)]
      (writer-fn os))))


(defn do-operations
  [^Path dest operations]
  (doseq [op operations]
    (try
      (case (:op op)
        :copy  (copy! (path (:src op)) dest (:path op) (select-keys op [:time :mode]))
        :write (write! dest (:path op) (:writer-fn op))
        (throw (UnsupportedOperationException. (pr-str op))))
      (catch Throwable e
        (throw (ex-info "Operation failed:" {:operation op :exception e}))))))


;; * extend


(extend-type String
  IPath
  (path [x] (Paths/get x (make-array String 0))))


(extend-type URI
  IPath
  (path [x] (Paths/get x)))


(extend-type File
  IPath
  (path [x] (.toPath x)))


(extend-type Path
  IPath
  (path [x] x))


(extend-type FileSystem
  IPath
  (path [x] (first (.getRootDirectories x))))


(set! *warn-on-reflection* false)


(comment
  (PosixFilePermissions/fromString "r--r-----")
  )

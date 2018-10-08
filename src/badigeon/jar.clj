(ns badigeon.jar
  (:require
   [clojure.java.io :as jio]
   [clojure.string :as str]
   [user.java.io.alpha :as io]
   )
  (:import
   java.io.ByteArrayOutputStream
   java.io.OutputStream
   java.net.URI
   java.nio.file.FileSystem
   java.nio.file.FileSystems
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
   java.util.HashMap
   java.util.Map
   java.util.Properties
   java.util.jar.Attributes
   java.util.jar.Attributes$Name
   java.util.jar.JarEntry
   java.util.jar.JarOutputStream
   java.util.jar.Manifest
   ))


(set! *warn-on-reflection* true)


;; * jarfs utils


(defn ^URI mkjaruri
  [^Path jarpath]
  (URI/create (str "jar:" (.. jarpath toAbsolutePath normalize toUri))))


(defn ^FileSystem mkjarfs
  ([^Path jarpath]
   (mkjarfs jarpath nil))
  ([^Path jarpath {:keys [create encoding]}]
   (let [jaruri (mkjaruri jarpath)
         env    (HashMap.)]
     (when create
       ;; (jio/make-parents jarpath)
       (io/mkparents jarpath)
       (.put env "create" (str (boolean create))))
     (when encoding (.put env "encoding" (str encoding)))
     (FileSystems/newFileSystem jaruri env))))


(defn ^FileSystem getjarfs
  [^Path jarpath]
  (let [jaruri (mkjaruri jarpath)]
    (if (io/file? jarpath)
      (try
        (FileSystems/getFileSystem jaruri)
        (catch java.nio.file.FileSystemNotFoundException _
          (mkjarfs jarpath)))
      (mkjarfs jarpath {:create true}))))


;; * manifest


(def ^:private default-manifest
  {"Built-By"   (System/getProperty "user.name")
   "Build-Jdk"  (System/getProperty "java.version")
   "Created-By" "Badigeon"})


(defn- put-attributes
  [^Attributes attributes ^java.util.Map kvs]
  (doseq [[k v] kvs]
    (.put attributes (Attributes$Name. (name k)) (str v))))


(defn ^Manifest create-manifest
  [main ext-attrs]
  (let [manifest   (Manifest.)
        attributes (.getMainAttributes manifest)]
    (.put attributes Attributes$Name/MANIFEST_VERSION "1.0")
    (when-let [main (and main (munge (str main)))]
      (.put attributes Attributes$Name/MAIN_CLASS main))
    (let [{main-attrs false sections true} (group-by (fn [e] (coll? (val e))) (merge default-manifest ext-attrs))]
      (put-attributes attributes main-attrs)
      (let [^Map entries (. manifest getEntries)]
        (doseq [[n kvs] sections
                :let    [attributes (Attributes.)]]
          (put-attributes attributes kvs)
          (.put entries (name n) attributes))))
    manifest))


(defn get-manifest-txt
  [^Manifest manifest]
  (with-open [os (ByteArrayOutputStream.)]
    (. manifest write os)
    (. os toString)))


;; * file visitor


(defn- make-file-visitor
  [visitor-fn]
  (reify FileVisitor
    (postVisitDirectory [_ dir exception] FileVisitResult/CONTINUE)
    (preVisitDirectory [_ dir attrs] FileVisitResult/CONTINUE)
    (visitFile [_ path attrs]
      (visitor-fn path attrs)
      FileVisitResult/CONTINUE)
    (visitFileFailed [_ file exception]
      (case (.getName ^Class exception)
        "java.nio.file.FileSystemLoopException" FileVisitResult/SKIP_SUBTREE
        "java.nio.file.NoSuchFileException"     FileVisitResult/SKIP_SUBTREE
        (throw exception)))))


;; * file operations


(defn get-paths-copy-operations
  [paths]
  (let [operations (transient [])]
    (doseq [start-path paths :let [start-path (io/path start-path)]]
      (Files/walkFileTree
        start-path
        (EnumSet/of FileVisitOption/FOLLOW_LINKS)
        Integer/MAX_VALUE
        (make-file-visitor
          (fn [^Path path ^BasicFileAttributes attrs]
            (conj! operations
              {:op   :copy
               :src  path
               :path (str (.relativize start-path path))
               :time (. ^BasicFileAttributes attrs lastModifiedTime)})))))
    (persistent! operations)))


(set! *warn-on-reflection* false)


(comment
  (defn inclusion-path [group-id artifact-id root-files path]
    (license-path group-id artifact-id root-files path))

  (defn exclusion-predicate [root-path path]
    (prn root-path path)
    true)

  (badigeon.clean/clean "target")
  (jar 'badigeong/badigeong
       {:mvn/version utils/version
        :classifier "cl"}
       {:manifest {"Built-By" "ewen2"
                   "Project-awesome-level" "super-great"
                   :my-section-1 [["MyKey1" "MyValue1"] ["MyKey2" "MyValue2"]]
                   :my-section-2 {"MyKey3" "MyValue3" "MyKey4" "MyValue4"}}
        #_:inclusion-path #_(partial inclusion-path "badigeongi2" "badigeonn3")
        #_:exclusion-predicate #_exclusion-predicate
        :paths ["src" "src-java"]
        #_:deps #_'{org.clojure/clojure {:mvn/version "1.9.0"}}
        :mvn/repos '{"clojars" {:url "https://repo.clojars.org/"}}
        :allow-all-dependencies? true})

  (jar 'badigeon/badigeon
       {:mvn/version utils/version}
       {:paths ["target/classes"]
        :mvn/repos '{"clojars" {:url "https://repo.clojars.org/"}}
        :allow-all-dependencies? true
        :main 'badigeon.main})

  )


;; AOT compilation, no sources in jar -> possibility to set a custom path (target/classes)

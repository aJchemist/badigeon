(ns badigeon.jar.maven.alpha
  (:require
   [clojure.java.io :as jio]
   [clojure.tools.deps.alpha.reader :as deps.reader]
   [user.java.io.alpha :as io]
   [badigeon.maven.alpha :as maven]
   [badigeon.jar :as jar]
   )
  (:import
   java.io.OutputStream
   java.nio.file.Path
   ))


(set! *warn-on-reflection* true)


(defn- path-resolve
  [^Path a b]
  (. a resolve (str b)))


(defn do-operations
  [^Path dest operations]
  (doseq [op operations]
    (try
      (case (:op op)
        :copy  (io/copy! (io/path (:src op)) (doto (path-resolve dest (:path op)) (io/mkparents)) (select-keys op [:time :mode]))
        :write (io/write! (doto (path-resolve dest (:path op)) (io/mkparents)) (:writer-fn op))
        (throw (UnsupportedOperationException. (pr-str op))))
      (catch Throwable e
        (throw (ex-info "Operation failed:" {:operation op :exception e}))))))


(defn ^String make-jarname
  [artifact-id {:keys [:mvn/version classifier extension]}]
  (let [classifier (when classifier (str "-" (name classifier)))
        version    (when version (str "-" version))
        extension  (or extension ".jar")]
    (str artifact-id version classifier extension)))


(defn ^Path make-jarpath
  [artifact-id maven-coords target-path]
  (let [target-path (io/path (or target-path "target"))]
    (.resolve target-path (make-jarname artifact-id maven-coords))))


(defn get-jar-filesystem
  [artifact-id maven-coords target-path]
  (jar/getjarfs (make-jarpath artifact-id maven-coords target-path)))


(defn pom-xml-operation
  [group-id artifact-id]
  (let [pom-path (io/path "pom.xml")]
    (when (io/file? pom-path)
      {:op :copy :src (str pom-path) :path (str "META-INF/maven/" group-id "/" artifact-id "/pom.xml")})))


(defn pom-properties-operation
  [pom-properties group-id artifact-id]
  (when pom-properties
    {:op        :write
     :path      (str "META-INF/maven/" group-id "/" artifact-id "/pom.properties")
     :writer-fn (fn [os] (maven/store-pom-properties os pom-properties nil))}))


(defn deps-edn-operation
  [group-id artifact-id]
  (let [edn-path (io/path "deps.edn")]
    (when (io/file? edn-path)
      {:op :copy :src (str edn-path) :path (str "META-INF/badigeon/" group-id "/" artifact-id "/deps.edn")})))


(defn check-non-maven-dependencies
  [{:keys [deps]}]
  (doseq [[lib {:keys [:mvn/version] :as dep}] deps]
    (when (nil? version)
      (throw
        (ex-info
          "All dependencies must be Maven-based. Use the \"allow-all-dependencies?\" option to continue building the jar anyway. When using the \"allow-all-dependencies?\" option, only Maven-based depedencies are added to the pom.xml file."
          {:lib lib
           :dep dep})))))


;; * exclusions


(defn dotfiles-pred
  [{:keys [path]}]
  (let [path (io/path path)]
    (.startsWith path ".")))


(defn emacs-backups-pred
  [{:keys [path]}]
  (let [path (io/path path)]
    (or (.endsWith path "~") (.startsWith path "#"))))


(defn default-exclusion-predicate
  [op]
  (or (dotfiles-pred op)
      (emacs-backups-pred op)))


;; * jar


(defn jar
  "Bundles project resources into a jar file. This function also generates maven description files. By default, this function ensures that all the project dependencies are maven based.
  - lib: A symbol naming the library.
  - maven-coords: A map with the same format than tools.deps maven coordinates.
  - out-path: The path of the produced jar file. When not provided, a default out-path is generated from the lib and maven coordinates.
  - main: A namespace to be added to the \"Main\" entry to the jar manifest. Default to nil.
  - manifest: A map of additionel entries to the jar manifest. Values of the manifest map can be maps to represent manifest sections. By default, the jar manifest contains the \"Created-by\", \"Built-By\" and \"Build-Jdk\" entries.
  - deps: The dependencies of the project. deps have the same format than the :deps entry of a tools.deps map. Dependencies are copied to the pom.xml file produced while generating the jar file. Default to the deps.edn dependencies of the project (excluding the system-level and user-level deps.edn dependencies).
  - mvn/repos: Repositories to be copied to the pom.xml file produced while generating the jar. Must have same format than the :mvn/repos entry of deps.edn. Default to nil.
  - exclusion-predicate: A predicate to exclude operations that would otherwise been operated to the jar. The predicate takes a parameter: file-operation. Default to a predicate that excludes dotfiles and emacs backup files.
  - allow-all-dependencies?: A boolean that can be set to true to allow any types of dependency, such as local or git dependencies. Default to false, in which case only maven dependencies are allowed - an exception is thrown when this is not the case. When set to true, the jar is produced even in the presence of non-maven dependencies, but only maven dependencies are added to the jar."
  ([lib maven-coords]
   (jar lib maven-coords nil nil))
  ([lib maven-coords
    paths
    {:keys [out-path
            target-path
            compile-path
            main
            manifest
            pom-properties
            extra-operations
            exclusion-predicate
            allow-all-dependencies?]
     :or   {exclusion-predicate default-exclusion-predicate}
     :as   options}]
   (let [artifact-id    (name lib)
         group-id       (or (namespace lib) artifact-id)
         out-path       (io/path (or out-path (make-jarpath artifact-id maven-coords target-path)))
         _              (when-not (.endsWith (str out-path) ".jar")
                          (throw
                            (ex-info "out-path must be a jar file"
                              {:out-path out-path})))
         jarfs          (jar/getjarfs out-path)
         the-manifest   (jar/create-manifest main manifest)
         pom-properties (or pom-properties (maven/make-pom-properties lib maven-coords))
         deps-map       (deps.reader/read-deps (:config-files (deps.reader/clojure-env)))
         paths          (or paths (:paths deps-map))]
     (when-not allow-all-dependencies?
       (check-non-maven-dependencies deps-map))
     (let [dest (io/path jarfs)]
       (do-operations
         dest
         (transduce
           (filter sequential?)
           (fn
             ([] [])
             ([res] res)
             ([res ops]
              (into res
                (comp
                  (filter map?)
                  (remove
                    (fn [op] (exclusion-predicate op))))
                ops)))
           [[{:op        :write
              :path      "META-INF/MANIFEST.MF"
              :writer-fn (fn [^OutputStream os] (. the-manifest write os))}
             (pom-xml-operation group-id artifact-id)
             (pom-properties-operation pom-properties group-id artifact-id)
             (deps-edn-operation group-id artifact-id)]
            (when compile-path (jar/get-paths-copy-operations [compile-path]))
            (when-not (empty? paths) (jar/get-paths-copy-operations paths))
            extra-operations])))
     (.close jarfs)
     (str out-path))))


(set! *warn-on-reflection* false)


(comment
  (jar 'badigeon {:mvn/version "0.1.0"} nil {:compile-path "target/classes"})
  )

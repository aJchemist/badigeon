(ns badigeon.maven.alpha
  (:require
   [clojure.java.io :as jio]
   [clojure.tools.deps.alpha.gen.pom :as gen.pom]
   [clojure.tools.deps.alpha.util.io :refer [printerrln]]
   )
  (:import
   clojure.data.xml.node.Element
   java.io.ByteArrayOutputStream
   java.io.File
   java.io.OutputStream
   java.io.Reader
   java.util.Properties
   org.apache.maven.artifact.repository.metadata.Metadata
   org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader
   org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer
   org.apache.maven.model.Build
   org.apache.maven.model.Dependency
   org.apache.maven.model.Exclusion
   org.apache.maven.model.License
   org.apache.maven.model.Model
   org.apache.maven.model.Repository
   org.apache.maven.model.Scm
   org.apache.maven.model.io.xpp3.MavenXpp3Reader
   org.apache.maven.model.io.xpp3.MavenXpp3Writer
   ))


(set! *warn-on-reflection* true)


;; * model


;; ** model components


(defn- ^Build model-build
  [[path & paths]]
  (doto (Build.)
    (.setSourceDirectory path)))


(defn- ^Dependency model-dependency
  [[lib {:keys [mvn/version classifier exclusions] :as coord}]]
  (doto (Dependency.)
    (.setGroupId (or (namespace lib) (name lib)))
    (.setArtifactId (name lib))
    (.setVersion version)
    (.setClassifier classifier)
    (.setExclusions (map
                      (fn [lib]
                        (doto (Exclusion.)
                          (.setGroupId (or (namespace lib) (name lib)))
                          (.setArtifactId (name lib))))
                      exclusions))))


(defn- ^Dependency model-repository
  [[^String id {:keys [^String url] :as repo}]]
  (doto (Repository.)
    (.setId id)
    (.setUrl url)))


;; ** main


(defn- without-nil-values
  [m]
  (into (empty m)
    (remove #(nil? (val %)))
    m))


(defn scm-to-map
  [^Scm scm]
  (without-nil-values
    {:connection           (.getConnection scm)
     :developer-connection (.getDeveloperConnection scm)
     :tag                  (.getTag scm)
     :url                  (.getUrl scm)}))


(defn license-to-map
  [^License license]
  (without-nil-values
    {:name         (.getName license)
     :url          (.getUrl license)
     :distribution (.getDistribution license)
     :comments     (.getComments license)}))


(defn model-to-map
  [^Model model]
  (without-nil-values
    {:name         (or (.getArtifactId model) (-> model .getParent .getArtifactId))
     :group        (or (.getGroupId model) (-> model .getParent .getGroupId))
     :version      (or (.getVersion model) (-> model .getParent .getVersion))
     :description  (.getDescription model)
     :homepage     (.getUrl model)
     :url          (.getUrl model)
     :licenses     (into [] (map license-to-map) (.getLicenses model))
     :scm          (when-let [scm (.getScm model)] (scm-to-map scm))
     :authors      (into [] (map (memfn ^File getName)) (.getContributors model))
     :packaging    (keyword (.getPackaging model))
     :dependencies (into []
                     (map
                       (fn [^Dependency dep]
                         {:group_name (.getGroupId dep)
                          :jar_name   (.getArtifactId dep)
                          :version    (or (.getVersion dep) "")
                          :scope      (or (.getScope dep) "compile")}))
                     (.getDependencies model))}))


(defn ^Model read-pom
  "Reads a pom file returning a maven Model object."
  [path]
  (with-open [reader (jio/reader path)]
    (.read (MavenXpp3Reader.) reader)))


(defn ^Model write-pom
  "Reads a pom file returning a maven Model object."
  [path ^Model pom]
  (with-open [writer (jio/writer path)]
    (.write (MavenXpp3Writer.) writer pom)))


(defn pom-to-map
  [path]
  (model-to-map (read-pom path)))


;; * gen


(defn ^Model replace-version
  [^Model pom version]
  (.setVersion pom version)
  pom)


(defn ^Model replace-deps
  [^Model pom deps]
  (.setDependencies pom (map model-dependency deps))
  pom)


(defn^Model replace-build
  [^Model pom paths]
  (.setBuild pom (model-build paths))
  pom)


(defn^Model replace-repos
  [^Model pom repos]
  (.setRepositories pom (map model-repository repos))
  pom)


(defn ^Model gen-pom
  [^String group-id ^String artifact-id ^String version deps paths repos]
  (doto (Model.)
    (.setGroupId group-id)
    (.setArtifactId artifact-id)
    (.setVersion version)
    (.setDependencies (map model-dependency deps))
    (.setBuild (model-build paths))
    (.setRepositories (map model-repository repos))))


(defn sync-pom
  ([lib mvn-coords deps-map]
   (sync-pom lib mvn-coords deps-map (jio/file ".")))
  ([lib {:keys [:mvn/version]} {:keys [deps paths :mvn/repos] :as c} ^File dir]
   (let [artifact-id (name lib)
         group-id    (or (namespace lib) artifact-id)
         repos       (remove #(= "https://repo1.maven.org/maven2/" (-> % val :url)) repos)
         pom-file    (jio/file dir "pom.xml")
         pom         (if (.exists pom-file)
                       (-> (read-pom pom-file)
                         (replace-version version)
                         (replace-deps deps)
                         (replace-build paths)
                         (replace-repos repos))
                       (gen-pom group-id artifact-id version deps paths repos))]
     (write-pom pom-file pom)
     pom-file)))


(defn ^Properties make-pom-properties
  [lib {:keys [:mvn/version]}]
  (let [artifact-id (name lib)
        group-id    (or (namespace lib) artifact-id)
        properties  (Properties.)]
    (.setProperty properties "groupId" group-id)
    (.setProperty properties "artifactId" artifact-id)
    (when version (.setProperty properties "version" version))
    properties))


(defn store-pom-properties
  [^OutputStream os ^Properties pom-properties ^String comments]
  (.store pom-properties os comments))


(set! *warn-on-reflection* false)


(comment
  (map
    model-dependency
    '{org.clojure/clojure         {:mvn/version "1.9.0" :exclusions [abc]}
      badigeon-deps/badigeon-deps {:local/root "badigeon-deps"}})


  (read-pom (jio/file (System/getProperty "java.io.tmpdir") "pom.xml"))


  (sync-pom
    'badigeon/badigeon
    '{:mvn/version "0.0.2-SNAPSHOT"}
    '{:deps      {org.clojure/clojure {:mvn/version "1.9.0"}
                  badigeon-deps/badigeon-deps
                  {:local/root "badigeon-deps"}}
      ;; :paths ["src"]
      :mvn/repos {"central" {:url "https://repo1.maven.org/maven2/"}
                  "clojars" {:url "https://repo.clojars.org/"}}}
    (jio/file (System/getProperty "java.io.tmpdir")))


  (jio/file (System/getProperty "java.io.tmpdir"))


  (make-pom-properties 'badigeong/badigeon '{:mvn/version "0.0.1-SNAPSHOT"})

  (.store properties baos "Badigeon")
  )

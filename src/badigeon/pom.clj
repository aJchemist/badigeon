(ns badigeon.pom
  (:require
   [clojure.java.io :as jio]
   [clojure.tools.deps.alpha.gen.pom :as gen.pom]
   [clojure.tools.deps.alpha.util.io :refer [printerrln]]
   [clojure.data.xml :as xml]
   )
  (:import
   clojure.data.xml.node.Element
   java.io.OutputStream
   java.io.ByteArrayOutputStream
   java.io.File
   java.io.Reader
   java.util.Properties
   ))


(set! *warn-on-reflection* true)


(xml/alias-uri 'pom "http://maven.apache.org/POM/4.0.0")


(defn- gen-pom
  [group-id artifact-id version deps [path & paths] repos]
  (xml/sexp-as-element
    [::pom/project
     {:xmlns                         "http://maven.apache.org/POM/4.0.0"
      (keyword "xmlns:xsi")          "http://www.w3.org/2001/XMLSchema-instance"
      (keyword "xsi:schemaLocation") "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"}
     [::pom/modelVersion "4.0.0"]
     [::pom/groupId group-id]
     [::pom/artifactId artifact-id]
     [::pom/version version]
     [::pom/name (symbol group-id artifact-id)]
     (#'gen.pom/gen-deps deps)
     (when path
       (when (seq paths) (apply printerrln "Skipping paths:" paths))
       [::pom/build (#'gen.pom/gen-source-dir path)])
     (when repos (#'gen.pom/gen-repos repos))]))


(defn sync-pom
  ([lib mvn-coords deps-map]
   (sync-pom lib mvn-coords deps-map (jio/file ".")))
  ([lib {:keys [:mvn/version]} {:keys [deps paths :mvn/repos] :as c} ^File dir]
   (let [artifact-id (name lib)
         group-id    (or (namespace lib) artifact-id)
         repos       (remove #(= "https://repo1.maven.org/maven2/" (-> % val :url)) repos)
         pom-file    (jio/file dir "pom.xml")
         pom         (if (.exists pom-file)
                       (with-open [rdr (jio/reader pom-file)]
                         (-> rdr
                           (#'gen.pom/parse-xml)
                           (#'gen.pom/replace-deps deps)
                           (#'gen.pom/replace-paths paths)
                           (#'gen.pom/replace-repos repos)))
                       (gen-pom group-id artifact-id version deps paths repos))]
     (spit pom-file (xml/indent-str pom))
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
  (sync-pom
    'badigeon/badigeon
    '{:mvn/version "0.0.1-SNAPSHOT"}
    '{:deps {org.clojure/clojure {:mvn/version "1.9.0"}
             badigeon-deps/badigeon-deps
             {:local/root "badigeon-deps"}}
      :mvn/repos {"central" {:url "https://repo1.maven.org/maven2/"}
                  "clojars" {:url "https://repo.clojars.org/"}}}
    (jio/file (System/getProperty "java.io.tmpdir")))

  (make-pom-properties 'badigeong/badigeon '{:mvn/version "0.0.1-SNAPSHOT"})

  (.store properties baos "Badigeon")
  )

(ns badigeon.deploy
  (:require
   [clojure.tools.deps.alpha.util.maven :as maven]
   [clojure.java.io :as jio]
   [badigeon.utils :as utils]
   )
  (:import
   org.apache.maven.settings.DefaultMavenSettingsBuilder
   org.apache.maven.settings.building.DefaultSettingsBuilderFactory
   org.apache.maven.settings.crypto.DefaultSettingsDecrypter
   org.apache.maven.settings.crypto.SettingsDecrypter
   org.eclipse.aether.deployment.DeployRequest
   org.eclipse.aether.repository.RemoteRepository$Builder
   org.eclipse.aether.util.repository.AuthenticationBuilder
   org.sonatype.plexus.components.cipher.DefaultPlexusCipher
   org.sonatype.plexus.components.sec.dispatcher.DefaultSecDispatcher
   java.nio.file.Path
   ))


(set! *warn-on-reflection* true)


(defn- set-settings-builder
  [^DefaultMavenSettingsBuilder default-builder settings-builder]
  (doto (.. default-builder getClass (getDeclaredField "settingsBuilder"))
    (.setAccessible true)
    (.set default-builder settings-builder)))


(defn- get-settings
  ^org.apache.maven.settings.Settings []
  (.buildSettings
   (doto (DefaultMavenSettingsBuilder.)
     (set-settings-builder (.newInstance (DefaultSettingsBuilderFactory.))))))


(defn remote-repo
  [[id {:keys [url]}] credentials]
  (let [repository          (RemoteRepository$Builder. id "default" url)
        ^org.apache.maven.settings.Server server-setting
        (first (filter
                 #(.equalsIgnoreCase ^String id (.getId ^org.apache.maven.settings.Server %))
                 (.getServers (get-settings))))
        ^String username    (or (:username credentials) (when server-setting
                                                          (.getUsername server-setting)))
        ^String password    (or (:password credentials) (when server-setting
                                                          (.getPassword server-setting)))
        ^String private-key (or (:private-key credentials) (when server-setting
                                                             (.getPassword server-setting)))
        ^String passphrase  (or (:passphrase credentials) (when server-setting
                                                            (.getPassphrase server-setting)))]
    (-> repository
      (.setAuthentication (.build
                            (doto (AuthenticationBuilder.)
                              (.addUsername username)
                              (.addPassword password)
                              (.addPrivateKey private-key passphrase))))
      (.build))))


(defn make-artifact
  [lib version {:keys [file-path] :as artifact}]
  (let [artifact (utils/artifact-with-default-extension artifact)]
    (-> (maven/coord->artifact lib (assoc artifact :mvn/version version))
      (.setFile (jio/file (str file-path))))))


(defn check-for-snapshot-deps [{:keys [version] :as project-map} deps]
  (when (and (not (re-find #"SNAPSHOT" version)))
    (doseq [{:keys [:mvn/version] :as dep} deps]
      (when (re-find #"SNAPSHOT" version)
        (throw (ex-info (str "Release versions may not depend upon snapshots."
                             "\nFreeze snapshots to dated versions or set the"
                             "\"allow-snapshot-deps?\" uberjar option.")
                 {:dependency dep}))))))


(defn ensure-signed-artifacts [artifacts version]
  (when-not (re-find #"SNAPSHOT" version)
    (when-not (some :badigeon/signature? artifacts)
      (throw (ex-info "Non-snapshot versions of artifacts should be signed. Consider setting the \"allow-unsigned?\" option to process anyway."
                      {:artifacts artifacts
                       :version version})))))


(defn deploy
  "Deploys a collection of artifacts to a remote repository. When deploying non-snapshot versions of artifacts, artifacts must be signed, unless the \"allow-unsigned?\" parameter is set to true.
  - lib: A symbol naming the library to be deployed.
  - version: The version of the library to be deployed.
  - artifacts: The collection of artifacts to be deployed. Each artifact must be a map with a :file-path and an optional :extension key. :extension defaults to \"jar\" for jar file and \"pom\" for pom files. Artifacts representing a signature must also have a :badigeon/signature? key set to true.
  - repository: A map with an :id and a :url key representing the remote repository where the artifacts are to be deployed. The :id is used to find credentials in the settings.xml file when authenticating to the repository.
  - allow-unsigned?: When set to true, allow deploying non-snapshot versions of unsigned artifacts. Default to false."
  ([lib version artifacts repository]
   (deploy lib version artifacts repository nil))
  ([lib version artifacts repository {:keys [credentials allow-unsigned?]}]
   (when-not allow-unsigned?
     (ensure-signed-artifacts artifacts version))
   (System/setProperty "aether.checksums.forSignature" "true")
   (let [system         (maven/make-system)
         session        (maven/make-session system maven/default-local-repo)
         artifacts      (map (partial make-artifact lib version) artifacts)
         deploy-request (-> (DeployRequest.)
                          (.setRepository (remote-repo repository credentials)))
         deploy-request (reduce #(.addArtifact ^DeployRequest %1 %2) deploy-request artifacts)]
     (.deploy system session deploy-request))))

;; Signature artifacts must have a :badigeon/signature? key. This key is added by badigeon.sign but must be manually added when not using badigeon.sign


(set! *warn-on-reflection* false)


(comment
  (DefaultPlexusCipher.)
  (DefaultSettingsDecrypter.)


  (.decrypt
    (doto (DefaultSecDispatcher.)
      (.setConfigurationFile (str (jio/file (System/getProperty "user.home") ".m2" "settings-security.xml"))))
    "{nFYsS1aDyTIHudQ17eBeZy3GtfKciydQ8zm3we35Es4=}")


  (first
    (filter
      #(.equalsIgnoreCase "clojars" (.getId ^org.apache.maven.settings.Server %))
      (.getServers (get-settings))))
  )

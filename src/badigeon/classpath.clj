(ns badigeon.classpath
  (:require
   [clojure.tools.deps.alpha :as deps]
   [clojure.tools.deps.alpha.reader :as deps.reader]
   ))


(defn get-classpath
  ([]
   (get-classpath nil))
  ([aliases]
   (get-classpath (deps.reader/read-deps (:config-files (deps.reader/clojure-env))) aliases))
  ([deps-map {:keys [resolve-aliases makecp-aliases aliases] :as as}]
   (let [resolve-args (deps/combine-aliases deps-map (concat aliases resolve-aliases))
         cp-args      (deps/combine-aliases deps-map (concat aliases makecp-aliases))]
     (-> (deps/resolve-deps deps-map resolve-args)
       (deps/make-classpath (:paths deps-map) cp-args)))))

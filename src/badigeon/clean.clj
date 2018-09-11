(ns badigeon.clean
  (:require
   [badigeon.io.alpha :as io]
   )
  (:import
   java.nio.file.FileVisitOption
   java.nio.file.FileVisitResult
   java.nio.file.FileVisitor
   java.nio.file.Files
   java.nio.file.Path
   java.nio.file.Paths
   ))


(set! *warn-on-reflection* true)


(defn- make-file-visitor
  []
  (reify FileVisitor
    (postVisitDirectory [_ dir exception]
      (if (nil? exception)
        (do
          (Files/delete dir)
          FileVisitResult/CONTINUE)
        (throw exception)))
    (visitFile [_ path attrs]
      (Files/delete path)
      FileVisitResult/CONTINUE)
    (preVisitDirectory [_ dir attrs]
      FileVisitResult/CONTINUE)
    (visitFileFailed [_ file exception]
      (throw exception))))


(defn delete-recursively
  [^Path path]
  (when (io/exists? path)
    (Files/walkFileTree path (make-file-visitor))))


(defn sanity-check
  [path allow-outside-target?]
  (let [root-path   (io/path (System/getProperty "user.dir"))
        target-path (.resolve root-path "target")]
    (when (not (io/is-parent-path? root-path path))
      (throw (IllegalArgumentException. "Cannot delete a directory outside of project root")))
    (when (and
            (not allow-outside-target?)
            (not (io/same-directory? target-path path))
            (not (io/is-parent-path? target-path path)))
      (throw (IllegalArgumentException. "Cannot delete a directory outside of target-directory. Consider setting the \"allow-outside-target?\" option if you really want to delete this directory.")))))


(defn clean
  "Delete the target-directory. The directory to delete must not be outside of project root. By default, the directory to delete must either be the directory named \"target\" or must be inside the directory named \"target\". Setting the \"allow-outside-target?\" parameter to true makes deleting directories outside \"target\" possible."
  ([target-directory]
   (clean target-directory nil))
  ([target-directory {:keys [allow-outside-target?]}]
   (let [path (io/path target-directory)]
     (sanity-check path allow-outside-target?)
     (delete-recursively path)
     target-directory)))


(set! *warn-on-reflection* false)


(comment
  (clean "target")
  )


;; We do not forbid file overwriting in compile/javac/jar/bundle because
;; compile -> does not work with an existing file which is not a directory
;;         -> can only overwrite .class files
;; jar -> can only overwrite a .jar file
;; javac/bundle -> quite similar to compile

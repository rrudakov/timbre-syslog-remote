(ns build
  (:require
   [clojure.tools.build.api :as b]))

(def lib 'io.rrudakov/timbre-syslog-remote)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (println "Clean target dir")
  (b/delete {:path "target"}))

(defn jar [_]
  (println "Write pom")
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     basis
                :src-dirs  ["src"]})
  (println "Copy sources and resources")
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir})
  (println "Build jar")
  (b/jar {:class-dir class-dir
          :jar-file  jar-file})
  (println "Created" jar-file))

(defn release [_]
  (println "Release version" version)
  (b/process {:command-args ["git" "tag" "--sign" version "-a" "-m" (str "Release " version)]})
  (println "Success!"))

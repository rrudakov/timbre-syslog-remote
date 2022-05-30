(ns build
  (:require
   [clojure.tools.build.api :as b]
   [deps-deploy.deps-deploy :as dd]))

(def lib 'net.clojars.rrudakov/timbre-syslog-remote)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def scm-url "git@github.com:rrudakov/timbre-syslog-remote.git")

(defn clean [_]
  (println "Clean target dir")
  (b/delete {:path "target"}))

(defn jar [_]
  (println "Write pom")
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     basis
                :src-dirs  ["src"]
                :scm       {:tag                 "HEAD"
                            :connection          (str "scm:git:" scm-url)
                            :developerConnection (str "scm:git:" scm-url)
                            :url                 scm-url}})
  (println "Copy sources and resources")
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir})
  (println "Build jar")
  (b/jar {:class-dir class-dir
          :jar-file  jar-file})
  (println "Created" jar-file))

(defn release [_]
  (println "Release version" version)
  (b/git-process {:git-args ["tag" "--sign" version "-a" "-m" (str "Release " version)]})
  (println "Success!"))

(defn deploy [_]
  (println "Deploy to Clojars")
  (dd/deploy {:installer      :remote
              :artifact       (b/resolve-path jar-file)
              :pom-file       (b/pom-path {:lib       lib
                                           :class-dir class-dir})
              :sign-releases? true})
  (println "Deployed successfully"))

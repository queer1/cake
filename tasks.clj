(ns user
  (:use cake cake.ant
        [useful :only [abort]]
        [cake.tasks.jar :only [release-to-clojars uberjarfile]])
  (:import [org.apache.tools.ant.taskdefs Jar Copy Move ExecTask]
           [java.io File]))

(defn bakejar []
  (file (format "bake-%s.jar" (:version *project*))))

(defn add-dev-jars [task]
  (doseq [jar (fileset-seq {:dir "lib/dev" :includes "*.jar"})]
    (add-zipfileset task {:src jar :includes "**/*.clj" :excludes "META-INF/**/project.clj"})))

(deftask uberjar
  (let [jarfile (uberjarfile)
        bakejar (bakejar)]
    (ant Jar {:dest-file bakejar}
         (add-fileset {:dir "bake"})
         (add-dev-jars))
    (ant Jar {:dest-file jarfile :update true}
         (add-fileset {:file bakejar})
         (add-dev-jars))))

(defn snapshot? [version]
  (.endsWith version "SNAPSHOT"))

(deftask gem
  "Build standalone gem package."
  (let [version (:version *project*)]
    (if (snapshot? version)
      (println "will not make gem since this is a snapshot version:" version)
      (do (run-task 'uberjar)
          (ant Copy {:file (uberjarfile) :tofile (file "gem/lib/cake.jar")})
          (ant Copy {:file (bakejar)     :tofile (file "gem/lib/bake.jar")})
          (ant Copy {:file (file "bin/cake") :tofile (file "gem/bin/cake")})
          (ant ExecTask {:executable "gem" :dir (file "gem")}
               (env {"CAKE_VERSION" version})
               (args ["build" "cake.gemspec"]))))))

(undeftask release)
(deftask release #{uberjar gem}
  "Release project jar to clojars and gem package to rubygems."
  (let [version (:version *project*)]
    (when-not (snapshot? version)
      (let [gem (str "cake-" version ".gem")]
        (log "Releasing gem: " gem)
        (ant ExecTask {:executable "gem" :dir (file "gem")}
             (args ["push" gem])))))
  (let [uberjarfile (uberjarfile)
        jarfile     (file "cake.jar")]
    (ant Copy {:file uberjarfile :tofile jarfile})
    (release-to-clojars jarfile)))
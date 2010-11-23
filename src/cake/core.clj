(ns cake.core
  (:use cake cake.utils.useful cake.file
        clojure.contrib.condition
        [bake.io :only [init-multi-out]]
        [bake.reload :only [reloader]]
        [clojure.string :only [join trim]]
        [cake.utils :only [os-name cake-exec *readline-marker*]])
  (:require [cake.project :as project]
            [cake.ant :as ant]
            [cake.server :as server])
  (:import [java.io File FileReader InputStreamReader OutputStreamWriter BufferedReader FileNotFoundException]
           [java.net Socket SocketException]))

(defn load-tasks [tasks]
  (let [complain? (seq (.listFiles (file "lib")))]
    (doseq [ns tasks]
      (try (require ns)
           (catch Exception e
             (when complain? (server/print-stacktrace e)))))))

(defmacro defproject [name version & opts]
  (let [opts (into-map opts)
        [tasks task-opts] (split-with symbol? (:tasks opts))
        task-opts (into-map task-opts)]
    `(do (alter-var-root #'*project*    (fn [_#] (project/create '~name ~version '~opts)))
         (alter-var-root #'project-root (fn [_#] (project/create '~name ~version '~opts)))
         (require 'cake.tasks.default)
         (load-tasks '~tasks)
         (undeftask ~@(:exclude task-opts)))))

(defmacro defcontext [name & opts]
  (let [opts (into-map opts)]
    `(alter-var-root #'*context* merge-in {'~name '~opts})))

(defn update-task [task deps doc action]
  (let [task (or task {:actions [] :deps #{} :doc []})]
    (-> task
        (update :deps    into deps)
        (update :doc     into doc)
        (update :actions conj action))))

(defonce tasks (atom {}))
(def run? nil)

(def implicit-tasks
  {'repl     ["Start an interactive shell with history and tab completion."]
   'reload   ["Reload any .clj files that have changed or restart."]
   'upgrade  ["Upgrade cake to the most current version."]
   'ps       ["List running cake jvm processes for all projects."]
   'kill     ["Kill running cake jvm processes. Use -9 to force."]
   'killall  ["Kill all running cake jvm processes for all projects."]})

(defn parse-task-opts [forms]
  (let [[deps forms] (if (set? (first forms))
                      [(first forms) (rest forms)]
                      [#{} forms])
        deps (map #(if-not (symbol? %) (eval %) %) deps)
        [doc forms] (split-with string? forms)
        [destruct forms] (if (map? (first forms))
                           [(first forms) (rest forms)]
                           [{} forms])
        [pred forms] (if (= :when (first forms))
                       `[~(second forms) ~(drop 2 forms)]
                       [true forms])]
    {:deps deps :doc doc :actions forms :destruct destruct :pred pred}))

(defn- in-ts [ts task-decl]
  (conj (drop 2 task-decl)
        (symbol (str ts "." (second task-decl)))
        (first task-decl)))

(defmacro ts
  "Wrap deftask calls with a task namespace. Takes docstrings for the namespace followed by forms.
   Creates a task named after the namespace that prints a list of tasks in that namespace."
  [ts & forms]
  (let [[doc forms] (split-with string? forms)
        doc (update (vec doc) 0 #(str % " --"))]
    `(do
       (deftask ~ts ~@doc
         (invoke ~'help {:help [~(name ts)]}))
       ~@(map (partial in-ts ts) forms))))

(defmacro deftask
  "Define a cake task. Each part of the body is optional. Task definitions can
   be broken up among multiple deftask calls and even multiple files:
   (deftask foo #{bar baz} ; a set of prerequisites for this task
     \"Documentation for task.\"
     {foo :foo} ; destructuring of *opts*
     (do-something)
     (do-something-else))"
  [name & forms]
  (verify (not (implicit-tasks name)) (str "Cannot redefine implicit task: " name))
  (let [{:keys [deps doc actions destruct pred]} (parse-task-opts forms)]
    `(swap! tasks update '~name update-task '~deps '~doc
            (fn [~destruct] (when ~pred ~@actions)))))

(defn task-run-file [task-name]
  (file ".cake" "run" task-name))

(defn run-file-task? [target-file deps]
  (let [{file-deps true task-deps false} (group-by string? deps)]
    (or (not (.exists target-file))
        (some #(newer? % target-file)
              (into file-deps
                    (map #(task-run-file %)
                         task-deps)))
        (empty? deps))))

(defmacro defile
  "Define a file task. Uses the same syntax as deftask, however the task name
   is a string representing the name of the file to be generated by the body.
   Source files may be specified in the dependencies set, in which case
   the file task will only be ran if the source is newer than the destination.
   (defile \"main.o\" #{\"main.c\"}
     (sh \"cc\" \"-c\" \"-o\" \"main.o\" \"main.c\"))"
  [name & forms]
  (let [{:keys [deps doc actions destruct pred]} (parse-task-opts forms)]
    `(swap! tasks update '~name update-task '~deps '~doc
            (fn [~destruct]
              (when (and ~pred
                         (run-file-task? *File* '~deps))
                (mkdir (.getParentFile *File*))
                ~@actions)))))

(defmacro undeftask [& names]
  `(swap! tasks dissoc ~@(map #(list 'quote %) names)))

(defmacro remove-dep! [task dep]
  `(swap! tasks update-in ['~task :deps] disj '~dep))

(defn- expand-defile-path [path]
  (file (.replaceAll path "\\+context\\+" (str (:context *project*)))))

(defn run-task
  "Execute the specified task after executing all prerequisite tasks."
  [name]
  (let [task (@tasks name)]
    (if (and (nil? task)
             (not (string? name)))
      (println "unknown task:" name)
      (verify (not= :in-progress (run? name))
              (str "circular dependency found in task: " name)
        (when-not (run? name)
          (set! run? (assoc run? name :in-progress))
          (doseq [dep (:deps task)] (run-task dep))
          (binding [*current-task* name
                    *File* (if-not (symbol? name) (expand-defile-path name))]
            (doseq [action (:actions task)] (action *opts*))
            (set! run? (assoc run? name true))
            (if (symbol? name)
              (touch (task-run-file name) :verbose false))))))))

(defmacro invoke [name & [opts]]
  `(binding [*opts* (or ~opts *opts*)]
     (run-task '~name)))

(defmacro bake [& args]
  `(project/bake ~@args))

(defn process-command [[task readline-marker]]
  (binding [*readline-marker* readline-marker, run? {}]
    (ant/in-project
     (doseq [dir ["lib" "classes" "build"]]
       (.mkdirs (file dir)))
     (handler-case :type
       (run-task 'deps)
       (run-task (symbol (name task)))
       (handle :abort-task
         (println (name task) "aborted:" (:message *condition*)))))))

(defn abort-task [& message]
  (raise {:type :abort-task :message (join " " message)}))

(defn repl []
  (binding [*current-task* "repl"]
    (ant/in-project (server/repl))))

(defn start-server [port]
  (ant/in-project
   (let [classpath (for [url (.getURLs (java.lang.ClassLoader/getSystemClassLoader))]
                     (File. (.getFile url)))
         project-files (project/files ["project.clj" "context.clj" "tasks.clj" "dev.clj"] ["tasks.clj" "dev.clj"])]
     (in-ns 'cake.core)
     (doseq [file project-files :when (.exists file)]
       (load-file (.getPath file)))     
     (when-not *project* (require '[cake.tasks help new]))
     (when (= "global" (:artifact-id *project*))
       (undeftask test autotest jar uberjar war uberwar install release)
       (require '[cake.tasks new]))
     (init-multi-out)
     ;; make sure to fetch all deps before initializing the project classloader
     (binding [run? {}] (run-task 'deps))
     (project/reload!)
     (server/create port process-command
       :reload (reloader classpath project-files (File. "lib/dev"))
       :repl   repl)
     nil)))
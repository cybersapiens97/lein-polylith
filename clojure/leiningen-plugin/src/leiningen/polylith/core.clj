(ns leiningen.polylith.core
  (:require [clojure.pprint :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.java.shell :as shell]
            [leiningen.polylith.file :as file]
            [leiningen.polylith.version :as v]))

(defn str->component [name]
  (symbol (str/replace name #"_" "-")))

(defn ns-components [component-paths]
  (let [component (-> component-paths ffirst str->component)
        path->ns (fn [path] (-> path file/read-file first second))
        namespaces (map #(-> % second path->ns) component-paths)]
    (map #(vector % component) namespaces)))

(defn api-ns->component [root-path]
  (into {}
        (reduce into []
                (map ns-components
                     (partition-by first (file/paths-in-dir (str root-path "/apis/src")))))))

(defn- ->imports
  ([imports]
   (->imports imports []))
  ([imports result]
   (when (sequential? imports)
     (if (= :require (first imports))
       (conj result (rest imports))
       (filter (comp not nil?)
               (map ->imports imports))))))

(defn imports [content api->component]
  (let [requires (ffirst (->imports (first content)))
        ns-imports (map (juxt last first)
                        (filter #(= :as (second %)) requires))]
    (filter #(api->component (second %)) ns-imports)))

(defn component? [content alias->ns]
  (and (list? content)
       (-> content first sequential? not)
       (contains? alias->ns (some-> content first namespace symbol))))

(defn replace-ns [function alias->ns]
  (let [fn-name (name function)
        fn-ns-name (name (alias->ns (-> function namespace symbol)))]
    (symbol fn-ns-name fn-name)))

(defn file-dependencies
  ([filename api->component]
   (let [content (file/read-file filename)
         alias->ns (into {} (imports content api->component))
         functions (flatten (file-dependencies alias->ns content []))]
     (set (map #(replace-ns % alias->ns) functions))))
  ([alias->ns content result]
   (when (sequential? content)
     (if (component? content alias->ns)
       (conj result (first content))
       (filter (comp not nil?)
               (map #(file-dependencies alias->ns % result) content))))))

(defn component-dependencies [component-paths api->component]
  (let [component (-> component-paths ffirst symbol)
        files (map second component-paths)
        dependencies (sort (into #{} (mapcat #(file-dependencies % api->component) files)))]
    [component (vec dependencies)]))

(defn all-dependencies [root-path]
  (let [development-dir (str root-path "/development/src")
        api->component (api-ns->component root-path)
        all-paths (partition-by first (file/paths-in-dir development-dir))]
    (into (sorted-map) (map #(component-dependencies % api->component) all-paths))))

(defn changed-dirs [dir file-paths]
  (let [f #(and (str/starts-with? % (str dir "/"))
                (> (count (str/split % #"/")) 2))]
    (vec (sort (set (map #(second (str/split % #"/"))
                         (filter f file-paths)))))))

(defn changed-system? [root-path path changed-systems]
  (let [systems-path (str root-path "/systems")
        system? (str/starts-with? path systems-path)
        changed? (and
                   system?
                   (let [system (second (str/split (subs path (count systems-path)) #"/"))]
                     (contains? (set changed-systems) system)))]
    {:system?  system?
     :changed? changed?}))

(defn changed-component? [root-path path changed-components]
  (let [components-path (str root-path "/components")
        component? (str/starts-with? path components-path)
        changed? (and
                   component?
                   (let [component (second (str/split (subs path (count components-path)) #"/"))]
                     (contains? (set changed-components) component)))]
    {:component? component?
     :changed?   changed?}))

(defn changed? [root-path file changed-systems changed-components]
  (let [path (file/file-path->real-path file)
        changed-system (changed-system? root-path path changed-systems)
        changed-component (changed-component? root-path path changed-components)]
    {:name     (file/path->dir-name path)
     :type     (cond
                 (:system? changed-system) "-> system"
                 (:component? changed-component) "-> component"
                 :else "?")
     :changed? (cond
                 (:system? changed-system) (:changed? changed-system)
                 (:component? changed-component) (:changed? changed-component)
                 :else false)}))

(defn build-links [root-path system changed-systems changed-components]
  (mapv #(changed? root-path % changed-systems changed-components)
        (file/directories (str root-path "/builds/" system "/src"))))

(defn build-info [root-path builds changed-systems changed-components]
  (into {} (mapv (juxt identity #(build-links root-path % changed-systems changed-components)) builds)))

(defn any-changes? [builds-info system]
  (or (some true? (map :changed? (builds-info system))) false))

(defn system-or-component-changed? [builds-info changed-builds]
  (let [system-changes (map (juxt identity #(any-changes? builds-info %)) (keys builds-info))]
    (map (juxt first #(or (last %) (contains? changed-builds (first %)))) system-changes)))

(defn diff [root-path last-success-sha1 current-sha1]
  (let [diff (:out (shell/sh "git" "diff" "--name-only" last-success-sha1 current-sha1 :dir root-path))]
    (str/split diff #"\n")))

(defn info
  ([root-path]
   (info root-path []))
  ([root-path last-success-sha1 current-sha1]
   (info root-path (diff root-path last-success-sha1 current-sha1)))
  ([root-path paths]
   (let [apis (set (file/directory-names (str root-path "/apis/src")))
         components (set (file/directory-names (str root-path "/components")))
         systems (set (file/directory-names (str root-path "/systems")))
         builds (file/directory-names (str root-path "/builds"))
         ;; make sure we only report changes that currently exist
         changed-apis (set (filter systems (set (changed-dirs "apis" paths))))
         changed-components (set (filter components (changed-dirs "components" paths)))
         changed-systems (set (filter systems (set (changed-dirs "systems" paths))))
         changed-builds-dir (set (filter systems (changed-dirs "builds" paths)))
         builds-info (build-info root-path builds changed-systems changed-components)
         changed-builds (mapv first (filter second (system-or-component-changed? builds-info (set changed-builds-dir))))]
     {:apis               (-> apis sort vec)
      :builds             (-> builds sort vec)
      :components         (-> components sort vec)
      :systems            (-> systems sort vec)
      :diff               paths
      :changed-apis       changed-apis
      :changed-builds     changed-builds
      :changed-components changed-components
      :changed-systems    changed-systems
      :changed-builds-dir changed-builds-dir
      :builds-info        builds-info})))

(defn changes [root-path cmd last-success-sha1 current-sha1]
  (let [{:keys [changed-apis
                changed-builds
                changed-systems
                changed-components]} (info root-path last-success-sha1 current-sha1)]
    (condp = cmd
      "a" changed-apis
      "b" changed-builds
      "s" changed-systems
      "c" changed-components
      [])))

(defn delete [root-path dev-dirs name]
  (file/delete-dir (str root-path "/apis/src/" name))
  (file/delete-dir (str root-path "/components/" name))
  (doseq [dir dev-dirs]
    (file/delete-file (str root-path "/" dir "/project-files/" name "-project.clj"))
    (file/delete-file (str root-path "/" dir "/resources/" name))
    (file/delete-file (str root-path "/" dir "/src/" name))
    (file/delete-file (str root-path "/" dir "/test/" name))
    (file/delete-file (str root-path "/" dir "/test-int/" name))))

(defn create-dev-links [root-path dev-dir name]
  (let [dir (str root-path "/" dev-dir)
        levels (inc (count (str/split dev-dir #"/")))
        parent-path (str/join (repeat levels "../"))
        path (str parent-path "components/" name)]
    (file/create-symlink (str dir "/resources/" name)
                         (str path "/resources/" name))
    (file/create-symlink (str dir "/project-files/" name "-project.clj")
                         (str path "/project.clj"))
    (file/create-symlink (str dir "/src/" name)
                         (str path "/src/" name))
    (file/create-symlink (str dir "/test/" name)
                         (str path "/test/" name))
    (file/create-symlink (str dir "/test-int/" name)
                         (str path "/test-int/" name))))

(defn create-workspace [path name ws-ns top-dir]
  (let [root-dir (str path "/" name)
        api-content [(str "(defproject " ws-ns "/apis \"1.0\"")
                     "  :description \"Component apis\""
                     "  :dependencies [[org.clojure/clojure \"1.9.0\"]]"
                     "  :aot :all)"]
        dev-content [(str "(defproject " ws-ns "/development \"1.0\"")
                     "  :description \"The development environment\""
                     (str "  :plugins [[polylith/lein-polylith \"" v/version "\"]]")
                     (str "  :polylith {:top-ns \"" ws-ns "\"")
                     (str "             :top-dir \"" top-dir "\"")
                     (str "             :development-dirs [\"development\"]")
                     (str "             :ignore-tests []}")
                     (str "  :profiles {:dev {:test-paths [\"test\" \"test-int\"]}")
                     "  :dependencies [[org.clojure/clojure \"1.9.0\"]])"]]
    (file/create-dir root-dir)
    (file/create-dir (str root-dir "/apis"))
    (file/create-dir (str root-dir "/apis/src"))
    (file/create-dir (str root-dir "/builds"))
    (file/create-dir (str root-dir "/components"))
    (file/create-dir (str root-dir "/development"))
    (file/create-dir (str root-dir "/development/project-files"))
    (file/create-dir (str root-dir "/development/resources"))
    (file/create-dir (str root-dir "/development/src"))
    (file/create-dir (str root-dir "/development/test"))
    (file/create-dir (str root-dir "/development/test-int"))
    (file/create-dir (str root-dir "/systems"))
    (file/create-file (str root-dir "/apis/project.clj") api-content)
    (file/create-file (str root-dir "/development/project.clj") dev-content)
    (file/create-symlink (str root-dir "/development/src-apis") "../apis/src")))

(defn create-component [root-path top-ns dev-dirs name]
  (let [comp-dir (str root-path "/components/" name)
        api-content [(str "(ns " name ".api)")
                     ""
                     ";; add your functions here..."
                     "(defn myfn [x])"]
        delegate-content [(str "(ns " name ".api")
                          (str "  (:require [" name ".core :as core]))")
                          ""
                          ";; delegate to the implementations..."
                          "(defn myfn [x]"
                          "  (core/myfn x))"]
        core-content [(str "(ns " name ".core)")
                      ""
                      ";; add your functions here..."
                      "(defn myfn [x]"
                      "  (+ 2 x)"]
        test-content [(str "(ns " name ".core-test)")
                      "  (:require [clojure.test :refer :all]"
                      (str "            [" name ".core :as core]")
                      ""
                      ";; add your tests here..."
                      "(deftest test-myfn"
                      "  (is (= 42 (core/myfn 40)))"]
        test-int-content [(str "(ns " name ".core-test)")
                          "  (:require [clojure.test :refer :all]"
                          (str "            [" name ".core :as core]")
                          ""
                          ";; add your integration tests here"]
        project-content [(str "(defproject " top-ns "/" name " \"0.1\"")
                         (str "  :description \"A " name " component\"")
                         (str "  :dependencies [[" top-ns "/apis \"1.0\"]")
                         (str "                 [org.clojure/clojure \"1.9.0\"]]")
                         (str "  :aot :all)")]]
    (file/create-dir comp-dir)
    (file/create-dir (str root-path "/apis/src/" name))
    (file/create-dir (str comp-dir "/resources"))
    (file/create-dir (str comp-dir "/resources/" name))
    (file/create-dir (str comp-dir "/src"))
    (file/create-dir (str comp-dir "/src/" name))
    (file/create-dir (str comp-dir "/test"))
    (file/create-dir (str comp-dir "/test/" name))
    (file/create-dir (str comp-dir "/test-int"))
    (file/create-dir (str comp-dir "/test-int/" name))
    (file/create-file (str comp-dir "/project.clj") project-content)
    (file/create-file (str root-path "/apis/src/" name "/api.clj") api-content)
    (file/create-file (str comp-dir "/src/" name "/api.clj") delegate-content)
    (file/create-file (str comp-dir "/src/" name "/core.clj") core-content)
    (file/create-file (str comp-dir "/test/" name "/core_test.clj") test-content)
    (file/create-file (str comp-dir "/test-int/" name "/core_test.clj") test-int-content)
    (doseq [dir dev-dirs]
      (create-dev-links root-path dir name))))

(defn path->ns [path]
  (second (first (file/read-file path))))

(defn system->tests [root-path dir system test-dir]
  (let [paths (map second (file/paths-in-dir (str root-path "/" dir "/" system "/" test-dir)))]
    (map path->ns paths)))

(defn tests
  ([root-path [tests? integration-tests?]]
   (let [changed-systems (file/directory-names (str root-path "/systems"))
         changed-components (file/directory-names (str root-path "/components"))]
     (tests root-path [tests? integration-tests?] changed-systems changed-components)))
  ([root-path [tests? integration-tests?] [last-success-sha1 current-sha1]]
   (let [{:keys [changed-systems
                 changed-components]} (info root-path last-success-sha1 current-sha1)]
     (tests root-path [tests? integration-tests?] changed-systems changed-components)))
  ([root-path [tests? integration-tests?] changed-systems changed-components]
    ;; todo: refactor this!
   (let [system-tests (if tests?
                        (mapcat #(system->tests root-path "systems" % "test") changed-systems)
                        [])
         system-itests (if integration-tests?
                         (mapcat #(system->tests root-path "systems" % "test-int") changed-systems)
                         [])
         component-tests (if tests?
                           (mapcat #(system->tests root-path "components" % "test") changed-components)
                           [])
         component-itests (if integration-tests?
                            (mapcat #(system->tests root-path "components" % "test-int") changed-components)
                            [])]
     (vec (sort (map str (concat system-tests system-itests component-tests component-itests)))))))

;(tests "/Users/joakimtengstrand/IdeaProjects/project-unicorn"
;       [true true]
;       ["59977793c809b3e9a9ae6fee1c8029643b7034b5"
;        "6f54526fca154d6d2e0b55f80b91269995177cec"])

(defn show-tests [tests single-line-statment?]
  (if single-line-statment?
    (if (empty? tests)
      (println "echo 'Nothing changed - no tests executed'")
      (println (str "lein test " (str/join " " tests))))
    (doseq [test tests]
      (println " " test))))

(defn run-tests [tests sing-line-statement?]
  (if (zero? (count tests))
    (println "Nothing to test")
    (do
      (println "Starts execution of" (count tests) "tests:")
      (show-tests tests sing-line-statement?)
      (apply shell/sh (concat ["lein" "test"] tests)))))

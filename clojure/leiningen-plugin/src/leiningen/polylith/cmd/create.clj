(ns leiningen.polylith.cmd.create
  (:require [clojure.string :as str]
            [leiningen.polylith.cmd.info :as info]
            [leiningen.polylith.file :as file]
            [leiningen.polylith.utils :as utils]
            [leiningen.polylith.cmd.create.component :as component]
            [leiningen.polylith.cmd.create.system :as system]
            [leiningen.polylith.cmd.create.workspace :as workspace]))

(defn validate-component [ws-path top-dir name interface]
  (let [interfaces (info/all-interfaces ws-path top-dir)
        components (info/all-components ws-path)
        bases (info/all-bases ws-path)]
    (cond
      (utils/is-empty-str? name) [false "Missing name."]
      (contains? components name) [false (str "Component '" name "' already exists.")]
      (contains? interfaces name) [false (str "An interface with the name '" name "' already exists.")]
      (contains? bases name) [false (str "Components and bases can't share names. "
                                         "A base with the name '" name "' already exists.")]
      (contains? bases interface) [false (str "You can't use an existing base (" name ") for the interface.")]
      :else [true])))

(defn validate-system [ws-path name base]
  (let [components (info/all-components ws-path)
        systems (info/all-systems ws-path)]
    (cond
      (utils/is-empty-str? name) [false "Missing name."]
      (contains? components base) [false (str "A component with the name '" name
                                              "' already exists. Components and bases can't share names.")]
      (contains? systems name) [false (str "System '" name "' already exists.")]
      :else [true])))

(defn validate-workspace [name ws-ns]
  (let [dir (str (file/current-path) "/" name)]
    (cond
      (file/file-exists dir) [false (str "Workspace '" name "' already exists.")]
      (utils/is-empty-str? name) [false "Missing name."]
      (nil? ws-ns) [false "Missing namespace name."]
      :else [true])))

(defn validate [ws-path top-dir cmd name arg2]
  (condp = cmd
    "c" (validate-component ws-path top-dir name arg2)
    "component" (validate-component top-dir ws-path name arg2)
    "s" (validate-system ws-path name arg2)
    "system" (validate-system ws-path name arg2)
    "w" (validate-workspace name arg2)
    "workspace" (validate-workspace name arg2)
    [false (str "Illegal first argument '" cmd "'")]))

(defn ->dir [ws-ns top-dir]
  (or top-dir
      (str/replace ws-ns #"\." "/")))

(defn execute [ws-path top-dir top-ns clojure-version clojure-spec-version [cmd name arg2 arg3]]
  (let [[ok? msg] (validate ws-path top-dir cmd name arg2)]
    (if ok?
      (condp = cmd
        "c" (component/create ws-path top-dir top-ns clojure-version clojure-spec-version name arg2)
        "component" (component/create ws-path top-dir top-ns clojure-version clojure-spec-version name arg2)
        "s" (system/create ws-path top-dir top-ns clojure-version clojure-spec-version name arg2)
        "system" (system/create ws-path top-dir top-ns clojure-version clojure-spec-version name arg2)
        "w" (workspace/create (file/current-path) name arg2 (->dir arg2 arg3) clojure-version clojure-spec-version)
        "workspace" (workspace/create (file/current-path) name arg2 (->dir arg2 arg3) clojure-version clojure-spec-version))
      (println msg))))

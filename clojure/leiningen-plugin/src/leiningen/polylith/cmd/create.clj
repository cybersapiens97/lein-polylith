(ns leiningen.polylith.cmd.create
  (:require [leiningen.polylith.file :as file]
            [clojure.string :as str]
            [leiningen.polylith.version :as v]))

(defn create-dev-links [root-path dev-dir name top-name]
  (let [dir (str root-path "/" dev-dir)
        levels (count (str/split dev-dir #"/"))
        src-levels (+ levels (count (str/split top-name #"/")))
        parent-path (str/join (repeat (inc levels) "../"))
        parent-src-path (str/join (repeat src-levels "../"))
        path (str parent-path "components/" name)
        src-path (str parent-src-path "components/" name)]
    (file/create-symlink (str dir "/resources/" name)
                         (str path "/resources/" name))
    (file/create-symlink (str dir "/project-files/" name "-project.clj")
                         (str path "/project.clj"))
    (file/create-symlink (str dir "/src/" top-name)
                         (str src-path "/src/" top-name))
    (file/create-symlink (str dir "/test/" top-name)
                         (str src-path "/test/" top-name))
    (file/create-symlink (str dir "/test-int/" top-name)
                         (str src-path "/test-int/" top-name))))

(defn create-src-dirs! [root-dir top-dir src-dir]
  (file/create-dir (str root-dir "/" src-dir))
  (let [dirs (str/split top-dir #"/")
        new-dirs (map #(str root-dir "/" src-dir "/" (str/join "/" (take % dirs)))
                      (range 1 (-> dirs count inc)))]
    (if (not (zero? (count dirs)))
      (doseq [dir new-dirs]
        (file/create-dir dir)))))

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
                     (str "  :profiles {:dev {:test-paths [\"test\" \"test-int\"]}}")
                     "  :dependencies [[org.clojure/clojure \"1.9.0\"]])"]]
    (file/create-dir root-dir)
    (file/create-dir (str root-dir "/apis"))
    (file/create-dir (str root-dir "/builds"))
    (file/create-dir (str root-dir "/components"))
    (file/create-dir (str root-dir "/development"))
    (file/create-dir (str root-dir "/development/project-files"))
    (file/create-dir (str root-dir "/development/resources"))
    (create-src-dirs! root-dir top-dir "/apis/src")
    (create-src-dirs! root-dir top-dir "/development/src")
    (create-src-dirs! root-dir top-dir "/development/test")
    (create-src-dirs! root-dir top-dir "/development/test-int")
    (file/create-dir (str root-dir "/systems"))
    (file/create-file (str root-dir "/apis/project.clj") api-content)
    (file/create-file (str root-dir "/development/project.clj") dev-content)
    (file/create-symlink (str root-dir "/development/src-apis") "../apis/src")))

(defn create-component [root-path top-dir top-ns dev-dirs name]
  (let [comp-dir (str root-path "/components/" name)
        ns-name (if (zero? (count top-ns)) name (str top-ns "." name))
        top-name (if (zero? (count top-dir)) name (str top-dir "/" name))
        api-content [(str "(ns " ns-name ".api)")
                     ""
                     ";; add your functions here..."
                     "(defn myfn [x])"]
        delegate-content [(str "(ns " ns-name ".api")
                          (str "  (:require [" ns-name ".core :as core]))")
                          ""
                          ";; delegate to the implementations..."
                          "(defn myfn [x]"
                          "  (core/myfn x))"]
        core-content [(str "(ns " ns-name ".core)")
                      ""
                      ";; add your functions here..."
                      "(defn myfn [x]"
                      "  (+ 2 x)"]
        test-content [(str "(ns " ns-name ".core-test)")
                      "  (:require [clojure.test :refer :all]"
                      (str "            [" ns-name ".core :as core]")
                      ""
                      ";; add your tests here..."
                      "(deftest test-myfn"
                      "  (is (= 42 (core/myfn 40)))"]
        test-int-content [(str "(ns " ns-name ".core-test)")
                          "  (:require [clojure.test :refer :all]"
                          (str "            [" ns-name ".core :as core]")
                          ""
                          ";; add your integration tests here"]
        project-content [(str "(defproject " top-ns "/" name " \"0.1\"")
                         (str "  :description \"A " name " component\"")
                         (str "  :dependencies [[" top-ns "/apis \"1.0\"]")
                         (str "                 [org.clojure/clojure \"1.9.0\"]]")
                         (str "  :aot :all)")]]
    (file/create-dir comp-dir)
    (file/create-dir (str comp-dir "/resources"))
    (file/create-dir (str comp-dir "/resources/" name))
    (create-src-dirs! root-path top-name "apis/src")
    (create-src-dirs! root-path top-name (str "components/" name "/src"))
    (create-src-dirs! root-path top-name (str "components/" name "/test"))
    (create-src-dirs! root-path top-name (str "components/" name "/test-int"))
    (file/create-file (str comp-dir "/project.clj") project-content)
    (file/create-file (str root-path "/apis/src/" top-name "/api.clj") api-content)
    (file/create-file (str comp-dir "/src/" top-name "/api.clj") delegate-content)
    (file/create-file (str comp-dir "/src/" top-name "/core.clj") core-content)
    (file/create-file (str comp-dir "/test/" top-name "/core_test.clj") test-content)
    (file/create-file (str comp-dir "/test-int/" top-name "/core_test.clj") test-int-content)
    (doseq [dev-dir dev-dirs]
      (create-dev-links root-path dev-dir name top-name))))

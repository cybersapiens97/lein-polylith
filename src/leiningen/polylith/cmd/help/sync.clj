(ns leiningen.polylith.cmd.help.sync)

(defn help []
  (println "  These steps are performed:")
  (println)
  (println "  1. Adds missing libraries to the development environment.")
  (println "     The way it does that is to first check which components and bases")
  (println "     are part of the development environment. Then it goes through")
  (println "     those components and bases and collects a list of all their dependencies")
  (println "     from each project.clj file. That list is compared with the dependencies")
  (println "     in environments/development/project.clj and missing libraries are added.")
  (println)
  (println "  2. Makes sure that the library versions for all components")
  (println "     and bases are in sync with the library versions in")
  (println "     environments/development/project.clj.")
  (println)
  (println "  3. Makes sure that each system has a library list that reflects")
  (println "     the sum of all libraries of its components and bases.")
  (println)
  (println "  4. Adds missing components to systems if possible/needed.")
  (println "     This can be performed only if each interface belongs to exactly")
  (println "     one component, otherwise an error message is displayed.")
  (println)
  (println "  lein polylith sync [FLAG]")
  (println "    FLAG = (omitted) -> syncs all (performs all steps).")
  (println "           +deps -> performs steps 1-4.")
  (println)
  (println "  examples:")
  (println "    lein polylith sync")
  (println "    lein polylith sync +deps"))

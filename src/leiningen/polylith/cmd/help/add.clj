(ns leiningen.polylith.cmd.help.add)

(defn help []
  (println "  Adds a component to a system.")
  (println)
  (println "  lein polylith add COMPONENT SYSTEM")
  (println "     COMPONENT = Component to add.")
  (println "     SYSTEM    = Add COMPONENT to SYSTEM.")
  (println)
  (println "  example:")
  (println "    lein polylith add mycomponent mysystem"))

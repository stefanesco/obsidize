;; src/obsidize/hints.clj
;; GraalVM native-image compilation hints
;; This namespace ensures critical Clojure runtime classes are available during native compilation
(ns obsidize.hints)

(defn- class-if-present ^Class [^String cn]
  (try
    (Class/forName cn)
    (catch Throwable _ nil)))

;; Only *try* to load these. On a normal JVM run they can be nil and that's fine.
(def ^Class _core_init (class-if-present "clojure.core__init"))
(def ^Class _spec_init (class-if-present "clojure.spec.alpha__init"))
(def ^Class _core_specs_init (class-if-present "clojure.core.specs.alpha__init"))

;; Core runtime classes – also optional here
(def ^Class _rt (class-if-present "clojure.lang.RT"))
(def ^Class _pvec (class-if-present "clojure.lang.PersistentVector"))
(def ^Class _plist (class-if-present "clojure.lang.PersistentList"))

;; Force class reachability at build time
(def ^Class _persistent-list clojure.lang.PersistentList)
(def ^Class _persistent-vector clojure.lang.PersistentVector)
(def ^Class _persistent-map clojure.lang.PersistentArrayMap)


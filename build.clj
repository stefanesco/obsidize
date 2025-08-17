(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(def lib 'stefanesco/obsidize)
(def version (if-let [tag (System/getenv "RELEASE_VERSION")]
               (if (str/starts-with? tag "v") (subs tag 1) tag)
               (format "0.1.%s" (b/git-count-revs nil))))
(def class-dir "target/classes")
(def release-dir (format "target/release/%s" version))
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "%s/%s-standalone.jar" release-dir (name lib)))
(def resources-dir "resources")
(def group "stefanesco")
(def artifact "obsidize")
(def native-config-dir (format "%s/META-INF/native-image/%s/%s" resources-dir group artifact))
(def version-file-path (str resources-dir "/obsidize/version.edn"))
(def fixture-input "resources/data/local/data-2025-08-04-11-59-03-batch-0000.dms")
(def fixture-output "target/test/agent-run-output")

(defn write-version-file [_]
  (println (str "Writing version " version " to " version-file-path))
  (.mkdirs (io/file resources-dir "obsidize"))
  (spit version-file-path (pr-str {:version version})))

(defn- rm-rf [p]
  (try
    (when (fs/exists? p)
      (if (fs/directory? p)
        (fs/delete-tree p)
        (fs/delete p)))
    (catch Exception e
      ;; don't fail the task just for cleanup issues
      (println "⚠️  Could not delete" p ":" (.getMessage e)))))

(defn clean [_]
  (println "🧹 Cleaning up generated files...")
  (doseq [p ["target"
             "trivy-report.json"
             "test-output"
             "out"
             "test-e2e-vault"
             "test-vault-integration"]]
    (rm-rf p))
  (println "✅ Cleanup complete."))

(defn uber [_]
  (println "📦 Creating uberjar...")
  (clean nil)
  (.mkdirs (io/file release-dir))
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir
                  :sort :topo})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'obsidize.core})
  (println (str "✅ Uberjar created: " uber-file)))

(defn- run-with-agent
  "Run obsidize.core under the Graal tracing agent with given CLI args."
  [basis & cli-args]
  (let [agent (format "-agentlib:native-image-agent=config-merge-dir=%s"
                      native-config-dir)
        jc (b/java-command {:basis basis
                            :java-opts (into (:java-opts basis)
                                             ["-Dclojure.main.report=stderr" agent])
                            :main 'clojure.main
                            :main-args (into ["-m" "obsidize.core"] cli-args)})
        _ (println "🔎 Tracing cmd:" (clojure.string/join " " (:command-args jc)))
        res (b/process (-> jc (assoc :out :inherit :err :inherit)))]
    (when-not (zero? (:exit res))
      (println "❌ Tracing run failed with exit" (:exit res))
      (System/exit 1))))

(defn generate-native-config [_]
  (println "🤔 Generating native-image configuration...")

  (let [jv (.. (ProcessBuilder. ["java" "-version"])
               (redirectErrorStream true)
               (start))
        out (slurp (.getInputStream jv))]
    (when-not (re-find #"GraalVM" out)
      (println "❌ JAVA_HOME does not point to GraalVM. `java -version` was:\n" out)
      (println "   Ensure GraalVM is active before running tracing:")
      (println "   export JAVA_HOME=<path-to-graalvm>; export PATH=\"$JAVA_HOME/bin:$PATH\"")
      (System/exit 1)))

  ;;> un-comment to delete configuration generated (b/delete {:path native-config-dir})

  (clojure.java.io/make-parents (str native-config-dir "/_.keep"))

  ;; Use runtime deps (no :native alias for tracing)
  (let [run-basis (b/create-basis {:project "deps.edn"})]
    ;; Exercise common CLI paths
    (run-with-agent run-basis "--help")
    (run-with-agent run-basis "--version")
    (run-with-agent run-basis "--diagnostics")

    ;; Real-ish scenario using committed fixture (if present)
    (when (.exists (clojure.java.io/file fixture-input))
      (println "🧪 Tracing scenario with fixture:" fixture-input)
      (run-with-agent run-basis
                      "--verbose"
                      "--debug"
                      "--input" fixture-input
                      "--output-dir" fixture-output
                      "--dry-run")
      (run-with-agent run-basis
                      "--verbose"
                      "--debug"
                      "--input" fixture-input
                      "--output-dir" fixture-output)))
  (println "✅ Native-image configuration written to:" native-config-dir))

(defn native-image [_]
  (println "🚀 Building native image... (This may take a while)")

  ;; 1) Generate tracing config to resources/META-INF/native-image/...
  (generate-native-config nil)

  ;; 2) Build uberjar with the :native alias so graal-build-time is on the classpath,
  ;;    and so the just-generated configs are included in the jar.
  (let [native-basis (b/create-basis {:project "deps.edn" :aliases [:native]})]
    (println "📦 Creating uberjar for native image (with :native deps + configs)...")
    (clean nil) ;; clean target
    (.mkdirs (io/file release-dir))
    (write-version-file nil)
    (b/copy-dir {:src-dirs ["src" "resources"] ;; include resources => includes generated configs
                 :target-dir class-dir})
    (b/compile-clj {:basis native-basis
                    :src-dirs ["src"]
                    :class-dir class-dir
                    :ns-compile '[obsidize.core
                                  obsidize.hints
                                  clojure.spec.alpha
                                  clojure.core.specs.alpha
                                  clojure.core.server]})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis native-basis
             :main 'obsidize.core}))

  ;; 3) Build the native image from that uberjar
  (println "Starting GraalVM native-image build...")
  (let [res (b/process {:command-args
                        ["native-image"
                         "-jar" uber-file
                         "--no-fallback"
                         "-o" (format "%s/%s" release-dir (name lib))
                         ;; comment the next line for portable binaries
                         "-march=native"
                         "--verbose"
                         "--report-unsupported-elements-at-runtime"
                         "-H:+ReportExceptionStackTraces"

                         ;; ✅ Force these libs to initialize at run time (overrides clj-easy’s package init)
                         "--initialize-at-run-time=clojure.pprint__init"
                         "--initialize-at-run-time=clojure.pprint.dispatch__init"
                         "--initialize-at-run-time=clojure.data.json__init"
                         ;; Build-time: core only

                         
                         ;; 🔑 Let clj-easy configure Clojure init/layout for Graal
                         "--features=clj_easy.graal_build_time.InitClojureClasses"

                         ;; 🔧 Helpful Clojure flags for native images
                         "-Dclojure.compiler.direct-linking=true"
                         "-Dclojure.spec.skip-macros=true"

                         ;; 🧠 Initialize Clojure runtime at build time
                         "--initialize-at-build-time=clojure.lang,clojure"

                         "--initialize-at-build-time=java.util.zip"]

                        :out :inherit :err :inherit})]
    (if (zero? (:exit res))
      (println "✅ Native image built.")
      (do (println "❌ native-image failed with exit" (:exit res))
          (System/exit (:exit res))))))
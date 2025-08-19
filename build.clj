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

;; ---- Platform helpers ----
(defn- os-name [] (System/getProperty "os.name"))
(defn- arch [] (System/getProperty "os.arch"))

(defn- platform-id []
  (let [os (str/lower-case (os-name))
        a  (str/lower-case (arch))]
    (cond
      (and (re-find #"mac|darwin" os) (re-find #"aarch64|arm64" a)) "macos-aarch64"
      (and (re-find #"mac|darwin" os) (re-find #"x86_64|amd64" a))  "macos-x64"
      (and (re-find #"win" os) (re-find #"x86_64|amd64" a))         "windows-x64"
      (re-find #"linux" os)                                        (str "linux-" a)
      :else (str (clojure.string/replace os #"[^a-z0-9]+" "-") "-" a))))

;; ---- jdeps + jlink flow ----
(defn- ensure-tool [tool]
  (when-not (fs/which tool)
    (println (format "❌ Required tool '%s' not found on PATH." tool))
    (System/exit 1)))

(defn- jdeps-mods [jar]
  (ensure-tool "jdeps")
  (let [args ["jdeps" "--multi-release" "21" "--print-module-deps" jar]
        res  (b/process {:command-args args :out :capture :err :inherit})
        out  (str/trim (:out res))]
    (when-not (zero? (:exit res))
      (println "❌ jdeps failed.")
      (System/exit 1))
    (if (str/blank? out)
      "java.base"
      out)))

(defn- write-file! [path content]
  (clojure.java.io/make-parents path)
  (spit path content)
  (fs/set-posix-file-permissions path "rwxr-xr-x"))

(defn jlink-image [_]
  (println "🔧 Building jlink runtime image...")
  (when-not (fs/exists? uber-file)
    (println "ℹ️  Uberjar not found, building it...")
    (uber nil))

  (ensure-tool "jlink")
  (let [mods (jdeps-mods uber-file)
        out-dir (format "%s/%s-%s" release-dir "obsidize" (platform-id))]
    (println "🧩 Modules:" mods)
    (rm-rf out-dir)
    (let [args ["jlink"
                "--strip-debug"
                "--no-header-files"
                "--no-man-pages"
                "--compress=zip-6"
                "--add-modules" mods
                "--output" out-dir]]
      (println "🚚 Running jlink...")
      (let [res (b/process {:command-args args :out :inherit :err :inherit})]
        (when-not (zero? (:exit res))
          (println "❌ jlink failed.")
          (System/exit 1))))

    ;; Place application jar & launcher into image
    (println "📁 Finalizing image layout...")
    (let [app-dir (str out-dir "/app")
          bin-dir (str out-dir "/bin")]
      (fs/create-dirs app-dir)
      (fs/copy uber-file (str app-dir "/" (fs/file-name uber-file)) {:replace-existing true})

      ;; POSIX launcher
      (let [launcher (str bin-dir "/" artifact)
            script   (format "#!/usr/bin/env bash
DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"
\"$DIR/java\" -jar \"$DIR/../app/%s\" \"$@\"\n"
                             (fs/file-name uber-file))]
        (write-file! launcher script))

      ;; Windows launcher
      (let [launcher (str bin-dir "/" artifact ".cmd")
            script   (format "@echo off\r\nset DIR=%%~dp0\r\n\"%%DIR%%java.exe\" -jar \"%%DIR%%..\\app\\%s\" %%*\r\n"
                             (fs/file-name uber-file))]
        (write-file! launcher script)))

    (println (str "✅ jlink runtime image created at: " out-dir))
    ;; Archive it
    (let [archive-base (format "%s-%s-%s" artifact version (platform-id))
          archive-tgz (str archive-base ".tar.gz")
          archive-zip (str archive-base ".zip")
          parent-dir   (str (fs/parent out-dir)) 
          out-name     (fs/file-name out-dir)]
      (when (re-find #"windows" (platform-id))
        ;; zip on windows
        (ensure-tool "zip")
        (println "🗜️  Creating ZIP archive: " archive-zip " from " out-name " in: " parent-dir)
        (let [res (b/process {:command-args ["zip" "-r" archive-zip out-name]
                              :dir parent-dir
                              :out :inherit :err :inherit})]
          (when-not (zero? (:exit res))
            (println "❌ zip failed.")
            (System/exit 1))
          (println "📦" archive-zip)))
      
      (when-not (re-find #"windows" (platform-id))
        (ensure-tool "tar")
        (println "🗜️  Creating tar.gz archive:" archive-tgz " from " out-name " in: " parent-dir)
        (let [res (b/process {:command-args ["tar" "-czf" archive-tgz out-name]
                              :dir parent-dir
                              :out :inherit :err :inherit})]
          (when-not (zero? (:exit res))
            (println "❌ tar failed.")
            (System/exit 1))
          (println "📦" archive-tgz))))))

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

                         ;; ✅ Force these libs to initialize at build time (overrides clj-easy’s package init)
                         "--initialize-at-run-time=clojure.pprint__init,clojure.pprint.dispatch__init"
                         "--initialize-at-run-time=clojure.data.json__init"

                         ;; 🔑 Let clj-easy configure Clojure init/layout for Graal
                         "--features=clj_easy.graal_build_time.InitClojureClasses"

                         ;; 🔧 Helpful Clojure flags for native images
                         "-Dclojure.compiler.direct-linking=true"
                         "-Dclojure.spec.skip-macros=true"

                         ;; 🧠 Initialize Clojure runtime at build time
                         "--initialize-at-build-time=clojure.lang,clojure"

                         "--initialize-at-build-time=java.util.zip"
                         "--trace-class-initialization=clojure.data.json__init,clojure.pprint__init,clojure.pprint.dispatch__init"]

                        :out :inherit :err :inherit})]
    (if (zero? (:exit res))
      (println "✅ Native image built.")
      (do (println "❌ native-image failed with exit" (:exit res))
          (System/exit (:exit res))))))
(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [babashka.fs :as fs]))

(def group "stefanesco")
(def artifact "obsidize")
(def lib 'stefanesco/obsidize)

(def version (if-let [tag (System/getenv "RELEASE_VERSION")]
               (if (str/starts-with? tag "v") (subs tag 1) tag)
               (format "0.1.%s" (b/git-count-revs nil))))

(def class-dir "target/classes")
(def class-native-dir "target/native/classes")
(def release-dir (format "target/release/%s" version))

(def resources-dir "resources")
(def version-file-path (str resources-dir "/obsidize/version.edn"))

(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "%s/%s-standalone.jar" release-dir (name lib)))
(def uber-native-file (format "%s/%s-native.jar" release-dir (name lib)))

(def native-config-dir (format "%s/META-INF/native-image/%s/%s" resources-dir group artifact))
(def native-artifact (str artifact "-native"))
(def native-bin   (str release-dir "/" native-artifact))

(def fixture-input "resources/data/local/data-2025-08-04-11-59-03-batch-0000.dms")
(def fixture-output "target/test/agent-run-output")

;; ---- Platform helpers ----
(defn- os-name [] (System/getProperty "os.name"))
(defn- arch [] (System/getProperty "os.arch"))
(defn- posix? [] (not (re-find #"(?i)windows" (os-name))))

(defn- platform-id []
  (let [os (str/lower-case (os-name))
        a  (str/lower-case (arch))]
    (cond
      (and (re-find #"mac|darwin" os) (re-find #"aarch64|arm64" a)) "macos-aarch64"
      (and (re-find #"mac|darwin" os) (re-find #"x86_64|amd64" a))  "macos-x64"
      (and (re-find #"win" os) (re-find #"x86_64|amd64" a))         "windows-x64"
      (re-find #"linux" os)                                        (str "linux-" a)
      :else (str (clojure.string/replace os #"[^a-z0-9]+" "-") "-" a))))

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
      (println "⚠️  Could not delete" p ":" (.getMessage e)))))

(defn clean [_]
  (println "🧹 Cleaning up generated files...")
  (doseq [p ["target" "trivy-report.json" "test-output" "out" "test-e2e-vault" "test-vault-integration"]]
    (rm-rf p))
  (println "✅ Cleanup complete."))

;; --------------------------------------------------------------------
;; RUNTIME UBER (for jlink)
;; --------------------------------------------------------------------
(defn uber-runtime [_]
  (println "📦 Creating runtime uberjar...")
  (fs/create-dirs release-dir)
  (write-version-file nil)
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
  (println "✅ Runtime uberjar:" uber-file))

;; --------------------------------------------------------------------
;; NATIVE UBER (with :native alias) + TRACING AGENT
;; --------------------------------------------------------------------
(defn- run-with-agent [basis & cli-args]
  (let [agent (format "-agentlib:native-image-agent=config-merge-dir=%s" native-config-dir)
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
  (clojure.java.io/make-parents (str native-config-dir "/_.keep"))
  (let [run-basis (b/create-basis {:project "deps.edn"})]
    (run-with-agent run-basis "--help")
    (run-with-agent run-basis "--version")
    (run-with-agent run-basis "--diagnostics")
    (when (.exists (clojure.java.io/file fixture-input))
      (println "🧪 Tracing with fixture:" fixture-input)
      (run-with-agent run-basis "--verbose" "--debug" "--input" fixture-input "--output-dir" fixture-output "--dry-run")
      (run-with-agent run-basis "--verbose" "--debug" "--input" fixture-input "--output-dir" fixture-output)))
  (println "✅ Native-image configuration written to:" native-config-dir))

(defn uber-native [_]
  (println "📦 Creating native uberjar (with :native alias + configs)...")
  (let [native-basis (b/create-basis {:project "deps.edn" :aliases [:native]})]
    (b/copy-dir {:src-dirs ["src" "resources"] ;; includes generated configs
                 :target-dir class-native-dir})
    (b/compile-clj {:basis native-basis
                    :src-dirs ["src"]
                    :class-dir class-native-dir
                    :sort :topo
                    ;; upfront compile common problematic namespaces
                    :ns-compile '[obsidize.core
                                  obsidize.hints
                                  clojure.spec.alpha
                                  clojure.core.specs.alpha
                                  clojure.core.server]})
    (b/uber {:class-dir class-native-dir
             :uber-file uber-native-file
             :basis native-basis
             :main 'obsidize.core}))
  (println "✅ Native uberjar:" uber-native-file))

(defn- native-flags []
  [;; Core inputs
   "-jar" uber-native-file
   "--no-fallback"
   "-o" (format "%s/%s" release-dir native-artifact)

   ;; comment next line for portable binaries
   "-march=native"

   ;; Logging / diagnostics
   "--verbose"
   "--report-unsupported-elements-at-runtime"
   "-H:+ReportExceptionStackTraces"

   ;; Clojure + Graal
   "--features=clj_easy.graal_build_time.InitClojureClasses"
   "-Dclojure.compiler.direct-linking=true"
   "-Dclojure.spec.skip-macros=true"

   ;; Class init tuning (adjust as needed)
   "--initialize-at-build-time=clojure.lang,clojure,java.util.zip"
   "--initialize-at-run-time=clojure.pprint__init,clojure.pprint.dispatch__init,clojure.data.json__init"

   ;; Optional tracing during build init
   "--trace-class-initialization=clojure.data.json__init,clojure.pprint__init,clojure.pprint.dispatch__init"])

(defn native-image [_]
  (println "🚀 Building native image... (This may take a while)")
  ;; Ensure configs are fresh
  (generate-native-config nil)
  ;; Build the uber for native image
  (uber-native nil)
  ;; Invoke native-image with the **native** uber jar
  (println "Starting GraalVM native-image build...")
  (let [args (into ["native-image"] (native-flags))
        res  (b/process {:command-args args :out :inherit :err :inherit})]
    (if (zero? (:exit res))
      (println "✅ Native image built at:" native-bin)
      (do (println "❌ native-image failed with exit" (:exit res))
          (System/exit (:exit res))))))

;; --------------------------------------------------------------------
;; jlink (from runtime uber), optionally bundle native on macOS
;; --------------------------------------------------------------------
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
    (if (str/blank? out) "java.base" out)))

(defn- write-file! [path content]
  (clojure.java.io/make-parents path)
  (spit path content)
  (when (posix?) (fs/set-posix-file-permissions path "rwxr-xr-x")))

(defn jlink-image [_]
  (println "🔧 Building jlink runtime image...")
  (when-not (fs/exists? uber-file)
    (println "ℹ️  Runtime uberjar not found, building it...")
    (uber-runtime nil))

  (ensure-tool "jlink")
  (let [mods        (jdeps-mods uber-file)
        plat        (platform-id)
        out-dir     (format "%s/%s-%s" release-dir "obsidize" plat)
        app-dir     (str out-dir "/app")
        bin-dir     (str out-dir "/bin")
        archive-base (format "%s-%s-%s" artifact version plat)
        tgz-path    (str release-dir "/" archive-base ".tar.gz")
        zip-path    (str release-dir "/" archive-base ".zip")
        image-parent (str (fs/parent out-dir))
        image-name   (fs/file-name out-dir)]

    (println "🧩 Modules:" mods)
    (rm-rf out-dir)

    (let [args ["jlink" "--strip-debug" "--no-header-files" "--no-man-pages"
                "--compress=zip-6" "--add-modules" mods "--output" out-dir]]
      (println "🚚 Running jlink...")
      (let [res (b/process {:command-args args :out :inherit :err :inherit})]
        (when-not (zero? (:exit res))
          (println "❌ jlink failed.")
          (System/exit 1))))

    ;; layout
    (println "📁 Finalizing image layout...")
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
      (write-file! launcher script))

    ;; If native binary exists and we’re on macOS, include it
    (when (and (re-find #"^macos-" plat)
               (fs/exists? native-bin))
      (let [dest (str bin-dir "/" native-artifact)]
        (println "➕ Including native executable in image:" dest)
        (fs/copy native-bin dest {:replace-existing true})
        (when (posix?) (fs/set-posix-file-permissions dest "rwxr-xr-x"))))

    (println (str "✅ jlink runtime image created at: " out-dir))

    ;; Archive to release-dir
    (if (re-find #"windows" plat)
      (do
        (if (fs/which "zip")
          (do
            (println "🗜️  Creating ZIP archive:" zip-path)
            (let [res (b/process {:command-args ["zip" "-r" zip-path image-name]
                                  :dir image-parent :out :inherit :err :inherit})]
              (when-not (zero? (:exit res))
                (println "❌ zip failed.")
                (System/exit 1))))
          (do
            (ensure-tool "tar") ;; tar.exe -a -> zip
            (println "🗜️  Creating ZIP archive via tar.exe:" zip-path)
            (let [res (b/process {:command-args ["tar" "-a" "-cf" zip-path image-name]
                                  :dir image-parent :out :inherit :err :inherit})]
              (when-not (zero? (:exit res))
                (println "❌ tar(zip) failed.")
                (System/exit 1)))))
        (when (fs/which "shasum")
          (spit (str zip-path ".sha256")
                (-> (b/process {:command-args ["shasum" "-a" "256" zip-path]
                                :out :capture :err :inherit})
                    :out (str/split #"\s+") first))))
      (do
        (ensure-tool "tar")
        (println "🗜️  Creating tar.gz archive:" tgz-path)
        (let [res (b/process {:command-args ["tar" "-czf" tgz-path "-C" image-parent image-name]
                              :out :inherit :err :inherit})]
          (when-not (zero? (:exit res))
            (println "❌ tar failed.")
            (System/exit 1)))
        (when (fs/which "shasum")
          (spit (str tgz-path ".sha256")
                (-> (b/process {:command-args ["shasum" "-a" "256" tgz-path]
                                :out :capture :err :inherit})
                    :out (str/split #"\s+") first)))))))

;; --------------------------------------------------------------------
;; Orchestrators
;; --------------------------------------------------------------------
(defn build-all [_]
  ;; plain runtime uber for jlink
  (uber-runtime nil)
  ;; native image (own jar + flags)
  (native-image nil)
  ;; jlink (includes native on macOS if present)
  (jlink-image nil)
  (println "🎁 Done: runtime uber, native image, and jlink archives."))
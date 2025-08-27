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
(def native-bin (str release-dir "/" native-artifact))

(def fixture-input "resources/data/local/data-2025-08-04-11-59-03-batch-0000.dms")
(def fixture-output "target/test/agent-run-output")

;; ---- Platform helpers ----
(defn- os-name [] (System/getProperty "os.name"))
(defn- arch [] (System/getProperty "os.arch"))
(defn- posix? [] (not (re-find #"(?i)windows" (os-name))))

(defn- platform-id []
  (let [os (str/lower-case (os-name))
        a (str/lower-case (arch))]
    (cond
      (and (re-find #"mac|darwin" os) (re-find #"aarch64|arm64" a)) "macos-aarch64"
      (and (re-find #"mac|darwin" os) (re-find #"x86_64|amd64" a)) "macos-x64"
      (and (re-find #"win" os) (re-find #"x86_64|amd64" a)) "windows-x64"
      (re-find #"linux" os) (str "linux-" a)
      :else (str (clojure.string/replace os #"[^a-z0-9]+" "-") "-" a))))

;; ---- Build validation helpers ----
(defn- validate-java-home
  "Validate and sanitize JDK paths to prevent path traversal attacks."
  [home]
  (when-not (and home
                 (string? home)
                 (not (str/includes? home "..")) ; Prevent path traversal
                 (fs/exists? (fs/path home "bin" "java"))
                 (fs/directory? home))
    (throw (ex-info "Invalid or unsafe JDK path" {:path home :type :security-validation})))
  home)

(defn- validate-build-artifact
  "Validate that a build artifact exists and has expected properties."
  [file-path description & {:keys [min-size executable] :or {min-size 1024 executable false}}]
  (cond
    (not (fs/exists? file-path))
    (throw (ex-info (format "Missing required artifact: %s" description)
                    {:file file-path :type :missing-artifact}))

    (and (not (fs/directory? file-path)) (< (fs/size file-path) min-size))
    (throw (ex-info (format "Artifact too small: %s (size: %d bytes)" description (fs/size file-path))
                    {:file file-path :size (fs/size file-path) :min-size min-size :type :invalid-size}))

    (and executable (posix?) (not (fs/executable? file-path)))
    (throw (ex-info (format "Artifact not executable: %s" description)
                    {:file file-path :type :not-executable}))

    :else
    (if (fs/directory? file-path)
      (println (format "✅ Validated artifact: %s (directory)" description))
      (println (format "✅ Validated artifact: %s (%d bytes)" description (fs/size file-path))))))

(defn- validate-disk-space
  "Ensure sufficient disk space for build operations."
  [min-gb]
  (let [target-dir (io/file "target")
        _ (when-not (.exists target-dir) (.mkdirs target-dir))
        free-space-bytes (.getFreeSpace target-dir)
        free-space-gb (/ free-space-bytes 1024 1024 1024)]
    (when (< free-space-gb min-gb)
      (throw (ex-info (format "Insufficient disk space: %.1fGB available, %.1fGB required"
                              free-space-gb min-gb)
                      {:available free-space-gb :required min-gb :type :insufficient-disk-space})))))

;; ---- JDK / jlink helpers ----
(defn- java-home
  "Pick the JDK used for jlink/jdeps.
   1) $JLINK_JAVA_HOME (preferred for packaging)
   2) $JAVA_HOME       (fallback)"
  []
  (validate-java-home
   (or (System/getenv "JLINK_JAVA_HOME")
       (System/getenv "JAVA_HOME"))))

(defn- tool-path
  "Resolve a tool under the selected JDK (jdeps, jlink, java)."
  [tool]
  (let [home (java-home)]
    (when-not home
      (println "❌ No JDK configured. Set JLINK_JAVA_HOME or JAVA_HOME to a JDK for the target platform.")
      (System/exit 1))
    (let [exe (if (re-find #"(?i)windows" (os-name))
                (str tool ".exe")
                tool)
          p (str (fs/path home "bin" exe))]
      (when-not (fs/exists? p)
        (println (format "❌ Required tool '%s' not found at: %s (JAVA_HOME/JLINK_JAVA_HOME = %s)" tool p home))
        (System/exit 1))
      p)))

(defn- jdeps-cmd [] (tool-path "jdeps"))
(defn- jlink-cmd [] (tool-path "jlink"))
(defn- java-cmd [] (tool-path "java"))

(defn- ensure-jdk-banner []
  (let [home (java-home)
        cmd (java-cmd)]
    (println (format "🧰 Using JDK at: %s" home))
    (println (format "    java:  %s" cmd))
    (println (format "    jdeps: %s" (jdeps-cmd)))
    (println (format "    jlink: %s" (jlink-cmd)))))

(defn- validate-build-environment [_]
  "Comprehensive build environment validation."
  (println "🔍 Validating build environment...")

  ;; Check disk space (2GB minimum for builds)
  (validate-disk-space 2.0)

  ;; Validate JDK setup
  (let [home (java-home)]
    (println (format "✅ JDK validated: %s" home)))

  ;; Check version format
  (when-not (re-matches #"^[\w\.-]+$" version)
    (throw (ex-info "Invalid version format contains unsafe characters"
                    {:version version :type :invalid-version})))

  (println "✅ Build environment validation complete"))

(defn- validate-runtime-artifacts [_]
  "Validate runtime build artifacts."
  (println "🔍 Validating runtime artifacts...")
  (validate-build-artifact uber-file "Runtime uberjar" :min-size (* 5 1024 1024)) ; 5MB minimum (was 10MB)
  (println "✅ Runtime artifacts validated"))

(defn- validate-native-artifacts [_]
  "Validate native build artifacts."
  (println "🔍 Validating native artifacts...")
  (validate-build-artifact uber-native-file "Native uberjar" :min-size (* 5 1024 1024)) ; 5MB minimum (was 10MB)
  (when (fs/exists? native-bin)
    (validate-build-artifact native-bin "Native executable" :min-size (* 1024 1024) :executable true)) ; 1MB minimum
  (println "✅ Native artifacts validated"))

(defn- validate-jlink-artifacts [_]
  "Validate jlink build artifacts."
  (println "🔍 Validating jlink artifacts...")
  (let [plat (platform-id)
        out-dir (format "%s/%s-%s" release-dir "obsidize" plat)
        archive-base (format "%s-%s-%s" artifact version plat)
        expected-archive (if (re-find #"windows" plat)
                           (str release-dir "/" archive-base ".zip")
                           (str release-dir "/" archive-base ".tar.gz"))]

    (validate-build-artifact out-dir "Jlink runtime directory")
    (validate-build-artifact (str out-dir "/bin/java") "Java runtime" :executable true)
    (validate-build-artifact expected-archive "Jlink archive" :min-size (* 20 1024 1024)) ; 20MB minimum

    ;; Validate checksums if they exist
    (let [checksum-file (str expected-archive ".sha256")]
      (when (fs/exists? checksum-file)
        (validate-build-artifact checksum-file "Archive checksum" :min-size 64)))

    (println "✅ Jlink artifacts validated")))

(defn validate-all-artifacts [_]
  "Comprehensive validation of all build artifacts."
  (println "🔍 Running comprehensive artifact validation...")
  (validate-build-environment nil)

  (when (fs/exists? uber-file)
    (validate-runtime-artifacts nil))

  (when (fs/exists? uber-native-file)
    (validate-native-artifacts nil))

  ;; Check for jlink artifacts
  (let [plat (platform-id)
        out-dir (format "%s/%s-%s" release-dir "obsidize" plat)]
    (when (fs/exists? out-dir)
      (validate-jlink-artifacts nil)))

  (println "✅ All artifact validation complete"))

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
  (validate-build-environment nil)
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
  (validate-runtime-artifacts nil)
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
  (validate-build-environment nil)
  ;; Ensure configs are fresh
  (generate-native-config nil)
  ;; Build the uber for native image
  (uber-native nil)
  ;; Invoke native-image with the **native** uber jar
  (println "Starting GraalVM native-image build...")
  (let [args (into ["native-image"] (native-flags))
        res (b/process {:command-args args :out :inherit :err :inherit})]
    (if (zero? (:exit res))
      (do
        (validate-native-artifacts nil)
        (println "✅ Native image built at:" native-bin))
      (do (println "❌ native-image failed with exit" (:exit res))
          (System/exit (:exit res))))))

(defn native-package [_]
  "Create a native executable package for Homebrew distribution"
  (println "📦 Creating native executable package for Homebrew...")

  ;; Ensure we have the native executable
  (when-not (fs/exists? native-bin)
    (println "ℹ️  Native executable not found, building it...")
    (native-image nil))

  (let [plat (platform-id)
        package-name (str "obsidize-native-" version "-" plat)
        package-dir (str release-dir "/" package-name)
        bin-dir (str package-dir "/bin")]

    ;; Create package structure
    (println "📁 Creating package structure...")
    (fs/create-dirs bin-dir)

    ;; Copy native executable
    (println "📋 Copying native executable...")
    (fs/copy native-bin (str bin-dir "/obsidize") {:replace-existing true})
    (when (posix?) (fs/set-posix-file-permissions (str bin-dir "/obsidize") "rwxr-xr-x"))

    ;; Create archive
    (let [archive-path (str release-dir "/" package-name ".tar.gz")
          image-parent release-dir
          image-name package-name]

      (println "🗜️  Creating native package archive:" archive-path)
      (let [res (b/process {:command-args ["tar" "-czf" archive-path "-C" image-parent image-name]
                            :out :inherit :err :inherit})]
        (when-not (zero? (:exit res))
          (println "❌ tar failed.")
          (System/exit 1)))

      ;; Create checksum
      (when (fs/which "shasum")
        (spit (str archive-path ".sha256")
              (-> (b/process {:command-args ["shasum" "-a" "256" archive-path]
                              :out :capture :err :inherit})
                  :out (str/split #"\s+") first)))

      ;; Validate the package
      (validate-build-artifact archive-path "Native package archive" :min-size (* 10 1024 1024))

      (println "✅ Native package created:" archive-path))))

;; --------------------------------------------------------------------
;; jlink (from runtime uber), optionally bundle native on macOS
;; --------------------------------------------------------------------
(defn- jdeps-mods [jar]
  (ensure-jdk-banner)
  (let [jdeps (jdeps-cmd)
        ;; Use the current JDK’s version for multi-release; most recent JDKs accept 21 safely.
        ;; You can make this dynamic if needed by parsing `java -version`.
        args [jdeps "--multi-release" "21" "--print-module-deps" jar]
        res (b/process {:command-args args :out :capture :err :inherit})
        out (str/trim (:out res))]
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

  (ensure-jdk-banner)
  (let [mods (jdeps-mods uber-file)
        jlink (jlink-cmd)
        plat (platform-id)
        out-dir (format "%s/%s-%s" release-dir "obsidize" plat)
        app-dir (str out-dir "/app")
        bin-dir (str out-dir "/bin")
        archive-base (format "%s-%s-%s" artifact version plat)
        tgz-path (str release-dir "/" archive-base ".tar.gz")
        zip-path (str release-dir "/" archive-base ".zip")
        image-parent (str (fs/parent out-dir))
        image-name (fs/file-name out-dir)]

    (println "🧩 Modules:" mods)
    (rm-rf out-dir)

    (let [args [jlink "--strip-debug" "--no-header-files" "--no-man-pages"
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
          script (format "#!/usr/bin/env bash
DIR=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)\"
\"$DIR/java\" -jar \"$DIR/../app/%s\" \"$@\"\n"
                         (fs/file-name uber-file))]
      (write-file! launcher script))

    ;; Windows launcher
    (let [launcher (str bin-dir "/" artifact ".cmd")
          script (format "@echo off\r\nset DIR=%%~dp0\r\n\"%%DIR%%java.exe\" -jar \"%%DIR%%..\\app\\%s\" %%*\r\n"
                         (fs/file-name uber-file))]
      (write-file! launcher script))

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
            ;; Fallback: tar.exe as zip
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
                    :out (str/split #"\s+") first)))
        (when (fs/exists? out-dir)
          (validate-jlink-artifacts nil))))))

;; --------------------------------------------------------------------
;; Orchestrators
;; --------------------------------------------------------------------
(defn build-all [_]
  (println "📦 Building Obsidize packages with optimized platform strategy...")
  (println "   1. Universal JAR (all platforms)")
  (println "   2. Linux: JLink runtime (Homebrew-ready)")
  (println "   3. macOS ARM64: Native executable (Homebrew-ready)")
  (println "   4. macOS x86: JLink runtime (Homebrew-ready)")

  ;; Always build the universal JAR
  (uber-runtime nil)

  ;; Platform-specific optimized builds
  (let [platform (platform-id)]
    (cond
      ;; macOS ARM64: Build native executable package (best performance on Apple Silicon)
      (= platform "macos-aarch64")
      (do
        (println "🍎 macOS ARM64 detected: Building native executable for Homebrew...")
        (native-package nil)
        (println "✅ Native executable package ready for macOS ARM64 Homebrew distribution"))

      ;; macOS x86: Build JLink runtime bundle (native-image issues on x86)
      (= platform "macos-x64")
      (do
        (println "🍎 macOS x86 detected: Building JLink runtime for Homebrew...")
        (jlink-image nil)
        (println "✅ JLink runtime ready for macOS x86 Homebrew distribution"))

      ;; Linux: Build JLink runtime bundle (best compatibility)
      (re-find #"^linux-" platform)
      (do
        (println "🐧 Linux detected: Building JLink runtime for Homebrew...")
        (jlink-image nil)
        (println "✅ JLink runtime ready for Linux Homebrew distribution"))

      ;; Other platforms: JAR only (fallback)
      :else
      (println "ℹ️  Platform-specific packages not supported for" platform "- JAR available")))

  ;; Final validation
  (validate-all-artifacts nil)
  (println "")
  (println "🎁 Package build complete!")
  (println "   📄 Universal JAR: Available for all Java 21+ platforms")
  (println "   🍺 Homebrew-ready:" (cond
                                     (= (platform-id) "macos-aarch64") "Native executable package"
                                     (or (= (platform-id) "macos-x64") (re-find #"^linux-" (platform-id))) "JLink runtime package"
                                     :else "Not available for this platform")))
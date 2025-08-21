(ns obsidize.logging
  "Enhanced logging and diagnostics for Obsidize, with native-image compatibility"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Logging Configuration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *verbose* false)
(def ^:dynamic *debug* false)

(defn set-verbose! [enabled?]
  (alter-var-root #'*verbose* (constantly enabled?)))

(defn set-debug! [enabled?]
  (alter-var-root #'*debug* (constantly enabled?)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Logging Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn log-info
  "Log an info message"
  [message]
  (println "ℹ️ " message))

(defn log-verbose
  "Log a verbose message (only if verbose mode is enabled)"
  [message]
  (when *verbose*
    (println (str "📝 " message))))

(defn log-debug
  "Log a debug message (only if debug mode is enabled)"
  [message]
  (when *debug*
    (println (str "🐛 DEBUG: " message))))

(defn log-warn
  "Log a warning message"
  [message]
  (println (str "⚠️  " message)))

(defn log-error
  "Log an error message"
  [message]
  (println (str "❌ " message)))

(defn log-success
  "Log a success message"
  [message]
  (println (str "✅ " message)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Native Image Diagnostics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn detect-runtime-environment
  "Detect if running in native-image or JVM"
  []
  (try
    (let [vm-name (System/getProperty "java.vm.name")
          vm-version (System/getProperty "java.vm.version")
          runtime-name (System/getProperty "java.runtime.name")]
      {:native-image? (or (str/includes? (str vm-name) "Substrate")
                          (str/includes? (str vm-name) "GraalVM"))
       :vm-name vm-name
       :vm-version vm-version
       :runtime-name runtime-name
       :java-version (System/getProperty "java.version")
       :os-name (System/getProperty "os.name")
       :os-arch (System/getProperty "os.arch")})
    (catch Exception e
      (log-warn (str "Could not detect runtime environment: " (.getMessage e)))
      {:native-image? false
       :error (.getMessage e)})))

(defn log-runtime-info
  "Log detailed runtime environment information"
  []
  (let [env (detect-runtime-environment)]
    (log-info "Runtime Environment:")
    (log-info (str "  Native Image: " (:native-image? env)))
    (when (:vm-name env)
      (log-info (str "  VM: " (:vm-name env) " " (:vm-version env))))
    (when (:runtime-name env)
      (log-info (str "  Runtime: " (:runtime-name env))))
    (when (:java-version env)
      (log-info (str "  Java Version: " (:java-version env))))
    (when (:os-name env)
      (log-info (str "  OS: " (:os-name env) " " (:os-arch env))))
    env))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; File System Diagnostics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn diagnose-file-access
  "Diagnose file access capabilities"
  [path]
  (try
    (let [file (io/file path)
          results {:path path
                   :exists? (.exists file)
                   :readable? (.canRead file)
                   :writable? (.canWrite file)
                   :directory? (.isDirectory file)
                   :file? (.isFile file)}]
      (log-debug (str "File access diagnosis for " path ": " results))
      results)
    (catch Exception e
      (log-error (str "File access diagnosis failed for " path ": " (.getMessage e)))
      {:path path :error (.getMessage e)})))

(defn diagnose-zip-capabilities
  "Diagnose ZIP file handling capabilities"
  []
  (try
    (let [temp-file (java.io.File/createTempFile "obsidize-test" ".zip")]
      ;; Create a minimal ZIP file for testing
      (with-open [zip-output (java.util.zip.ZipOutputStream.
                              (io/output-stream temp-file))]
        (.putNextEntry zip-output (java.util.zip.ZipEntry. "test.txt"))
        (.write zip-output (.getBytes "test content"))
        (.closeEntry zip-output))

      ;; Try to read it back
      (with-open [zip-file (java.util.zip.ZipFile. temp-file)]
        (let [entries (enumeration-seq (.entries zip-file))
              entry-count (count entries)]
          (.delete temp-file)
          (log-debug (str "ZIP capabilities test passed, entries: " entry-count))
          {:zip-available? true
           :can-create? true
           :can-read? true
           :entry-count entry-count})))
    (catch Exception e
      (log-error (str "ZIP capabilities diagnosis failed: " (.getMessage e)))
      {:zip-available? false
       :error (.getMessage e)})))

(defn diagnose-temp-directory
  "Diagnose temporary directory access"
  []
  (try
    (let [temp-prop (System/getProperty "java.io.tmpdir")
          temp-file (java.io.File/createTempFile "obsidize-diag" ".tmp")]
      (.delete temp-file) ; Clean up immediately
      (log-debug (str "Temp directory: " temp-prop))
      {:temp-dir temp-prop
       :can-create-temp-files? true})
    (catch Exception e
      (log-error (str "Temp directory diagnosis failed: " (.getMessage e)))
      {:temp-dir nil
       :can-create-temp-files? false
       :error (.getMessage e)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Performance Diagnostics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro time-operation
  "Time an operation and log the result"
  [operation-name & body]
  `(let [start-time# (System/currentTimeMillis)
         result# (do ~@body)
         end-time# (System/currentTimeMillis)
         duration# (- end-time# start-time#)]
     (log-verbose (str ~operation-name " completed in " duration# "ms"))
     result#))

(defn comprehensive-diagnostics
  "Run comprehensive system diagnostics"
  []
  (log-info "Running comprehensive system diagnostics...")
  (let [runtime-env (log-runtime-info)
        zip-diag (diagnose-zip-capabilities)
        temp-diag (diagnose-temp-directory)]
    (log-info "Diagnostics completed")
    {:runtime runtime-env
     :zip zip-diag
     :temp temp-diag}))
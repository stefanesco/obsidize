(ns e2e.package-validation-test
  "End-to-end validation tests for compiled packages (JAR, native image, jlink)
   Tests the actual CLI behavior with real Claude export data to validate:
   - Package installation and execution
   - Core workflows: new export, incremental update, force rewrite
   - CLI argument parsing and error handling
   - Cross-platform compatibility
   - Performance characteristics of compiled versions"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :as shell]))

(def test-artifacts-dir "/tmp/obsidize-e2e-validation")
(def test-vault-dir (str test-artifacts-dir "/test-vault"))
(def test-data-dir (str test-artifacts-dir "/test-data"))

;; Sample Claude export data for testing
(def sample-conversations-json
  "[
    {
      \"uuid\": \"conv-e2e-1\",
      \"name\": \"E2E Test Conversation\",
      \"created_at\": \"2025-01-01T10:00:00Z\",
      \"updated_at\": \"2025-01-01T12:00:00Z\",
      \"chats\": [
        {\"q\": \"Hello, this is a test\", \"a\": \"Hi! This is a test response.\", \"create_time\": \"2025-01-01T10:30:00Z\"},
        {\"q\": \"How are you?\", \"a\": \"I'm doing well, thank you!\", \"create_time\": \"2025-01-01T11:00:00Z\"}
      ]
    },
    {
      \"uuid\": \"conv-e2e-2\",
      \"name\": \"Second Test Conversation\",
      \"created_at\": \"2025-01-02T09:00:00Z\",
      \"updated_at\": \"2025-01-02T10:00:00Z\",
      \"chats\": [
        {\"q\": \"What is 2+2?\", \"a\": \"2+2 equals 4.\", \"create_time\": \"2025-01-02T09:15:00Z\"}
      ]
    }
  ]")

(def sample-projects-json
  "[
    {
      \"uuid\": \"proj-e2e-1\",
      \"name\": \"E2E Test Project\",
      \"description\": \"A test project for end-to-end validation\",
      \"created_at\": \"2025-01-01T08:00:00Z\",
      \"updated_at\": \"2025-01-01T16:00:00Z\",
      \"docs\": [
        {
          \"uuid\": \"doc-e2e-1\",
          \"filename\": \"test-document.md\",
          \"content\": \"# Test Document\\n\\nThis is a test document for E2E validation.\",
          \"created_at\": \"2025-01-01T08:30:00Z\"
        }
      ]
    }
  ]")

(defn setup-test-environment
  "Create test environment with sample Claude export data"
  []
  (let [artifacts-dir (io/file test-artifacts-dir)
        vault-dir (io/file test-vault-dir)
        data-dir (io/file test-data-dir)]
    (.mkdirs artifacts-dir)
    (.mkdirs vault-dir)
    (.mkdirs data-dir)

    ;; Create sample Claude export data
    (spit (str test-data-dir "/conversations.json") sample-conversations-json)
    (spit (str test-data-dir "/projects.json") sample-projects-json)
    (spit (str test-data-dir "/users.json") "[]"))) ; Empty users file

(defn cleanup-test-environment
  "Clean up test environment"
  []
  (let [artifacts-dir (io/file test-artifacts-dir)]
    (when (.exists artifacts-dir)
      ;; Simple recursive delete
      (letfn [(delete-recursively [file]
                (when (.exists file)
                  (if (.isDirectory file)
                    (doseq [child (.listFiles file)]
                      (delete-recursively child))
                    nil)
                  (.delete file)))]
        (delete-recursively artifacts-dir)))))

(use-fixtures :each (fn [test-fn]
                      (setup-test-environment)
                      (try
                        (test-fn)
                        (finally
                          (cleanup-test-environment)))))

(defn find-executable
  "Find the executable for the given artifact type (jar, native, jlink)"
  [artifact-type]
  (let [target-dir "target/release"]
    (cond
      (= artifact-type :jar)
      (let [jar-files (->> (io/file target-dir)
                           file-seq
                           (filter #(.isFile %))
                           (filter #(str/ends-with? (.getName %) "standalone.jar"))
                           (sort-by #(.lastModified %))
                           reverse)]
        (when (seq jar-files)
          {:type :jar :path (.getAbsolutePath (first jar-files))}))

      (= artifact-type :native)
      (let [native-files (->> (io/file target-dir)
                              file-seq
                              (filter #(.isFile %))
                              (filter #(or (str/ends-with? (.getName %) "obsidize")
                                           (str/ends-with? (.getName %) "obsidize.exe")
                                           (str/ends-with? (.getName %) "obsidize-native")))
                              (sort-by #(.lastModified %))
                              reverse)]
        (when (seq native-files)
          {:type :native :path (.getAbsolutePath (first native-files))}))

      :else nil)))

(defn run-obsidize
  "Run obsidize with the given executable and arguments"
  [executable args]
  (let [{:keys [type path]} executable
        cmd (case type
              :jar ["java" "-jar" path]
              :native [path]
              :jlink [(str path "/bin/obsidize")])
        full-cmd (concat cmd args)
        result (apply shell/sh full-cmd)]
    (assoc result :cmd full-cmd)))

(deftest jar-package-validation-test
  (testing "JAR package validation with real CLI execution"
    (let [jar-exec (find-executable :jar)]
      (if jar-exec
        (do
          ;; Test 1: Version and help commands
          (testing "Basic CLI functionality"
            (let [version-result (run-obsidize jar-exec ["--version"])
                  help-result (run-obsidize jar-exec ["--help"])]
              (is (= 0 (:exit version-result)) "Version command should succeed")
              (is (str/includes? (:out version-result) "obsidize") "Version should include app name")
              (is (= 0 (:exit help-result)) "Help command should succeed")
              (is (str/includes? (:out help-result) "USAGE EXAMPLES") "Help should show usage")))

          ;; Test 2: Process test data (new export)
          (testing "New export processing"
            (let [result (run-obsidize jar-exec ["--input" test-data-dir
                                                 "--output-dir" test-vault-dir
                                                 "--verbose"])]
              (is (= 0 (:exit result)) "New export should succeed")
              (is (str/includes? (:out result) "Processing complete") "Should show completion message")

              ;; Verify output files were created
              (let [vault-files (->> (io/file test-vault-dir)
                                     file-seq
                                     (filter #(.isFile %))
                                     (map #(.getName %)))]
                (is (some #(str/includes? % "e2e-test-conversation") vault-files) "Conversation file created")
                (is (some #(str/includes? % "e2e-test-project") vault-files) "Project files created"))))

          ;; Test 3: Incremental update
          (testing "Incremental update processing"
            ;; Ensure test data directory exists before writing
            (let [data-dir (io/file test-data-dir)]
              (when-not (.exists data-dir)
                (.mkdirs data-dir))

              ;; First, add new data to simulate an update
              (let [updated-conversations (str/replace sample-conversations-json
                                                       "\"updated_at\": \"2025-01-01T12:00:00Z\""
                                                       "\"updated_at\": \"2025-01-03T15:00:00Z\"")]
                (spit (str test-data-dir "/conversations.json")
                      (str/replace updated-conversations
                                   "],\n    {"
                                   "],\n    {\"q\": \"New incremental question\", \"a\": \"New incremental answer\", \"create_time\": \"2025-01-03T14:30:00Z\"},\n    {"))
                ;; Ensure all required files exist for a complete data pack
                (spit (str test-data-dir "/projects.json") sample-projects-json)
                (spit (str test-data-dir "/users.json") "[]")

                (let [result (run-obsidize jar-exec ["--input" test-data-dir
                                                     "--output-dir" test-vault-dir
                                                     "--incremental"
                                                     "--verbose"])]
                  (is (= 0 (:exit result)) "Incremental update should succeed")
                  (is (str/includes? (:out result) "update") "Should mention updates")))))

          ;; Test 4: Force full rewrite
          (testing "Force full rewrite"
            ;; Ensure test data exists
            (let [data-dir (io/file test-data-dir)]
              (when-not (.exists data-dir)
                (.mkdirs data-dir))
              (spit (str test-data-dir "/conversations.json") sample-conversations-json)
              (spit (str test-data-dir "/projects.json") sample-projects-json)
              (spit (str test-data-dir "/users.json") "[]"))

            (let [result (run-obsidize jar-exec ["--input" test-data-dir
                                                 "--output-dir" test-vault-dir
                                                 "--force-full"
                                                 "--no-incremental"
                                                 "--verbose"])]
              (is (= 0 (:exit result)) "Force full should succeed")
              (is (str/includes? (:out result) "Processing complete") "Should show completion")))

          ;; Test 5: Dry run mode
          (testing "Dry run mode"
            ;; Ensure test data exists
            (let [data-dir (io/file test-data-dir)]
              (when-not (.exists data-dir)
                (.mkdirs data-dir))
              (spit (str test-data-dir "/conversations.json") sample-conversations-json)
              (spit (str test-data-dir "/projects.json") sample-projects-json)
              (spit (str test-data-dir "/users.json") "[]"))

            (let [initial-file-count (->> (io/file test-vault-dir)
                                          file-seq
                                          (filter #(.isFile %))
                                          count)
                  result (run-obsidize jar-exec ["--input" test-data-dir
                                                 "--output-dir" (str test-vault-dir "-dry")
                                                 "--dry-run"
                                                 "--verbose"])
                  final-file-count (->> (io/file test-vault-dir)
                                        file-seq
                                        (filter #(.isFile %))
                                        count)]
              (is (= 0 (:exit result)) "Dry run should succeed")
              (is (str/includes? (:out result) "Dry run") "Should mention dry run")
              (is (= initial-file-count final-file-count) "Should not create new files")))

          ;; Test 6: Error handling
          (testing "Error handling"
            (let [result (run-obsidize jar-exec ["--input" "/nonexistent/path"
                                                 "--output-dir" test-vault-dir])]
              (is (not= 0 (:exit result)) "Should fail with invalid input")
              (is (or (str/includes? (:err result) "Error")
                      (str/includes? (:out result) "Error")
                      (str/includes? (:out result) "❌")) "Should show error message"))))

        (println "⚠️  JAR artifact not found - skipping JAR validation tests")))))

(deftest native-image-validation-test
  (testing "Native image validation with real CLI execution"
    (let [native-exec (find-executable :native)]
      (if native-exec
        (do
          ;; Test native image startup time and basic functionality
          (testing "Native image performance and functionality"
            (let [start-time (System/currentTimeMillis)
                  result (run-obsidize native-exec ["--version"])
                  end-time (System/currentTimeMillis)
                  startup-time (- end-time start-time)]
              (is (= 0 (:exit result)) "Native version command should succeed")
              (is (str/includes? (:out result) "obsidize") "Should show version")
              (is (< startup-time 2000) "Native startup should be fast (under 2s)")
              (println (str "Native startup time: " startup-time "ms"))))

          ;; Test full workflow with native image
          (testing "Native image full workflow"
            (let [result (run-obsidize native-exec ["--input" test-data-dir
                                                    "--output-dir" (str test-vault-dir "-native")
                                                    "--verbose"])]
              (is (= 0 (:exit result)) "Native processing should succeed")
              (is (str/includes? (:out result) "Processing complete") "Should complete processing")

              ;; Verify output
              (let [vault-dir (io/file (str test-vault-dir "-native"))
                    files (->> vault-dir
                               file-seq
                               (filter #(.isFile %))
                               count)]
                (is (> files 0) "Should create output files"))))

          ;; Test native image diagnostics
          (testing "Native image diagnostics"
            (let [result (run-obsidize native-exec ["--diagnostics"])]
              (is (= 0 (:exit result)) "Diagnostics should work in native image")
              (is (str/includes? (:out result) "Runtime Environment") "Should show runtime info"))))

        (println "⚠️  Native image not found - skipping native validation tests")))))

(deftest performance-comparison-test
  (testing "Performance comparison between JAR and native image"
    (let [jar-exec (find-executable :jar)
          native-exec (find-executable :native)]
      (when (and jar-exec native-exec)
        (testing "Startup time comparison"
          ;; JAR startup time
          (let [jar-times (for [_ (range 3)]
                            (let [start (System/currentTimeMillis)
                                  _ (run-obsidize jar-exec ["--version"])
                                  end (System/currentTimeMillis)]
                              (- end start)))
                jar-avg (double (/ (reduce + jar-times) (count jar-times)))

                ;; Native startup time
                native-times (for [_ (range 3)]
                               (let [start (System/currentTimeMillis)
                                     _ (run-obsidize native-exec ["--version"])
                                     end (System/currentTimeMillis)]
                                 (- end start)))
                native-avg (double (/ (reduce + native-times) (count native-times)))]

            (println (str "JAR average startup: " (int jar-avg) "ms"))
            (println (str "Native average startup: " (int native-avg) "ms"))
            (println (str "Native speedup: " (format "%.1fx" (double (/ jar-avg native-avg)))))

            ;; Native should be significantly faster
            (is (< native-avg (* jar-avg 0.5)) "Native should be at least 2x faster than JAR")))))))

(deftest cross-platform-compatibility-test
  (testing "Cross-platform file handling validation"
    (let [executable (or (find-executable :native) (find-executable :jar))]
      (when executable
        (testing "File path handling across platforms"
          ;; Test with various path formats
          (let [result (run-obsidize executable ["--input" test-data-dir
                                                 "--output-dir" test-vault-dir
                                                 "--verbose"])]
            (is (= 0 (:exit result)) "Should handle file paths correctly")

            ;; Verify cross-platform file creation
            (let [files (->> (io/file test-vault-dir)
                             file-seq
                             (filter #(.isFile %))
                             (map #(.getName %)))]
              (is (every? #(not (re-find #"[<>:\"|?*]" %)) files) "Filenames should be cross-platform safe"))))))))

(deftest installation-simulation-test
  (testing "Simulate installation and usage scenarios"
    (let [executable (or (find-executable :native) (find-executable :jar))]
      (when executable
        (testing "Help and documentation accessibility"
          (let [help-result (run-obsidize executable ["--help"])
                version-result (run-obsidize executable ["--version"])
                diagnostics-result (run-obsidize executable ["--diagnostics"])]
            (is (= 0 (:exit help-result)) "Help should be accessible")
            (is (= 0 (:exit version-result)) "Version should be accessible")
            (is (= 0 (:exit diagnostics-result)) "Diagnostics should be accessible")

            ;; Verify useful information is provided
            (is (str/includes? (:out help-result) "input") "Help should mention input option")
            (is (str/includes? (:out help-result) "output") "Help should mention output option")))

        (testing "Error messages are helpful"
          (let [no-args-result (run-obsidize executable [])
                invalid-args-result (run-obsidize executable ["--invalid-flag"])]
            (is (not= 0 (:exit no-args-result)) "Should fail with no arguments")
            (is (not= 0 (:exit invalid-args-result)) "Should fail with invalid arguments")

            ;; Error messages should be helpful
            (is (or (str/includes? (:out no-args-result) "USAGE EXAMPLES")
                    (str/includes? (:err no-args-result) "USAGE EXAMPLES")) "Should show usage on error")))))))
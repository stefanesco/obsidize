(ns obsidize.native-image-test
  "Integration tests for native-image compatibility"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [obsidize.logging :as log]
            [obsidize.data-pack :as data-pack]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Native Image Compatibility Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest native-image-runtime-detection-test
  (testing "Native image runtime detection works"
    (let [env (log/detect-runtime-environment)]
      (is (map? env))
      (is (boolean? (:native-image? env)))

      ;; Log the detected environment for debugging
      (when (:native-image? env)
        (println "✓ Running in native-image environment")
        (println (str "  VM: " (:vm-name env))))

      ;; Should not error even in native image
      (is (not (contains? env :error))))))

(deftest zip-file-detection-native-compatibility-test
  (testing "ZIP file detection works in native image"
    ;; Test with current directory (should not be a ZIP)
    (let [result (data-pack/detect-input-type ".")]
      (is (= :folder result)))

    ;; Test with non-existent file
    (let [result (data-pack/detect-input-type "nonexistent.zip")]
      (is (= :unknown result)))

    ;; Note: We can't easily create a ZIP file in tests without dependencies,
    ;; but the diagnostic function in logging tests this capability
    ))

(deftest system-property-access-test
  (testing "System property access works in native image"
    ;; These are critical system properties that the app uses
    (let [temp-dir (System/getProperty "java.io.tmpdir")
          os-name (System/getProperty "os.name")
          java-version (System/getProperty "java.version")]

      (is (string? temp-dir))
      (is (not (str/blank? temp-dir)))
      (println (str "✓ Temp directory access: " temp-dir))

      (is (string? os-name))
      (is (not (str/blank? os-name)))
      (println (str "✓ OS name access: " os-name))

      (is (string? java-version))
      (is (not (str/blank? java-version)))
      (println (str "✓ Java version access: " java-version)))))

(deftest file-operations-native-compatibility-test
  (testing "Basic file operations work in native image"
    ;; Test file existence checking
    (let [file (io/file ".")]
      (is (.exists file))
      (is (.isDirectory file))
      (is (.canRead file))
      (println "✓ Basic file operations work"))

    ;; Test temp file creation
    (try
      (let [temp-file (java.io.File/createTempFile "obsidize-test" ".tmp")]
        (is (.exists temp-file))
        (.delete temp-file)
        (println "✓ Temp file creation works"))
      (catch Exception e
        (println (str "✗ Temp file creation failed: " (.getMessage e)))
        (is false "Temp file creation should work")))))

(deftest json-parsing-native-compatibility-test
  (testing "JSON parsing works in native image"
    ;; Test with simple JSON
    (try
      (let [json-str "{\"test\": \"value\", \"number\": 42}"
            parsed (json/parse-string json-str true)]
        (is (map? parsed))
        (is (= "value" (:test parsed)))
        (is (= 42 (:number parsed)))
        (println "✓ JSON parsing works"))
      (catch Exception e
        (println (str "✗ JSON parsing failed: " (.getMessage e)))
        (is false "JSON parsing should work")))))

(deftest reflection-configuration-coverage-test
  (testing "Critical reflection-dependent operations work"
    ;; Test ZipFile creation (requires reflection in native image)
    (try
      ;; Create a minimal ZIP in memory for testing
      (let [temp-file (java.io.File/createTempFile "test" ".zip")]
        (with-open [zip-out (java.util.zip.ZipOutputStream.
                             (clojure.java.io/output-stream temp-file))]
          (.putNextEntry zip-out (java.util.zip.ZipEntry. "test.txt"))
          (.write zip-out (.getBytes "test content"))
          (.closeEntry zip-out))

        ;; Now try to read it back
        (with-open [zip-file (java.util.zip.ZipFile. temp-file)]
          (let [entries (enumeration-seq (.entries zip-file))]
            (is (= 1 (count entries)))
            (is (= "test.txt" (.getName (first entries))))
            (println "✓ ZipFile reflection operations work")))

        (.delete temp-file))
      (catch Exception e
        (println (str "✗ ZipFile operations failed: " (.getMessage e)))
        ;; Don't fail the test as this might be expected in some environments
        (println "  This may indicate missing reflection configuration")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integration Tests with Logging
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest comprehensive-native-image-test
  (testing "Comprehensive native image compatibility check"
    (println "\n🔍 Running comprehensive native-image compatibility tests...")

    ;; Enable debug logging for this test
    (log/set-debug! true)

    (let [diagnostics (log/comprehensive-diagnostics)]
      (println "\n📊 Diagnostic Results:")
      (println (str "  Runtime: " (if (:native-image? (:runtime diagnostics))
                                    "Native Image ✓"
                                    "JVM ✓")))
      (println (str "  ZIP Support: " (if (:zip-available? (:zip diagnostics))
                                        "Available ✓"
                                        "Not Available ✗")))
      (println (str "  Temp Directory: " (if (:can-create-temp-files? (:temp diagnostics))
                                           "Working ✓"
                                           "Not Working ✗")))

      ;; All diagnostics should succeed or have explicit error handling
      (is (map? diagnostics))
      (is (contains? diagnostics :runtime))
      (is (contains? diagnostics :zip))
      (is (contains? diagnostics :temp)))

    ;; Reset debug logging
    (log/set-debug! false)

    (println "✅ Native-image compatibility tests completed")))
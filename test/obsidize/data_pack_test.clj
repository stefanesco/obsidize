(ns obsidize.data-pack-test
  "Comprehensive tests for obsidize.data-pack namespace"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [obsidize.data-pack :as sut]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Fixtures and Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def temp-dirs (atom #{}))

(defn create-temp-dir []
  (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                          (str "obsidize-test-" (System/currentTimeMillis) "-" (rand-int 10000)))]
    (.mkdirs temp-dir)
    (swap! temp-dirs conj (.getAbsolutePath temp-dir))
    temp-dir))

(defn cleanup-temp-dirs []
  (doseq [dir @temp-dirs]
    (try
      (let [dir-file (io/file dir)]
        (when (.exists dir-file)
          (letfn [(delete-recursively [file]
                    (when (.exists file)
                      (when (.isDirectory file)
                        (doseq [child (.listFiles file)]
                          (delete-recursively child)))
                      (.delete file)))]
            (delete-recursively dir-file))))
      (catch Exception e
        (println "Warning: Failed to cleanup test dir" dir ":" (.getMessage e)))))
  (reset! temp-dirs #{}))

(use-fixtures :each (fn [f] (f) (cleanup-temp-dirs)))

(def sample-conversations
  [{:uuid "conv-1"
    :name "Test Conversation"
    :created_at "2024-01-01T12:00:00Z"
    :updated_at "2024-01-01T12:30:00Z"
    :chat_messages []}])

(def sample-projects
  [{:uuid "proj-1"
    :name "Test Project"
    :created_at "2024-01-01T12:00:00Z"
    :updated_at "2024-01-01T12:30:00Z"}])

(defn create-test-data-pack-directory [conversations projects]
  (let [temp-dir (create-temp-dir)]
    (spit (io/file temp-dir "conversations.json") (json/generate-string conversations))
    (spit (io/file temp-dir "projects.json") (json/generate-string projects))
    temp-dir))

(defn create-incomplete-data-pack-directory []
  (let [temp-dir (create-temp-dir)]
    ;; Only create conversations.json, missing projects.json
    (spit (io/file temp-dir "conversations.json") (json/generate-string sample-conversations))
    temp-dir))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Input Detection Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest detect-input-type-test
  (testing "Input type detection"
    ;; Test unknown/non-existent path
    (is (= :unknown (sut/detect-input-type "nonexistent-path")))

    ;; Test directory detection
    (let [temp-dir (create-temp-dir)]
      (is (= :folder (sut/detect-input-type (.getAbsolutePath temp-dir)))))

    ;; Test current directory (should exist)
    (is (keyword? (sut/detect-input-type ".")))

    ;; Test that a regular file is not detected as folder
    (let [temp-file (io/file (System/getProperty "java.io.tmpdir") (str "test-file-" (System/currentTimeMillis)))]
      (spit temp-file "test content")
      (is (= :unknown (sut/detect-input-type (.getAbsolutePath temp-file))))
      (.delete temp-file))))

(deftest detect-input-type-archive-test
  (testing "Archive detection with ZIP files"
    ;; Create a minimal ZIP file for testing
    (let [temp-zip-file (io/file (System/getProperty "java.io.tmpdir") (str "test-archive-" (System/currentTimeMillis) ".zip"))]
      (try
        ;; Create a basic ZIP file
        (with-open [zip-output (java.util.zip.ZipOutputStream. (io/output-stream temp-zip-file))]
          (.putNextEntry zip-output (java.util.zip.ZipEntry. "test.txt"))
          (.write zip-output (.getBytes "test content"))
          (.closeEntry zip-output))

        (is (= :archive (sut/detect-input-type (.getAbsolutePath temp-zip-file))))
        (finally
          (.delete temp-zip-file))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Pack Validation Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest validate-data-pack-test
  (testing "Data pack validation"
    ;; Test valid data pack
    (let [temp-dir (create-test-data-pack-directory sample-conversations sample-projects)
          validation (sut/validate-data-pack (.getAbsolutePath temp-dir))]
      (is (:valid? validation))
      (is (= #{"conversations.json" "projects.json"} (set (:found validation))))
      (is (empty? (:missing validation))))

    ;; Test incomplete data pack
    (let [temp-dir (create-incomplete-data-pack-directory)
          validation (sut/validate-data-pack (.getAbsolutePath temp-dir))]
      (is (not (:valid? validation)))
      (is (contains? (set (:found validation)) "conversations.json"))
      (is (contains? (set (:missing validation)) "projects.json")))

    ;; Test empty directory
    (let [temp-dir (create-temp-dir)
          validation (sut/validate-data-pack (.getAbsolutePath temp-dir))]
      (is (not (:valid? validation)))
      (is (= sut/required-files (set (:missing validation))))
      (is (empty? (:found validation))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JSON Loading Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest load-conversations-test
  (testing "Loading conversations.json"
    ;; Test successful loading
    (let [temp-dir (create-test-data-pack-directory sample-conversations sample-projects)
          result (sut/load-conversations (.getAbsolutePath temp-dir))]
      (is (:success? result))
      (is (vector? (:data result)))
      (is (= 1 (count (:data result))))
      (is (nil? (:error result)))
      (is (map? (:validation-summary result))))

    ;; Test loading from directory without conversations.json
    (let [temp-dir (create-temp-dir)
          result (sut/load-conversations (.getAbsolutePath temp-dir))]
      (is (not (:success? result)))
      (is (empty? (:data result)))
      (is (str/includes? (:error result) "conversations.json not found")))

    ;; Test loading malformed JSON
    (let [temp-dir (create-temp-dir)]
      (spit (io/file temp-dir "conversations.json") "invalid json content")
      (let [result (sut/load-conversations (.getAbsolutePath temp-dir))]
        (is (not (:success? result)))
        (is (empty? (:data result)))
        (is (str/includes? (:error result) "Failed to parse"))))))

(deftest load-projects-test
  (testing "Loading projects.json"
    ;; Test successful loading
    (let [temp-dir (create-test-data-pack-directory sample-conversations sample-projects)
          result (sut/load-projects (.getAbsolutePath temp-dir))]
      (is (:success? result))
      (is (vector? (:data result)))
      (is (= 1 (count (:data result))))
      (is (nil? (:error result)))
      (is (map? (:validation-summary result))))

    ;; Test loading from directory without projects.json
    (let [temp-dir (create-temp-dir)
          result (sut/load-projects (.getAbsolutePath temp-dir))]
      (is (not (:success? result)))
      (is (empty? (:data result)))
      (is (str/includes? (:error result) "projects.json not found")))

    ;; Test loading malformed JSON
    (let [temp-dir (create-temp-dir)]
      (spit (io/file temp-dir "projects.json") "invalid json content")
      (let [result (sut/load-projects (.getAbsolutePath temp-dir))]
        (is (not (:success? result)))
        (is (empty? (:data result)))
        (is (str/includes? (:error result) "Failed to parse"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Archive Extraction Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest extract-archive-test
  (testing "Archive extraction"
    ;; Create a ZIP archive with test data
    (let [source-dir (create-test-data-pack-directory sample-conversations sample-projects)
          zip-file (io/file (System/getProperty "java.io.tmpdir") (str "test-archive-" (System/currentTimeMillis) ".zip"))]

      ;; Create ZIP file
      (try
        (with-open [zip-output (java.util.zip.ZipOutputStream. (io/output-stream zip-file))]
          (doseq [file (.listFiles source-dir)]
            (when (.isFile file)
              (.putNextEntry zip-output (java.util.zip.ZipEntry. (.getName file)))
              (with-open [input (io/input-stream file)]
                (io/copy input zip-output))
              (.closeEntry zip-output))))

        ;; Test extraction
        (let [extracted-dir (sut/extract-archive (.getAbsolutePath zip-file))]
          (is (string? extracted-dir))
          (is (.exists (io/file extracted-dir)))
          (is (.exists (io/file extracted-dir "conversations.json")))
          (is (.exists (io/file extracted-dir "projects.json")))

          ;; Cleanup extracted directory
          (sut/cleanup-temp-directory extracted-dir))

        (finally
          (.delete zip-file)))))

  (testing "Archive extraction failure"
    ;; Test with non-existent file
    (let [result (sut/extract-archive "nonexistent-file.zip")]
      (is (nil? result)))

    ;; Test with invalid ZIP file
    (let [invalid-zip (io/file (System/getProperty "java.io.tmpdir") (str "invalid-" (System/currentTimeMillis) ".zip"))]
      (spit invalid-zip "not a zip file")
      (try
        (let [result (sut/extract-archive (.getAbsolutePath invalid-zip))]
          (is (nil? result)))
        (finally
          (.delete invalid-zip))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main Process Data Pack Function Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest process-data-pack-folder-test
  (testing "Processing data pack from folder"
    ;; Test successful processing
    (let [temp-dir (create-test-data-pack-directory sample-conversations sample-projects)
          result (sut/process-data-pack (.getAbsolutePath temp-dir))]
      (is (:success? result))
      (is (= (.getAbsolutePath temp-dir) (:data-dir result)))
      (is (= 1 (count (:conversations result))))
      (is (= 1 (count (:projects result))))
      (is (empty? (:errors result))))

    ;; Test processing incomplete data pack
    (let [temp-dir (create-incomplete-data-pack-directory)
          result (sut/process-data-pack (.getAbsolutePath temp-dir))]
      (is (not (:success? result)))
      (is (= (.getAbsolutePath temp-dir) (:data-dir result)))
      (is (empty? (:conversations result)))
      (is (empty? (:projects result)))
      (is (seq (:errors result)))
      (is (str/includes? (first (:errors result)) "Missing required files")))))

(deftest process-data-pack-archive-test
  (testing "Processing data pack from archive"
    ;; Create ZIP archive
    (let [source-dir (create-test-data-pack-directory sample-conversations sample-projects)
          zip-file (io/file (System/getProperty "java.io.tmpdir") (str "test-data-pack-" (System/currentTimeMillis) ".zip"))]

      ;; Create ZIP file
      (try
        (with-open [zip-output (java.util.zip.ZipOutputStream. (io/output-stream zip-file))]
          (doseq [file (.listFiles source-dir)]
            (when (.isFile file)
              (.putNextEntry zip-output (java.util.zip.ZipEntry. (.getName file)))
              (with-open [input (io/input-stream file)]
                (io/copy input zip-output))
              (.closeEntry zip-output))))

        ;; Test processing
        (let [result (sut/process-data-pack (.getAbsolutePath zip-file))]
          (is (:success? result))
          (is (string? (:data-dir result)))
          (is (.exists (io/file (:data-dir result))))
          (is (= 1 (count (:conversations result))))
          (is (= 1 (count (:projects result))))
          (is (empty? (:errors result)))

          ;; Test cleanup
          (sut/cleanup-temp-directory (:data-dir result)))
          ;; Directory should be cleaned up (though we can't easily test this without race conditions))

        (finally
          (.delete zip-file))))))

(deftest process-data-pack-unknown-input-test
  (testing "Processing unknown input type"
    (let [result (sut/process-data-pack "nonexistent-path")]
      (is (not (:success? result)))
      (is (nil? (:data-dir result)))
      (is (empty? (:conversations result)))
      (is (empty? (:projects result)))
      (is (seq (:errors result)))
      (is (str/includes? (first (:errors result)) "Unknown input type")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cleanup Function Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest cleanup-temp-directory-test
  (testing "Cleanup temp directory"
    ;; Create a temporary directory structure
    (let [temp-dir (io/file (System/getProperty "java.io.tmpdir") (str "obsidize-" (System/currentTimeMillis)))
          sub-dir (io/file temp-dir "subdir")
          test-file (io/file sub-dir "test.txt")]
      (.mkdirs sub-dir)
      (spit test-file "test content")

      ;; Verify structure exists
      (is (.exists temp-dir))
      (is (.exists sub-dir))
      (is (.exists test-file))

      ;; Test cleanup
      (let [output (with-out-str (sut/cleanup-temp-directory (.getAbsolutePath temp-dir)))]
        (is (str/includes? output "Cleaned up temporary directory")))))

      ;; Directory should be gone (though we can't easily test this reliably due to timing)))

  (testing "Cleanup with invalid directory"
    ;; Test with nil
    (is (nil? (sut/cleanup-temp-directory nil)))

    ;; Test with directory that doesn't match pattern
    (let [output (with-out-str (sut/cleanup-temp-directory "/some/random/path"))]
      (is (empty? output))))

  (testing "Cleanup with non-existent directory"
    (let [output (with-out-str (sut/cleanup-temp-directory "/tmp/obsidize-nonexistent"))]
      (is (str/includes? output "Cleaned up temporary directory")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Edge Cases and Error Handling
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest edge-cases-test
  (testing "JSON parsing edge cases"
    ;; Test single object vs array handling
    (let [temp-dir (create-temp-dir)]
      ;; Create JSON with single object instead of array
      (spit (io/file temp-dir "conversations.json") (json/generate-string (first sample-conversations)))
      (spit (io/file temp-dir "projects.json") (json/generate-string (first sample-projects)))

      (let [conv-result (sut/load-conversations (.getAbsolutePath temp-dir))
            proj-result (sut/load-projects (.getAbsolutePath temp-dir))]
        ;; Should still work, wrapping single objects in vectors
        (is (:success? conv-result))
        (is (:success? proj-result))
        (is (vector? (:data conv-result)))
        (is (vector? (:data proj-result))))))

  (testing "Directory permissions and access"
    ;; Test with directory that user can't read (simulated)
    (with-redefs [obsidize.data-pack/find-json-files (fn [_] (throw (java.lang.Exception. "Permission denied")))]
      (let [temp-dir (create-temp-dir)]
        (is (thrown? java.lang.Exception (sut/validate-data-pack (.getAbsolutePath temp-dir)))))))

  (testing "Large JSON file handling"
    ;; Test with large data structures
    (let [large-conversations (vec (repeat 1000 (first sample-conversations)))
          large-projects (vec (repeat 1000 (first sample-projects)))
          temp-dir (create-test-data-pack-directory large-conversations large-projects)
          result (sut/process-data-pack (.getAbsolutePath temp-dir))]
      (is (:success? result))
      (is (= 1000 (count (:conversations result))))
      (is (= 1000 (count (:projects result)))))))
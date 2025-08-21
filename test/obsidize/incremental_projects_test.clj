(ns obsidize.incremental-projects-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [obsidize.incremental-projects :as sut])
  (:import [java.io File]
           [obsidize.incremental_projects ProjectOverviewMetadata]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Data and Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sample-project-overview-content
  "---
uuid: project-123
type: project-overview
project_name: Test Project
created_at: 2025-01-01T00:00:00Z
updated_at: 2025-01-02T00:00:00Z
obsidized_at: 2025-01-03T00:00:00Z
obsidize_version: 1.0.0
source: claude-export
---

# Test Project

This is a sample project description for testing incremental updates.

## Project Documents

- [[001_first-document.md]]
- [[002_second-document.md]]
- [[003_third-document.md]]

## Linked to

- [[Related Project]]

## Tags

- #test
- #project
")

(def sample-vault-index-project
  {:folder-path "/tmp/test-vault/Test Project"
   :overview-file "/tmp/test-vault/Test Project/Test Project.md"
   :created-at "2025-01-01T00:00:00Z"
   :updated-at "2025-01-02T00:00:00Z"
   :obsidized-at "2025-01-03T00:00:00Z"
   :documents [{:file-path "/tmp/test-vault/Test Project/001_first-document.md"
                :file-name "001_first-document.md"
                :uuid "doc-1-uuid"
                :created-at "2025-01-01T10:00:00Z"
                :obsidized-at "2025-01-03T00:00:00Z"}
               {:file-path "/tmp/test-vault/Test Project/002_second-document.md"
                :file-name "002_second-document.md"
                :uuid "doc-2-uuid"
                :created-at "2025-01-01T11:00:00Z"
                :obsidized-at "2025-01-03T00:00:00Z"}
               {:file-path "/tmp/test-vault/Test Project/003_third-document.md"
                :file-name "003_third-document.md"
                :uuid "doc-3-uuid"
                :created-at "2025-01-01T12:00:00Z"
                :obsidized-at "2025-01-03T00:00:00Z"}]})

(def sample-claude-project
  {:uuid "project-123"
   :name "Test Project"
   :description "This is an updated project description with new content."
   :created_at "2025-01-01T00:00:00Z"
   :updated_at "2025-01-05T00:00:00Z" ; Updated since last obsidize
   :docs [{:uuid "doc-1-uuid"
           :filename "first-document.md"
           :content "# First Document\n\nExisting document content"
           :created_at "2025-01-01T10:00:00Z"}
          {:uuid "doc-2-uuid"
           :filename "second-document.md"
           :content "# Second Document\n\nExisting document content"
           :created_at "2025-01-01T11:00:00Z"}
          {:uuid "doc-3-uuid"
           :filename "third-document.md"
           :content "# Third Document\n\nExisting document content"
           :created_at "2025-01-01T12:00:00Z"}
          {:uuid "doc-4-uuid"
           :filename "fourth-document.md"
           :content "# Fourth Document\n\nNew document content"
           :created_at "2025-01-04T08:00:00Z"} ; New document
          {:uuid "doc-5-uuid"
           :filename "fifth-document.md"
           :content "# Fifth Document\n\nAnother new document"
           :created_at "2025-01-04T09:00:00Z"}]}) ; New document

(defn create-temp-directory
  "Create a temporary directory for testing."
  []
  (let [temp-dir (File/createTempFile "obsidize-test" "")]
    (.delete temp-dir)
    (.mkdirs temp-dir)
    (.deleteOnExit temp-dir)
    (.getAbsolutePath temp-dir)))

(defn create-test-overview-file
  "Create a temporary overview file with sample content."
  [content]
  (let [temp-file (File/createTempFile "test-overview" ".md")]
    (.deleteOnExit temp-file)
    (spit temp-file content)
    (.getAbsolutePath temp-file)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest parse-project-overview-test
  (testing "Parses project overview markdown correctly"
    (let [overview-file (create-test-overview-file sample-project-overview-content)
          result (sut/parse-project-overview overview-file sample-vault-index-project)]

      (is (:success? result))
      (let [data (:data result)]
        (is (instance? ProjectOverviewMetadata data))
        (is (= "project-123" (:project-uuid data)))
        (is (= "Test Project" (:project-name data)))
        (is (= "2025-01-01T00:00:00Z" (:project-created-at data)))
        (is (= "2025-01-02T00:00:00Z" (:project-updated-at data)))
        (is (= "2025-01-03T00:00:00Z" (:obsidized-at data)))
        (is (= 3 (:highest-doc-index data)))

        ;; Check existing documents mapping
        (let [existing-docs (:existing-documents data)]
          (is (= 3 (count existing-docs)))
          (is (contains? existing-docs "doc-1-uuid"))
          (is (= 1 (get-in existing-docs ["doc-1-uuid" :index])))
          (is (= "001_first-document.md" (get-in existing-docs ["doc-1-uuid" :filename])))
          (is (= 3 (get-in existing-docs ["doc-3-uuid" :index]))))))))

(deftest parse-project-overview-empty-documents-test
  (testing "Handles overview with no documents"
    (let [empty-overview "---\nuuid: project-456\ntype: project-overview\nproject_name: Empty Project\n---\n# Empty Project\n\nNo documents yet."
          overview-file (create-test-overview-file empty-overview)
          empty-vault-project (assoc sample-vault-index-project :documents [])
          result (sut/parse-project-overview overview-file empty-vault-project)]

      (is (:success? result))
      (let [data (:data result)]
        (is (= 0 (:highest-doc-index data)))
        (is (empty? (:existing-documents data)))))))

(deftest identify-new-documents-test
  (testing "Correctly identifies new documents"
    (let [overview-file (create-test-overview-file sample-project-overview-content)
          parse-result (sut/parse-project-overview overview-file sample-vault-index-project)]

      (is (:success? parse-result))
      (let [parsed-overview (:data parse-result)
            result (sut/identify-new-documents sample-claude-project parsed-overview sample-vault-index-project)
            new-docs (:new-documents result)]

        ;; Should identify 2 new documents (doc-4 and doc-5)
        (is (= 2 (count new-docs)))
        (is (= "doc-4-uuid" (:uuid (first new-docs))))
        (is (= 4 (:assigned-index (first new-docs))))
        (is (= "doc-5-uuid" (:uuid (second new-docs))))
        (is (= 5 (:assigned-index (second new-docs))))

        ;; Should detect metadata changes
        (is (:project-metadata-changed? result))

        ;; All documents should include existing + new
        (is (= 5 (count (:all-documents result))))))))

(deftest identify-new-documents-no-changes-test
  (testing "Handles case with no new documents or metadata changes"
    (let [overview-file (create-test-overview-file sample-project-overview-content)
          parse-result (sut/parse-project-overview overview-file sample-vault-index-project)]

      (is (:success? parse-result))
      (let [parsed-overview (:data parse-result)
            unchanged-project (assoc sample-claude-project
                                     :description "This is a sample project description for testing incremental updates."
                                     :updated_at "2025-01-02T00:00:00Z" ; Same as parsed overview
                                     :docs (take 3 (:docs sample-claude-project))) ; Only existing docs
            result (sut/identify-new-documents unchanged-project parsed-overview sample-vault-index-project)]

        (is (empty? (:new-documents result)))
        (is (not (:project-metadata-changed? result)))
        (is (= 3 (count (:all-documents result))))))))

(deftest process-new-documents-test
  (testing "Processes new documents correctly"
    (let [temp-dir (create-temp-directory)
          new-docs [{:uuid "doc-4-uuid"
                     :filename "fourth-document.md"
                     :content "# Fourth Document Content"
                     :created_at "2025-01-04T08:00:00Z"
                     :assigned-index 4}
                    {:uuid "doc-5-uuid"
                     :filename "fifth-document.md"
                     :content "# Fifth Document Content"
                     :created_at "2025-01-04T09:00:00Z"
                     :assigned-index 5}]
          result (sut/process-new-documents temp-dir new-docs "Test Project" "2.0.0")]

      (is (= 2 (count result)))

      ;; Check first document
      (let [first-doc (first result)]
        (is (= "doc-4-uuid" (get-in first-doc [:document :uuid])))
        (is (str/ends-with? (:file-path first-doc) "/004_fourth-document.md"))
        (is (= "004_fourth-document.md" (:filename first-doc)))
        (is (str/includes? (:content first-doc) "uuid: doc-4-uuid"))
        (is (str/includes? (:content first-doc) "type: project-document"))
        (is (str/includes? (:content first-doc) "# Fourth Document Content")))

      ;; Check second document
      (let [second-doc (second result)]
        (is (= "doc-5-uuid" (get-in second-doc [:document :uuid])))
        (is (str/ends-with? (:file-path second-doc) "/005_fifth-document.md"))
        (is (= "005_fifth-document.md" (:filename second-doc)))))))

(deftest update-project-overview-test
  (testing "Updates project overview with new documents"
    (let [temp-file (create-test-overview-file sample-project-overview-content)
          overview-file (create-test-overview-file sample-project-overview-content)
          parse-result (sut/parse-project-overview overview-file sample-vault-index-project)]

      (is (:success? parse-result))
      (let [parsed-overview (:data parse-result)
            processed-docs [{:document {:uuid "doc-4-uuid" :created_at "2025-01-04T08:00:00Z"}
                             :filename "004_fourth-document.md"}
                            {:document {:uuid "doc-5-uuid" :created_at "2025-01-04T09:00:00Z"}
                             :filename "005_fifth-document.md"}]
            all-docs (:docs sample-claude-project)
            result (sut/update-project-overview temp-file sample-claude-project all-docs "2.0.0" parsed-overview processed-docs)]

        (is (:success? result))
        (is (:data result))

        ;; Verify the file was updated
        (let [updated-content (slurp temp-file)]
          (is (str/includes? updated-content "obsidize_version: 2.0.0"))
          (is (str/includes? updated-content "updated_at: 2025-01-05T00:00:00Z"))
          (is (str/includes? updated-content "This is an updated project description"))
          (is (str/includes? updated-content "004_fourth-document.md"))
          (is (str/includes? updated-content "005_fifth-document.md")))))))

(deftest incremental-project-update-integration-test
  (testing "Complete incremental update workflow"
    (let [temp-dir (create-temp-directory)
          project-dir (str temp-dir "/Test Project")
          _ (.mkdirs (io/file project-dir))
          overview-file (str project-dir "/Test Project.md")
          _ (spit overview-file sample-project-overview-content)

          vault-index-project (assoc sample-vault-index-project
                                     :folder-path project-dir
                                     :overview-file overview-file)

          options {:output-dir temp-dir
                   :dry-run false
                   :verbose false
                   :tags ["test"]
                   :links []}

          result (sut/incremental-project-update sample-claude-project vault-index-project temp-dir "2.0.0" options)]

      (is (:success? result))
      (is (= 2 (get-in result [:data :new-documents-count])))
      (is (get-in result [:data :metadata-changed?]))

      ;; Verify new document files were created
      (is (.exists (io/file project-dir "004_fourth-document.md")))
      (is (.exists (io/file project-dir "005_fifth-document.md")))

      ;; Verify overview was updated
      (let [updated-overview (slurp overview-file)]
        (is (str/includes? updated-overview "004_fourth-document.md"))
        (is (str/includes? updated-overview "005_fifth-document.md"))
        (is (str/includes? updated-overview "updated_at: 2025-01-05T00:00:00Z"))))))

(deftest incremental-project-update-dry-run-test
  (testing "Dry run mode doesn't write files"
    (let [temp-dir (create-temp-directory)
          project-dir (str temp-dir "/Test Project")
          _ (.mkdirs (io/file project-dir))
          overview-file (str project-dir "/Test Project.md")
          _ (spit overview-file sample-project-overview-content)
          original-content (slurp overview-file)

          vault-index-project (assoc sample-vault-index-project
                                     :folder-path project-dir
                                     :overview-file overview-file)

          options {:output-dir temp-dir
                   :dry-run true
                   :verbose false}

          result (sut/incremental-project-update sample-claude-project vault-index-project temp-dir "2.0.0" options)]

      (is (:success? result))
      (is (get-in result [:data :dry-run?]))
      (is (= 2 (get-in result [:data :new-documents-count])))

      ;; Verify no files were actually written
      (is (not (.exists (io/file project-dir "004_fourth-document.md"))))
      (is (not (.exists (io/file project-dir "005_fifth-document.md"))))
      (is (= original-content (slurp overview-file))))))
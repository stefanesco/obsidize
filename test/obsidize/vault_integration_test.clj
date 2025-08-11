(ns obsidize.vault-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [obsidize.vault-scanner :as vs]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def test-vault-path "test-vault-integration")

(defn create-test-vault 
  "Create a test vault with sample files to test full integration"
  []
  (let [vault-dir (io/file test-vault-path)]
    ;; Create directories
    (.mkdirs vault-dir)
    (.mkdirs (io/file test-vault-path "Project Alpha"))
    (.mkdirs (io/file test-vault-path "Project Beta"))

    ;; Create conversation files
    (spit (str test-vault-path "/conv1_conv-123.md")
          "---\nuuid: conv-123\ncreated_at: 2025-08-04T10:30:00Z\nupdated_at: 2025-08-04T15:45:00Z\nobsidized_at: 2025-08-05T09:15:00Z\ntype: conversation\nsource: claude-export\nobsidize_version: 1.0.0\n---\n\n# Test Conversation\n\n**2025-08-04T10:30:00Z Me:** Hello\n\n**Claude:** Hi there!")

    (spit (str test-vault-path "/conv2_conv-999.md")
          "---\nuuid: conv-999\ncreated_at: 2025-08-05T08:00:00Z\nupdated_at: 2025-08-05T08:00:00Z\nobsidized_at: 2025-08-05T10:00:00Z\ntype: conversation\nsource: claude-export\nobsidize_version: 1.0.0\n---\n\n# Another Chat\n\n**2025-08-05T08:00:00Z Me:** Test\n\n**Claude:** Response")

    ;; Create project files - Project Alpha
    (spit (str test-vault-path "/Project Alpha/project-alpha.md")
          "---\nuuid: proj-456\ncreated_at: 2025-08-03T12:00:00Z\nupdated_at: 2025-08-04T14:30:00Z\nobsidized_at: 2025-08-05T09:20:00Z\ntype: project-overview\nsource: claude-export\nobsidize_version: 1.0.0\n---\n\n# Project Alpha\n\nA test project description\n\n## Project Documents\n\n- [[1_document.md]]")

    (spit (str test-vault-path "/Project Alpha/1_document.md")
          "---\nuuid: doc-789\nproject_name: Project Alpha\ntype: project-document\ncreated_at: 2025-08-03T13:00:00Z\nobsidized_at: 2025-08-05T09:20:00Z\nsource: claude-export\nobsidize_version: 1.0.0\n---\n\n# Document 1\n\nProject content here")

    ;; Create project files - Project Beta (empty project)
    (spit (str test-vault-path "/Project Beta/project-beta.md")
          "---\nuuid: proj-789\ncreated_at: 2025-08-05T10:00:00Z\nupdated_at: 2025-08-05T10:00:00Z\nobsidized_at: 2025-08-05T11:00:00Z\ntype: project-overview\nsource: claude-export\nobsidize_version: 1.0.0\n---\n\n# Project Beta\n\nEmpty project with no documents")

    ;; Create file without frontmatter (should be ignored)
    (spit (str test-vault-path "/regular-note.md") "# Regular Note\n\nNo frontmatter here, should be ignored by scanner")

    ;; Create a corrupted file with malformed frontmatter
    (spit (str test-vault-path "/malformed.md") "---\nbroken yaml: no closing\n\n# Content")))

(defn delete-test-vault 
  "Clean up test vault recursively"
  []
  (letfn [(delete-recursively [file]
            (when (.exists file)
              (if (.isDirectory file)
                (doseq [child (.listFiles file)]
                  (delete-recursively child))
                nil) ; else branch for non-directory files
              (.delete file)))]
    (delete-recursively (io/file test-vault-path))))

(use-fixtures :each
  (fn [test-fn]
    (delete-test-vault)
    (create-test-vault)
    (try
      (test-fn)
      (finally
        (delete-test-vault)))))

(deftest full-vault-scan-test
  (testing "Complete vault scanning integration"
    (let [index (vs/scan-vault test-vault-path)]
      ;; Check totals
      (is (= 5 (:total-files index))) ; 5 files with proper frontmatter
      (is (= 2 (count (:conversations index))))
      (is (= 2 (count (:projects index))))

      ;; Check conversation details
      (let [conv1 (get-in index [:conversations "conv-123"])
            conv2 (get-in index [:conversations "conv-999"])]
        (is conv1)
        (is conv2)
        (is (str/includes? (:file-path conv1) "conv1_conv-123.md"))
        (is (= "2025-08-05T09:15:00Z" (:obsidized-at conv1)))
        (is (= "2025-08-04T15:45:00Z" (:updated-at conv1))))

      ;; Check project details
      (let [proj-alpha (get-in index [:projects "proj-456"])
            proj-beta (get-in index [:projects "proj-789"])]
        (is proj-alpha)
        (is proj-beta)
        (is (str/includes? (:folder-path proj-alpha) "Project Alpha"))
        (is (= 1 (count (:documents proj-alpha))))
        (is (= 0 (count (:documents proj-beta))))
        (is (= "doc-789" (-> proj-alpha :documents first :uuid)))))))

(deftest update-planning-test
  (testing "Plan updates based on vault scan and Claude data"
    (let [vault-index (vs/scan-vault test-vault-path)

          ;; Simulate Claude export data
          claude-conversations [{:uuid "conv-123" ; Existing, older
                                 :created_at "2025-08-04T10:30:00Z"
                                 :updated_at "2025-08-04T15:00:00Z"} ; Before obsidized_at
                                {:uuid "conv-999" ; Existing, newer
                                 :created_at "2025-08-05T08:00:00Z"
                                 :updated_at "2025-08-05T11:00:00Z"} ; After obsidized_at
                                {:uuid "conv-new" ; New conversation
                                 :created_at "2025-08-05T12:00:00Z"
                                 :updated_at "2025-08-05T12:00:00Z"}]

          claude-projects [{:uuid "proj-456" ; Existing, no update needed
                            :created_at "2025-08-03T12:00:00Z"
                            :updated_at "2025-08-04T14:00:00Z"} ; Before obsidized_at
                           {:uuid "proj-new" ; New project  
                            :created_at "2025-08-05T13:00:00Z"
                            :updated_at "2025-08-05T13:00:00Z"}]

          update-plan (vs/plan-updates vault-index claude-conversations claude-projects)]

      ;; Check conversation updates
      (is (= 1 (count (get-in update-plan [:conversations :create-new]))))
      (is (= 1 (count (get-in update-plan [:conversations :update-existing]))))
      (is (= 1 (count (get-in update-plan [:conversations :no-update]))))

      ;; Check project updates
      (is (= 1 (count (get-in update-plan [:projects :create-new]))))
      (is (= 0 (count (get-in update-plan [:projects :update-existing]))))
      (is (= 1 (count (get-in update-plan [:projects :no-update]))))

      ;; Check summary
      (is (= {:conversations {:create-new 1 :update-existing 1 :no-update 1}
              :projects {:create-new 1 :update-existing 0 :no-update 1}}
             (:summary update-plan)))

      ;; Verify specific items
      (let [new-conv (first (get-in update-plan [:conversations :create-new]))
            update-conv (first (get-in update-plan [:conversations :update-existing]))]
        (is (= "conv-new" (:uuid new-conv)))
        (is (= "conv-999" (:uuid update-conv)))))))

(deftest edge-cases-test
  (testing "Handle edge cases in vault scanning"
    ;; Test with empty vault
    (delete-test-vault)
    (.mkdirs (io/file test-vault-path))
    (let [empty-index (vs/scan-vault test-vault-path)]
      (is (= 0 (:total-files empty-index)))
      (is (empty? (:conversations empty-index)))
      (is (empty? (:projects empty-index))))

    ;; Test with non-existent vault
    (delete-test-vault)
    (let [missing-index (vs/scan-vault test-vault-path)]
      (is (= 0 (:total-files missing-index)))
      (is (empty? (:conversations missing-index)))
      (is (empty? (:projects missing-index))))

    ;; Recreate vault for cleanup
    (create-test-vault)))
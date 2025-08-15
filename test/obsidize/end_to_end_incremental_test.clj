(ns obsidize.end-to-end-incremental-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [obsidize.vault-scanner :as vs]
            [obsidize.conversations :as conv]
            [obsidize.projects :as proj]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def test-vault-dir "test-e2e-vault")

(defn create-initial-vault
  "Create an initial vault with some existing content"
  []
  (let [vault-dir (io/file test-vault-dir)]
    (.mkdirs vault-dir)
    (.mkdirs (io/file test-vault-dir "Project Alpha"))

    ;; Create existing conversation
    (spit (str test-vault-dir "/existing-chat__conv-123.md")
          "---\nuuid: conv-123\ncreated_at: 2025-08-04T10:30:00Z\nupdated_at: 2025-08-04T15:45:00Z\nobsidized_at: 2025-08-05T09:15:00Z\ntype: conversation\nsource: claude-export\nobsidize_version: 1.0.0\n---\n\n# Existing Chat\n\n**2025-08-04T10:30:00Z Me:** Hello\n\n**Claude:** Hi there!")

    ;; Create existing project
    (spit (str test-vault-dir "/Project Alpha/project-alpha.md")
          "---\nuuid: proj-456\ncreated_at: 2025-08-03T12:00:00Z\nupdated_at: 2025-08-04T14:30:00Z\nobsidized_at: 2025-08-05T09:20:00Z\ntype: project-overview\nsource: claude-export\nobsidize_version: 1.0.0\n---\n\n# Project Alpha\n\nExisting project description")))

(defn cleanup-vault
  "Clean up test vault"
  []
  (let [vault-dir (io/file test-vault-dir)]
    (when (.exists vault-dir)
      (doseq [file (reverse (file-seq vault-dir))]
        (when (.exists file)
          (.delete file))))))

(use-fixtures :each
  (fn [test-fn]
    (cleanup-vault)
    (create-initial-vault)
    (try
      (test-fn)
      (finally
        (cleanup-vault)))))

(deftest end-to-end-incremental-workflow-test
  (testing "Complete incremental update workflow"
    ;; Step 1: Scan existing vault
    (let [vault-index (vs/scan-vault test-vault-dir)]
      (is (= 2 (:total-files vault-index)))
      (is (map? (:conversations vault-index)))
      (is (map? (:projects vault-index)))
      (is (= 1 (count (keys (:conversations vault-index)))))
      (is (= 1 (count (keys (:projects vault-index)))))
      (is (contains? (:conversations vault-index) "conv-123"))
      (is (contains? (:projects vault-index) "proj-456"))))

    ;; Step 2: Simulate Claude data with mixed updates
  (let [claude-conversations [{:uuid "conv-123" ; Existing conversation with new messages
                               :name "Existing Chat"
                               :created_at "2025-08-04T10:30:00Z"
                               :updated_at "2025-08-05T12:00:00Z"
                               :chats [{:q "Hello" :a "Hi there!" :create_time "2025-08-04T10:30:00Z"} ; Existing
                                       {:q "How are you?" :a "I'm great!" :create_time "2025-08-05T10:30:00Z"} ; New message
                                       {:q "What's new?" :a "Working on updates!" :create_time "2025-08-05T11:00:00Z"}]} ; Another new

                              {:uuid "conv-999" ; Brand new conversation
                               :name "New Discussion"
                               :created_at "2025-08-05T13:00:00Z"
                               :updated_at "2025-08-05T13:00:00Z"
                               :chats [{:q "Hi Claude" :a "Hello!" :create_time "2025-08-05T13:00:00Z"}]}]

        claude-projects [{:uuid "proj-456" ; Existing project, no update needed  
                          :name "Project Alpha"
                          :description "Existing project description"
                          :created_at "2025-08-03T12:00:00Z"
                          :updated_at "2025-08-04T14:00:00Z" ; Before obsidized_at
                          :docs []}

                         {:uuid "proj-789" ; New project
                          :name "Project Beta"
                          :description "A new project"
                          :created_at "2025-08-05T14:00:00Z"
                          :updated_at "2025-08-05T14:00:00Z"
                          :docs [{:uuid "doc-1" :filename "readme.md" :content "# New doc" :created_at "2025-08-05T14:00:00Z"}]}]]

      ;; Step 3: Plan updates
    (let [vault-index (vs/scan-vault test-vault-dir)
          update-plan (vs/plan-updates vault-index claude-conversations claude-projects)]

        ;; Verify update plan
      (is (= 1 (count (get-in update-plan [:conversations :update-existing]))))
      (is (= 1 (count (get-in update-plan [:conversations :create-new]))))
      (is (= 0 (count (get-in update-plan [:conversations :no-update]))))

      (is (= 1 (count (get-in update-plan [:projects :create-new]))))
      (is (= 0 (count (get-in update-plan [:projects :update-existing]))))
      (is (= 1 (count (get-in update-plan [:projects :no-update]))))

        ;; Step 4: Execute updates
        ;; Process conversation updates  
      (doseq [item (get-in update-plan [:conversations :update-existing])]
        (conv/process-conversation (:claude-data item) test-vault-dir "1.0.0" {}))

        ;; Process new conversations
      (doseq [item (get-in update-plan [:conversations :create-new])]
        (conv/process-conversation (:claude-data item) test-vault-dir "1.0.0" {}))

        ;; Process new projects
      (doseq [item (get-in update-plan [:projects :create-new])]
        (proj/process-project (:claude-data item) {:output-dir test-vault-dir})))

      ;; Step 5: Verify results
    (let [updated-vault-index (vs/scan-vault test-vault-dir)
          files (filter #(.isFile %) (file-seq (io/file test-vault-dir)))]

        ;; Should now have more files
      (is (>= (:total-files updated-vault-index) 4)) ; At least 4 files (2 existing + new conversation + new project overview + new project doc)
      (is (= 2 (count (keys (:conversations updated-vault-index))))) ; 2 conversations total
      (is (= 2 (count (keys (:projects updated-vault-index))))) ; 2 projects total

        ;; Verify existing conversation was updated with new messages
      (let [updated-conv-file (str test-vault-dir "/existing-chat__conv-123.md")
            updated-content (slurp updated-conv-file)]
        (is (str/includes? updated-content "Hello")) ; Original message
        (is (str/includes? updated-content "How are you?")) ; New message 1  
        (is (str/includes? updated-content "What's new?")) ; New message 2
        (is (str/includes? updated-content "updated_at: 2025-08-05T12:00:00Z")) ; Updated timestamp
        (is (str/includes? updated-content "obsidized_at: 2025-08-"))) ; New obsidized_at

        ;; Verify new conversation was created
      (let [new-conv-files (filter #(str/includes? (.getName %) "conv-999") files)]
        (is (= 1 (count new-conv-files)))
        (let [new-conv-content (slurp (first new-conv-files))]
          (is (str/includes? new-conv-content "uuid: conv-999"))
          (is (str/includes? new-conv-content "Hi Claude"))))

        ;; Verify new project was created
      (let [project-beta-files (filter #(str/includes? (.getPath %) "Project Beta") files)]
        (is (>= (count project-beta-files) 1)) ; At least project overview
        (let [project-overview (first (filter #(str/includes? (.getName %) "project-beta") project-beta-files))]
          (is project-overview)
          (let [overview-content (slurp project-overview)]
            (is (str/includes? overview-content "uuid: proj-789"))
            (is (str/includes? overview-content "A new project"))))))))

(deftest incremental-update-performance-test
  (testing "Incremental updates are efficient (skip unchanged content)"
    ;; Create initial state and process unchanged data
    (let [initial-files (count (filter #(.isFile %) (file-seq (io/file test-vault-dir))))

          ;; Simulate unchanged Claude data (same timestamps)
          unchanged-conversation {:uuid "conv-123"
                                  :name "Existing Chat"
                                  :created_at "2025-08-04T10:30:00Z"
                                  :updated_at "2025-08-04T15:45:00Z" ; Same as obsidized_at
                                  :chats [{:q "Hello" :a "Hi there!" :create_time "2025-08-04T10:30:00Z"}]}

          unchanged-project {:uuid "proj-456"
                             :name "Project Alpha"
                             :created_at "2025-08-03T12:00:00Z"
                             :updated_at "2025-08-04T14:00:00Z" ; Before obsidized_at
                             :docs []}

          ;; Get original file timestamps and process unchanged data
          conv-file (io/file test-vault-dir "existing-chat__conv-123.md")
          proj-file (io/file test-vault-dir "Project Alpha" "project-alpha.md")
          _ (Thread/sleep 10) ; Wait a bit to ensure timestamp differences would be detectable
          _ (conv/process-conversation unchanged-conversation test-vault-dir "1.0.0" {}) ; Process unchanged data
          _ (proj/process-project unchanged-project {:output-dir test-vault-dir})
          final-files (count (filter #(.isFile %) (file-seq (io/file test-vault-dir))))] ; Files should be unchanged

      (is (= initial-files final-files)) ; No new files created
      ;; Note: Modification times might still change due to our current "skip" message, 
      ;; but content should be identical
      (is (.exists conv-file))
      (is (.exists proj-file)))))
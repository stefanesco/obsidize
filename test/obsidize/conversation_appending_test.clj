(ns obsidize.conversation-appending-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [obsidize.conversations :as conv]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def test-output-dir "test-conversation-appending")

(defn create-test-env
  "Create test environment"
  []
  (let [dir (io/file test-output-dir)]
    (.mkdirs dir)))

(defn cleanup-test-env
  "Clean up test environment"
  []
  (let [dir (io/file test-output-dir)]
    (when (.exists dir)
      (doseq [file (reverse (file-seq dir))]
        (when (.exists file)
          (.delete file))))))

(use-fixtures :each
  (fn [test-fn]
    (cleanup-test-env)
    (create-test-env)
    (try
      (test-fn)
      (finally
        (cleanup-test-env)))))

(deftest determine-new-messages-test
  (testing "Identify new messages based on obsidized_at timestamp"
    (let [existing-content "---\nuuid: conv-123\ncreated_at: 2025-08-04T10:30:00Z\nupdated_at: 2025-08-04T15:45:00Z\nobsidized_at: 2025-08-05T09:15:00Z\ntype: conversation\n---\n\n# Test Conversation\n\n**2025-08-04T10:30:00Z Me:** Hello\n\n**Claude:** Hi there!"

          conversation {:uuid "conv-123"
                        :chats [{:q "Hello" :a "Hi there!" :create_time "2025-08-04T10:30:00Z"} ; Old message
                                {:q "How are you?" :a "I'm good!" :create_time "2025-08-05T10:30:00Z"} ; New message (after obsidized_at)
                                {:q "What's new?" :a "Not much" :create_time "2025-08-05T11:00:00Z"}]} ; Another new message

          result (conv/determine-new-messages conversation existing-content)]

      (is result)
      (is (= 2 (count (:new-messages result))))
      (is (str/includes? (:messages-md result) "How are you?"))
      (is (str/includes? (:messages-md result) "What's new?"))))

  (testing "Return nil when no new messages"
    (let [existing-content "---\nuuid: conv-123\nobsidized_at: 2025-08-05T12:00:00Z\ntype: conversation\n---\n\n# Test"
          conversation {:uuid "conv-123"
                        :chats [{:q "Hello" :a "Hi!" :create_time "2025-08-05T10:30:00Z"}]} ; Before obsidized_at
          result (conv/determine-new-messages conversation existing-content)]
      (is (nil? result))))

  (testing "Handle missing obsidized_at (corrupted frontmatter)"
    (let [existing-content "---\nuuid: conv-123\ntype: conversation\n---\n\n# Test" ; No obsidized_at
          conversation {:uuid "conv-123"
                        :chats [{:q "Hello" :a "Hi!" :create_time "2025-08-05T10:30:00Z"}]}
          result (conv/determine-new-messages conversation existing-content)]
      (is (nil? result)))))

(deftest process-conversation-incremental-test
  (testing "Create new conversation file"
    (let [conversation {:uuid "conv-new"
                        :name "New Conversation"
                        :created_at "2025-08-05T12:00:00Z"
                        :updated_at "2025-08-05T12:00:00Z"
                        :chats [{:q "Hello" :a "Hi!" :create_time "2025-08-05T12:00:00Z"}]}]

      (conv/process-conversation conversation test-output-dir "1.0.0" {})

      (let [files (filter #(.isFile %) (file-seq (io/file test-output-dir)))
            created-file (first files)]
        (is (= 1 (count files)))
        (is created-file)
        (let [content (slurp created-file)]
          (is (str/includes? content "uuid: conv-new"))
          (is (str/includes? content "obsidized_at:"))
          (is (str/includes? content "type: conversation"))
          (is (str/includes? content "Hello"))))))

  (testing "Append new messages to existing conversation"
    ;; First, create an existing conversation file
    (let [existing-content "---\nuuid: conv-123\ncreated_at: 2025-08-04T10:30:00Z\nupdated_at: 2025-08-04T15:45:00Z\nobsidized_at: 2025-08-05T09:15:00Z\ntype: conversation\nsource: claude-export\nobsidize_version: 1.0.0\n---\n\n# Test Conversation\n\n**2025-08-04T10:30:00Z Me:** Hello\n\n**Claude:** Hi there!"
          filename "test-conversation__conv-123.md"
          filepath (str test-output-dir "/" filename)]

      ;; Create the existing file
      (spit filepath existing-content)

      ;; Process conversation with new messages
      (let [updated-conversation {:uuid "conv-123"
                                  :name "Test Conversation"
                                  :created_at "2025-08-04T10:30:00Z"
                                  :updated_at "2025-08-05T12:00:00Z" ; Updated
                                  :chats [{:q "Hello" :a "Hi there!" :create_time "2025-08-04T10:30:00Z"} ; Existing
                                          {:q "How are you?" :a "I'm good!" :create_time "2025-08-05T10:30:00Z"}]}] ; New

        (conv/process-conversation updated-conversation test-output-dir "1.0.0" {})

        (let [updated-content (slurp filepath)]
          ;; Should contain original message
          (is (str/includes? updated-content "Hello"))
          (is (str/includes? updated-content "Hi there!"))

          ;; Should contain new message  
          (is (str/includes? updated-content "How are you?"))
          (is (str/includes? updated-content "I'm good!"))

          ;; Should have updated timestamps
          (is (str/includes? updated-content "updated_at: 2025-08-05T12:00:00Z"))
          (is (str/includes? updated-content "obsidized_at: 2025-08-")) ; Should be updated to current time

          ;; Should not duplicate existing content
          (is (= 1 (count (re-seq #"Hello" updated-content))))))))

  (testing "Skip conversation with no new messages"
    ;; Create existing file
    (let [existing-content "---\nuuid: conv-123\nobsidized_at: 2025-08-05T12:00:00Z\ntype: conversation\n---\n\n# Test\n\n**Me:** Hello"
          filepath (str test-output-dir "/test__conv-123.md")]

      (spit filepath existing-content)
      (let [original-content (slurp filepath)]

        ;; Process same conversation (no new messages)
        (conv/process-conversation {:uuid "conv-123"
                                    :name "Test"
                                    :chats [{:q "Hello" :a "Hi!" :create_time "2025-08-05T10:00:00Z"}]} ; Before obsidized_at
                                   test-output-dir "1.0.0" {})

        ;; File should be unchanged
        (let [final-content (slurp filepath)]
          (is (= original-content final-content)))))))
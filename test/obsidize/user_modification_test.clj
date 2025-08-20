(ns obsidize.user-modification-test
  "Comprehensive tests for handling user modifications to Obsidian vault files"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [obsidize.conversations :as conv]))

(def test-vault-dir "target/test-user-modifications")

(defn setup-test-vault
  "Create a test vault with initial obsidize-generated content"
  []
  (let [vault-dir (io/file test-vault-dir)]
    (.mkdirs vault-dir)

    ;; Create initial conversation file (simulating obsidize-generated content)
    (spit (str test-vault-dir "/initial-chat__conv-123.md")
          "---
uuid: conv-123
title: Initial Chat
created_at: 2025-08-04T10:30:00Z
updated_at: 2025-08-04T15:45:00Z
obsidized_at: 2025-08-05T09:15:00Z
type: conversation
source: claude-export
obsidize_version: 1.0.0
---

# Initial Chat

**2025-08-04T10:30:00Z Me:** Hello Claude

**Claude:** Hi there! How can I help you today?

**2025-08-04T11:00:00Z Me:** Can you explain quantum computing?

**Claude:** Quantum computing is a fascinating field that leverages quantum mechanics...")))

(defn cleanup-test-vault
  "Clean up test vault directory"
  []
  (let [vault-dir (io/file test-vault-dir)]
    (when (.exists vault-dir)
      (doseq [file (reverse (file-seq vault-dir))]
        (when (.exists file)
          (.delete file))))))

(use-fixtures :each
  (fn [test-fn]
    (cleanup-test-vault)
    (setup-test-vault)
    (try
      (test-fn)
      (finally
        (cleanup-test-vault)))))

;; =============================================================================
;; CRITICAL: User Content Modification Tests
;; =============================================================================

(deftest user-added-notes-preservation-test
  (testing "Preserve user-added notes when appending new messages"
    ;; Step 1: User manually adds notes to the conversation
    (let [original-file (str test-vault-dir "/initial-chat__conv-123.md")
          original-content (slurp original-file)

          ;; Simulate user adding personal notes
          user-modified-content (str original-content "

## My Notes

This is really interesting! I should research more about:
- Quantum superposition
- Quantum entanglement
- Real-world applications

**Personal reminder:** Follow up on this topic next week.")]

      ;; User saves their modifications  
      (spit original-file user-modified-content)

      ;; Step 2: New Claude export arrives with additional messages
      (let [updated-conversation {:uuid "conv-123"
                                  :name "Initial Chat"
                                  :created_at "2025-08-04T10:30:00Z"
                                  :updated_at "2025-08-05T14:00:00Z"
                                  :chats [{:q "Hello Claude" :a "Hi there! How can I help you today?" :create_time "2025-08-04T10:30:00Z"}
                                          {:q "Can you explain quantum computing?" :a "Quantum computing is a fascinating field..." :create_time "2025-08-04T11:00:00Z"}
                                          {:q "What are some practical applications?" :a "Great question! Some practical applications include..." :create_time "2025-08-05T12:30:00Z"}]}]

        ;; Step 3: Process the conversation update
        (conv/process-conversation updated-conversation test-vault-dir "1.0.0" {})

        ;; Step 4: Verify user content is preserved AND new message is added
        (let [final-content (slurp original-file)]
          ;; User notes should be preserved
          (is (str/includes? final-content "## My Notes"))
          (is (str/includes? final-content "This is really interesting!"))
          (is (str/includes? final-content "- Quantum superposition"))
          (is (str/includes? final-content "**Personal reminder:**"))

          ;; New message should be appended
          (is (str/includes? final-content "What are some practical applications?"))
          (is (str/includes? final-content "Great question! Some practical applications"))

          ;; Timestamps should be updated
          (is (str/includes? final-content "updated_at: 2025-08-05T14:00:00Z"))
          (is (str/includes? final-content "obsidized_at: 2025-08-")))))))

(deftest user-modified-frontmatter-handling-test
  (testing "Handle user modifications to frontmatter gracefully"
    ;; Step 1: User modifies frontmatter (adds custom fields, changes title)
    (let [original-file (str test-vault-dir "/initial-chat__conv-123.md")
          original-content (slurp original-file)

          ;; User adds custom frontmatter fields and changes title
          modified-frontmatter-content (str/replace original-content
                                                    "title: Initial Chat"
                                                    "title: My Quantum Computing Discussion
tags: [quantum, physics, learning]
priority: high
my_rating: 5")]

      (spit original-file modified-frontmatter-content)

      ;; Step 2: Process conversation update
      (let [updated-conversation {:uuid "conv-123"
                                  :name "Initial Chat" ; Original Claude title
                                  :created_at "2025-08-04T10:30:00Z"
                                  :updated_at "2025-08-05T16:00:00Z"
                                  :chats [{:q "Hello Claude" :a "Hi there! How can I help you today?" :create_time "2025-08-04T10:30:00Z"}
                                          {:q "Can you explain quantum computing?" :a "Quantum computing is a fascinating field..." :create_time "2025-08-04T11:00:00Z"}
                                          {:q "Any good books on this topic?" :a "Absolutely! I recommend..." :create_time "2025-08-05T15:30:00Z"}]}]

        (conv/process-conversation updated-conversation test-vault-dir "1.0.0" {})

        ;; Step 3: Verify user's custom frontmatter is preserved
        (let [final-content (slurp original-file)]
          ;; User's custom title should be preserved (not overwritten by Claude's)
          (is (str/includes? final-content "title: My Quantum Computing Discussion"))
          (is (str/includes? final-content "tags: [quantum, physics, learning]"))
          (is (str/includes? final-content "priority: high"))
          (is (str/includes? final-content "my_rating: 5"))

          ;; System fields should still be updated
          (is (str/includes? final-content "updated_at: 2025-08-05T16:00:00Z"))
          (is (str/includes? final-content "uuid: conv-123")) ; Should remain unchanged

          ;; New message should be appended
          (is (str/includes? final-content "Any good books on this topic?"))
          (is (str/includes? final-content "Absolutely! I recommend")))))))

(deftest user-reordered-content-handling-test
  (testing "Handle user reordering/restructuring conversation content"
    ;; Step 1: User completely restructures the conversation content
    (let [original-file (str test-vault-dir "/initial-chat__conv-123.md")

          ;; User creates a completely new structure with the same messages
          restructured-content "---
uuid: conv-123
title: My Quantum Computing Q&A
created_at: 2025-08-04T10:30:00Z
updated_at: 2025-08-04T15:45:00Z
obsidized_at: 2025-08-05T09:15:00Z
type: conversation
source: claude-export
obsidize_version: 1.0.0
tags: [quantum, learning]
---

# My Quantum Computing Q&A

## Introduction
This conversation covers the basics of quantum computing.

## Questions and Answers

### Q1: Basic Greeting
**2025-08-04T10:30:00Z Me:** Hello Claude
**Claude:** Hi there! How can I help you today?

### Q2: Main Topic  
**2025-08-04T11:00:00Z Me:** Can you explain quantum computing?
**Claude:** Quantum computing is a fascinating field that leverages quantum mechanics...

## My Summary
- Quantum computing uses quantum mechanics
- Very promising technology
- Need to learn more!"]

      (spit original-file restructured-content)

      ;; Step 2: Process new message
      (let [updated-conversation {:uuid "conv-123"
                                  :name "Initial Chat"
                                  :created_at "2025-08-04T10:30:00Z"
                                  :updated_at "2025-08-05T17:00:00Z"
                                  :chats [{:q "Hello Claude" :a "Hi there! How can I help you today?" :create_time "2025-08-04T10:30:00Z"}
                                          {:q "Can you explain quantum computing?" :a "Quantum computing is a fascinating field..." :create_time "2025-08-04T11:00:00Z"}
                                          {:q "What about quantum algorithms?" :a "Quantum algorithms are specialized..." :create_time "2025-08-05T16:45:00Z"}]}]

        (conv/process-conversation updated-conversation test-vault-dir "1.0.0" {})

        ;; Step 3: Verify user structure is preserved
        (let [final-content (slurp original-file)]
          ;; User's custom structure should be preserved
          (is (str/includes? final-content "# My Quantum Computing Q&A"))
          (is (str/includes? final-content "## Introduction"))
          (is (str/includes? final-content "### Q1: Basic Greeting"))
          (is (str/includes? final-content "### Q2: Main Topic"))
          (is (str/includes? final-content "## My Summary"))
          (is (str/includes? final-content "- Quantum computing uses quantum mechanics"))

          ;; New message should still be appended (though it may not fit the user's structure perfectly)
          (is (str/includes? final-content "What about quantum algorithms?"))
          (is (str/includes? final-content "Quantum algorithms are specialized")))))))

(deftest user-deleted-messages-handling-test
  (testing "Handle cases where user deleted some messages"
    ;; Step 1: User deletes one of the original messages
    (let [original-file (str test-vault-dir "/initial-chat__conv-123.md")
          original-content (slurp original-file)

          ;; User deletes the quantum computing question/answer
          content-with-deleted-message (str/replace original-content
                                                    #"(?s)\*\*2025-08-04T11:00:00Z Me:\*\* Can you explain quantum computing\?.*?quantum mechanics\.\.\."
                                                    "")]

      (spit original-file content-with-deleted-message)

      ;; Step 2: Process conversation update with new message
      (let [updated-conversation {:uuid "conv-123"
                                  :name "Initial Chat"
                                  :created_at "2025-08-04T10:30:00Z"
                                  :updated_at "2025-08-05T18:00:00Z"
                                  :chats [{:q "Hello Claude" :a "Hi there! How can I help you today?" :create_time "2025-08-04T10:30:00Z"}
                                          {:q "Can you explain quantum computing?" :a "Quantum computing is a fascinating field..." :create_time "2025-08-04T11:00:00Z"}
                                          {:q "Thanks for the explanation!" :a "You're welcome! Feel free to ask more questions." :create_time "2025-08-05T17:30:00Z"}]}]

        (conv/process-conversation updated-conversation test-vault-dir "1.0.0" {})

        ;; Step 3: Verify behavior (current implementation will just append)
        (let [final-content (slurp original-file)]
          ;; The deleted message should remain deleted (user's choice respected)
          (is (not (str/includes? final-content "Can you explain quantum computing?")))

          ;; New message should still be appended
          (is (str/includes? final-content "Thanks for the explanation!"))
          (is (str/includes? final-content "You're welcome! Feel free to ask")))))))

;; =============================================================================
;; EDGE CASES: Corrupted or Invalid Modifications  
;; =============================================================================

(deftest corrupted-frontmatter-recovery-test
  (testing "Recover gracefully from user-corrupted frontmatter"
    ;; Step 1: User accidentally corrupts the YAML frontmatter
    (let [original-file (str test-vault-dir "/initial-chat__conv-123.md")

          ;; Simulate various frontmatter corruption scenarios
          corrupted-content "---
uuid: conv-123
title: Initial Chat
created_at: 2025-08-04T10:30:00Z
updated_at: 2025-08-04T15:45:00Z
obsidized_at: invalid-timestamp-format
type conversation  # Missing colon
source: claude-export
obsidize_version: 1.0.0
broken yaml syntax here
---

# Initial Chat

**2025-08-04T10:30:00Z Me:** Hello Claude
**Claude:** Hi there! How can I help you today?"]

      (spit original-file corrupted-content)

      ;; Step 2: Attempt to process conversation update
      (let [updated-conversation {:uuid "conv-123"
                                  :name "Initial Chat"
                                  :created_at "2025-08-04T10:30:00Z"
                                  :updated_at "2025-08-05T19:00:00Z"
                                  :chats [{:q "Hello Claude" :a "Hi there! How can I help you today?" :create_time "2025-08-04T10:30:00Z"}
                                          {:q "How are you today?" :a "I'm doing well, thank you for asking!" :create_time "2025-08-05T18:30:00Z"}]}]

        ;; Should not crash, should handle gracefully
        (is (= :success (try
                          (conv/process-conversation updated-conversation test-vault-dir "1.0.0" {})
                          :success
                          (catch Exception e
                            (println "Expected: Graceful handling of corrupted frontmatter, got exception:" (.getMessage e))
                            :exception))))

        ;; In case of corruption, system should either:
        ;; Option 1: Skip the file and log warning
        ;; Option 2: Regenerate the file completely (preserving user content in comments)
        ;; Option 3: Create a backup and recreate  
        (let [final-content (slurp original-file)]
          ;; At minimum, should not crash the entire process
          (is (string? final-content))
          (is (> (count final-content) 0)))))))

(deftest missing-uuid-handling-test
  (testing "Handle files where user removed the UUID"
    ;; Step 1: User removes UUID from frontmatter
    (let [original-file (str test-vault-dir "/initial-chat__conv-123.md")
          content-without-uuid (str/replace (slurp original-file)
                                            "uuid: conv-123\n"
                                            "")]

      (spit original-file content-without-uuid)

      ;; Step 2: Try to process update  
      (let [updated-conversation {:uuid "conv-123"
                                  :name "Initial Chat"
                                  :created_at "2025-08-04T10:30:00Z"
                                  :updated_at "2025-08-05T20:00:00Z"
                                  :chats [{:q "Hello Claude" :a "Hi there! How can I help you today?" :create_time "2025-08-04T10:30:00Z"}
                                          {:q "Test message" :a "Test response" :create_time "2025-08-05T19:30:00Z"}]}]

        ;; Should handle gracefully (probably create a new file or skip)
        (conv/process-conversation updated-conversation test-vault-dir "1.0.0" {})

        ;; Verify graceful handling (specific behavior depends on implementation choice)
        (let [files-in-vault (->> (file-seq (io/file test-vault-dir))
                                  (filter #(.isFile %))
                                  (map #(.getName %)))]
          ;; Should have handled this somehow - either updated existing or created new
          (is (>= (count files-in-vault) 1)))))))

;; =============================================================================
;; PERFORMANCE: User Modifications at Scale
;; =============================================================================

(deftest large-user-modified-file-test
  (testing "Handle large files with user modifications efficiently"
    ;; Step 1: Create a conversation with many messages and user modifications
    (let [original-file (str test-vault-dir "/large-discussion__conv-456.md")

          ;; Generate large conversation content with user notes interspersed
          large-content (str "---
uuid: conv-456
title: Large Discussion
created_at: 2025-08-04T08:00:00Z
updated_at: 2025-08-04T16:00:00Z
obsidized_at: 2025-08-05T10:00:00Z
type: conversation
source: claude-export
obsidize_version: 1.0.0
---

# Large Discussion

"
                             ;; Generate 100 messages with user notes
                             (str/join "\n"
                                       (for [i (range 100)]
                                         (str "**2025-08-04T" (format "%02d" (+ 8 (quot i 10))) ":" (format "%02d" (* (mod i 10) 6)) ":00Z Me:** Question " i
                                              "\n\n**Claude:** Response " i
                                              "\n\n<!-- User note " i ": This is interesting! -->"))))]

      (spit original-file large-content)

      ;; Step 2: Process update with new messages
      (let [start-time (System/currentTimeMillis)

            ;; Create conversation with original + new messages
            updated-conversation {:uuid "conv-456"
                                  :name "Large Discussion"
                                  :created_at "2025-08-04T08:00:00Z"
                                  :updated_at "2025-08-05T12:00:00Z"
                                  :chats (concat
                                          ;; Original 100 messages
                                          (for [i (range 100)]
                                            {:q (str "Question " i)
                                             :a (str "Response " i)
                                             :create_time (str "2025-08-04T" (format "%02d" (+ 8 (quot i 10))) ":" (format "%02d" (* (mod i 10) 6)) ":00Z")})
                                          ;; 5 new messages
                                          (for [i (range 5)]
                                            {:q (str "New question " i)
                                             :a (str "New response " i)
                                             :create_time (str "2025-08-05T11:" (format "%02d" (* i 10)) ":00Z")}))}]

        (conv/process-conversation updated-conversation test-vault-dir "1.0.0" {})

        (let [end-time (System/currentTimeMillis)
              processing-time (- end-time start-time)]

          ;; Should complete in reasonable time (under 5 seconds for 100+ messages)
          (is (< processing-time 5000)
              (str "Processing took " processing-time "ms, expected under 5000ms"))

          ;; Verify all user notes are preserved
          (let [final-content (slurp original-file)]
            (is (str/includes? final-content "<!-- User note 50: This is interesting! -->"))
            (is (str/includes? final-content "New question 0"))
            (is (str/includes? final-content "New response 4"))))))))

;; =============================================================================
;; INTEGRATION: Full Workflow with User Modifications
;; =============================================================================

(deftest complete-user-modification-workflow-test
  (testing "End-to-end workflow with various user modifications"
    ;; This test simulates a realistic user workflow over multiple obsidize runs

    ;; Step 1: Initial conversation creation (obsidize first run)
    (let [initial-conversation {:uuid "conv-789"
                                :name "Planning Session"
                                :created_at "2025-08-01T09:00:00Z"
                                :updated_at "2025-08-01T11:00:00Z"
                                :chats [{:q "Let's plan our project" :a "Great! What's the project about?" :create_time "2025-08-01T09:00:00Z"}
                                        {:q "It's about building a task manager" :a "Excellent choice! Let's break it down." :create_time "2025-08-01T09:30:00Z"}]}]

      (conv/process-conversation initial-conversation test-vault-dir "1.0.0" {})

      ;; Step 2: User modifies the file over several days
      (let [conversation-file (first (filter #(str/includes? (.getName %) "conv-789")
                                             (file-seq (io/file test-vault-dir))))
            original-content (slurp conversation-file)

            ;; User adds extensive notes and modifications
            user-enhanced-content (str original-content "

## Project Planning Notes

### Key Requirements
- [ ] User authentication
- [ ] Task creation/editing  
- [ ] Due date reminders
- [ ] Team collaboration

### Technical Decisions
- **Frontend:** React + TypeScript
- **Backend:** Node.js + PostgreSQL
- **Hosting:** Vercel + Supabase

### Next Steps
1. Create wireframes
2. Set up development environment
3. Implement core features
4. Testing and deployment

**Note to self:** Remember to research existing solutions first!")]

        (spit conversation-file user-enhanced-content)

        ;; Step 3: New Claude conversation data arrives (obsidize second run)
        (let [updated-conversation {:uuid "conv-789"
                                    :name "Planning Session"
                                    :created_at "2025-08-01T09:00:00Z"
                                    :updated_at "2025-08-02T14:00:00Z"
                                    :chats [{:q "Let's plan our project" :a "Great! What's the project about?" :create_time "2025-08-01T09:00:00Z"}
                                            {:q "It's about building a task manager" :a "Excellent choice! Let's break it down." :create_time "2025-08-01T09:30:00Z"}
                                            {:q "What about the database design?" :a "For a task manager, you'll want tables for users, projects, tasks..." :create_time "2025-08-02T10:00:00Z"}
                                            {:q "Should we use microservices?" :a "For a task manager, I'd recommend starting with a monolith..." :create_time "2025-08-02T13:30:00Z"}]}]

          (conv/process-conversation updated-conversation test-vault-dir "1.0.0" {})

          ;; Step 4: Verify comprehensive preservation and addition
          (let [final-content (slurp conversation-file)]
            ;; All user content should be preserved
            (is (str/includes? final-content "## Project Planning Notes"))
            (is (str/includes? final-content "### Key Requirements"))
            (is (str/includes? final-content "- [ ] User authentication"))
            (is (str/includes? final-content "**Frontend:** React + TypeScript"))
            (is (str/includes? final-content "**Note to self:** Remember to research"))

            ;; New Claude messages should be appended
            (is (str/includes? final-content "What about the database design?"))
            (is (str/includes? final-content "Should we use microservices?"))
            (is (str/includes? final-content "I'd recommend starting with a monolith"))

            ;; Timestamps should be updated
            (is (str/includes? final-content "updated_at: 2025-08-02T14:00:00Z"))))))))
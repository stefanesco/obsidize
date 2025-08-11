(ns obsidize.templates-test
  "Tests for the templates namespace covering all template functions and constants."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [obsidize.templates :as templates]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest constants-test
  (testing "Application constants are defined"
    (is (= "claude-export" templates/source-identifier))
    (is (= "1.0.0" templates/obsidize-version))
    (is (= "---\n" templates/frontmatter-delimiter)))
  
  (testing "Placeholder constants are defined"
    (is (= "Untitled Conversation" templates/default-conversation-title))
    (is (= "Unknown Date" templates/default-date-prefix))
    (is (= "[Missing question]" templates/missing-question-placeholder))
    (is (= "[Missing answer]" templates/missing-answer-placeholder))
    (is (= "[No messages found]" templates/no-messages-placeholder))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Template Substitution Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest substitute-template-test
  (testing "Basic template substitution"
    (is (= "Hello World" 
           (templates/substitute-template "Hello {name}" {:name "World"})))
    (is (= "User 123 has 5 items" 
           (templates/substitute-template "User {id} has {count} items" 
                                        {:id 123 :count 5}))))
  
  (testing "Missing placeholders are left unchanged"
    (is (= "Hello {missing}" 
           (templates/substitute-template "Hello {missing}" {:name "World"}))))
  
  (testing "Extra context values are ignored"
    (is (= "Hello World" 
           (templates/substitute-template "Hello {name}" 
                                        {:name "World" :extra "ignored"})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Frontmatter Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest frontmatter-test
  (testing "Basic frontmatter formatting"
    (let [frontmatter {:uuid "123" :type "test" :created_at "2025-01-01"}
          result (templates/format-frontmatter frontmatter)]
      (is (str/starts-with? result "---\n"))
      (is (str/includes? result "uuid: 123"))
      (is (str/includes? result "type: test"))
      (is (str/includes? result "created_at: 2025-01-01"))
      (is (str/ends-with? result "---\n\n"))))
  
  (testing "Empty frontmatter"
    (let [result (templates/format-frontmatter {})]
      (is (= "---\n\n---\n\n" result))))
  
  (testing "Conversation frontmatter includes base fields"
    (is (= "conversation" (:type templates/conversation-frontmatter)))
    (is (= "claude-export" (:source templates/conversation-frontmatter)))
    (is (= "1.0.0" (:obsidize_version templates/conversation-frontmatter))))
  
  (testing "Project frontmatter includes base fields"
    (is (= "project-overview" (:type templates/project-overview-frontmatter)))
    (is (= "project-document" (:type templates/project-document-frontmatter)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Conversation Template Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest conversation-templates-test
  (testing "Conversation filename formatting"
    (is (= "test-chat__uuid-123.md" 
           (templates/format-conversation-filename "test-chat" "uuid-123"))))
  
  (testing "Conversation message formatting"
    (let [result (templates/format-conversation-message 
                  "2025-01-01 10:00:00" "Hello" "Hi there")]
      (is (str/includes? result "**2025-01-01 10:00:00 Me:** Hello"))
      (is (str/includes? result "**Claude:** Hi there"))))
  
  (testing "Complete conversation content formatting"
    (let [frontmatter {:uuid "123" :type "conversation"}
          title "Test Chat"
          messages ["**10:00 Me:** Hello\n\n**Claude:** Hi\n"]
          result (templates/format-conversation-content frontmatter title messages)]
      (is (str/starts-with? result "---\n"))
      (is (str/includes? result "uuid: 123"))
      (is (str/includes? result "# Test Chat"))
      (is (str/includes? result "**10:00 Me:** Hello")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Project Template Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest project-templates-test
  (testing "Project document filename formatting"
    (is (= "1_readme.md" 
           (templates/format-project-document-filename 1 "readme.md")))
    (is (= "10_setup-guide.md" 
           (templates/format-project-document-filename 10 "setup-guide.md"))))
  
  (testing "Project overview filename formatting"
    (is (= "my-project.md" 
           (templates/format-project-overview-filename "my-project"))))
  
  (testing "Project documents section formatting"
    (let [filenames ["doc1.md" "doc2.md" "doc3.md"]
          result (templates/format-project-documents-section filenames)]
      (is (str/includes? result "## Project Documents"))
      (is (str/includes? result "- [[doc1.md]]"))
      (is (str/includes? result "- [[doc2.md]]"))
      (is (str/includes? result "- [[doc3.md]]"))))
  
  (testing "Empty documents section"
    (is (nil? (templates/format-project-documents-section [])))
    (is (nil? (templates/format-project-documents-section nil))))
  
  (testing "Project links section formatting"
    (let [links ["Project Alpha" "Project Beta"]
          result (templates/format-project-links-section links)]
      (is (str/includes? result "## Linked to"))
      (is (str/includes? result "- [[Project Alpha]]"))
      (is (str/includes? result "- [[Project Beta]]"))))
  
  (testing "Empty links section"
    (is (nil? (templates/format-project-links-section [])))
    (is (nil? (templates/format-project-links-section nil))))
  
  (testing "Complete project content formatting"
    (let [frontmatter {:uuid "proj-123" :type "project-overview"}
          name "Test Project"
          description "A test project"
          docs-section "\n\n## Project Documents\n\n- [[doc1.md]]"
          links-section nil
          tags-section nil
          result (templates/format-project-content 
                  frontmatter name description docs-section links-section tags-section)]
      (is (str/starts-with? result "---\n"))
      (is (str/includes? result "uuid: proj-123"))
      (is (str/includes? result "# Test Project"))
      (is (str/includes? result "A test project"))
      (is (str/includes? result "## Project Documents")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validation and Utility Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest validation-utilities-test
  (testing "Template placeholder extraction"
    (let [template "Hello {name}, you have {count} items in {category}"
          placeholders (templates/list-template-placeholders template)]
      (is (= #{:name :count :category} placeholders))))
  
  (testing "Empty template placeholders"
    (is (= #{} (templates/list-template-placeholders "No placeholders here"))))
  
  (testing "Template validation - valid"
    (let [template "Hello {name}"
          required [:name]]
      (is (nil? (templates/validate-template template required)))))
  
  (testing "Template validation - missing keys"
    (let [template "Hello {name}"
          required [:name :age]]
      (is (some? (templates/validate-template template required))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integration Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest integration-test
  (testing "End-to-end conversation generation"
    (let [conversation {:uuid "conv-123"
                       :name "Test Chat"
                       :created_at "2025-01-01T10:00:00Z"
                       :updated_at "2025-01-01T10:30:00Z"
                       :chats [{:q "Hello" :a "Hi" :create_time "2025-01-01T10:00:00Z"}]}
          ;; Simulate the process
          title (:name conversation)
          filename (templates/format-conversation-filename title (:uuid conversation))
          message (templates/format-conversation-message 
                   "2025-01-01 10:00:00" "Hello" "Hi")
          frontmatter (merge templates/conversation-frontmatter
                            {:uuid (:uuid conversation)
                             :created_at (:created_at conversation)
                             :updated_at (:updated_at conversation)
                             :obsidized_at "2025-01-01T11:00:00Z"})
          content (templates/format-conversation-content frontmatter title [message])]
      
      (is (= "Test Chat__conv-123.md" filename))
      (is (str/includes? content "uuid: conv-123"))
      (is (str/includes? content "# Test Chat"))
      (is (str/includes? content "**2025-01-01 10:00:00 Me:** Hello"))
      (is (str/includes? content "**Claude:** Hi"))))
  
  (testing "End-to-end project generation"
    (let [project {:uuid "proj-456"
                  :name "My Project"
                  :description "A sample project"
                  :created_at "2025-01-01T09:00:00Z"
                  :updated_at "2025-01-01T09:30:00Z"
                  :docs [{:filename "readme.md" :content "# README"}
                         {:filename "guide.md" :content "# Guide"}]}
          ;; Simulate the process
          doc-filenames [(templates/format-project-document-filename 1 "readme.md")
                        (templates/format-project-document-filename 2 "guide.md")]
          sanitized-name "my-project"
          overview-filename (templates/format-project-overview-filename sanitized-name)
          docs-section (templates/format-project-documents-section doc-filenames)
          frontmatter (merge templates/project-overview-frontmatter
                            {:uuid (:uuid project)
                             :project_name (:name project)
                             :created_at (:created_at project)
                             :updated_at (:updated_at project)
                             :obsidized_at "2025-01-01T10:00:00Z"})
          content (templates/format-project-content
                   frontmatter (:name project) (:description project)
                   docs-section nil nil)]
      
      (is (= ["1_readme.md" "2_guide.md"] doc-filenames))
      (is (= "my-project.md" overview-filename))
      (is (str/includes? content "uuid: proj-456"))
      (is (str/includes? content "# My Project"))
      (is (str/includes? content "A sample project"))
      (is (str/includes? content "## Project Documents")))))

;; Tests run via Kaocha
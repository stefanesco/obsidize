(ns obsidize.processing-test
  "Unit tests for consolidated utility functions (formerly processing utilities)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [obsidize.utils :as sut]
            [obsidize.templates :as templates]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Data
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def test-messages
  [{:id 1 :create_time "2024-01-01T10:00:00Z" :text "First message"}
   {:id 2 :create_time "2024-01-01T12:00:00Z" :text "Second message"}
   {:id 3 :create_time "2024-01-01T08:00:00Z" :text "Third message"}])

(def test-documents
  [{:id 1 :created_at "2024-01-02T10:00:00Z" :title "Doc B"}
   {:id 2 :created_at "2024-01-01T10:00:00Z" :title "Doc A"}
   {:id 3 :created_at "2024-01-03T10:00:00Z" :title "Doc C"}])

(def base-frontmatter
  {:uuid "test-uuid"
   :created_at "2024-01-01T10:00:00Z"
   :obsidize_version "1.0.0"})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Timestamp Processing Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; NOTE: process-items-with-timestamps was removed as over-engineered
;; Functionality merged into sort-by-timestamp for simplicity

(deftest extract-latest-timestamp-test
  (testing "Extract latest timestamp from collection"
    (let [result (sut/extract-latest-timestamp test-messages :create_time)]
      (is (= "2024-01-01T12:00:00Z" result))))

  (testing "Extract latest timestamp from documents"
    (let [result (sut/extract-latest-timestamp test-documents :created_at)]
      (is (= "2024-01-03T10:00:00Z" result))))

  (testing "Empty collection returns nil"
    (let [result (sut/extract-latest-timestamp [] :create_time)]
      (is (nil? result))))

  (testing "Collection with no valid timestamps returns nil"
    (let [items [{:id 1} {:id 2}]
          result (sut/extract-latest-timestamp items :create_time)]
      (is (nil? result))))

  (testing "Collection with mixed valid/invalid timestamps"
    (let [items [{:create_time "2024-01-01T10:00:00Z"} {:id 2} {:create_time "2024-01-02T10:00:00Z"}]
          result (sut/extract-latest-timestamp items :create_time)]
      (is (= "2024-01-02T10:00:00Z" result)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Frontmatter Generation Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; NOTE: generate-frontmatter was simplified into create-frontmatter-with-timestamps
;; which now handles the common use case more directly

(deftest create-frontmatter-with-options-test
  (testing "Add tags and links when present with 3-arity function"
    (let [template templates/conversation-frontmatter
          data-map {:uuid "test-uuid" :created_at "2024-01-01T10:00:00Z"}
          options {:tags ["ai" "claude"] :links ["[[Notes]]" "[[AI Tools]]"]}
          result (sut/create-frontmatter-with-timestamps template data-map options)]
      (is (= "test-uuid" (:uuid result)))
      (is (= "2024-01-01T10:00:00Z" (:created_at result)))
      (is (= ["ai" "claude"] (:tags result)))
      (is (= ["[[Notes]]" "[[AI Tools]]"] (:links result)))
      (is (string? (:obsidized_at result)))))

  (testing "Handle comma-separated strings"
    (let [template templates/conversation-frontmatter
          data-map {:uuid "test-uuid" :created_at "2024-01-01T10:00:00Z"}
          options {:tags "ai,claude" :links "[[Notes]],[[AI Tools]]"}
          result (sut/create-frontmatter-with-timestamps template data-map options)]
      (is (= ["ai" "claude"] (:tags result)))
      (is (= ["[[Notes]]" "[[AI Tools]]"] (:links result)))))

  (testing "Skip empty tags and links"
    (let [template templates/conversation-frontmatter
          data-map {:uuid "test-uuid" :created_at "2024-01-01T10:00:00Z"}
          options {:tags [] :links nil}
          result (sut/create-frontmatter-with-timestamps template data-map options)]
      (is (not (contains? result :tags)))
      (is (not (contains? result :links)))))

  (testing "2-arity function without options"
    (let [template templates/conversation-frontmatter
          data-map {:uuid "test-uuid" :created_at "2024-01-01T10:00:00Z"}
          result (sut/create-frontmatter-with-timestamps template data-map)]
      (is (= "test-uuid" (:uuid result)))
      (is (= "2024-01-01T10:00:00Z" (:created_at result)))
      (is (string? (:obsidized_at result))))))

;; NOTE: create-frontmatter-with-timestamps-and-options functionality
;; is now integrated into create-frontmatter-with-timestamps 3-arity version

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Content Processing Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest generate-content-with-sections-test
  (testing "Generate content with frontmatter and sections"
    (let [frontmatter {:title "Test" :uuid "test-uuid"}
          content-fn (fn [sections]
                       (str (:frontmatter-yaml sections) "\n# " (:title sections)))
          sections {:title "Test Title"}
          result (sut/generate-content-with-sections frontmatter content-fn sections)]
      (is (str/includes? result "title: Test"))
      (is (str/includes? result "uuid: test-uuid"))
      (is (str/includes? result "# Test Title")))))

(deftest sort-by-timestamp-test
  (testing "Sort documents by created_at"
    (let [result (sut/sort-by-timestamp test-documents :created_at)]
      (is (= 3 (count result)))
      (is (= "Doc A" (:title (first result))))
      (is (= "Doc C" (:title (last result))))))

  (testing "Sort messages by create_time"
    (let [result (sut/sort-by-timestamp test-messages :create_time)]
      (is (= 3 (count result)))
      (is (= "Third message" (:text (first result))))
      (is (= "Second message" (:text (last result)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; File Processing Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest generate-safe-values-test
  (testing "Extract safe values with defaults"
    (let [data {:name "Test" :uuid "123"}
          defaults {:name "Default Name" :description "Default Desc" :version "1.0"}
          result (sut/generate-safe-values data defaults)]
      (is (= "Test" (:name result)))
      (is (= "123" (:uuid result)))
      (is (= "Default Desc" (:description result)))
      (is (= "1.0" (:version result)))))

  (testing "Handle nil values in data"
    (let [data {:name nil :uuid "123"}
          defaults {:name "Default Name" :description "Default Desc"}
          result (sut/generate-safe-values data defaults)]
      (is (= "Default Name" (:name result)))
      (is (= "123" (:uuid result)))
      (is (= "Default Desc" (:description result)))))

  (testing "Empty data with defaults"
    (let [data {}
          defaults {:name "Default" :version "1.0"}
          result (sut/generate-safe-values data defaults)]
      (is (= "Default" (:name result)))
      (is (= "1.0" (:version result))))))

(deftest process-with-defaults-test
  (testing "Merge data with defaults"
    (let [data {:name "Test" :version "2.0"}
          defaults {:name "Default" :description "Default Desc" :version "1.0"}
          result (sut/process-with-defaults data defaults)]
      (is (= "Test" (:name result)))
      (is (= "2.0" (:version result)))
      (is (= "Default Desc" (:description result)))))

  (testing "Empty data uses all defaults"
    (let [data {}
          defaults {:name "Default" :version "1.0"}
          result (sut/process-with-defaults data defaults)]
      (is (= defaults result))))

  (testing "Nil data handled gracefully"
    (let [data nil
          defaults {:name "Default"}
          result (sut/process-with-defaults data defaults)]
      (is (= "Default" (:name result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integration Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest utilities-integration-test
  (testing "Complete workflow using consolidated utility functions"
    (let [;; Raw data simulation
          raw-data {:uuid "conv-123" :name nil :chats test-messages}
          defaults {:name "Untitled Conversation" :description ""}

          ;; Step 1: Process with safe defaults
          safe-data (sut/process-with-defaults raw-data defaults)

          ;; Step 2: Extract latest timestamp
          latest-time (sut/extract-latest-timestamp (:chats safe-data) :create_time)

          ;; Step 3: Sort messages chronologically  
          sorted-messages (sut/sort-by-timestamp (:chats safe-data) :create_time)

          ;; Step 4: Generate frontmatter with options
          options {:tags ["test"] :links ["[[Notes]]"]}
          frontmatter (sut/create-frontmatter-with-timestamps
                       templates/conversation-frontmatter
                       {:uuid (:uuid safe-data)
                        :created_at (first (map :create_time sorted-messages))
                        :updated_at latest-time}
                       options)]

      ;; Verify the complete workflow
      (is (= "Untitled Conversation" (:name safe-data)))
      (is (= "2024-01-01T12:00:00Z" latest-time))
      (is (= 3 (count sorted-messages)))
      (is (= "Third message" (:text (first sorted-messages))))
      (is (= "conv-123" (:uuid frontmatter)))
      (is (= ["test"] (:tags frontmatter)))
      (is (= ["[[Notes]]"] (:links frontmatter)))
      (is (string? (:obsidized_at frontmatter))))))
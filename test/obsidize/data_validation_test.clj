(ns obsidize.data-validation-test
  (:require [clojure.test :refer [deftest is testing]]
            [obsidize.data-validation :as dv]))

(deftest valid-uuid-test
  (testing "UUID validation"
    (is (dv/valid-uuid? "550e8400-e29b-41d4-a716-446655440000"))
    (is (not (dv/valid-uuid? "")))
    (is (not (dv/valid-uuid? nil)))
    (is (not (dv/valid-uuid? "invalid-uuid")))
    (is (not (dv/valid-uuid? "too-short")))))

(deftest valid-timestamp-test
  (testing "Timestamp validation"
    (is (dv/valid-timestamp? "2025-08-05T10:30:00Z"))
    (is (dv/valid-timestamp? "2025-08-05T10:30:00.123Z"))
    (is (not (dv/valid-timestamp? "")))
    (is (not (dv/valid-timestamp? nil)))
    (is (not (dv/valid-timestamp? "invalid-timestamp")))
    (is (not (dv/valid-timestamp? "2025-13-45")))))

(deftest safe-get-test
  (testing "Safe map access"
    (let [test-map {:a 1 :b nil :c "value"}]
      (is (= 1 (dv/safe-get test-map :a)))
      (is (= nil (dv/safe-get test-map :b)))
      (is (= "default" (dv/safe-get test-map :b "default")))
      (is (= nil (dv/safe-get test-map :missing)))
      (is (= "fallback" (dv/safe-get test-map :missing "fallback"))))))

(deftest validate-conversation-test
  (testing "Valid conversation passes through"
    (let [valid-conv {:uuid "550e8400-e29b-41d4-a716-446655440000"
                      :name "Test Chat"
                      :created_at "2025-08-05T10:30:00Z"
                      :updated_at "2025-08-05T11:00:00Z"
                      :chats [{:q "Hello" :a "Hi!" :create_time "2025-08-05T10:30:00Z"}]}
          result (dv/validate-conversation valid-conv)]
      (is (not (contains? result :validation-error)))
      (is (= "550e8400-e29b-41d4-a716-446655440000" (:uuid result)))
      (is (= "Test Chat" (:name result)))
      (is (= 1 (count (:chats result))))))

  (testing "Missing UUID causes validation error"
    (let [invalid-conv {:name "No UUID"}
          result (dv/validate-conversation invalid-conv)]
      (is (contains? result :validation-error))
      (is (contains? result :original-data))))

  (testing "Invalid chats are filtered out"
    (let [mixed-conv {:uuid "550e8400-e29b-41d4-a716-446655440000"
                      :name "Mixed Chat"
                      :created_at "2025-08-05T10:30:00Z"
                      :chats [{:q "Good" :a "Valid" :create_time "2025-08-05T10:30:00Z"}
                              {:q "" :a "Invalid - empty Q"} ; Should be filtered
                              {:q "Also good" :a "Valid too" :create_time "2025-08-05T10:31:00Z"}
                              "not-a-map" ; Should be filtered
                              {:q "Missing A"}]} ; Should be filtered
          result (dv/validate-conversation mixed-conv)]
      (is (not (contains? result :validation-error)))
      (is (= 2 (count (:chats result)))) ; Only 2 valid chats
      (is (contains? result :validation-warnings))))

  (testing "Missing fields get defaults"
    (let [minimal-conv {:uuid "550e8400-e29b-41d4-a716-446655440000"}
          result (dv/validate-conversation minimal-conv)]
      (is (not (contains? result :validation-error)))
      (is (= "Untitled Conversation" (:name result)))
      (is (= "1970-01-01T00:00:00Z" (:created_at result)))
      (is (= [] (:chats result))))))

(deftest validate-project-test
  (testing "Valid project passes through"
    (let [valid-proj {:uuid "550e8400-e29b-41d4-a716-446655440000"
                      :name "Test Project"
                      :description "A test project"
                      :created_at "2025-08-05T10:30:00Z"
                      :updated_at "2025-08-05T11:00:00Z"
                      :docs [{:uuid "doc-1" :filename "test.md" :content "# Test" :created_at "2025-08-05T10:30:00Z"}]}
          result (dv/validate-project valid-proj)]
      (is (not (contains? result :validation-error)))
      (is (= "Test Project" (:name result)))
      (is (= 1 (count (:docs result))))))

  (testing "Invalid documents are filtered out"
    (let [mixed-proj {:uuid "550e8400-e29b-41d4-a716-446655440000"
                      :name "Mixed Project"
                      :created_at "2025-08-05T10:30:00Z"
                      :docs [{:uuid "doc-1" :filename "valid.md" :content "Valid"}
                             {:filename "no-uuid.md"} ; Missing UUID - should be filtered
                             "not-a-map"]} ; Not a map - should be filtered
          result (dv/validate-project mixed-proj)]
      (is (not (contains? result :validation-error)))
      (is (= 1 (count (:docs result)))) ; Only 1 valid doc
      (is (contains? result :validation-warnings)))))

(deftest validate-conversations-batch-test
  (testing "Batch conversation validation"
    (let [conversations [{:uuid "conv-1" :name "Valid 1" :created_at "2025-08-05T10:30:00Z" :chats []}
                         {:uuid "conv-2" :name "Valid 2" :created_at "2025-08-05T10:30:00Z" :chats []}
                         {:name "Invalid - no UUID" :chats []}]
          result (dv/validate-conversations conversations)]
      (is (= 3 (:total-count result)))
      (is (= 2 (:valid-count result)))
      (is (= 1 (:invalid-count result)))
      (is (= 2 (count (:valid-conversations result))))
      (is (= 1 (count (:invalid-conversations result)))))))

(deftest validation-summary-test
  (testing "Generate validation summary"
    (let [conv-result {:total-count 10 :valid-count 8 :invalid-count 2}
          proj-result {:total-count 5 :valid-count 5 :invalid-count 0}
          summary (dv/validation-summary conv-result proj-result)]
      (is (= 8 (get-in summary [:conversations :valid])))
      (is (= 4/5 (get-in summary [:conversations :success-rate])))
      (is (= 5 (get-in summary [:projects :valid])))
      (is (= 1 (get-in summary [:projects :success-rate])))
      (is (:overall-success? summary)))))
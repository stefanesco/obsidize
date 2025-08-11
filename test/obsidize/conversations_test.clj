(ns obsidize.conversations-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [obsidize.conversations :as sut]
            [obsidize.utils :as utils]))

(deftest sanitize-filename-test
  (testing "Basic filename sanitization"
    (is (string? (utils/sanitize-filename "test.md")))
    (is (not (nil? (utils/sanitize-filename "Test File.md"))))))

(deftest format-message-timestamp-test
  (testing "Timestamp formatting"
    (is (string? (sut/format-message-timestamp "2023-01-01T10:00:00Z")))
    (is (nil? (sut/format-message-timestamp nil)))))

(deftest basic-functionality-test
  (testing "Functions don't crash with valid input"
    (let [mock-conversation {:uuid "123"
                             :name "Test"
                             :created_at "2023-01-01T10:00:00Z"
                             :updated_at "2023-01-01T10:00:00Z"
                             :chats [{:q "Hello" :a "Hi" :create_time "2023-01-01T10:00:00Z"}]}
          result (sut/generate-markdown-for-new-note mock-conversation "1.0.0")]
      (is (map? result))
      (is (:title result))
      (is (:filename result))
      (is (:content result)))))

(deftest edge-cases-test
  (testing "generate-title-for-nameless-convo handles edge cases"
    (testing "nil chat"
      (is (= "Unknown Date Untitled Conversation" (sut/generate-title-for-nameless-convo nil))))

    (testing "empty chat"
      (is (string? (sut/generate-title-for-nameless-convo {}))))

    (testing "missing question"
      (is (string? (sut/generate-title-for-nameless-convo {:create_time "2023-01-01T10:00:00Z"}))))

    (testing "empty question"
      (is (str/includes? (sut/generate-title-for-nameless-convo {:q "" :create_time "2023-01-01T10:00:00Z"})
                         "Untitled Conversation")))

    (testing "missing create_time"
      (is (str/includes? (sut/generate-title-for-nameless-convo {:q "Hello world"})
                         "Unknown Date")))

    (testing "whitespace-only question"
      (is (str/includes? (sut/generate-title-for-nameless-convo {:q "   " :create_time "2023-01-01T10:00:00Z"})
                         "Untitled Conversation"))))

  (testing "generate-markdown-for-new-note handles edge cases"
    (testing "empty conversation"
      (let [result (sut/generate-markdown-for-new-note {} "1.0.0")]
        (is (map? result))
        (is (:title result))
        (is (:filename result))
        (is (:content result))))

    (testing "nil chats"
      (let [result (sut/generate-markdown-for-new-note {:uuid "test" :chats nil} "1.0.0")]
        (is (str/includes? (:content result) "[No messages found]"))))

    (testing "empty chats"
      (let [result (sut/generate-markdown-for-new-note {:uuid "test" :chats []} "1.0.0")]
        (is (str/includes? (:content result) "[No messages found]"))))

    (testing "malformed chat entries"
      (let [result (sut/generate-markdown-for-new-note {:uuid "test"
                                                        :chats [{}
                                                                {:q nil :a nil}
                                                                {:q "" :a ""}]} "1.0.0")]
        (is (str/includes? (:content result) "[Missing question]"))
        (is (str/includes? (:content result) "[Missing answer]"))))

    (testing "missing required fields"
      (let [result (sut/generate-markdown-for-new-note {:chats [{:q "Hello" :a "Hi"}]} "1.0.0")]
        (is (str/includes? (:content result) "unknown-uuid"))
        (is (str/includes? (:content result) "Unknown"))))))

;; Tests run via Kaocha
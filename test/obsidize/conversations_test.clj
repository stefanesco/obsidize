(ns obsidize.conversations-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [obsidize.conversations :as sut]
            [obsidize.utils :as utils]
            [obsidize.vault-scanner :as vault-scanner]))

;; Fixed timestamp for deterministic tests
(def ^:private fixed-now "2025-01-01T00:00:00Z")

(deftest sanitize-filename-test
  (testing "Basic filename sanitization"
    (is (string? (utils/sanitize-filename "test.md")))
    (is (not (nil? (utils/sanitize-filename "Test File.md"))))))

(deftest format-message-timestamp-test
  (testing "Timestamp formatting"
    (is (string? (sut/format-message-timestamp "2023-01-01T10:00:00Z")))
    (is (nil? (sut/format-message-timestamp nil)))))

(deftest generate-title-for-nameless-convo-edge-cases
  (testing "generate-title-for-nameless-convo handles edge cases"
    (is (= "Unknown Date Untitled Conversation"
           (sut/generate-title-for-nameless-convo nil)))
    (is (string? (sut/generate-title-for-nameless-convo {})))
    (is (string? (sut/generate-title-for-nameless-convo {:create_time "2023-01-01T10:00:00Z"})))
    (is (str/includes? (sut/generate-title-for-nameless-convo {:q "" :create_time "2023-01-01T10:00:00Z"})
                       "Untitled Conversation"))
    (is (str/includes? (sut/generate-title-for-nameless-convo {:q "Hello world"})
                       "Unknown Date"))
    (is (str/includes? (sut/generate-title-for-nameless-convo {:q "   " :create_time "2023-01-01T10:00:00Z"})
                       "Untitled Conversation"))))

(deftest generate-markdown-for-new-note-basic-and-edge
  (testing "generate-markdown-for-new-note basic"
    (with-redefs [utils/current-timestamp (fn [] fixed-now)]
      (let [mock {:uuid "123"
                  :name "Test"
                  :created_at "2023-01-01T10:00:00Z"
                  :updated_at "2023-01-01T11:00:00Z"
                  :chats [{:q "Hello" :a "Hi" :create_time "2023-01-01T10:00:00Z"}]}
            res (sut/generate-markdown-for-new-note mock "1.0.0" {:tags ["ai"] :links ["[[L]]"]})]
        (is (map? res))
        (is (string? (:title res)))
        (is (string? (:filename res)))
        (is (string? (:content res)))
        (is (str/includes? (:content res) "obsidized_at: 2023-01-01T10:00:00Z"))
        (is (str/includes? (:content res) "obsidize_version: 1.0.0"))
        (is (str/includes? (:content res) "tags:"))
        (is (str/includes? (:content res) "[[L]]")))))

  (testing "no chats → no messages placeholder"
    (with-redefs [utils/current-timestamp (fn [] fixed-now)]
      (doseq [chats [nil []]]
        (let [res (sut/generate-markdown-for-new-note {:uuid "x" :chats chats} "1.0.0" {})]
          (is (str/includes? (:content res) "[No messages found]"))))))

  (testing "malformed chat entries produce placeholders"
    (with-redefs [utils/current-timestamp (fn [] fixed-now)]
      (let [res (sut/generate-markdown-for-new-note
                 {:uuid "test" :chats [{} {:q nil :a nil} {:q "" :a ""}]}
                 "1.0.0" {})]
        (is (str/includes? (:content res) "[Missing question]"))
        (is (str/includes? (:content res) "[Missing answer]"))))))

(deftest calculate-conversation-updates-detection
  (testing "detects messages after obsidized_at"
    (with-redefs [;; Simulate frontmatter with obsidized_at at 10:00
                  obsidize.vault-scanner/extract-frontmatter (fn [_] {:frontmatter "obsidized_at: 2023-01-01T10:00:00Z"})
                  obsidize.vault-scanner/parse-simple-yaml (fn [_] {:obsidized_at "2023-01-01T10:00:00Z"})
                  obsidize.vault-scanner/parse-timestamp (fn [s] (java.time.Instant/parse s))
                  ;; message formatting stable
                  utils/format-timestamp identity
                  utils/current-timestamp (fn [] "2023-01-01T11:00:00Z")]
      (let [conv {:updated_at "2023-01-01T10:30:00Z"
                  :chats [{:q "before" :a "a" :create_time "2023-01-01T09:00:00Z"}
                          {:q "after" :a "b" :create_time "2023-01-01T10:00:01Z"}]}
            res (sut/calculate-conversation-updates conv "---")]
        (is (:needs-update? res))
        (is (= 1 (count (:new-messages res))))
        (is (str/includes? (:messages-md res) "after"))))))

(deftest process-conversation-creates-new-file
  (testing "creates new file when it does not exist and uses sanitized filename"
    (let [spit-args (atom nil)]
      (with-redefs [utils/current-timestamp (fn [] fixed-now)
                    io/file (fn [p]
                              (proxy [java.io.File] [p]
                                (exists [] false)))
                    ;; capture writes
                    spit (fn [path content] (reset! spit-args [path content]))]
        (sut/process-conversation {:uuid "U" :name "Bad/Name: ?*" :chats []} "out" "1.2.3" {:tags ["t"]})
        (let [[path content] @spit-args
              filename (last (clojure.string/split path #"/"))]
          (is (str/starts-with? path "out/"))
          (is (not (re-find #"[/:*?]" filename))) ;; sanitized filename (not full path)
          (is (str/includes? content "obsidize_version: 1.2.3"))
          (is (str/includes? content "tags:")))))))

(deftest process-conversation-appends-when-new-messages
  (testing "appends when file exists and new messages detected; updates timestamps"
    (let [spit-content (atom nil)]
      (with-redefs [utils/current-timestamp (fn [] fixed-now)
                    io/file (fn [p]
                              (proxy [java.io.File] [p]
                                (exists [] true)))
                    slurp (fn [_] (str (str/join "\n" ["updated_at: 2020-01-01T00:00:00Z"
                                                       "obsidized_at: 2020-01-01T00:00:00Z"])
                                       "\n\n--- old content ---"))
                    obsidize.vault-scanner/extract-frontmatter (fn [_] {:frontmatter "obsidized_at: 2020-01-01T00:00:00Z"})
                    obsidize.vault-scanner/parse-simple-yaml (fn [_] {:obsidized_at "2020-01-01T00:00:00Z"})
                    obsidize.vault-scanner/parse-timestamp (fn [s] (java.time.Instant/parse s))
                    ;; force new messages
                    sut/calculate-conversation-updates (fn [conv _] {:needs-update? true
                                                                     :new-messages [{}]
                                                                     :messages-md "MSG"
                                                                     :updated-frontmatter {:updated_at (:updated_at conv)
                                                                                           :obsidized_at fixed-now}})
                    spit (fn [_ content] (reset! spit-content content))]
        (sut/process-conversation {:uuid "u" :updated_at "2025-02-02T00:00:00Z"} "out" "1.0.0" {})
        (let [c @spit-content]
          (is (str/includes? c "updated_at: 2025-02-02T00:00:00Z"))
          (is (str/includes? c "obsidized_at: 2025-01-01T00:00:00Z"))
          (is (str/includes? c "MSG")))))))

(deftest process-conversation-skips-when-no-new
  (testing "does not write when no new messages"
    (let [spit-called (atom 0)]
      (with-redefs [io/file (fn [p]
                              (proxy [java.io.File] [p]
                                (exists [] true)))
                    slurp (fn [_] "content")
                    sut/calculate-conversation-updates (fn [_ _] {:needs-update? false})
                    spit (fn [& _] (swap! spit-called inc))]
        (sut/process-conversation {:uuid "u"} "out" "1.0.0" {})
        (is (zero? @spit-called))))))

;; Tests run via Kaocha
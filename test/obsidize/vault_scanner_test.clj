(ns obsidize.vault-scanner-test
  (:require [clojure.test :refer [deftest is testing]]
            [obsidize.vault-scanner :as sut]
            [clojure.string :as str])
)

(deftest extract-frontmatter-test
  (testing "Extract frontmatter from markdown content"
    (let [content "---\nuuid: test-123\ntype: conversation\n---\n\n# Title\n\nContent here"
          result (sut/extract-frontmatter content)]
      (is (:has-frontmatter? result))
      (is (= "uuid: test-123\ntype: conversation" (:frontmatter result)))
      (is (str/includes? (:content result) "# Title"))))

  (testing "Handle content without frontmatter"
    (let [content "# Title\n\nJust content"
          result (sut/extract-frontmatter content)]
      (is (not (:has-frontmatter? result)))
      (is (= "" (:frontmatter result)))
      (is (= content (:content result)))))

  (testing "Handle malformed frontmatter"
    (let [content "---\nuuid: test\n# Missing closing ---\nContent"
          result (sut/extract-frontmatter content)]
      (is (not (:has-frontmatter? result))))))

(deftest parse-simple-yaml-test
  (testing "Parse simple YAML frontmatter"
    (let [yaml "uuid: conv-123\ntype: conversation\ncreated_at: 2025-08-04T10:30:00Z"
          result (sut/parse-simple-yaml yaml)]
      (is (= "conv-123" (:uuid result)))
      (is (= "conversation" (:type result)))
      (is (= "2025-08-04T10:30:00Z" (:created_at result)))))

  (testing "Handle empty or nil YAML"
    (is (nil? (sut/parse-simple-yaml nil)))
    (is (= {} (sut/parse-simple-yaml ""))))

  (testing "Handle lines without colons"
    (let [yaml "uuid: conv-123\nsome random line\ntype: conversation"
          result (sut/parse-simple-yaml yaml)]
      (is (= "conv-123" (:uuid result)))
      (is (= "conversation" (:type result))))))

(deftest needs-update-test
  (testing "Update decision logic"
    (let [existing-item {:obsidized-at "2025-08-05T09:15:00Z"}
          claude-old {:updated_at "2025-08-04T15:00:00Z"} ; Before obsidized_at
          claude-new {:updated_at "2025-08-05T10:30:00Z"}] ; After obsidized_at

      (is (not (sut/needs-update? existing-item claude-old)))
      (is (sut/needs-update? existing-item claude-new))
      (is (sut/needs-update? nil claude-new)))) ; New item

  (testing "Handle malformed timestamps"
    (let [existing-item {:obsidized-at "invalid-timestamp"}
          claude-item {:updated_at "2025-08-05T10:30:00Z"}]
      (is (sut/needs-update? existing-item claude-item)))))
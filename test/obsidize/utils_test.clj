(ns obsidize.utils-test
  (:require [clojure.test :refer [deftest is testing]]
            [obsidize.utils :as sut]
))

(deftest sanitize-filename-test
  (testing "Basic filename sanitization"
    (is (= "test-file.md" (sut/sanitize-filename "Test File.md")))
    (is (= "file-with-special-chars.txt" (sut/sanitize-filename "File with /\\:*?\"<>| Special Chars!.txt")))
    (is (= "simple.txt" (sut/sanitize-filename "simple.txt")))))

(deftest create-tags-section-test
  (testing "Tag creation"
    (is (= "\n\n#tag1 #tag2" (sut/create-tags-section ["tag1" "tag2"])))
    (is (nil? (sut/create-tags-section [])))
    (is (nil? (sut/create-tags-section nil)))))

(deftest sort-docs-chronologically-test
  (testing "Document sorting by created_at"
    (let [docs [{:created_at "2023-01-02"} {:created_at "2023-01-01"} {:created_at "2023-01-03"}]
          sorted (sut/sort-docs-chronologically docs)]
      (is (= ["2023-01-01" "2023-01-02" "2023-01-03"] (map :created_at sorted))))))

(deftest format-timestamp-test
  (testing "Timestamp formatting"
    (is (= "2023-01-01 10:00:00" (sut/format-timestamp "2023-01-01T10:00:00Z")))
    (is (= "2023-01-01 10:00:00" (sut/format-timestamp "2023-01-01T10:00:00.123Z")))
    (is (nil? (sut/format-timestamp nil)))))


;; Tests run via Kaocha
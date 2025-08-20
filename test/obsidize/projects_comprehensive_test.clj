(ns obsidize.projects-comprehensive-test
  (:require [clojure.test :refer [deftest is testing]]
            [obsidize.projects :as proj]))

(deftest project-edge-cases-test
  (testing "Handle project with missing fields gracefully"
    (let [minimal-project {:uuid "proj-1"
                           :name ""
                           :description nil
                           :docs nil}
          options {:output-dir "/tmp"
                   :app-version "1.0.0"}]
      ;; Should not crash
      (is (nil? (proj/process-project minimal-project options)))))

  (testing "Handle malformed document data"
    (let [bad-project {:uuid "proj-2"
                       :name "Bad Project"
                       :docs [{:uuid nil :filename nil :content nil}
                              {:filename "bad/file:name*.md" :content "test"}]}
          options {:output-dir "/tmp"
                   :app-version "1.0.0"}]
      ;; Should process without crashing
      (is (nil? (proj/process-project bad-project options))))))

(deftest project-performance-test
  (testing "Handle moderate sized projects efficiently"
    (let [start-time (System/currentTimeMillis)
          medium-project {:uuid "proj-perf"
                          :name "Performance Test"
                          :docs (for [i (range 5)]
                                  {:uuid (str "doc-" i)
                                   :filename (str "doc-" i ".md")
                                   :content (str "Content " i)
                                   :created_at "2025-01-01T10:00:00Z"})}
          options {:output-dir "/tmp"
                   :app-version "1.0.0"}]

      (proj/process-project medium-project options)

      (let [processing-time (- (System/currentTimeMillis) start-time)]
        ;; Should complete quickly
        (is (< processing-time 2000))))))
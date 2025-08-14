(ns obsidize.core-test
  "Comprehensive tests for obsidize.core namespace"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [obsidize.core :as sut]
            [obsidize.data-pack :as data-pack]
            [obsidize.vault-scanner :as vault-scanner]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Basic Function Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest app-version-test
  (testing "app-version should be a string"
    (is (string? sut/app-version))
    (is (not (str/blank? sut/app-version)))))

(deftest cli-options-test
  (testing "cli-options structure"
    (is (vector? sut/cli-options))
    (is (> (count sut/cli-options) 5))

    ;; Check that required options exist by looking at the raw structure
    (let [cli-options-str (str sut/cli-options)]
      (is (str/includes? cli-options-str ":input"))
      (is (str/includes? cli-options-str ":output-dir"))
      (is (str/includes? cli-options-str ":incremental"))
      (is (str/includes? cli-options-str ":force-full"))
      (is (str/includes? cli-options-str ":dry-run"))
      (is (str/includes? cli-options-str ":verbose"))
      (is (str/includes? cli-options-str "--help"))
      (is (str/includes? cli-options-str ":version"))
      (is (str/includes? cli-options-str "--version"))
      (is (str/includes? cli-options-str "\"-V\"")))))

(deftest only-verbose-provided?-test
  (testing "only-verbose-provided? function"
    (is (true? (sut/only-verbose-provided? ["--verbose"])))
    (is (true? (sut/only-verbose-provided? ["-v"])))
    (is (not (sut/only-verbose-provided? [])))
    (is (false? (sut/only-verbose-provided? ["--help"])))
    (is (false? (sut/only-verbose-provided? ["--verbose" "--help"])))
    (is (false? (sut/only-verbose-provided? ["-v" "-h"])))))

(deftest print-help-test
  (testing "print-help function"
    (let [output (with-out-str (sut/print-help "test summary"))]
      (is (str/includes? output "OBSIDIZE"))
      (is (str/includes? output sut/app-version))
      (is (str/includes? output "USAGE EXAMPLES"))
      (is (str/includes? output "TROUBLESHOOTING"))
      (is (str/includes? output "test summary")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLI Argument Parsing Tests (-main function)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest main-help-flag-test
  (testing "-main with --help flag"
    (let [output (with-out-str (sut/-main "--help"))]
      (is (str/includes? output "OBSIDIZE"))
      (is (str/includes? output "USAGE EXAMPLES")))))

(deftest main-no-args-test
  (testing "-main with no arguments shows help"
    (let [output (with-out-str (sut/-main))]
      (is (str/includes? output "OBSIDIZE"))
      (is (str/includes? output "USAGE EXAMPLES")))))

(deftest main-only-verbose-test
  (testing "-main with only --verbose shows help"
    (let [output (with-out-str (sut/-main "--verbose"))]
      (is (str/includes? output "OBSIDIZE"))
      (is (str/includes? output "USAGE EXAMPLES"))))

  (testing "-main with only -v shows help"
    (let [output (with-out-str (sut/-main "-v"))]
      (is (str/includes? output "OBSIDIZE"))
      (is (str/includes? output "USAGE EXAMPLES")))))

(deftest main-version-flag-test
  (testing "-main with --version flag"
    (let [output (with-out-str (sut/-main "--version"))]
      (is (str/includes? output "obsidize"))
      (is (str/includes? output sut/app-version))
      (is (not (str/includes? output "USAGE EXAMPLES")))))

  (testing "-main with -V flag"
    (let [output (with-out-str (sut/-main "-V"))]
      (is (str/includes? output "obsidize"))
      (is (str/includes? output sut/app-version))
      (is (not (str/includes? output "USAGE EXAMPLES"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integration Tests - Simple Cases
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest run-function-with-dry-run-test
  (testing "run function basic behavior with mocked dependencies"
    ;; Create a simple mock that avoids the complexity of full dependency mocking
    (with-redefs [obsidize.data-pack/process-data-pack
                  (fn [_] {:success? true :conversations [] :projects [] :data-dir nil})
                  obsidize.vault-scanner/scan-vault
                  (fn [_] {:conversations {} :projects {} :total-files 0})
                  obsidize.vault-scanner/plan-updates
                  (fn [_ _ _] {:conversations {:create-new [] :update-existing [] :no-update []}
                               :projects {:create-new [] :update-existing [] :no-update []}
                               :summary {:conversations {:create-new 0 :update-existing 0 :no-update 0}
                                         :projects {:create-new 0 :update-existing 0 :no-update 0}}})]
      (let [options {:input "test.dms" :output-dir "test" :dry-run true :incremental true
                     :force-full false :verbose false}
            output (with-out-str (sut/run options))]
        (is (str/includes? output "Starting Claude to Obsidian conversion"))
        (is (str/includes? output "Update Plan"))
        (is (str/includes? output "Dry run mode"))))))

(deftest run-function-error-handling-test
  (testing "run function handles data pack processing errors"
    (let [exit-code (atom nil)]
      (with-redefs [obsidize.data-pack/process-data-pack
                    (fn [_] {:success? false :conversations [] :projects []
                             :errors ["Mock error message"] :data-dir nil})
                    sut/exit
                    (fn [status] (reset! exit-code status))]
        (let [options {:input "test.dms" :output-dir "test" :dry-run true}
              output (with-out-str (sut/run options))]
          (is (str/includes? output "Error processing Claude data pack:"))
          (is (str/includes? output "Mock error message"))
          (is (= 1 @exit-code)))))))

(deftest run-function-verbose-mode-test
  (testing "run function with verbose output"
    (with-redefs [obsidize.data-pack/process-data-pack
                  (fn [_] {:success? true :conversations [] :projects [] :data-dir nil})
                  obsidize.vault-scanner/scan-vault
                  (fn [_] {:conversations {} :projects {} :total-files 0})
                  obsidize.vault-scanner/plan-updates
                  (fn [_ _ _] {:conversations {:create-new [] :update-existing [] :no-update []}
                               :projects {:create-new [] :update-existing [] :no-update []}
                               :summary {:conversations {:create-new 0 :update-existing 0 :no-update 0}
                                         :projects {:create-new 0 :update-existing 0 :no-update 0}}})]
      (let [options {:input "test.dms" :output-dir "test" :dry-run true
                     :incremental true :verbose true}
            output (with-out-str (sut/run options))]
        (is (str/includes? output "Options:"))
        (is (str/includes? output "Scanning existing vault"))))))

(deftest run-function-force-full-mode-test
  (testing "run function with force-full mode"
    (with-redefs [obsidize.data-pack/process-data-pack
                  (fn [_] {:success? true :conversations [] :projects [] :data-dir nil})
                  obsidize.vault-scanner/plan-updates
                  (fn [_ _ _] {:conversations {:create-new [] :update-existing [] :no-update []}
                               :projects {:create-new [] :update-existing [] :no-update []}
                               :summary {:conversations {:create-new 0 :update-existing 0 :no-update 0}
                                         :projects {:create-new 0 :update-existing 0 :no-update 0}}})]
      (let [options {:input "test.dms" :output-dir "test" :dry-run true
                     :force-full true :incremental true}
            output (with-out-str (sut/run options))]
        (is (str/includes? output "Starting Claude to Obsidian conversion"))
        ;; In force-full mode, vault scanning should be skipped
        (is (not (str/includes? output "Scanning existing vault")))))))

(deftest run-function-incremental-disabled-test
  (testing "run function with incremental disabled"
    (with-redefs [obsidize.data-pack/process-data-pack
                  (fn [_] {:success? true :conversations [] :projects [] :data-dir nil})
                  obsidize.vault-scanner/plan-updates
                  (fn [_ _ _] {:conversations {:create-new [] :update-existing [] :no-update []}
                               :projects {:create-new [] :update-existing [] :no-update []}
                               :summary {:conversations {:create-new 0 :update-existing 0 :no-update 0}
                                         :projects {:create-new 0 :update-existing 0 :no-update 0}}})]
      (let [options {:input "test.dms" :output-dir "test" :dry-run true
                     :incremental false}
            output (with-out-str (sut/run options))]
        (is (str/includes? output "Starting Claude to Obsidian conversion"))
        ;; When incremental is disabled, vault scanning should be skipped  
        (is (not (str/includes? output "Scanning existing vault")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Edge Cases and Error Handling
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest run-with-empty-data-test
  (testing "run function handles empty conversations and projects"
    (with-redefs [obsidize.data-pack/process-data-pack
                  (fn [_] {:success? true :conversations [] :projects [] :data-dir nil})
                  obsidize.vault-scanner/scan-vault
                  (fn [_] {:conversations {} :projects {} :total-files 0})
                  obsidize.vault-scanner/plan-updates
                  (fn [_ _ _] {:conversations {:create-new [] :update-existing [] :no-update []}
                               :projects {:create-new [] :update-existing [] :no-update []}
                               :summary {:conversations {:create-new 0 :update-existing 0 :no-update 0}
                                         :projects {:create-new 0 :update-existing 0 :no-update 0}}})]
      (let [options {:input "test.dms" :output-dir "test" :dry-run true}
            output (with-out-str (sut/run options))]
        (is (str/includes? output "Found 0 conversations and 0 projects"))))))

(deftest run-with-cleanup-test
  (testing "run function calls cleanup when data-dir is provided"
    (let [cleanup-called (atom false)]
      (with-redefs [obsidize.data-pack/process-data-pack
                    (fn [_] {:success? true :conversations [] :projects [] :data-dir "/tmp/test-dir"})
                    obsidize.data-pack/cleanup-temp-directory
                    (fn [_] (reset! cleanup-called true))
                    obsidize.vault-scanner/scan-vault
                    (fn [_] {:conversations {} :projects {} :total-files 0})
                    obsidize.vault-scanner/plan-updates
                    (fn [_ _ _] {:conversations {:create-new [] :update-existing [] :no-update []}
                                 :projects {:create-new [] :update-existing [] :no-update []}
                                 :summary {:conversations {:create-new 0 :update-existing 0 :no-update 0}
                                           :projects {:create-new 0 :update-existing 0 :no-update 0}}})]
        (let [options {:input "test.dms" :output-dir "test" :dry-run true}]
          (with-out-str (sut/run options)))
        (is @cleanup-called)))))
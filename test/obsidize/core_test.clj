(ns obsidize.core-test
  "Comprehensive tests for obsidize.core namespace"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.java.io :as io]
            [obsidize.core :as sut]
            [obsidize.data-pack :as data-pack]
            [obsidize.vault-scanner :as vault-scanner]
            [obsidize.logging :as logging]
            [obsidize.conversations :as conversations]
            [obsidize.projects :as projects]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Basic Function Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest app-version-test
  (testing "app-version should be a string"
    (is (string? sut/app-version))
    (is (not (str/blank? sut/app-version)))))

(deftest cli-options-structure-test
  (testing "cli-options structural checks (avoid brittle string search)"
    (is (vector? sut/cli-options))
    (is (> (count sut/cli-options) 5))
    ;; Extract IDs from all cli-options
    (let [option-ids (for [option sut/cli-options
                           :let [kvs (drop 3 option)]
                           :when (even? (count kvs))
                           :let [m (apply hash-map kvs)
                                 id (:id m)]
                           :when id]
                       id)
          expected-ids #{:input :output-dir :incremental :force-full :dry-run :verbose
                         :debug :diagnostics :version :help}]
      ;; Check that all expected IDs are present
      (doseq [expected-id expected-ids]
        (is (some #{expected-id} option-ids) (str "Missing option ID: " expected-id)))

      ;; Check specific option properties
      (let [output-dir-option (first (filter #(let [kvs (drop 3 %)]
                                                (and (even? (count kvs))
                                                     (= :output-dir (:id (apply hash-map kvs)))))
                                             sut/cli-options))
            output-dir-map (apply hash-map (drop 3 output-dir-option))]
        (is (= "obsidian_vault" (:default output-dir-map))))

      (let [incremental-option (first (filter #(let [kvs (drop 3 %)]
                                                 (and (even? (count kvs))
                                                      (= :incremental (:id (apply hash-map kvs)))))
                                              sut/cli-options))
            incremental-map (apply hash-map (drop 3 incremental-option))]
        (is (= true (:default incremental-map)))))))

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
    (let [exit-code (atom nil)
          output (with-redefs [sut/exit (fn [c] (reset! exit-code c))]
                   (with-out-str (sut/-main "--help")))]
      (is (str/includes? output "OBSIDIZE"))
      (is (str/includes? output "USAGE EXAMPLES"))
      (is (= 0 @exit-code)))))

(deftest main-no-args-test
  (testing "-main with no arguments shows help"
    (let [exit-code (atom nil)
          output (with-redefs [sut/exit (fn [c] (reset! exit-code c))]
                   (with-out-str (sut/-main)))]
      (is (str/includes? output "OBSIDIZE"))
      (is (str/includes? output "USAGE EXAMPLES"))
      (is (= 0 @exit-code)))))

(deftest main-only-verbose-test
  (testing "-main with only --verbose shows help"
    (let [exit-code (atom nil)
          output (with-redefs [sut/exit (fn [c] (reset! exit-code c))]
                   (with-out-str (sut/-main "--verbose")))]
      (is (str/includes? output "OBSIDIZE"))
      (is (str/includes? output "USAGE EXAMPLES"))
      (is (= 0 @exit-code))))
  (testing "-main with only -v shows help"
    (let [exit-code (atom nil)
          output (with-redefs [sut/exit (fn [c] (reset! exit-code c))]
                   (with-out-str (sut/-main "-v")))]
      (is (str/includes? output "OBSIDIZE"))
      (is (str/includes? output "USAGE EXAMPLES"))
      (is (= 0 @exit-code)))))

(deftest main-version-flag-test
  (testing "-main with --version flag"
    (let [exit-code (atom nil)
          output (with-redefs [sut/exit (fn [c] (reset! exit-code c))]
                   (with-out-str (sut/-main "--version")))]
      (is (str/includes? output "obsidize"))
      (is (str/includes? output sut/app-version))
      (is (not (str/includes? output "USAGE EXAMPLES")))
      (is (= 0 @exit-code))))
  (testing "-main with -V flag"
    (let [exit-code (atom nil)
          output (with-redefs [sut/exit (fn [c] (reset! exit-code c))]
                   (with-out-str (sut/-main "-V")))]
      (is (str/includes? output "obsidize"))
      (is (str/includes? output sut/app-version))
      (is (not (str/includes? output "USAGE EXAMPLES")))
      (is (= 0 @exit-code)))))

(deftest main-diagnostics-flag-test
  (testing "-main with --diagnostics flag"
    (let [exit-code (atom nil)
          output (with-redefs [sut/exit (fn [status] (reset! exit-code status))]
                   (with-out-str (sut/-main "--diagnostics")))]
      (is (str/includes? output "Running comprehensive system diagnostics"))
      (is (str/includes? output "Runtime Environment:"))
      (is (not (str/includes? output "USAGE EXAMPLES")))
      (is (= 0 @exit-code)))))

(deftest main-unknown-option-errors
  (testing "Unknown option yields parse error and exit 1"
    (let [exit-code (atom nil)
          output (with-redefs [sut/exit (fn [c] (reset! exit-code c))]
                   (with-out-str (sut/-main "--nope")))]
      (is (str/includes? output "❌ Error parsing arguments"))
      (is (str/includes? output "Use --help"))
      (is (= 1 @exit-code)))))

(deftest main-unexpected-positional-args
  (testing "Unexpected positional args should error"
    (let [exit-code (atom nil)
          output (with-redefs [sut/exit (fn [c] (reset! exit-code c))]
                   (with-out-str (sut/-main "--input" "x.dms" "EXTRA")))]
      (is (str/includes? output "Unexpected arguments"))
      (is (= 1 @exit-code)))))

(deftest main-missing-input-errors
  (testing "Missing --input errors out (non-help/version/diagnostics path)"
    (let [exit-code (atom nil)
          output (with-redefs [sut/exit (fn [c] (reset! exit-code c))]
                   (with-out-str (sut/-main "--dry-run")))]
      (is (str/includes? output "Missing required option"))
      (is (= 1 @exit-code)))))

(deftest main-mutually-exclusive-flags
  (testing "--incremental and --force-full together should error"
    (let [exit-code (atom nil)
          output (with-redefs [sut/exit (fn [c] (reset! exit-code c))]
                   (with-out-str (sut/-main "--input" "x.dms" "--force-full" "--incremental")))]
      (is (str/includes? output "mutually exclusive"))
      (is (= 1 @exit-code)))))

(deftest negatable-incremental-parses-correctly
  (testing "--[no-]incremental parsing"
    (let [p #(-> (cli/parse-opts % sut/cli-options) :options :incremental)]
      (is (true? (p [])))
      (is (true? (p ["--incremental"])))
      (is (false? (p ["--no-incremental"]))))))

(deftest main-debug-flag-test
  (testing "-main with --debug flag should enable debug logging"
    ;; Since debug mode affects global state, test via side effects
    (with-redefs [logging/set-debug! (fn [enabled?] (is (true? enabled?)))
                  logging/set-verbose! (fn [_] nil)
                  logging/log-runtime-info (fn [] {:native-image? false})
                  sut/exit (fn [_] nil)
                  sut/run (fn [_] nil)]
      (with-out-str (sut/-main "--debug" "--input" "test" "--dry-run")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integration-ish Tests - Simple Cases
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest run-function-with-dry-run-test
  (testing "run function basic behavior with mocked dependencies"
    (with-redefs [data-pack/process-data-pack
                  (fn [_] {:success? true :conversations [] :projects [] :data-dir nil})
                  vault-scanner/scan-vault
                  (fn [_] {:conversations {} :projects {} :total-files 0})
                  vault-scanner/plan-updates
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

(deftest run-does-not-create-dir-on-dry-run
  (testing "dry-run should not mkdirs"
    (let [mkdirs-called (atom false)]
      (with-redefs [data-pack/process-data-pack
                    (fn [_] {:success? true :conversations [] :projects [] :data-dir nil})
                    clojure.java.io/file
                    (fn [& parts]
                      (proxy [java.io.File] [(apply str (interpose "/" parts))]
                        (mkdirs [] (reset! mkdirs-called true))))]
        (with-out-str (sut/run {:input "x.dms" :output-dir "out" :dry-run true :incremental true}))
        (is (false? @mkdirs-called))))))

(deftest run-dry-run-does-not-process
  (testing "dry-run should not call writers"
    (let [conv-called (atom 0)
          proj-called (atom 0)]
      (with-redefs [data-pack/process-data-pack
                    (fn [_] {:success? true
                             :conversations [{:uuid "c1"}]
                             :projects [{:uuid "p1"}]
                             :data-dir nil})
                    vault-scanner/scan-vault
                    (fn [_] {:conversations {} :projects {} :total-files 0})
                    vault-scanner/plan-updates
                    (fn [_ _ _]
                      {:conversations {:create-new [{:uuid "c1" :claude-data {}}]
                                       :update-existing [] :no-update []}
                       :projects {:create-new [{:uuid "p1" :claude-data {}}]
                                  :update-existing [] :no-update []}
                       :summary {:conversations {:create-new 1 :update-existing 0 :no-update 0}
                                 :projects {:create-new 1 :update-existing 0 :no-update 0}}})
                    conversations/process-conversation (fn [& _] (swap! conv-called inc))
                    projects/process-project (fn [& _] (swap! proj-called inc))]
        (with-out-str (sut/run {:input "x.dms" :output-dir "out" :dry-run true :incremental true}))
        (is (zero? @conv-called))
        (is (zero? @proj-called))))))

(deftest run-function-error-handling-test
  (testing "run function handles data pack processing errors"
    (let [exit-code (atom nil)]
      (with-redefs [data-pack/process-data-pack
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
    (with-redefs [data-pack/process-data-pack
                  (fn [_] {:success? true :conversations [] :projects [] :data-dir nil})
                  vault-scanner/scan-vault
                  (fn [_] {:conversations {} :projects {} :total-files 0})
                  vault-scanner/plan-updates
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
    (with-redefs [data-pack/process-data-pack
                  (fn [_] {:success? true :conversations [] :projects [] :data-dir nil})
                  vault-scanner/plan-updates
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
    (with-redefs [data-pack/process-data-pack
                  (fn [_] {:success? true :conversations [] :projects [] :data-dir nil})
                  vault-scanner/plan-updates
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
    (with-redefs [data-pack/process-data-pack
                  (fn [_] {:success? true :conversations [] :projects [] :data-dir nil})
                  vault-scanner/scan-vault
                  (fn [_] {:conversations {} :projects {} :total-files 0})
                  vault-scanner/plan-updates
                  (fn [_ _ _] {:conversations {:create-new [] :update-existing [] :no-update []}
                               :projects {:create-new [] :update-existing [] :no-update []}
                               :summary {:conversations {:create-new 0 :update-existing 0 :no-update 0}
                                         :projects {:create-new 0 :update-existing 0 :no-update 0}}})]
      (let [options {:input "test.dms" :output-dir "test" :dry-run true}
            output (with-out-str (sut/run options))]
        (is (str/includes? output "Found 0 conversations and 0 projects"))))))

(deftest run-with-cleanup-success-test
  (testing "run function calls cleanup when data-dir is provided (success path)"
    (let [cleanup-called (atom false)]
      (with-redefs [data-pack/process-data-pack
                    (fn [_] {:success? true :conversations [] :projects [] :data-dir "/tmp/test-dir"})
                    data-pack/cleanup-temp-directory
                    (fn [_] (reset! cleanup-called true))
                    vault-scanner/scan-vault
                    (fn [_] {:conversations {} :projects {} :total-files 0})
                    vault-scanner/plan-updates
                    (fn [_ _ _] {:conversations {:create-new [] :update-existing [] :no-update []}
                                 :projects {:create-new [] :update-existing [] :no-update []}
                                 :summary {:conversations {:create-new 0 :update-existing 0 :no-update 0}
                                           :projects {:create-new 0 :update-existing 0 :no-update 0}}})]
        (with-out-str (sut/run {:input "test.dms" :output-dir "test" :dry-run true}))
        (is @cleanup-called)))))

(deftest run-with-cleanup-failure-test
  (testing "run function calls cleanup when data-dir is provided (failure path)"
    (let [cleanup-called (atom 0)]
      (with-redefs [data-pack/process-data-pack
                    (fn [_] {:success? false :errors ["x"] :data-dir "/tmp/z"})
                    data-pack/cleanup-temp-directory
                    (fn [_] (swap! cleanup-called inc))
                    sut/exit (fn [_] nil)]
        (with-out-str (sut/run {:input "x.dms" :output-dir "out" :dry-run true}))
        (is (pos? @cleanup-called))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parsing helpers (tags/links)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest parse-tags-and-links
  (testing "tags and links parse/trim/drop-blanks"
    (let [{:keys [options]} (cli/parse-opts
                             ["--tags" " ai,  claude, "
                              "--links" "[[AI Tools]], , [[Notes]]"]
                             sut/cli-options)]
      (is (= ["ai" "claude"] (:tags options)))
      (is (= ["[[AI Tools]]" "[[Notes]]"] (:links options))))))

(deftest tags-links-propagated-to-writers
  (testing "options passed down to writers in non-dry-run"
    (let [seen (atom nil)]
      (with-redefs [data-pack/process-data-pack
                    (fn [_] {:success? true
                             :conversations [{:uuid "c"}]
                             :projects [{:uuid "p"}] :data-dir nil})
                    vault-scanner/scan-vault
                    (fn [_] {:conversations {} :projects {} :total-files 0})
                    vault-scanner/plan-updates
                    (fn [_ _ _]
                      {:conversations {:create-new [{:uuid "c" :claude-data {}}]
                                       :update-existing [] :no-update []}
                       :projects {:create-new [{:uuid "p" :claude-data {}}]
                                  :update-existing [] :no-update []}
                       :summary {:conversations {:create-new 1 :update-existing 0 :no-update 0}
                                 :projects {:create-new 1 :update-existing 0 :no-update 0}}})
                    conversations/process-conversation
                    (fn [_ _ _ opts] (reset! seen opts))
                    projects/process-project
                    (fn [_ opts] (reset! seen opts))]
        (with-out-str
          (sut/run {:input "x.dms" :output-dir "out" :dry-run false :incremental true
                    :tags ["t1"] :links ["[[L]]"]}))
        (is (= ["t1"] (:tags @seen)))
        (is (= ["[[L]]"] (:links @seen)))))))
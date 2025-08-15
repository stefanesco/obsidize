(ns obsidize.logging-test
  "Tests for obsidize.logging namespace"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [obsidize.logging :as sut]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Basic Logging Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest logging-configuration-test
  (testing "Logging configuration functions"
    ;; Test initial state
    (is (false? sut/*verbose*))
    (is (false? sut/*debug*))

    ;; Test setting verbose
    (sut/set-verbose! true)
    (is (true? sut/*verbose*))
    (sut/set-verbose! false)
    (is (false? sut/*verbose*))

    ;; Test setting debug
    (sut/set-debug! true)
    (is (true? sut/*debug*))
    (sut/set-debug! false)
    (is (false? sut/*debug*))))

(deftest logging-functions-test
  (testing "Basic logging functions produce output"
    (let [info-output (with-out-str (sut/log-info "test info"))]
      (is (str/includes? info-output "test info"))
      (is (str/includes? info-output "ℹ️")))

    (let [warn-output (with-out-str (sut/log-warn "test warning"))]
      (is (str/includes? warn-output "test warning"))
      (is (str/includes? warn-output "⚠️")))

    (let [error-output (with-out-str (sut/log-error "test error"))]
      (is (str/includes? error-output "test error"))
      (is (str/includes? error-output "❌")))

    (let [success-output (with-out-str (sut/log-success "test success"))]
      (is (str/includes? success-output "test success"))
      (is (str/includes? success-output "✅")))))

(deftest verbose-logging-test
  (testing "Verbose logging respects configuration"
    ;; Verbose disabled - no output
    (sut/set-verbose! false)
    (let [output (with-out-str (sut/log-verbose "verbose message"))]
      (is (str/blank? output)))

    ;; Verbose enabled - output present
    (sut/set-verbose! true)
    (let [output (with-out-str (sut/log-verbose "verbose message"))]
      (is (str/includes? output "verbose message"))
      (is (str/includes? output "📝")))

    ;; Reset
    (sut/set-verbose! false)))

(deftest debug-logging-test
  (testing "Debug logging respects configuration"
    ;; Debug disabled - no output
    (sut/set-debug! false)
    (let [output (with-out-str (sut/log-debug "debug message"))]
      (is (str/blank? output)))

    ;; Debug enabled - output present
    (sut/set-debug! true)
    (let [output (with-out-str (sut/log-debug "debug message"))]
      (is (str/includes? output "debug message"))
      (is (str/includes? output "🐛 DEBUG:")))

    ;; Reset
    (sut/set-debug! false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Native Image Compatibility Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest runtime-environment-detection-test
  (testing "Runtime environment detection"
    (let [env (sut/detect-runtime-environment)]
      (is (map? env))
      (is (contains? env :native-image?))
      (is (boolean? (:native-image? env)))

      ;; Should have basic runtime properties
      (when-not (:error env)
        (is (contains? env :java-version))
        (is (contains? env :os-name))
        (is (contains? env :os-arch))))))

(deftest runtime-info-logging-test
  (testing "Runtime info logging produces output"
    (let [output (with-out-str (sut/log-runtime-info))]
      (is (str/includes? output "Runtime Environment:"))
      (is (str/includes? output "Native Image:")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; File System Diagnostics Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest file-access-diagnostics-test
  (testing "File access diagnostics for current directory"
    (let [result (sut/diagnose-file-access ".")]
      (is (map? result))
      (is (= "." (:path result)))
      (is (true? (:exists? result)))
      (is (true? (:directory? result)))
      (is (false? (:file? result)))))

  (testing "File access diagnostics for non-existent path"
    (let [result (sut/diagnose-file-access "nonexistent-file-12345")]
      (is (map? result))
      (is (= "nonexistent-file-12345" (:path result)))
      (is (false? (:exists? result))))))

(deftest temp-directory-diagnostics-test
  (testing "Temporary directory diagnostics"
    (let [result (sut/diagnose-temp-directory)]
      (is (map? result))
      (if (:error result)
        ;; If there's an error, it should be reported
        (is (false? (:can-create-temp-files? result)))
        ;; Otherwise, temp directory should work
        (do
          (is (true? (:can-create-temp-files? result)))
          (is (string? (:temp-dir result))))))))

(deftest zip-capabilities-diagnostics-test
  (testing "ZIP capabilities diagnostics"
    (let [result (sut/diagnose-zip-capabilities)]
      (is (map? result))
      (is (contains? result :zip-available?))

      (if (:zip-available? result)
        ;; If ZIP is available, should be able to create and read
        (do
          (is (true? (:can-create? result)))
          (is (true? (:can-read? result)))
          (is (= 1 (:entry-count result)))) ; Test file has one entry
        ;; If ZIP is not available, should have error
        (is (contains? result :error))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Performance Diagnostics Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest time-operation-macro-test
  (testing "Time operation macro"
    (sut/set-verbose! true)
    (let [output (with-out-str
                   (let [result (sut/time-operation "test operation"
                                                    (Thread/sleep 10)
                                                    :test-result)]
                     (is (= :test-result result))))]
      (is (str/includes? output "test operation completed in"))
      (is (str/includes? output "ms")))
    (sut/set-verbose! false)))

(deftest comprehensive-diagnostics-test
  (testing "Comprehensive diagnostics"
    (sut/set-debug! true) ; Enable debug for more detailed output
    (let [output (with-out-str
                   (let [result (sut/comprehensive-diagnostics)]
                     (is (map? result))
                     (is (contains? result :runtime))
                     (is (contains? result :zip))
                     (is (contains? result :temp))))]
      (is (str/includes? output "Running comprehensive system diagnostics"))
      (is (str/includes? output "Diagnostics completed")))
    (sut/set-debug! false)))
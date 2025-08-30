#!/usr/bin/env bb
(ns obsidize.core
  "Converts a Claude projects JSON export into a structured Obsidian vault."
  (:gen-class)
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.tools.cli :as cli]
            [obsidize.data-pack :as data-pack]
            [obsidize.conversations :as conversations]
            [obsidize.projects :as projects]
            [obsidize.vault-scanner :as vault-scanner]
            [obsidize.logging :as log]
            [obsidize.hints]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Application Version
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def app-version
  (or 
    ;; First try system property (set during native image build)
    (System/getProperty "obsidize.version")
    ;; Fall back to resource file (for JAR/development)
    (try
      (-> "obsidize/version.edn" io/resource slurp edn/read-string :version)
      (catch Exception _ "DEV"))))

(defn exit
  "Wrapper for System/exit to allow mocking in tests."
  [code]
  (System/exit code))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Command-Line Interface Definition
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def cli-options
  [["-i" "--input ARCHIVE/DIR" "Path to Claude data pack (.dms archive or folder)"
    :id :input]

   ["-o" "--output-dir DIR" "Directory to save the Obsidian notes"
    :id :output-dir
    :default "obsidian_vault"
    :default-desc "obsidian_vault"]

   ["-t" "--tags TAGS" "Comma-separated tags to add to all notes"
    :id :tags
    :parse-fn #(->> (str/split % #",")
                    (map str/trim)
                    (remove str/blank?)
                    vec)]

   ["-l" "--links LINKS" "Comma-separated Obsidian links to add to all notes"
    :id :links
    :parse-fn #(->> (str/split % #",")
                    (map str/trim)
                    (remove str/blank?)
                    vec)]

   [nil "--[no-]incremental" "Enable incremental updates (scan existing vault)"
    :id :incremental
    :default true]

   ["-f" "--force-full" "Force full re-import (ignore existing vault)"
    :id :force-full]

   ["-n" "--dry-run" "Show what would be done without making changes"
    :id :dry-run]

   ["-v" "--verbose" "Verbose output"
    :id :verbose]

   [nil "--debug" "Enable debug output"
    :id :debug]

   ["-d" "--diagnostics" "Run system diagnostics and exit"
    :id :diagnostics]

   ["-V" "--version" "Show version information"
    :id :version]

   ["-h" "--help" "Show this help message"
    :id :help]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main Entrypoint
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn only-verbose-provided?
  "Check if only --verbose (or -v) flag was provided"
  [args]
  (and (not-empty args)
       (or (= args ["--verbose"])
           (= args ["-v"]))))

(defn print-help
  "Print comprehensive help message with examples and troubleshooting."
  [summary]
  (println (str "OBSIDIZE v" app-version " - Claude to Obsidian Converter"))
  (println)
  (println "Move your Claude AI exported conversations and projects into an Obsidian friendly")
  (println "notes folder structure ready to support intelligent incremental updates.")
  (println)
  (println "OPTIONS:")
  (println summary)
  (println)
  (println "USAGE EXAMPLES:")
  (println)
  (println "  Show Version:")
  (println "    obsidize --version")
  (println "    → Displays the current version of obsidize")
  (println)
  (println "  System Diagnostics:")
  (println "    obsidize --diagnostics")
  (println "    → Run comprehensive system diagnostics (useful for troubleshooting native image issues)")
  (println)
  (println "  Debug Mode:")
  (println "    obsidize --debug --input data.dms --output-dir vault/")
  (println "    → Enable detailed debug logging for troubleshooting")
  (println)
  (println "  First Import (from .dms archive):")
  (println "    obsidize --input data-2024-12-01.dms --output-dir my-vault/")
  (println "    → Extracts archive, creates vault structure, imports all conversations & projects")
  (println)
  (println "  First Import (from extracted folder):")
  (println "    obsidize --input claude-export/ --output-dir my-vault/")
  (println "    → Processes folder containing conversations.json and projects.json")
  (println)
  (println "  Incremental Update (default behavior):")
  (println "    obsidize --input new-export.dms --output-dir my-vault/")
  (println "    → Only adds new messages/projects since last import, preserves existing files")
  (println)
  (println "  Preview Changes (dry run):")
  (println "    obsidize --input data.dms --output-dir vault/ --dry-run")
  (println "    → Shows what would be created/updated without making any changes")
  (println)
  (println "  Force Full Re-import:")
  (println "    obsidize --input data.dms --output-dir vault/ --force-full")
  (println "    → Completely rebuilds vault, ignoring existing files")
  (println)
  (println "  With Custom Tags and Links:")
  (println "    obsidize --input data.dms --output-dir vault/ --tags \"ai,claude\" --links \"[[AI Tools]],[[Notes]]\"")
  (println "    → Adds specified tags and Obsidian links to all generated notes")
  (println)
  (println "TROUBLESHOOTING:")
  (println)
  (println "  File System Permissions:")
  (println "    • Ensure read access to input file/directory")
  (println "    • Ensure write access to output directory")
  (println "    • On macOS/Linux: chmod +r input-file && chmod +w output-dir/")
  (println)
  (println "  Invalid Input Data:")
  (println "    • Verify .dms file is a valid Claude export (not corrupted)")
  (println "    • Check that conversations.json and projects.json exist in folder")
  (println "    • Use --verbose flag to see detailed processing information")
  (println)
  (println "  Failed Updates:")
  (println "    • Backup your vault before major updates")
  (println "    • Use --dry-run first to preview changes")
  (println "    • Try --force-full to bypass incremental update issues")
  (println "    • Check disk space - large exports can require significant storage")
  (println)
  (println "  Common File Issues:")
  (println "    • Filename conflicts: Obsidize sanitizes filenames for cross-platform compatibility")
  (println "    • Missing conversations: Check Claude export includes all desired conversations")
  (println "    • Sync problems: Obsidize uses frontmatter metadata for cross-device compatibility")
  (println)
  (println "This software is free software and is provided as-is without warranty - use at your own risk.")
  (println "It is recommended to backup your Obsidian vault before running obsidize.")
  (println "Copyright © 2025 Tudor Stefanescu")
  (println "For more help, license information visit: https://github.com/stefanesco/obsidize"))

 ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Command Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run
  "Main execution function that processes Claude data packs with incremental updates."
  [options]
  (let [{:keys [input output-dir incremental force-full dry-run verbose]} options]
    (println "Starting Claude to Obsidian conversion...")
    (when verbose (println "Options:" options))

    ;; Create output directory unless dry-run
    (when-not dry-run
      (.mkdirs (io/file output-dir)))

    (let [data-pack-result (data-pack/process-data-pack input)]
      (try
        (if (:success? data-pack-result)
          (let [{:keys [conversations projects]} data-pack-result]
            (println (str "Found " (count conversations) " conversations and "
                          (count projects) " projects in Claude export."))

            ;; Step 2: Scan existing vault (if incremental mode)
            (let [vault-index (if (and incremental (not force-full))
                                (do
                                  (when verbose (println "Scanning existing vault..."))
                                  (vault-scanner/scan-vault output-dir))
                                {:conversations {} :projects {} :total-files 0})]

              (when verbose
                (println (str "Found " (:total-files vault-index) " existing files in vault: "
                              (count (:conversations vault-index)) " conversations, "
                              (count (:projects vault-index)) " projects.")))

              ;; Step 3: Plan updates
              (let [update-plan (vault-scanner/plan-updates vault-index conversations projects)
                    summary (:summary update-plan)]

                ;; Display update plan
                (println "\n📋 Update Plan:")
                (println (str "  Conversations: " (:create-new (:conversations summary)) " new, "
                              (:update-existing (:conversations summary)) " updates, "
                              (:no-update (:conversations summary)) " unchanged"))
                (println (str "  Projects: " (:create-new (:projects summary)) " new, "
                              (:update-existing (:projects summary)) " updates, "
                              (:no-update (:projects summary)) " unchanged"))

                (if dry-run
                  (println "\n🔍 Dry run mode - no changes will be made.")
                  (do
                    ;; Step 4: Execute updates
                    (println "\n⚡ Processing updates...")

                    ;; Process new conversations
                    (doseq [item (get-in update-plan [:conversations :create-new])]
                      (when verbose (println (str "Creating conversation: " (:uuid item))))
                      (conversations/process-conversation (:claude-data item) output-dir app-version options))

                    ;; Process conversation updates  
                    (doseq [item (get-in update-plan [:conversations :update-existing])]
                      (when verbose (println (str "Updating conversation: " (:uuid item))))
                      ;; Incremental conversation updates: appends new messages since obsidized_at
                      (conversations/process-conversation (:claude-data item) output-dir app-version options))

                    ;; Process new projects
                    (doseq [item (get-in update-plan [:projects :create-new])]
                      (when verbose (println (str "Creating project: " (:uuid item))))
                      (projects/process-project (:claude-data item) options))

                    ;; Process project updates
                    ;; Process project updates using incremental system
                    (doseq [item (get-in update-plan [:projects :update-existing])]
                      (when verbose (println (str "Updating project: " (:uuid item))))
                      (let [result (projects/process-project-incremental (:claude-data item) (:existing-data item) options)]
                        (if (:success? result)
                          (when verbose
                            (let [details (:data result)]
                              (println (str "  Added " (:new-documents-count details) " new documents, "
                                            "metadata changed: " (:metadata-changed? details)))))
                          (println (str "❌ Failed to update project " (:uuid item) ": " (first (:errors result)))))))

                    (println "✅ Processing complete!"))))))

          ;; Handle data pack processing errors
          (do
            (println "❌ Error processing Claude data pack:")
            (doseq [error (:errors data-pack-result)]
              (println (str "  - " error)))
            (exit 1)))
        (finally
          ;; Cleanup temporary directory if present (both success and failure)
          (when-let [tmp (:data-dir data-pack-result)]
            (data-pack/cleanup-temp-directory tmp)))))))

(defn handle-version
  "Handle --version command."
  [_options]
  (println (str "obsidize " app-version))
  (shutdown-agents)
  (exit 0))

(defn handle-diagnostics
  "Handle --diagnostics command."
  [_options]
  (log/comprehensive-diagnostics)
  (shutdown-agents)
  (exit 0))

(defn handle-help
  "Handle --help command."
  [summary _options]
  (print-help summary)
  (shutdown-agents)
  (exit 0))

(defn handle-empty-args
  "Handle case when no arguments are provided."
  [summary _options]
  (print-help summary)
  (shutdown-agents)
  (exit 1))

(defn handle-verbose-only
  "Handle case when only --verbose flag is provided."
  [summary _options]
  (print-help summary)
  (shutdown-agents)
  (exit 1))

(defn handle-parsing-errors
  "Handle CLI parsing errors."
  [errors _options]
  (println "❌ Error parsing arguments:")
  (println (str/join "\n" errors))
  (println "\nUse --help for usage information.")
  (shutdown-agents)
  (exit 1))

(defn handle-unexpected-args
  "Handle unexpected positional arguments."
  [arguments _options]
  (println "❌ Unexpected arguments:" (pr-str arguments))
  (println "\nUse --help for usage information.")
  (shutdown-agents)
  (exit 1))

(defn handle-mutually-exclusive-flags
  "Handle mutually exclusive flags."
  [_options]
  (println "❌ Options --incremental and --force-full are mutually exclusive.")
  (shutdown-agents)
  (exit 1))

(defn handle-missing-input
  "Handle missing required --input option."
  [_options]
  (println "❌ Missing required option: --input ARCHIVE/DIR")
  (println "\nUse --help for usage information.")
  (shutdown-agents)
  (exit 1))

(defn handle-input-not-exists
  "Handle case when input file does not exist."
  [input _options]
  (println "❌ Input path does not exist:" input)
  (println "\nUse --help for usage information.")
  (shutdown-agents)
  (exit 1))

(defn handle-normal-execution
  "Handle normal application execution with proper error handling."
  [options]
  (try
    ;; Configure logging based on options
    (when (:debug options)
      (log/set-debug! true))
    (when (or (:verbose options) (:debug options))
      (log/set-verbose! true))

    ;; Log runtime info if debug is enabled
    (when (:debug options)
      (log/log-runtime-info))

    (run options)
    (shutdown-agents)

    (catch Exception e
      (println "❌ Unexpected error occurred:")
      (println (str "  " (.getMessage e)))
      (when (:debug options)
        (println "\nStack trace:")
        (.printStackTrace e))
      (shutdown-agents)
      (exit 1))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Command Dispatch System
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn dispatch-command
  "Dispatch to appropriate command handler based on options."
  [options summary]
  (cond
    (:version options) (handle-version options)
    (:diagnostics options) (handle-diagnostics options)
    (:help options) (handle-help summary options)
    :else (throw (ex-info "Unknown command dispatch" {:options options}))))

(defn validate-arguments
  "Validate parsed CLI arguments and handle errors."
  [options errors summary arguments args]
  (cond
    ;; Handle parsing errors
    (seq errors) (handle-parsing-errors errors options)

    ;; Unexpected positional arguments
    (seq arguments) (handle-unexpected-args arguments options)

    ;; Show help when no arguments provided
    (and (empty? args) (not (:help options))) (handle-empty-args summary options)

    ;; Show help when only --verbose flag is provided
    (only-verbose-provided? args) (handle-verbose-only summary options)

    ;; Mutually exclusive flags
    (and (:incremental options) (:force-full options)) (handle-mutually-exclusive-flags options)

    ;; Ensure input is provided
    (nil? (:input options)) (handle-missing-input options)

    ;; Validate input file exists
    (not (.exists (io/file (:input options)))) (handle-input-not-exists (:input options) options)

    ;; All validations pass
    :else :valid))

(defn run-application
  "Run the main application logic."
  [options]
  (handle-normal-execution options))

(defn -main
  [& args]
  (try
    (let [{:keys [options errors summary arguments]} (cli/parse-opts args cli-options)]
      (cond
        ;; Handle dispatch commands first
        (some options [:version :diagnostics :help])
        (dispatch-command options summary)

        ;; Validate arguments and run application
        :else
        (let [validation-result (validate-arguments options errors summary arguments args)]
          (when (= validation-result :valid)
            (run-application options)))))

    (catch Exception e
      (println "❌ Fatal error during application startup:")
      (println (str "  " (.getMessage e)))
      (println "\nThis may indicate a serious configuration or system issue.")
      (println "Try running with --debug for more information.")
      (println "\nFor help, use: obsidize --help")
      (shutdown-agents)
      (System/exit 1))))

;; Execute the main function if the script is run directly
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
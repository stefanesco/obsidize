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
            [obsidize.vault-scanner :as vault-scanner]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Application Version
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def app-version
  (try
    (-> "obsidize/version.edn" io/resource slurp edn/read-string :version)
    (catch Exception _ "DEV")))

(defn exit
  "Wrapper for System/exit to allow mocking in tests."
  [code]
  (System/exit code))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Command-Line Interface Definition
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def cli-options
  [["-i" "--input FILE" "Path to Claude data pack (.dms archive or folder)"
    :id :input
    :default "projects.json"
    :validate [#(.exists (io/file %)) "Input file must exist"]]
   ["-o" "--output-dir DIR" "Directory to save the Obsidian notes"
    :id :output-dir
    :default "obsidian_vault"]
   ["-t" "--tags TAGS" "Comma-separated tags to add to all notes"
    :id :tags
    :parse-fn #(when % (str/split % #","))]
   ["-l" "--links LINKS" "Comma-separated Obsidian links to add to all notes"
    :id :links
    :parse-fn #(when % (str/split % #","))]
   ["--incremental" "Enable incremental updates (scan existing vault)"
    :id :incremental
    :default true]
   ["--force-full" "Force full re-import (ignore existing vault)"
    :id :force-full
    :default false]
   ["--dry-run" "Show what would be done without making changes"
    :id :dry-run
    :default false]
   ["-v" "--verbose" "Verbose output"
    :id :verbose
    :default false]
   ["-h" "--help" "Show this help message"]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main Entrypoint
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run
  "Main execution function that processes Claude data packs with incremental updates."
  [options]
  (let [{:keys [input output-dir incremental force-full dry-run verbose]} options]
    (println "Starting Claude to Obsidian conversion...")
    (when verbose (println "Options:" options))

    ;; Create output directory if it doesn't exist
    (.mkdirs (io/file output-dir))

    ;; Step 1: Process Claude data pack
    (let [data-pack-result (data-pack/process-data-pack input)]
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
                    (conversations/process-conversation (:claude-data item) output-dir app-version))

                  ;; Process conversation updates
                  (doseq [item (get-in update-plan [:conversations :update-existing])]
                    (when verbose (println (str "Updating conversation: " (:uuid item))))
                    ;; TODO: Implement incremental conversation updates
                    (conversations/process-conversation (:claude-data item) output-dir app-version))

                  ;; Process new projects
                  (doseq [item (get-in update-plan [:projects :create-new])]
                    (when verbose (println (str "Creating project: " (:uuid item))))
                    (projects/process-project (:claude-data item) options))

                  ;; Process project updates
                  (doseq [item (get-in update-plan [:projects :update-existing])]
                    (when verbose (println (str "Updating project: " (:uuid item))))
                    ;; TODO: Implement incremental project updates
                    (projects/process-project (:claude-data item) options))

                  (println "✅ Processing complete!")))))

          ;; Cleanup temporary directory if needed
          (when (:data-dir data-pack-result)
            (data-pack/cleanup-temp-directory (:data-dir data-pack-result))))

        ;; Handle data pack processing errors
        (do
          (println "❌ Error processing Claude data pack:")
          (doseq [error (:errors data-pack-result)]
            (println (str "  - " error)))
          (exit 1))))))

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

(defn -main
  [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)]
    (cond
      ;; Show help when explicitly requested
      (:help options)
      (do (print-help summary)
          (shutdown-agents))

      ;; Show help when no arguments provided
      (and (empty? args) (not (:help options)))
      (do (print-help summary)
          (shutdown-agents))

      ;; Show help when only --verbose flag is provided
      (only-verbose-provided? args)
      (do (print-help summary)
          (shutdown-agents))

      ;; Handle parsing errors
      errors
      (do (println "❌ Error parsing arguments:")
          (println (str/join "\n" errors))
          (println "\nUse --help for usage information.")
          (shutdown-agents)
          (exit 1))

      ;; Normal execution
      :else
      (do (run options)
          (shutdown-agents)))))

;; Execute the main function if the script is run directly
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

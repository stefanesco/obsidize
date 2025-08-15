(ns obsidize.projects
  "convert Claude projects JSON export into a structured Obsidian-ready file structure."
  (:require [clojure.string :as str]
            [obsidize.templates :as templates]
            [obsidize.utils :as utils]
            [obsidize.incremental-projects :as incremental]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Function moved to utils namespace

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Core Logic
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn process-project
  "Processes a single project and creates/updates the corresponding folder and files.

  `project` — Claude project map with keys like :uuid :name :created_at :updated_at :docs
  `options` — {:output-dir string
               :tags       [string ...] or comma-string
               :links      [string ...] or comma-string
               :app-version string (optional; defaults to \"DEV\")}

  NOTE: caller (core) is responsible for honoring --dry-run semantics before calling here.
  "
  [project {:keys [output-dir tags links app-version] :as _options}]
  (let [version (or app-version "DEV")
        project-name (:name project)
        _ (println (str "Processing project: " project-name " (v" version ")"))

        ;; 1) Ensure project directory (keep original project name for the directory)
        project-dir (utils/ensure-directory (str output-dir "/" project-name))

        ;; Normalize options consistently
        tags* (utils/normalize-list-option tags)
        links* (utils/normalize-list-option links)
        tags-section (utils/create-tags-section tags*)

        ;; 2) Sort docs and generate document files
        docs (or (:docs project) [])
        sorted-docs (utils/sort-docs-chronologically docs)

        doc-filenames
        (doall
         (map-indexed
          (fn [idx doc]
            (let [filename (templates/format-project-document-filename
                            (inc idx)
                            (utils/sanitize-filename (or (:filename doc) (str "doc-" (inc idx) ".md"))))
                  filepath (str (.getPath project-dir) "/" filename)
                  frontmatter-data (utils/create-frontmatter-with-timestamps
                                    templates/project-document-frontmatter
                                    {:uuid (:uuid doc)
                                     :project_name project-name
                                     :obsidize_version version
                                     :created_at (:created_at doc)})
                  content (str (templates/format-frontmatter frontmatter-data)
                               (or (:content doc) "")
                               (or tags-section ""))]
              (utils/write-note-if-changed filepath content)
              filename))
          sorted-docs))

        ;; 3) Generate and write the project overview file
        sanitized-name (utils/sanitize-filename (str project-name ".md"))
        sanitized-name-base (first (str/split sanitized-name #"\."))
        overview-filename (templates/format-project-overview-filename sanitized-name-base)
        overview-filepath (str (.getPath project-dir) "/" overview-filename)
        overview-frontmatter (utils/create-frontmatter-with-timestamps
                              templates/project-overview-frontmatter
                              {:uuid (:uuid project)
                               :project_name project-name
                               :created_at (:created_at project)
                               :obsidize_version version
                               :updated_at (:updated_at project)})
        project-docs-section (templates/format-project-documents-section doc-filenames)
        user-links-section (templates/format-project-links-section links*)
        overview-content (templates/format-project-content
                          overview-frontmatter
                          project-name
                          (:description project)
                          project-docs-section
                          user-links-section
                          tags-section)]
    ;; Write overview last
    (utils/write-note-if-changed overview-filepath overview-content)))

(defn process-project-incremental
  "Process a single project with incremental updates for existing projects.
   
   Uses the incremental update system to:
   - Only process new documents not already in the vault
   - Update project overview with new document links
   - Preserve existing document numbering and chronological order
   
   `project` — Claude project map with keys like :uuid :name :created_at :updated_at :docs
   `vault-index-project` — Existing vault state from vault-scanner
   `options` — {:output-dir string
                :tags       [string ...] or comma-string
                :links      [string ...] or comma-string
                :app-version string (optional; defaults to \"DEV\")
                :dry-run    boolean
                :verbose    boolean}
   
   Returns: Map with :success? boolean and :details about what was processed"
  [project vault-index-project {:keys [output-dir app-version] :as options}]
  (let [version (or app-version "DEV")]
    (incremental/incremental-project-update project vault-index-project output-dir version options)))
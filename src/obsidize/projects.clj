(ns obsidize.projects
  "convert Claude projects JSON export into a structured Obsidian-ready file structure."
  (:require [clojure.string :as str]
            [obsidize.templates :as templates]
            [obsidize.utils :as utils]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Removed duplicate create-tags-section - using utils/create-tags-section instead

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Core Logic
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn process-project
  "Processes a single project and creates the corresponding folder and files."
  [project {:keys [output-dir tags links app-version]}]
  (println (str "Processing project: " (:name project) " (v" app-version ")"))
  (let [project-name-sanitized (:name project) ; Keep original name for directory
        project-dir (utils/ensure-directory (str output-dir "/" project-name-sanitized))
        tags-section (utils/create-tags-section tags)

        ;; 2. Sort docs and generate document files
        sorted-docs (utils/sort-docs-chronologically (:docs project))
        doc-filenames (doall
                       (map-indexed
                        (fn [idx doc]
                          (let [filename (templates/format-project-document-filename
                                          (inc idx)
                                          (utils/sanitize-filename (:filename doc)))
                                filepath (str (.getPath project-dir) "/" filename)
                                frontmatter-data (utils/create-frontmatter-with-timestamps
                                                  templates/project-document-frontmatter
                                                  {:uuid (:uuid doc)
                                                   :project_name (:name project) 
                                                   :obsidize_version app-version
                                                   :created_at (:created_at doc)})
                                content (str (templates/format-frontmatter frontmatter-data)
                                             (:content doc)
                                             (or tags-section ""))]
                            (utils/write-note-if-changed filepath content)
                            filename))
                        sorted-docs))

        ;; 3. Generate and write the project overview file
        sanitized-name (utils/sanitize-filename (str (:name project) ".md"))
        sanitized-name-base (first (str/split sanitized-name #"\."))
        overview-filename (templates/format-project-overview-filename sanitized-name-base)
        overview-filepath (str (.getPath project-dir) "/" overview-filename)
        overview-frontmatter (utils/create-frontmatter-with-timestamps
                              templates/project-overview-frontmatter
                              {:uuid (:uuid project)
                               :project_name (:name project)
                               :created_at (:created_at project) 
                               :obsidize_version app-version
                               :updated_at (:updated_at project)})
        project-docs-section (templates/format-project-documents-section doc-filenames)
        user-links-section (templates/format-project-links-section links)
        overview-content (templates/format-project-content
                          overview-frontmatter
                          (:name project)
                          (:description project)
                          project-docs-section
                          user-links-section
                          tags-section)]

    ;; 1. Create the project directory - handled by ensure-directory call above
    ;; 2. Documents already generated
    ;; 3. Write the project overview file
    (utils/write-note-if-changed overview-filepath overview-content)))
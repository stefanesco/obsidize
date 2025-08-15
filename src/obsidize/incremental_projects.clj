(ns obsidize.incremental-projects
  "Incremental project update system for Obsidize.
   
   This namespace handles incremental updates to Claude projects in the Obsidian vault.
   Key features:
   - Parse existing project overviews to understand current vault state
   - Identify new documents by comparing Claude data vs vault index
   - Process only new documents with proper chronological indexing
   - Update project overviews while preserving existing content
   
   Design principles:
   - Pure functions for data transformation separated from I/O operations
   - Leverages existing templates and utilities
   - Maintains cross-machine compatibility via filesystem-based state"
  (:require [obsidize.templates :as templates]
            [obsidize.utils :as utils]
            [obsidize.vault-scanner :as vault-scanner]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Structures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord ProjectOverviewMetadata
           [project-uuid
            project-name
            project-description
            project-created-at
            project-updated-at
            obsidized-at
            highest-doc-index
            existing-documents]) ; Map of {uuid {:index :filename :created-at}}

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Core Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-project-overview
  "Parse existing project overview markdown to extract metadata and document information.
   
   Reads the overview file and extracts:
   - Project metadata from YAML frontmatter
   - Existing document wikilinks from '## Project Documents' section
   - Highest document index for sequential numbering
   
   Args:
     overview-file-path - String path to the project overview markdown file
     vault-index-project - Project data from vault-scanner with document info
     
   Returns:
     ProjectOverviewMetadata record with:
     - project-uuid, project-name, etc. from frontmatter  
     - highest-doc-index - Integer for next document numbering
     - existing-documents - Map of {uuid {:index :filename :created-at}}
     
   Throws:
     IOException if file cannot be read
     Exception if frontmatter parsing fails"
  [overview-file-path vault-index-project]
  {:pre [(string? overview-file-path)
         (map? vault-index-project)]
   :post [(instance? obsidize.incremental_projects.ProjectOverviewMetadata %)]}
  (try
    (let [content (slurp overview-file-path)
          frontmatter-data (vault-scanner/extract-frontmatter content)
          parsed-frontmatter (vault-scanner/parse-simple-yaml (:frontmatter frontmatter-data))

          ;; Extract wikilinks from Project Documents section
          doc-section-pattern #"## Project Documents\s*\n((?:\s*- \[\[.*?\]\]\s*\n)*)"
          doc-section-match (re-find doc-section-pattern content)
          doc-links-text (if doc-section-match (second doc-section-match) "")

          ;; Parse wikilinks to extract filenames 
          wikilink-pattern #"- \[\[(.*?)\]\]"
          wikilinks (->> (re-seq wikilink-pattern doc-links-text)
                         (map second)
                         set)

          ;; Get vault document data and correlate with wikilinks
          vault-docs (:documents vault-index-project)
          existing-docs
          (->> vault-docs
               (filter #(wikilinks (:file-name %))) ; Match by filename
               (map (fn [doc]
                      (let [filename (:file-name doc)
                            ;; Extract index from filename format: "001_document-name.md"
                            index (if-let [[_ index-str] (re-find #"^(\d+)_" filename)]
                                    (Integer/parseInt index-str)
                                    0)]
                        [(:uuid doc) {:index index
                                      :filename filename
                                      :created-at (:created-at doc)}])))
               (into {}))

          highest-index (if (empty? existing-docs)
                          0
                          (->> existing-docs vals (map :index) (apply max)))]

      (map->ProjectOverviewMetadata
       {:project-uuid (:uuid parsed-frontmatter)
        :project-name (:project_name parsed-frontmatter)
        :project-description nil ; Will be extracted from content body if needed
        :project-created-at (:created_at parsed-frontmatter)
        :project-updated-at (:updated_at parsed-frontmatter)
        :obsidized-at (:obsidized_at parsed-frontmatter)
        :highest-doc-index highest-index
        :existing-documents existing-docs}))

    (catch Exception e
      (throw (ex-info (str "Failed to parse project overview: " overview-file-path)
                      {:file-path overview-file-path
                       :error-type :parse-error}
                      e)))))

(defn identify-new-documents
  "Compare Claude project documents against vault state to identify new documents.
   
   Compares document UUIDs between Claude project and existing vault documents
   to determine which documents are new and need processing.
   
   Args:
     claude-project - Map with :uuid :name :description :docs etc.
     parsed-overview - ProjectOverviewMetadata from parse-project-overview  
     vault-index-project - Project data from vault-scanner
     
   Returns:
     Map with keys:
     - :new-documents - Vector of new Claude documents with assigned indices
     - :all-documents - All documents (existing + new) sorted chronologically
     - :project-metadata-changed? - Boolean if project metadata changed"
  [claude-project parsed-overview vault-index-project]
  {:pre [(map? claude-project)
         (instance? obsidize.incremental_projects.ProjectOverviewMetadata parsed-overview)
         (map? vault-index-project)]}

  (let [claude-docs (or (:docs claude-project) [])
        existing-doc-uuids (set (keys (:existing-documents parsed-overview)))
        vault-doc-uuids (set (map :uuid (:documents vault-index-project)))

        ;; Find documents that are in Claude but not in vault
        new-claude-docs (remove #(or (existing-doc-uuids (:uuid %))
                                     (vault-doc-uuids (:uuid %)))
                                claude-docs)

        ;; Assign sequential indices to new documents
        next-index (inc (:highest-doc-index parsed-overview))
        new-docs-with-indices
        (vec (map-indexed (fn [idx doc]
                            (assoc doc :assigned-index (+ next-index idx)))
                          new-claude-docs))

        ;; Check if project metadata changed  
        ;; Note: We don't compare description since it's not extracted from overview content
        metadata-changed? (not= (:updated_at claude-project)
                                (:project-updated-at parsed-overview))

        ;; Create sorted list of all documents for overview
        all-docs (sort-by :created_at (concat claude-docs))]

    {:new-documents new-docs-with-indices
     :all-documents all-docs
     :project-metadata-changed? metadata-changed?}))

(defn process-new-documents
  "Process new Claude documents by generating markdown content and file paths.
   
   For each new document:
   - Generates markdown content using templates
   - Creates properly indexed filename
   - Prepares file path within project folder
   
   Args:
     project-folder-path - String path to project folder in vault
     new-documents - Vector of Claude documents with :assigned-index
     project-name - String name of the project
     app-version - String version of obsidize app
     
   Returns:
     Vector of maps with :document :file-path :filename :content"
  [project-folder-path new-documents project-name app-version]
  {:pre [(string? project-folder-path)
         (vector? new-documents)
         (string? project-name)
         (string? app-version)]}

  (mapv (fn [doc]
          (let [index (:assigned-index doc)
                original-filename (utils/sanitize-filename
                                   (or (:filename doc)
                                       (str "doc-" index ".md")))
                indexed-filename (templates/format-project-document-filename
                                  (format "%03d" index)
                                  original-filename)
                file-path (str project-folder-path "/" indexed-filename)

                ;; Generate frontmatter
                frontmatter-data (utils/create-frontmatter-with-timestamps
                                  templates/project-document-frontmatter
                                  {:uuid (:uuid doc)
                                   :project_name project-name
                                   :obsidize_version app-version
                                   :created_at (:created_at doc)})

                ;; Generate complete markdown content
                content (str (templates/format-frontmatter frontmatter-data)
                             (or (:content doc) ""))]

            {:document doc
             :file-path file-path
             :filename indexed-filename
             :content content}))
        new-documents))

(defn update-project-overview
  "Update project overview file with new documents and metadata.
   
   Preserves existing overview content while updating:
   - YAML frontmatter with latest obsidized_at timestamp
   - Project description if changed
   - Project Documents section with new wikilinks
   
   Args:
     overview-file-path - String path to overview file
     claude-project - Map with updated project data
     all-documents - Sorted list of all documents (existing + new)
     app-version - String version of obsidize app
     parsed-overview - ProjectOverviewMetadata with existing state
     processed-docs - Vector of newly processed documents with filenames
     
   Returns:
     Boolean indicating success"
  [overview-file-path claude-project all-documents app-version parsed-overview processed-docs]
  {:pre [(string? overview-file-path)
         (map? claude-project)
         (sequential? all-documents)
         (string? app-version)
         (instance? obsidize.incremental_projects.ProjectOverviewMetadata parsed-overview)
         (vector? processed-docs)]}

  (try
    (let [;; Create updated frontmatter
          updated-frontmatter (utils/create-frontmatter-with-timestamps
                               templates/project-overview-frontmatter
                               {:uuid (:uuid claude-project)
                                :project_name (:name claude-project)
                                :created_at (:created_at claude-project)
                                :obsidize_version app-version
                                :updated_at (:updated_at claude-project)})

          ;; Create UUID to filename mapping
          existing-doc-map (:existing-documents parsed-overview)
          new-doc-map (->> processed-docs
                           (map (fn [{:keys [document filename]}]
                                  [(:uuid document) filename]))
                           (into {}))
          uuid-to-filename (merge (into {} (map (fn [[uuid data]] [uuid (:filename data)]) existing-doc-map))
                                  new-doc-map)

          ;; Generate document filenames for all docs (preserving order and indices)
          doc-filenames (->> all-documents
                             (sort-by :created_at) ; Ensure chronological order
                             (keep (fn [doc]
                                     (uuid-to-filename (:uuid doc))))
                             vec)

          ;; Generate updated content sections
          documents-section (templates/format-project-documents-section doc-filenames)

          ;; Generate complete updated content
          updated-content (templates/format-project-content
                           updated-frontmatter
                           (:name claude-project)
                           (:description claude-project)
                           documents-section
                           nil ; links-section - preserve existing
                           nil)] ; tags-section - preserve existing

      (spit overview-file-path updated-content)
      true)

    (catch Exception e
      (println (str "Error updating project overview: " overview-file-path " - " (.getMessage e)))
      false)))

(defn incremental-project-update
  "Main function to perform incremental update of a single project.
   
   Orchestrates the complete incremental update process:
   1. Parse existing project overview
   2. Identify new documents
   3. Process new documents
   4. Update project overview
   
   Args:
     claude-project - Map with project data from Claude export
     vault-index-project - Map with existing vault data from vault-scanner
     output-dir - String path to vault output directory
     app-version - String version of obsidize app
     options - Map with additional options (dry-run, verbose, etc.)
     
   Returns:
     Map with :success? boolean and :details about what was processed"
  [claude-project vault-index-project output-dir app-version options]
  {:pre [(map? claude-project)
         (map? vault-index-project)
         (string? output-dir)
         (string? app-version)
         (map? options)]}

  (let [{:keys [dry-run verbose]} options
        project-name (:name claude-project)
        project-folder (:folder-path vault-index-project)
        overview-file (:overview-file vault-index-project)]

    (when verbose
      (println (str "Starting incremental update for project: " project-name)))

    (try
      ;; Step 1: Parse existing overview
      (when verbose (println "  Parsing existing overview..."))
      (let [parsed-overview (parse-project-overview overview-file vault-index-project)

            ;; Step 2: Identify new documents
            _ (when verbose (println "  Identifying new documents..."))
            analysis (identify-new-documents claude-project parsed-overview vault-index-project)
            new-docs (:new-documents analysis)
            metadata-changed? (:project-metadata-changed? analysis)

            ;; Step 3: Process new documents (if any)
            processed-docs (if (seq new-docs)
                             (do
                               (when verbose
                                 (println (str "  Processing " (count new-docs) " new documents...")))
                               (process-new-documents project-folder new-docs project-name app-version))
                             [])

            ;; Step 4: Write new documents (unless dry-run)
            write-results (if (and (seq processed-docs) (not dry-run))
                            (do
                              (when verbose (println "  Writing new documents..."))
                              (doseq [{:keys [file-path content]} processed-docs]
                                (utils/write-note-if-changed file-path content))
                              true)
                            (or dry-run (not (seq processed-docs)))) ; true if no docs to write

            ;; Step 5: Update overview (if needed and not dry-run)
            overview-updated? (if (and (or (seq new-docs) metadata-changed?) (not dry-run))
                                (do
                                  (when verbose (println "  Updating project overview..."))
                                  (update-project-overview overview-file
                                                           claude-project
                                                           (:all-documents analysis)
                                                           app-version
                                                           parsed-overview
                                                           processed-docs))
                                true)] ; true if no update needed

        (when verbose
          (println (str "  Incremental update complete: "
                        (count new-docs) " new documents, "
                        "metadata changed: " metadata-changed?)))

        {:success? (and write-results overview-updated?)
         :details {:new-documents-count (count new-docs)
                   :processed-documents processed-docs
                   :metadata-changed? metadata-changed?
                   :dry-run? dry-run}})

      (catch Exception e
        (println (str "Error in incremental project update for " project-name ": " (.getMessage e)))
        {:success? false
         :error {:message (.getMessage e)
                 :type :incremental-update-error
                 :project-name project-name}}))))
(ns obsidize.templates
  "Templates and formatting constants for Obsidize application.
   
   This namespace centralizes all string templates, formats, and constants
   used throughout the application for generating markdown files and filenames.
   
   Design principles:
   - All templates use keyword substitution for variable interpolation
   - Constants are grouped by domain (conversation, project, common)
   - Templates are composable and can be extended
   - Format functions provide a clean API for template usage"
  (:require [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Application Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const source-identifier "claude-export")
(def ^:const obsidize-version "1.0.0")
(def ^:const frontmatter-delimiter "---\n")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Common Templates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def frontmatter-base
  "Base frontmatter fields common to all document types."
  {:source source-identifier
   :obsidize_version obsidize-version})

(def yaml-frontmatter-template
  "Template for YAML frontmatter block."
  (str frontmatter-delimiter
       "{frontmatter-content}\n"
       frontmatter-delimiter
       "\n"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Conversation Templates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def conversation-filename-template
  "Template for conversation filenames: title__uuid.md"
  "{title}__{uuid}.md")

(def conversation-frontmatter
  "Frontmatter template for conversation documents."
  (merge frontmatter-base
         {:type "conversation"}))

(def conversation-message-template
  "Template for individual conversation messages."
  "**{timestamp} Me:** {question}\n\n**Claude:** {answer}\n")

(def conversation-content-template
  "Complete template for conversation markdown content."
  (str "{frontmatter-yaml}"
       "# {title}\n\n"
       "{messages}"))

(def default-conversation-title "Untitled Conversation")
(def default-date-prefix "Unknown Date")
(def missing-question-placeholder "[Missing question]")
(def missing-answer-placeholder "[Missing answer]")
(def no-messages-placeholder "[No messages found]")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Project Templates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def project-document-filename-template
  "Template for project document filenames: index_filename"
  "{index}_{filename}")

(def project-overview-filename-template
  "Template for project overview filenames: sanitized-name.md"
  "{sanitized-name}.md")

(def project-document-frontmatter
  "Frontmatter template for project documents."
  (merge frontmatter-base
         {:type "project-document"}))

(def project-overview-frontmatter
  "Frontmatter template for project overview documents."
  (merge frontmatter-base
         {:type "project-overview"}))

(def project-documents-section-template
  "Template for project documents section."
  "\n\n## Project Documents\n\n{documents}")

(def project-links-section-template
  "Template for project links section."
  "\n\n## Linked to\n\n{links}")

(def project-overview-content-template
  "Complete template for project overview markdown content."
  (str "{frontmatter-yaml}"
       "# {name}\n\n"
       "{description}"
       "{documents-section}"
       "{links-section}"
       "{tags-section}"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Template Formatting Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn substitute-template
  "Substitute placeholders in template string with values from context map.
   Placeholders are in the format {key} and are replaced with (str (get context :key))."
  [template context]
  (reduce-kv (fn [result key value]
               (str/replace result (str "{" (name key) "}") (str value)))
             template
             context))

(defn format-frontmatter
  "Format frontmatter map as YAML string with delimiters."
  [frontmatter-map]
  (let [yaml-lines (map (fn [[k v]] 
                         (str (name k) ": " v))
                       frontmatter-map)
        yaml-content (str/join "\n" yaml-lines)]
    (substitute-template yaml-frontmatter-template
                        {:frontmatter-content yaml-content})))

(defn format-conversation-filename
  "Format conversation filename from title and UUID."
  [title uuid]
  (substitute-template conversation-filename-template
                      {:title title :uuid uuid}))

(defn format-conversation-message
  "Format a single conversation message."
  [timestamp question answer]
  (substitute-template conversation-message-template
                      {:timestamp timestamp
                       :question question
                       :answer answer}))

(defn format-conversation-content
  "Format complete conversation markdown content."
  [frontmatter title messages]
  (let [frontmatter-yaml (format-frontmatter frontmatter)
        messages-content (str/join "\n" messages)]
    (substitute-template conversation-content-template
                        {:frontmatter-yaml frontmatter-yaml
                         :title title
                         :messages messages-content})))

(defn format-project-document-filename
  "Format project document filename with index and original filename."
  [index filename]
  (substitute-template project-document-filename-template
                      {:index index :filename filename}))

(defn format-project-overview-filename
  "Format project overview filename from sanitized project name."
  [sanitized-name]
  (substitute-template project-overview-filename-template
                      {:sanitized-name sanitized-name}))

(defn format-project-documents-section
  "Format the project documents section with wikilinks."
  [document-filenames]
  (when (seq document-filenames)
    (let [links (str/join "\n" (map #(str "- [[" % "]]") document-filenames))]
      (substitute-template project-documents-section-template
                          {:documents links}))))

(defn format-project-links-section
  "Format the project external links section."
  [link-names]
  (when (seq link-names)
    (let [links (str/join "\n" (map #(str "- [[" % "]]") link-names))]
      (substitute-template project-links-section-template
                          {:links links}))))

(defn format-project-content
  "Format complete project overview markdown content."
  [frontmatter name description documents-section links-section tags-section]
  (let [frontmatter-yaml (format-frontmatter frontmatter)]
    (substitute-template project-overview-content-template
                        {:frontmatter-yaml frontmatter-yaml
                         :name name
                         :description (or description "")
                         :documents-section (or documents-section "")
                         :links-section (or links-section "")
                         :tags-section (or tags-section "")})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validation and Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn validate-template
  "Validate that a template string contains all required placeholders."
  [template required-keys]
  (let [template-placeholders (set (map second (re-seq #"\{([^}]+)\}" template)))
        required-placeholder-names (set (map name required-keys))
        missing-keys (remove template-placeholders required-placeholder-names)]
    (when (seq missing-keys)
      {:error :missing-template-keys
       :missing (map keyword missing-keys)
       :template template})))

(defn list-template-placeholders
  "Extract all placeholder keys from a template string."
  [template]
  (->> (re-seq #"\{([^}]+)\}" template)
       (map second)
       (map keyword)
       set))
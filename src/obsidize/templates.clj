(ns obsidize.templates
  "Simplified templates and formatting for Obsidize application."
  (:require [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Application Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const source-identifier "claude-export")
(def ^:const obsidize-version "1.0.0")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Default Values
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-conversation-title "Untitled Conversation")
(def default-date-prefix "Unknown Date")
(def missing-question-placeholder "[Missing question]")
(def missing-answer-placeholder "[Missing answer]")
(def no-messages-placeholder "[No messages found]")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Base Frontmatter
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def frontmatter-base
  "Base frontmatter fields common to all document types."
  {:source source-identifier
   :obsidize_version obsidize-version})

(def conversation-frontmatter
  "Frontmatter template for conversation documents."
  (merge frontmatter-base
         {:type "conversation"}))

(def project-document-frontmatter
  "Frontmatter template for project documents."
  (merge frontmatter-base
         {:type "project-document"}))

(def project-overview-frontmatter
  "Frontmatter template for project overview documents."
  (merge frontmatter-base
         {:type "project-overview"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Formatting Functions (Simplified)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn format-frontmatter
  "Format frontmatter map as YAML string with delimiters."
  [frontmatter-map]
  (let [yaml-lines (map (fn [[k v]]
                          (str (name k) ": " v))
                        frontmatter-map)
        yaml-content (str/join "\n" yaml-lines)]
    (str "---\n" yaml-content "\n---\n\n")))

(defn format-conversation-filename
  "Format conversation filename from title and UUID."
  [title uuid]
  (str title "__" uuid ".md"))

(defn format-conversation-message
  "Format a single conversation message."
  [timestamp question answer]
  (str "**" timestamp " Me:** " question "\n\n**Claude:** " answer "\n"))

(defn format-conversation-content
  "Format complete conversation markdown content."
  [frontmatter title messages]
  (let [frontmatter-yaml (format-frontmatter frontmatter)
        messages-content (str/join "\n" messages)]
    (str frontmatter-yaml "# " title "\n\n" messages-content)))

(defn format-project-document-filename
  "Format project document filename with index and original filename."
  [index filename]
  (str index "_" filename))

(defn format-project-overview-filename
  "Format project overview filename from sanitized project name."
  [sanitized-name]
  (str sanitized-name ".md"))

(defn format-project-documents-section
  "Format the project documents section with wikilinks."
  [document-filenames]
  (when (seq document-filenames)
    (let [links (str/join "\n" (map #(str "- [[" % "]]") document-filenames))]
      (str "\n\n## Project Documents\n\n" links))))

(defn format-project-links-section
  "Format the project external links section."
  [link-names]
  (when (seq link-names)
    (let [links (str/join "\n" (map #(str "- [[" % "]]") link-names))]
      (str "\n\n## Linked to\n\n" links))))

(defn format-project-content
  "Format complete project overview markdown content."
  [frontmatter name description documents-section links-section tags-section]
  (let [frontmatter-yaml (format-frontmatter frontmatter)]
    (str frontmatter-yaml
         "# " name "\n\n"
         (or description "")
         (or documents-section "")
         (or links-section "")
         (or tags-section ""))))
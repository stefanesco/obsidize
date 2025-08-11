(ns obsidize.utils
  "Shared utility functions for the Obsidize application"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; String Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sanitize-filename
  "Sanitizes a string to be a valid filename, preserving the extension."
  [filename]
  (let [extension (last (str/split filename #"\."))
        name (first (str/split filename #"\."))]
    (str (-> name
             (str/lower-case)
             (str/replace #"[^a-z0-9\-\._]" "-")
             (str/replace #"-+" "-")
             (str/replace #"-$" "")
             (str/replace #"^-" ""))
         "."
         extension)))

(defn create-tags-section
  "Creates a string of Obsidian tags for the note body."
  [tags]
  (when (seq tags)
    (str "\n\n" (str/join " " (map #(str "#" %) tags)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Date/Time Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sort-docs-chronologically
  "Sorts a collection of documents by their :created_at timestamp."
  [docs]
  (sort-by :created_at docs))

(defn format-timestamp
  "Formats an ISO timestamp for display"
  [timestamp]
  (when timestamp
    (-> timestamp
        (str/replace #"T" " ")
        (str/replace #"Z$" "")
        (str/split #"\.")
        first)))

(defn current-timestamp
  "Returns the current timestamp as a string for obsidized_at fields"
  []
  (str (java.time.Instant/now)))

(defn create-frontmatter-with-timestamps
  "Creates frontmatter by merging template with common timestamp fields"
  [template-frontmatter data-map]
  (merge template-frontmatter
         (assoc data-map :obsidized_at (current-timestamp))))

(defn ensure-directory
  "Ensures a directory exists, creating it if necessary"
  [path]
  (let [dir (io/file path)]
    (.mkdirs dir)
    dir))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Markdown Generation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; File I/O Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn write-note-if-changed
  "Writes content to a file only if it's different from the existing content."
  [file-path content]
  (let [file (io/file file-path)]
    (if (.exists file)
      (let [existing-content (slurp file)]
        (when (not= content existing-content)
          (println (str "Updating file: " file-path))
          (spit file-path content)))
      (do
        (println (str "Creating file: " file-path))
        (spit file-path content)))))


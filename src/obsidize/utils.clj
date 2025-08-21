(ns obsidize.utils
  "Shared utility functions for the Obsidize application"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [obsidize.templates :as templates]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; String Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sanitize-filename
  "Sanitizes a string to be a valid filename, preserving the extension."
  ^String [^String filename]
  (if (str/includes? filename ".")
    (let [last-dot-idx (.lastIndexOf filename ".")
          name (subs filename 0 last-dot-idx)
          extension (subs filename (inc last-dot-idx))]
      (str (-> name
               (str/lower-case)
               (str/replace #"[^a-z0-9\-\._]" "-")
               (str/replace #"-+" "-")
               (str/replace #"-$" "")
               (str/replace #"^-" ""))
           "."
           extension))
    ;; No extension - sanitize the whole string
    (-> filename
        (str/lower-case)
        (str/replace #"[^a-z0-9\-\._]" "-")
        (str/replace #"-+" "-")
        (str/replace #"-$" "")
        (str/replace #"^-" ""))))

(defn create-tags-section
  "Creates a string of Obsidian tags for the note body."
  [tags]
  (when (seq tags)
    (str "\n\n" (str/join " " (map #(str "#" %) tags)))))

(defn normalize-list-option
  "Trims and drops blank strings from a vector or comma-separated string.
   Returns a vector (possibly empty)."
  [v]
  (->> (cond
         (nil? v) []
         (vector? v) v
         (string? v) (str/split v #",")
         :else [(str v)]) ; Convert non-strings to string first
       (map str/trim)
       (remove str/blank?)
       vec))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Date/Time Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sort-by-timestamp
  "Sorts a collection of items by their timestamp field.
   
   Parameters:
   - items: Collection to sort
   - timestamp-key: Key containing timestamp (e.g., :create_time, :created_at)
   
   Returns: Items sorted chronologically by timestamp"
  [items timestamp-key]
  (sort-by timestamp-key items))

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
  "Creates frontmatter by merging template with common timestamp fields.
   
   Optionally accepts tags and links from options map to be included in frontmatter.
   
   Parameters:
   - template-frontmatter: Base template map
   - data-map: Core data (uuid, created_at, etc.)
   - options: Optional map with :tags and :links
   
   Returns: Complete frontmatter map with timestamps and optional user data"
  ([template-frontmatter data-map]
   (merge template-frontmatter
          (assoc data-map :obsidized_at (current-timestamp))))
  ([template-frontmatter data-map options]
   (let [tags (normalize-list-option (:tags options))
         links (normalize-list-option (:links options))
         base-frontmatter (merge template-frontmatter
                                 (assoc data-map :obsidized_at (current-timestamp)))]
     (cond-> base-frontmatter
       (seq tags) (assoc :tags tags)
       (seq links) (assoc :links links)))))

 ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Timestamp Processing Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn extract-latest-timestamp
  "Extract the latest timestamp from a collection of timestamped items.
   
   Parameters:
   - items: Collection of items with timestamps
   - timestamp-key: Key to extract timestamp from (e.g., :create_time, :created_at)
   
   Returns: Latest timestamp or nil if no timestamps found"
  [items timestamp-key]
  (when (seq items)
    (->> items
         (map timestamp-key)
         (filter some?)
         (sort)
         (last))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Processing Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn process-with-defaults
  "Process data with safe defaults, common pattern for malformed data.
   
   Parameters:
   - data: Raw data map that may have missing keys
   - defaults: Map of key -> default pairs
   
   Returns: Map with guaranteed safe values"
  [data defaults]
  (let [clean-data (or data {})]
    (merge defaults
           (into {} (filter (fn [[_ v]] (some? v)) clean-data)))))

(defn generate-safe-values
  "Extract safe values from a map, providing defaults for missing keys.
   
   Parameters:
   - data: Source data map
   - key-defaults: Map of key -> default-value pairs
   
   Returns: Map with safe values (no nils)"
  [data key-defaults]
  (let [safe-data (into {} (filter (fn [[_ v]] (some? v)) data))]
    (reduce (fn [acc [k default-val]]
              (assoc acc k (or (get safe-data k) default-val)))
            safe-data
            key-defaults)))

 ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Content Generation Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn generate-content-with-sections
  "Generate complete markdown content with multiple sections.
   
   Parameters:
   - frontmatter: Complete frontmatter map
   - content-template: Template function for content generation
   - sections: Map of section data for template
   
   Returns: Complete markdown content string"
  [frontmatter content-template sections]
  (let [frontmatter-yaml (templates/format-frontmatter frontmatter)]
    (content-template (assoc sections :frontmatter-yaml frontmatter-yaml))))

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

;; Moved to earlier in file to avoid forward reference


(ns obsidize.processing
  "Shared processing utilities for deduplicating common patterns across conversations and projects."
  (:require [clojure.string :as str]
            [obsidize.utils :as utils]
            [obsidize.templates :as templates]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Timestamp Processing Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn process-items-with-timestamps
  "Generic function for processing timestamped items.
   
   Parameters:
   - items: Collection of items to process
   - extract-timestamp-fn: Function to extract timestamp from each item
   - sort-key: Key to sort by (usually same as timestamp field)
   
   Returns: Items sorted by timestamp with temporary :parsed-timestamp removed"
  [items extract-timestamp-fn sort-key]
  (->> items
       (map #(assoc % :parsed-timestamp (extract-timestamp-fn %)))
       (sort-by sort-key)
       (map #(dissoc % :parsed-timestamp))))

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
;; Frontmatter Generation Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn generate-frontmatter
  "Generic frontmatter generation using template substitution.
   
   Parameters:
   - template: Base frontmatter template map
   - data: Map of data to merge with template
   
   Returns: Complete frontmatter map with all substitutions applied"
  [template data]
  (merge template data))

(defn generate-frontmatter-with-options
  "Generate frontmatter with optional user tags and links.
   
   Parameters:
   - base-frontmatter: Base frontmatter map
   - options: Map containing :tags and :links
   
   Returns: Frontmatter with tags and links added if present"
  [base-frontmatter options]
  (let [tags (utils/normalize-list-option (:tags options))
        links (utils/normalize-list-option (:links options))]
    (cond-> base-frontmatter
      (seq tags) (assoc :tags tags)
      (seq links) (assoc :links links))))

(defn create-frontmatter-with-timestamps-and-options
  "Create complete frontmatter with timestamps and user options.
   
   Combines:
   - Base template frontmatter
   - Data map with UUIDs, names, etc.
   - Automatic timestamp addition (obsidized_at)
   - User tags and links from options
   
   Parameters:
   - template-frontmatter: Base template map
   - data-map: Core data (uuid, created_at, etc.)
   - options: User options with :tags and :links
   
   Returns: Complete frontmatter map ready for YAML generation"
  [template-frontmatter data-map options]
  (-> template-frontmatter
      (utils/create-frontmatter-with-timestamps data-map)
      (generate-frontmatter-with-options options)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Content Processing Utilities
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

(defn process-and-sort-by-timestamp
  "Process and sort items by timestamp, commonly used pattern.
   
   Parameters:
   - items: Collection to process
   - timestamp-key: Key containing timestamp (e.g., :create_time, :created_at)
   
   Returns: Items sorted chronologically by timestamp"
  [items timestamp-key]
  (sort-by timestamp-key items))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; File Processing Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn generate-safe-values
  "Extract safe values from a map, providing defaults for missing keys.
   
   Parameters:
   - data: Source data map
   - key-defaults: Map of key -> default-value pairs
   
   Returns: Map with safe values (no nils)"
  [data key-defaults]
  (let [safe-data (into {} (filter (fn [[k v]] (some? v)) data))]
    (reduce (fn [acc [k default-val]]
              (assoc acc k (or (get safe-data k) default-val)))
            safe-data
            key-defaults)))

(defn process-with-defaults
  "Process data with safe defaults, common pattern for malformed data.
   
   Parameters:
   - data: Raw data map that may have missing keys
   - defaults: Map of key -> default pairs
   
   Returns: Map with guaranteed safe values"
  [data defaults]
  (let [clean-data (or data {})]
    (merge defaults
           (into {} (filter (fn [[k v]] (some? v)) clean-data)))))
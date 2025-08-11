(ns obsidize.vault-scanner
  "Scans existing Obsidian vault structure to detect previously imported content"
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time Instant]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Frontmatter Parsing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn extract-frontmatter
  "Extract YAML frontmatter from markdown content"
  [content]
  (let [lines (str/split-lines content)]
    (if (and (>= (count lines) 3)
             (= "---" (first lines)))
      (let [end-idx (first (keep-indexed #(when (= "---" %2) %1)
                                         (drop 1 lines)))]
        (if end-idx
          {:frontmatter (str/join "\n" (take end-idx (drop 1 lines)))
           :content (str/join "\n" (drop (+ end-idx 2) lines))
           :has-frontmatter? true}
          {:frontmatter ""
           :content content
           :has-frontmatter? false}))
      {:frontmatter ""
       :content content
       :has-frontmatter? false})))

(defn parse-simple-yaml
  "Parse simple YAML frontmatter into a Clojure map"
  [yaml-str]
  (when yaml-str
    (reduce
     (fn [acc line]
       (let [line (str/trim line)]
         (if (and (not (str/blank? line))
                  (str/includes? line ":"))
           (let [[k v] (str/split line #":" 2)]
             (assoc acc
                    (keyword (str/trim k))
                    (str/trim v)))
           acc)))
     {}
     (str/split-lines yaml-str))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Vault Scanning Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn scan-markdown-files
  "Scan all markdown files in vault and extract their frontmatter"
  [vault-path]
  (let [vault-dir (io/file vault-path)]
    (if (.exists vault-dir)
      (->> (file-seq vault-dir)
           (filter #(and (.isFile %)
                         (str/ends-with? (.getName %) ".md")))
           (map (fn [file]
                  (let [content (slurp file)
                        parsed (extract-frontmatter content)
                        yaml-data (parse-simple-yaml (:frontmatter parsed))]
                    {:file-path (.getPath file)
                     :file-name (.getName file)
                     :frontmatter yaml-data
                     :has-frontmatter? (:has-frontmatter? parsed)})))
           (filter :has-frontmatter?)) ; Only files with frontmatter
      [])))

(defn build-vault-index
  "Build comprehensive index of vault contents organized by type"
  [vault-path]
  (let [all-files (scan-markdown-files vault-path)
        group-by-type (group-by #(get-in % [:frontmatter :type]) all-files)]
    {:conversations
     (->> (get group-by-type "conversation" [])
          (map (fn [file]
                 {(get-in file [:frontmatter :uuid])
                  {:file-path (:file-path file)
                   :file-name (:file-name file)
                   :created-at (get-in file [:frontmatter :created_at])
                   :updated-at (get-in file [:frontmatter :updated_at])
                   :obsidized-at (get-in file [:frontmatter :obsidized_at])}}))
          (apply merge))

     :projects
     (->> (get group-by-type "project-overview" [])
          (map (fn [file]
                 (let [project-uuid (get-in file [:frontmatter :uuid])
                       project-folder (-> (:file-path file)
                                          io/file
                                          .getParentFile
                                          .getPath)
                       ;; Find project documents in same folder
                       project-docs (->> (get group-by-type "project-document" [])
                                         (filter #(str/starts-with? (:file-path %) project-folder))
                                         (map (fn [doc]
                                                {:file-path (:file-path doc)
                                                 :file-name (:file-name doc)
                                                 :uuid (get-in doc [:frontmatter :uuid])
                                                 :created-at (get-in doc [:frontmatter :created_at])
                                                 :obsidized-at (get-in doc [:frontmatter :obsidized_at])})))]
                   {project-uuid
                    {:folder-path project-folder
                     :overview-file (:file-path file)
                     :created-at (get-in file [:frontmatter :created_at])
                     :updated-at (get-in file [:frontmatter :updated_at])
                     :obsidized-at (get-in file [:frontmatter :obsidized_at])
                     :documents project-docs}})))
          (apply merge))

     :total-files (count all-files)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Update Decision Logic
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-timestamp
  "Parse ISO timestamp string to Instant"
  [timestamp-str]
  (when timestamp-str
    (try
      (Instant/parse timestamp-str)
      (catch Exception _ nil))))

(defn timestamp-before?
  "Check if timestamp1 is before timestamp2"
  [ts1 ts2]
  (when (and ts1 ts2)
    (.isBefore ts1 ts2)))

(defn needs-update?
  "Determine if an item needs updating based on timestamps"
  [existing-item claude-item]
  (let [existing-obsidized-at (parse-timestamp (:obsidized-at existing-item))
        claude-updated-at (parse-timestamp (:updated_at claude-item))]
    (or (nil? existing-obsidized-at) ; Never imported
        (nil? claude-updated-at) ; Be safe if Claude data is malformed  
        (timestamp-before? existing-obsidized-at claude-updated-at)))) ; Claude data is newer

(defn determine-update-type
  "Determine what type of update is needed"
  [existing-item claude-item]
  (cond
    (nil? existing-item) :create-new
    (needs-update? existing-item claude-item) :update-existing
    :else :no-update))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn scan-vault
  "Main entry point - scan vault and return structured index"
  [vault-path]
  (build-vault-index vault-path))

(defn plan-updates
  "Compare vault index with Claude data and create update plan"
  [vault-index claude-conversations claude-projects]
  (let [conversation-plan
        (map (fn [claude-conv]
               (let [uuid (:uuid claude-conv)
                     existing (get-in vault-index [:conversations uuid])
                     update-type (determine-update-type existing claude-conv)]
                 {:type :conversation
                  :uuid uuid
                  :update-type update-type
                  :claude-data claude-conv
                  :existing-data existing}))
             claude-conversations)

        project-plan
        (map (fn [claude-proj]
               (let [uuid (:uuid claude-proj)
                     existing (get-in vault-index [:projects uuid])
                     update-type (determine-update-type existing claude-proj)]
                 {:type :project
                  :uuid uuid
                  :update-type update-type
                  :claude-data claude-proj
                  :existing-data existing}))
             claude-projects)]

    {:conversations (group-by :update-type conversation-plan)
     :projects (group-by :update-type project-plan)
     :summary {:conversations {:create-new (count (get (group-by :update-type conversation-plan) :create-new []))
                               :update-existing (count (get (group-by :update-type conversation-plan) :update-existing []))
                               :no-update (count (get (group-by :update-type conversation-plan) :no-update []))}
               :projects {:create-new (count (get (group-by :update-type project-plan) :create-new []))
                          :update-existing (count (get (group-by :update-type project-plan) :update-existing []))
                          :no-update (count (get (group-by :update-type project-plan) :no-update []))}}}))
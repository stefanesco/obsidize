(ns obsidize.data-validation
  "Robust data validation for Claude exports to prevent NullPointerExceptions"
  (:require [clojure.string :as str]
            [obsidize.error :as error]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Validation Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn valid-uuid?
  "Check if a UUID string is valid"
  [uuid]
  (and (string? uuid)
       (not (str/blank? uuid))
       (re-matches #"[a-fA-F0-9\-]{36}" uuid)))

(defn valid-timestamp?
  "Check if a timestamp string is valid ISO format"
  [timestamp]
  (and (string? timestamp)
       (not (str/blank? timestamp))
       (try
         (java.time.Instant/parse timestamp)
         true
         (catch Exception _ false))))

(defn safe-get
  "Safely get a value from a map with a fallback"
  ([m key] (safe-get m key nil))
  ([m key fallback]
   (let [val (get m key)]
     (if (nil? val) fallback val))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Conversation Validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn validate-conversation
  "Validate a conversation object and return cleaned/corrected version"
  [conversation]
  (let [uuid (safe-get conversation :uuid "")
        name (safe-get conversation :name "Untitled Conversation")
        created_at (safe-get conversation :created_at "1970-01-01T00:00:00Z")
        updated_at (safe-get conversation :updated_at created_at)
        chats (safe-get conversation :chats [])]

    ;; Validate required fields
    (cond
      (str/blank? uuid)
      (error/failure "Conversation missing UUID")

      (not (valid-timestamp? created_at))
      (error/failure (str "Invalid created_at timestamp: " created_at))

      :else
      ;; Clean and validate chats
      (let [validated-chats (->> chats
                                 (filter map?) ; Only keep map entries
                                 (map (fn [chat]
                                        (let [q (safe-get chat :q "")
                                              a (safe-get chat :a "")
                                              create_time (safe-get chat :create_time created_at)]
                                          (when (and (not (str/blank? q))
                                                     (not (str/blank? a)))
                                            {:q q
                                             :a a
                                             :create_time (if (valid-timestamp? create_time)
                                                            create_time
                                                            created_at)}))))
                                 (filter identity))] ; Remove nil entries

        (error/success {:uuid uuid
                        :name name
                        :created_at created_at
                        :updated_at (if (valid-timestamp? updated_at) updated_at created_at)
                        :chats validated-chats
                        :validation-warnings (when (< (count validated-chats) (count chats))
                                               [(str "Filtered out " (- (count chats) (count validated-chats)) " invalid chat entries")])})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Project Validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn validate-project-document
  "Validate a project document"
  [doc]
  (let [uuid (safe-get doc :uuid "")
        filename (safe-get doc :filename "document.md")
        content (safe-get doc :content "")
        created_at (safe-get doc :created_at "1970-01-01T00:00:00Z")]

    (if (str/blank? uuid)
      (error/failure "Document missing UUID")
      (error/success {:uuid uuid
                      :filename (if (str/blank? filename) "document.md" filename)
                      :content content
                      :created_at (if (valid-timestamp? created_at) created_at "1970-01-01T00:00:00Z")}))))

(defn validate-project
  "Validate a project object and return cleaned/corrected version"
  [project]
  (let [uuid (safe-get project :uuid "")
        name (safe-get project :name "Untitled Project")
        description (safe-get project :description "")
        created_at (safe-get project :created_at "1970-01-01T00:00:00Z")
        updated_at (safe-get project :updated_at created_at)
        docs (safe-get project :docs [])]

    ;; Validate required fields
    (cond
      (str/blank? uuid)
      (error/failure "Project missing UUID")

      (not (valid-timestamp? created_at))
      (error/failure (str "Invalid created_at timestamp: " created_at))

      :else
      ;; Clean and validate documents
      (let [doc-results (->> docs
                             (filter map?) ; Only keep map entries
                             (map validate-project-document))
            successful-docs (map :data (filter :success? doc-results))
            validation-warnings (when (< (count successful-docs) (count docs))
                                  [(str "Filtered out " (- (count docs) (count successful-docs)) " invalid documents")])]

        (error/success {:uuid uuid
                        :name name
                        :description description
                        :created_at created_at
                        :updated_at (if (valid-timestamp? updated_at) updated_at created_at)
                        :docs successful-docs
                        :validation-warnings validation-warnings})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Batch Validation Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn validate-conversations
  "Validate a collection of conversations, filtering out invalid ones"
  [conversations]
  (let [results (->> conversations
                     (map validate-conversation))
        successful-results (filter :success? results)
        failed-results (filter #(not (:success? %)) results)]

    {:valid-conversations (vec (map :data successful-results))
     :invalid-conversations (vec (map #(assoc {} :validation-error (first (:errors %))) failed-results))
     :total-count (count conversations)
     :valid-count (count successful-results)
     :invalid-count (count failed-results)}))

(defn validate-projects
  "Validate a collection of projects, filtering out invalid ones"
  [projects]
  (let [results (->> projects
                     (map validate-project))
        successful-results (filter :success? results)
        failed-results (filter #(not (:success? %)) results)]

    {:valid-projects (vec (map :data successful-results))
     :invalid-projects (vec (map #(assoc {} :validation-error (first (:errors %))) failed-results))
     :total-count (count projects)
     :valid-count (count successful-results)
     :invalid-count (count failed-results)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Summary and Reporting Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn validation-summary
  "Generate a summary of validation results"
  [conversations-result projects-result]
  (let [total-conversations (:total-count conversations-result)
        valid-conversations (:valid-count conversations-result)
        invalid-conversations (:invalid-count conversations-result)

        total-projects (:total-count projects-result)
        valid-projects (:valid-count projects-result)
        invalid-projects (:invalid-count projects-result)]

    {:conversations {:total total-conversations
                     :valid valid-conversations
                     :invalid invalid-conversations
                     :success-rate (if (> total-conversations 0)
                                     (/ valid-conversations total-conversations)
                                     1.0)}
     :projects {:total total-projects
                :valid valid-projects
                :invalid invalid-projects
                :success-rate (if (> total-projects 0)
                                (/ valid-projects total-projects)
                                1.0)}
     :overall-success? (and (> valid-conversations 0)
                            (> valid-projects 0))}))

(defn print-validation-report
  "Print a human-readable validation report"
  [summary]
  (println "\n🔍 Data Validation Report:")
  (println (str "📝 Conversations: "
                (get-in summary [:conversations :valid]) " valid, "
                (get-in summary [:conversations :invalid]) " invalid "
                "(success rate: " (int (* 100 (get-in summary [:conversations :success-rate]))) "%)"))
  (println (str "📁 Projects: "
                (get-in summary [:projects :valid]) " valid, "
                (get-in summary [:projects :invalid]) " invalid "
                "(success rate: " (int (* 100 (get-in summary [:projects :success-rate]))) "%)"))
  (when-not (:overall-success? summary)
    (println "⚠️  Some data validation issues found - check logs for details")))
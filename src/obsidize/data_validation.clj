(ns obsidize.data-validation
  "Robust data validation for Claude exports to prevent NullPointerExceptions"
  (:require [clojure.string :as str]))

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
  (try
    (let [uuid (safe-get conversation :uuid "")
          name (safe-get conversation :name "Untitled Conversation")
          created_at (safe-get conversation :created_at "1970-01-01T00:00:00Z")
          updated_at (safe-get conversation :updated_at created_at)
          chats (safe-get conversation :chats [])]

      ;; Validate required fields
      (when (str/blank? uuid)
        (throw (ex-info "Conversation missing UUID" {:conversation conversation})))

      (when-not (valid-timestamp? created_at)
        (throw (ex-info "Invalid created_at timestamp" {:timestamp created_at})))

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

        {:uuid uuid
         :name name
         :created_at created_at
         :updated_at (if (valid-timestamp? updated_at) updated_at created_at)
         :chats validated-chats
         :validation-warnings (when (< (count validated-chats) (count chats))
                                [(str "Filtered out " (- (count chats) (count validated-chats)) " invalid chat entries")])}))

    (catch Exception e
      {:validation-error (.getMessage e)
       :original-data conversation})))

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

    (when (str/blank? uuid)
      (throw (ex-info "Document missing UUID" {:document doc})))

    {:uuid uuid
     :filename (if (str/blank? filename) "document.md" filename)
     :content content
     :created_at (if (valid-timestamp? created_at) created_at "1970-01-01T00:00:00Z")}))

(defn validate-project
  "Validate a project object and return cleaned/corrected version"
  [project]
  (try
    (let [uuid (safe-get project :uuid "")
          name (safe-get project :name "Untitled Project")
          description (safe-get project :description "")
          created_at (safe-get project :created_at "1970-01-01T00:00:00Z")
          updated_at (safe-get project :updated_at created_at)
          docs (safe-get project :docs [])]

      ;; Validate required fields
      (when (str/blank? uuid)
        (throw (ex-info "Project missing UUID" {:project project})))

      (when-not (valid-timestamp? created_at)
        (throw (ex-info "Invalid created_at timestamp" {:timestamp created_at})))

      ;; Clean and validate documents
      (let [validated-docs (->> docs
                                (filter map?) ; Only keep map entries
                                (map (fn [doc]
                                       (try
                                         (validate-project-document doc)
                                         (catch Exception _
                                           nil)))) ; Skip invalid docs
                                (filter identity))]

        {:uuid uuid
         :name name
         :description description
         :created_at created_at
         :updated_at (if (valid-timestamp? updated_at) updated_at created_at)
         :docs validated-docs
         :validation-warnings (when (< (count validated-docs) (count docs))
                                [(str "Filtered out " (- (count docs) (count validated-docs)) " invalid documents")])}))

    (catch Exception e
      {:validation-error (.getMessage e)
       :original-data project})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Batch Validation Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn validate-conversations
  "Validate a collection of conversations, filtering out invalid ones"
  [conversations]
  (let [results (->> conversations
                     (map validate-conversation)
                     (group-by #(contains? % :validation-error)))]

    {:valid-conversations (get results false [])
     :invalid-conversations (get results true [])
     :total-count (count conversations)
     :valid-count (count (get results false []))
     :invalid-count (count (get results true []))}))

(defn validate-projects
  "Validate a collection of projects, filtering out invalid ones"
  [projects]
  (let [results (->> projects
                     (map validate-project)
                     (group-by #(contains? % :validation-error)))]

    {:valid-projects (get results false [])
     :invalid-projects (get results true [])
     :total-count (count projects)
     :valid-count (count (get results false []))
     :invalid-count (count (get results true []))}))

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
#!/usr/bin/env bb

(ns obsidize.conversations
  "Functions for processing conversations and generating markdown notes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [obsidize.templates :as templates]
            [obsidize.utils :as utils]
            [obsidize.vault-scanner :as vault-scanner]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pure helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn format-message-timestamp [ts]
  (utils/format-timestamp ts))

(defn generate-title-for-nameless-convo [chat]
  (if (nil? chat)
    (str templates/default-date-prefix " " templates/default-conversation-title)
    (let [q-text (or (:q chat) "")
          create-time (or (:create_time chat) "")
          words (if (str/blank? q-text)
                  []
                  (str/split q-text #"\s+"))
          first-q (if (seq words)
                    (->> words (take 6) (str/join " "))
                    templates/default-conversation-title)
          date-prefix (if (str/blank? create-time)
                        templates/default-date-prefix
                        (-> create-time (str/split #"T") first))]
      (str date-prefix " " first-q))))

(defn generate-markdown-for-new-note
  "Pure: builds {:title :filename :content} for a conversation.
   Includes :tags and :links (from options) in the frontmatter if provided.
   options keys used: :tags, :links."
  [conversation app-version options]
  (let [{:keys [uuid name created_at updated_at chats]} conversation
        safe-uuid (or uuid "unknown-uuid")
        safe-created-at (or created_at "Unknown")
        safe-updated-at (or updated_at "Unknown")
        safe-chats (or chats [])
        title (if (str/blank? name)
                (generate-title-for-nameless-convo (first safe-chats))
                name)
        ;; FIXED: Use the timestamp of the latest message for obsidized_at
        ;; This ensures incremental updates work correctly
        latest-message-time (if (seq safe-chats)
                              (->> safe-chats
                                   (map :create_time)
                                   (filter some?)
                                   (sort)
                                   (last))
                              nil)
        obsidized-at (or latest-message-time (utils/current-timestamp))
        tags (utils/normalize-list-option (:tags options))
        links (utils/normalize-list-option (:links options))

        ;; Format messages using templates
        chat-messages (if (seq safe-chats)
                        (->> safe-chats
                             (sort-by :create_time)
                             (map (fn [{:keys [q a create_time]}]
                                    (let [safe-q (or q templates/missing-question-placeholder)
                                          safe-a (or a templates/missing-answer-placeholder)
                                          safe-timestamp (or create_time "Unknown time")]
                                      (templates/format-conversation-message
                                       (format-message-timestamp safe-timestamp)
                                       safe-q
                                       safe-a)))))
                        [templates/no-messages-placeholder])

        ;; Frontmatter
        base-front (merge templates/conversation-frontmatter
                          {:uuid safe-uuid
                           :created_at safe-created-at
                           :updated_at safe-updated-at
                           :obsidize_version app-version
                           :obsidized_at obsidized-at})
        frontmatter (cond-> base-front
                      (seq tags) (assoc :tags tags)
                      (seq links) (assoc :links links))

        ;; Filename
        filename (templates/format-conversation-filename title safe-uuid)]
    {:title title
     :filename (utils/sanitize-filename filename)
     :content (templates/format-conversation-content frontmatter title chat-messages)}))

(defn extract-existing-message-signatures
  "Extract message signatures from existing file content to detect what's already present.
   Returns a set of message signatures in the format 'timestamp::question'."
  [content]
  (let [message-pattern #"\*\*(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z) Me:\*\* (.+)"
        matches (re-seq message-pattern content)]
    (->> matches
         (map (fn [[_ timestamp question]]
                (str timestamp "::" (str/trim question))))
         (set))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pure Logic Functions (Phase 3.1)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn calculate-conversation-updates
  "Pure function: determines what updates are needed for a conversation.
   Returns map with update information including new messages and frontmatter."
  [conversation existing-content]
  (let [parsed (vault-scanner/extract-frontmatter existing-content)
        frontmatter (vault-scanner/parse-simple-yaml (:frontmatter parsed))
        obsidized-at-str (:obsidized_at frontmatter)
        obsidized-at (vault-scanner/parse-timestamp obsidized-at-str)]
    (if obsidized-at
      (let [existing-signatures (extract-existing-message-signatures existing-content)
            new-messages (->> (:chats conversation)
                              (filter (fn [{:keys [create_time q]}]
                                        (when (and create_time q)
                                          (let [msg-time (vault-scanner/parse-timestamp create_time)
                                                signature (str create_time "::" (str/trim q))]
                                            (and msg-time
                                                 (.isAfter msg-time obsidized-at)
                                                 (not (contains? existing-signatures signature)))))))
                              (sort-by :create_time))
            new-obsidized-at (or (:updated_at conversation) (utils/current-timestamp))
            ;; Include the actual conversation data in updated frontmatter
            updated-frontmatter (merge
                                 (when (:updated_at conversation)
                                   {:updated_at (:updated_at conversation)})
                                 {:obsidized_at new-obsidized-at})]
        {:needs-update? (seq new-messages)
         :new-messages new-messages
         :updated-frontmatter updated-frontmatter
         :messages-md (when (seq new-messages)
                        (->> new-messages
                             (map (fn [{:keys [q a create_time]}]
                                    (templates/format-conversation-message
                                     (format-message-timestamp create_time)
                                     (or q templates/missing-question-placeholder)
                                     (or a templates/missing-answer-placeholder))))
                             (str/join "\n")))})
      ;; If no obsidized_at found, treat as corrupted and regenerate completely
      {:needs-update? false
       :regenerate? true
       :new-messages []
       :updated-frontmatter {}
       :messages-md nil})))

(defn generate-conversation-markdown
  "Pure function: generates complete markdown content for a conversation."
  [conversation app-version options]
  (generate-markdown-for-new-note conversation app-version options))

(defn apply-frontmatter-updates
  "Pure function: applies frontmatter updates to existing content."
  [existing-content updated-frontmatter]
  (reduce (fn [content [key value]]
            (str/replace content
                         (re-pattern (str (name key) ": .*"))
                         (str (name key) ": " value)))
          existing-content
          updated-frontmatter))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Impure I/O
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn write-conversation-updates!
  "I/O function: handles file operations for conversation updates."
  [output-path updates existing-content]
  (when (:needs-update? updates)
    (println (str "📝 Appending " (count (:new-messages updates)) " new messages"))
    (let [updated-content (apply-frontmatter-updates existing-content (:updated-frontmatter updates))
          final-content (str updated-content "\n\n" (:messages-md updates))]
      (spit output-path final-content))))

(defn write-new-conversation!
  "I/O function: creates a new conversation file."
  [output-path conversation-data]
  (println (str "✨ Creating new conversation: " (:filename conversation-data)))
  (spit output-path (:content conversation-data)))

(defn read-existing-conversation
  "I/O function: reads existing conversation file if it exists."
  [output-path]
  (let [output-file (io/file output-path)]
    (when (.exists output-file)
      (slurp output-file))))

(defn process-conversation
  "Creates or updates a conversation note on disk.
   Signature aligned with core: (conversation output-dir app-version options).
   NOTE: caller (core) is responsible for honoring --dry-run."
  [conversation output-dir app-version options]
  (let [generated-note (generate-conversation-markdown conversation app-version options)
        output-path (str output-dir "/" (:filename generated-note))
        existing-content (read-existing-conversation output-path)]
    (if existing-content
      ;; Handle existing conversation with pure logic
      (let [updates (calculate-conversation-updates conversation existing-content)]
        (cond
          (:regenerate? updates)
          (write-new-conversation! output-path generated-note)

          (:needs-update? updates)
          (write-conversation-updates! output-path updates existing-content)

          :else
          (println (str "⏭️  No new messages for: " (:filename generated-note)))))
      ;; Create new conversation
      (write-new-conversation! output-path generated-note))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Minimal -main (kept for manual/debug usage)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn -main [& _args]
  (let [output-dir "conversations-md"
        input-file "conversations.json"]
    (io/make-parents (str output-dir "/.placeholder"))
    (if-not (.exists (io/file input-file))
      (println "Error: Input file not found.")
      (do
        (println "Note: -main function requires JSON parsing dependency.")
        (println "Use obsidize.core for full functionality.")))
    (shutdown-agents)))

(when (= *file* (System/getProperty "babashka.main"))
  (-main))
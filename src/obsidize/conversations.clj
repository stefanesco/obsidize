#!/usr/bin/env bb

(ns obsidize.conversations
  "Functions for processing conversations and generating markdown notes."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [obsidize.templates :as templates]
            [obsidize.utils :as utils]
            [obsidize.vault-scanner :as vault-scanner]))

;; --- Pure Utility Functions (Easily Testable) ---

;; Use utils/sanitize-filename for consistent behavior

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
                    (->> words
                         (take 6)
                         (str/join " "))
                    templates/default-conversation-title)
          date-prefix (if (str/blank? create-time)
                        templates/default-date-prefix
                        (-> create-time
                            (str/split #"T")
                            first))]
      (str date-prefix " " first-q))))

(defn generate-markdown-for-new-note [conversation app-version]
  (let [{:keys [uuid name created_at updated_at chats]} conversation
        safe-uuid (or uuid "unknown-uuid")
        safe-created-at (or created_at "Unknown")
        safe-updated-at (or updated_at "Unknown")
        safe-chats (or chats [])
        title (if (str/blank? name)
                (generate-title-for-nameless-convo (first safe-chats))
                name)
        obsidized-at (utils/current-timestamp)

        ;; Format messages using templates
        chat-messages (if (seq safe-chats)
                        (->> safe-chats
                             (sort-by :create_time)
                             (map (fn [chat]
                                    (let [{:keys [q a create_time]} chat
                                          safe-q (or q templates/missing-question-placeholder)
                                          safe-a (or a templates/missing-answer-placeholder)
                                          safe-timestamp (or create_time "Unknown time")]
                                      (templates/format-conversation-message
                                       (format-message-timestamp safe-timestamp)
                                       safe-q
                                       safe-a)))))
                        [templates/no-messages-placeholder])

        ;; Create frontmatter using templates
        frontmatter (merge templates/conversation-frontmatter
                           {:uuid safe-uuid
                            :created_at safe-created-at
                            :updated_at safe-updated-at 
                            :obsidize_version app-version
                            :obsidized_at obsidized-at})

        ;; Generate filename using template
        filename (templates/format-conversation-filename title safe-uuid)]

    {:title title
     :filename (utils/sanitize-filename filename)
     :content (templates/format-conversation-content frontmatter title chat-messages)}))

(defn determine-new-messages
  "Determine which messages in conversation are newer than the existing note's obsidized_at timestamp"
  [conversation existing-note-content]
  (let [parsed (vault-scanner/extract-frontmatter existing-note-content)
        frontmatter (vault-scanner/parse-simple-yaml (:frontmatter parsed))
        obsidized-at-str (:obsidized_at frontmatter)
        obsidized-at (vault-scanner/parse-timestamp obsidized-at-str)]

    (if obsidized-at
      (let [new-messages (->> (:chats conversation)
                              (filter (fn [{:keys [create_time]}]
                                        (when create_time
                                          (let [msg-time (vault-scanner/parse-timestamp create_time)]
                                            (and msg-time
                                                 (.isAfter msg-time obsidized-at))))))
                              (sort-by :create_time))]
        (when (seq new-messages)
          {:new-messages new-messages
           :messages-md (->> new-messages
                             (map (fn [{:keys [q a create_time]}]
                                    (templates/format-conversation-message
                                     (format-message-timestamp create_time)
                                     (or q templates/missing-question-placeholder)
                                     (or a templates/missing-answer-placeholder))))
                             (str/join "\n"))}))
      ;; If no obsidized_at found, treat as corrupted and regenerate completely
      nil)))

;; --- Impure File I/O Functions (Side Effects) ---

(defn process-conversation [conversation output-dir app-version]
  (let [generated-note (generate-markdown-for-new-note conversation app-version)
        output-path (str output-dir "/" (:filename generated-note))
        output-file (io/file output-path)]
    (if (.exists output-file)
      ;; File exists - try incremental update
      (let [existing-content (slurp output-file)
            update-data (determine-new-messages conversation existing-content)]
        (if update-data
          ;; Found new messages - append them
          (let [new-obsidized-at (utils/current-timestamp)
                ;; Update both updated_at and obsidized_at in frontmatter
                updated-content (-> existing-content
                                    (str/replace
                                     (re-pattern "updated_at: .*")
                                     (str "updated_at: " (:updated_at conversation)))
                                    (str/replace
                                     (re-pattern "obsidized_at: .*")
                                     (str "obsidized_at: " new-obsidized-at)))]
            (println (str "📝 Appending " (count (:new-messages update-data)) " new messages to: " (:filename generated-note)))
            (spit output-path (str updated-content "\n\n" (:messages-md update-data))))
          ;; No new messages or corrupted file - skip or regenerate
          (println (str "⏭️  No new messages for: " (:filename generated-note)))))
      ;; File doesn't exist - create new
      (do
        (println (str "✨ Creating new conversation: " (:filename generated-note)))
        (spit output-path (:content generated-note))))))

;; --- Main Execution ---

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
(ns obsidize.data-pack
  "Functions for handling Claude data pack input in various formats (folder, .dms archive)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :as set]
            [cheshire.core :as json]
            [obsidize.data-validation :as validation]
            [obsidize.logging :as log])
  (:import [java.util.zip ZipFile ZipEntry]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Input Detection Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- file-exists?
  "Check if a file exists at the given path"
  [path]
  (.exists (io/file path)))

(defn- directory?
  "Check if path is a directory"
  [path]
  (.isDirectory (io/file path)))

(defn- zip-file?
  "Check if path is a ZIP archive (including .dms files).
   Enhanced with native-image diagnostics."
  [path]
  (and (file-exists? path)
       (not (directory? path))
       (try
         (log/log-debug (str "Attempting to open ZIP file: " path))
         (with-open [zip (ZipFile. path)]
           (let [entry-count (count (enumeration-seq (.entries zip)))]
             (log/log-debug (str "Successfully opened ZIP file with " entry-count " entries: " path))
             true))
         (catch Exception e
           (log/log-debug (str "Failed to open as ZIP file: " path " - " (.getMessage e)))
           false))))

(defn detect-input-type
  "Detect the type of Claude data pack input.
   Returns: :folder, :archive, or :unknown"
  [path]
  (cond
    (directory? path) :folder
    (zip-file? path) :archive
    :else :unknown))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Archive Extraction Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- extract-zip-entry
  "Extract a single entry from ZIP file to target directory"
  [^ZipFile zip-file ^ZipEntry entry target-dir]
  (let [entry-path (.getName entry)
        target-file (io/file target-dir entry-path)]
    (when-not (.isDirectory entry)
      (io/make-parents target-file)
      (with-open [input (.getInputStream zip-file entry)
                  output (io/output-stream target-file)]
        (io/copy input output)))
    target-file))

(defn extract-archive
  "Extract a .dms/.zip archive to a temporary directory.
   Returns the path to the extracted directory or nil if extraction fails.
   Enhanced with diagnostics and native-image compatibility."
  [archive-path]
  (try
    (log/log-verbose (str "Extracting archive: " archive-path))

    ;; Diagnose temp directory access first
    (let [temp-diag (log/diagnose-temp-directory)]
      (when-not (:can-create-temp-files? temp-diag)
        (throw (Exception. (str "Cannot create temporary files: " (:error temp-diag))))))

    (let [temp-dir (io/file (System/getProperty "java.io.tmpdir")
                            (str "obsidize-" (System/currentTimeMillis)))]
      (log/log-debug (str "Creating temp directory: " (.getAbsolutePath temp-dir)))
      (.mkdirs temp-dir)

      (log/time-operation
       "Archive extraction"
       (with-open [zip-file (ZipFile. archive-path)]
         (let [entries (enumeration-seq (.entries zip-file))
               entry-count (count entries)]
           (log/log-verbose (str "Extracting " entry-count " entries from archive"))
           (doseq [entry entries]
             (log/log-debug (str "Extracting: " (.getName entry)))
             (extract-zip-entry zip-file entry temp-dir)))))

      (let [result-path (.getAbsolutePath temp-dir)]
        (log/log-success (str "Archive extracted to: " result-path))
        result-path))
    (catch Exception e
      (log/log-error (str "Failed to extract archive: " (.getMessage e)))
      (log/log-debug (str "Archive extraction stack trace: " (.printStackTrace e)))
      nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Pack Validation Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def required-files
  "Files that must be present in a Claude data pack"
  #{"conversations.json" "projects.json"})

(defn- find-json-files
  "Find all JSON files in directory, returns a set of filenames"
  [directory]
  (->> (file-seq (io/file directory))
       (filter #(.isFile %))
       (map #(.getName %))
       (filter #(str/ends-with? % ".json"))
       (set)))

(defn validate-data-pack
  "Validate that a directory contains the required Claude data pack files.
   Returns {:valid? true/false, :missing [], :found []}"
  [directory]
  (let [found-files (find-json-files directory)
        missing-files (set/difference required-files found-files)]
    {:valid? (empty? missing-files)
     :missing (vec missing-files)
     :found (vec (set/intersection required-files found-files))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JSON Loading with Error Handling
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- safe-parse-json
  "Parse JSON with error handling, returns {:success? bool, :data [], :error str}"
  [file-path]
  (try
    (let [content (slurp file-path)
          data (json/parse-string content true)]
      {:success? true
       :data (cond
               (vector? data) (vec data) ; Ensure vector, not lazy seq
               (seq? data) (vec data) ; Convert lazy seq to vector
               :else [data]) ; Wrap single item in vector
       :error nil})
    (catch Exception e
      {:success? false
       :data []
       :error (str "Failed to parse " file-path ": " (.getMessage e))})))

(defn load-conversations
  "Load conversations.json with error handling and validation"
  [directory]
  (let [conversations-path (str directory "/conversations.json")]
    (if (file-exists? conversations-path)
      (let [parse-result (safe-parse-json conversations-path)]
        (if (:success? parse-result)
          (let [validation-result (validation/validate-conversations (:data parse-result))]
            {:success? true
             :data (:valid-conversations validation-result)
             :error nil
             :validation-summary {:total (:total-count validation-result)
                                  :valid (:valid-count validation-result)
                                  :invalid (:invalid-count validation-result)}})
          parse-result))
      {:success? false
       :data []
       :error "conversations.json not found"})))

(defn load-projects
  "Load projects.json with error handling and validation"
  [directory]
  (let [projects-path (str directory "/projects.json")]
    (if (file-exists? projects-path)
      (let [parse-result (safe-parse-json projects-path)]
        (if (:success? parse-result)
          (let [validation-result (validation/validate-projects (:data parse-result))]
            {:success? true
             :data (:valid-projects validation-result)
             :error nil
             :validation-summary {:total (:total-count validation-result)
                                  :valid (:valid-count validation-result)
                                  :invalid (:invalid-count validation-result)}})
          parse-result))
      {:success? false
       :data []
       :error "projects.json not found"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main Data Pack Processing Function
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn process-data-pack
  "Main function to process a Claude data pack from any input format.
   Returns: {:success? bool, :data-dir str, :conversations [], :projects [], :errors []}"
  [input-path]
  (let [input-type (detect-input-type input-path)
        errors (atom [])]

    (case input-type
      :unknown
      {:success? false
       :data-dir nil
       :conversations []
       :projects []
       :errors [(str "Unknown input type: " input-path)]}

      :folder
      (let [validation (validate-data-pack input-path)]
        (if (:valid? validation)
          (let [conversations-result (load-conversations input-path)
                projects-result (load-projects input-path)]
            (when-not (:success? conversations-result)
              (swap! errors conj (:error conversations-result)))
            (when-not (:success? projects-result)
              (swap! errors conj (:error projects-result)))
            {:success? (and (:success? conversations-result) (:success? projects-result))
             :data-dir input-path
             :conversations (:data conversations-result)
             :projects (:data projects-result)
             :errors @errors})
          {:success? false
           :data-dir input-path
           :conversations []
           :projects []
           :errors [(str "Missing required files: " (str/join ", " (:missing validation)))]}))

      :archive
      (if-let [extracted-dir (extract-archive input-path)]
        (let [result (process-data-pack extracted-dir)]
          (assoc result :data-dir extracted-dir)) ; Keep track of temp directory
        {:success? false
         :data-dir nil
         :conversations []
         :projects []
         :errors [(str "Failed to extract archive: " input-path)]}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cleanup Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn cleanup-temp-directory
  "Clean up temporary directory created during archive extraction"
  [temp-dir]
  (when (and temp-dir (str/includes? temp-dir "obsidize-"))
    (try
      ;; Use Java-based deletion instead of shell to avoid process threads
      (let [dir-file (io/file temp-dir)]
        (when (.exists dir-file)
          (letfn [(delete-recursively [file]
                    (when (.exists file)
                      (if (.isDirectory file)
                        (doseq [child (.listFiles file)]
                          (delete-recursively child))
                        nil) ; else branch for non-directory files
                      (.delete file)))]
            (delete-recursively dir-file)))
        (println (str "Cleaned up temporary directory: " temp-dir)))
      (catch Exception e
        (println (str "Warning: Failed to cleanup " temp-dir ": " (.getMessage e)))))))
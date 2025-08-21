(ns obsidize.projects-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [obsidize.projects :as sut]
            [obsidize.utils :as utils]
            [obsidize.templates :as templates]))

(deftest process-project-basic-writes
  (testing "writes docs + overview with normalized tags/links and version"
    (let [writes (atom [])
          dirs (atom [])
          msgs (atom [])]
      (with-redefs [println (fn [& xs] (swap! msgs conj (str/join " " xs)))
                    ;; ensure-directory returns an object with .getPath
                    obsidize.utils/ensure-directory
                    (fn [p] (swap! dirs conj p)
                      (proxy [java.io.File] [p]
                        (getPath [] p)))
                    obsidize.utils/sort-by-timestamp (fn [docs timestamp-key] (sort-by timestamp-key docs))
                    obsidize.utils/sanitize-filename identity
                    obsidize.utils/create-tags-section (fn [tags] (when (seq tags) (str "\nTAGS:" (pr-str tags))))
                    obsidize.utils/create-frontmatter-with-timestamps (fn [base m] (merge base m))
                    obsidize.utils/write-note-if-changed (fn [path content] (swap! writes conj [path content]))

                    ;; template fns
                    obsidize.templates/format-project-document-filename
                    (fn [idx fname] (format "%02d-%s" idx fname))
                    obsidize.templates/format-frontmatter
                    (fn [fm] (str "FM:" (pr-str (select-keys fm [:uuid :project_name :obsidize_version :created_at :updated_at])) "\n"))
                    obsidize.templates/project-document-frontmatter {}
                    obsidize.templates/project-overview-frontmatter {}
                    obsidize.templates/format-project-overview-filename
                    (fn [base] (str base "-OV.md"))
                    obsidize.templates/format-project-documents-section
                    (fn [filenames] (when (seq filenames)
                                      (str "## Documents\n"
                                           (clojure.string/join "\n" (map #(str "- [[" % "]]") filenames)))))
                    obsidize.templates/format-project-links-section
                    (fn [links] (str "LINKS:" (pr-str links)))
                    obsidize.templates/format-project-content
                    (fn [fm title desc docs links tags]
                      (str fm "T:" title "\nD:" desc "\n" docs "\n" links "\n" tags))]

        (sut/process-project
         {:uuid "P-1"
          :name "My Project"
          :description "desc"
          :created_at "2025-01-01T00:00:00Z"
          :updated_at "2025-01-02T00:00:00Z"
          :docs [{:uuid "d1" :filename "a.md" :created_at "2025-01-01T01:00:00Z" :content "A"}
                 {:uuid "d2" :filename "b.md" :created_at "2025-01-01T02:00:00Z" :content "B"}]}
         {:output-dir "OUT"
          :tags " t1,  t2 ,"
          :links ["[[L1]]" "  " "[[L2]]"]
          :app-version "1.2.3"})

        ;; one directory ensured
        (is (= ["OUT/My Project"] @dirs))

        ;; 3 writes: 2 docs + overview
        (is (= 3 (count @writes)))

        (let [[[p1 c1] [p2 c2] [p3 c3]] @writes]
          ;; file paths are within OUT/My Project and use our filename formatter
          (is (every? #(str/starts-with? % "OUT/My Project/") [p1 p2 p3]))
          (is (or (str/ends-with? p1 "01-a.md") (str/ends-with? p2 "01-a.md")))
          (is (or (str/ends-with? p1 "02-b.md") (str/ends-with? p2 "02-b.md")))
          (is (str/ends-with? p3 "My Project-OV.md"))

          ;; doc contents contain frontmatter, content, and tags section
          (is (every? #(str/includes? % "FM:{") [c1 c2]))
          (is (every? #(str/includes? % "TAGS:[\"t1\" \"t2\"]") [c1 c2]))

          ;; overview contains links + tags + docs references + obsidize_version
          ;; Updated expectations to match actual mock behavior
          (is (str/includes? c3 "LINKS:[\"[[L1]]\" \"[[L2]]\"]"))
          (is (str/includes? c3 "## Documents\n- [[01-a.md]]\n- [[02-b.md]]"))
          (is (str/includes? c3 "TAGS:[\"t1\" \"t2\"]"))
          (is (str/includes? c3 ":obsidize_version \"1.2.3\"")))

        ;; printed message includes version
        (is (some #(str/includes? % "(v1.2.3)") @msgs))))))

(deftest process-project-default-version
  (testing "defaults version to DEV when :app-version missing"
    (let [msgs (atom [])]
      (with-redefs [println (fn [& xs] (swap! msgs conj (str/join " " xs)))
                    obsidize.utils/ensure-directory
                    (fn [p] (proxy [java.io.File] [p] (getPath [] p)))
                    obsidize.utils/sort-by-timestamp (fn [docs timestamp-key] (sort-by timestamp-key docs))
                    obsidize.utils/sanitize-filename identity
                    obsidize.utils/create-tags-section (constantly nil)
                    obsidize.utils/create-frontmatter-with-timestamps merge
                    obsidize.utils/write-note-if-changed (fn [& _] nil)
                    obsidize.templates/format-project-document-filename (fn [i f] (str i "-" f))
                    obsidize.templates/format-frontmatter (fn [_] "")
                    obsidize.templates/project-document-frontmatter {}
                    obsidize.templates/project-overview-frontmatter {}
                    obsidize.templates/format-project-overview-filename (fn [b] (str b ".md"))
                    obsidize.templates/format-project-documents-section pr-str
                    obsidize.templates/format-project-links-section pr-str
                    obsidize.templates/format-project-content (fn [& _] "")]
        (sut/process-project
         {:uuid "P" :name "X" :docs []}
         {:output-dir "OUT" :tags [] :links []}) ;; no app-version
        (is (some #(str/includes? % "(vDEV)") @msgs))))))

(deftest process-project-nil-docs
  (testing "nil docs only writes overview"
    (let [writes (atom 0)]
      (with-redefs [obsidize.utils/ensure-directory
                    (fn [p] (proxy [java.io.File] [p] (getPath [] p)))
                    obsidize.utils/sort-by-timestamp (fn [docs timestamp-key] (sort-by timestamp-key docs))
                    obsidize.utils/sanitize-filename identity
                    obsidize.utils/create-tags-section (constantly nil)
                    obsidize.utils/create-frontmatter-with-timestamps merge
                    obsidize.utils/write-note-if-changed (fn [& _] (swap! writes inc))
                    obsidize.templates/format-project-document-filename (fn [i f] (str i "-" f))
                    obsidize.templates/format-frontmatter (fn [_] "")
                    obsidize.templates/project-document-frontmatter {}
                    obsidize.templates/project-overview-frontmatter {}
                    obsidize.templates/format-project-overview-filename (fn [b] (str b ".md"))
                    obsidize.templates/format-project-documents-section pr-str
                    obsidize.templates/format-project-links-section pr-str
                    obsidize.templates/format-project-content (fn [& _] "")]
        (sut/process-project
         {:uuid "P" :name "OnlyOverview" :docs nil}
         {:output-dir "OUT" :tags nil :links nil :app-version "2.0.0"})
        ;; Only overview write
        (is (= 1 @writes))))))
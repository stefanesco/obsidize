(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]))

(def lib 'stefanesco/obsidize)
(def version (if-let [tag (System/getenv "RELEASE_VERSION")]
               (if (str/starts-with? tag "v") (subs tag 1) tag)
               (format "0.1.%s" (b/git-count-revs nil))))
(def class-dir "target/classes")
(def release-dir (format "target/release/%s" version))
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "%s/%s-standalone.jar" release-dir (name lib)))
(def resources-dir "resources")
(def version-file-path (str resources-dir "/obsidize/version.edn"))

(defn write-version-file [_]
  (println (str "Writing version " version " to " version-file-path))
  (.mkdirs (io/file resources-dir "obsidize"))
  (spit version-file-path (pr-str {:version version})))

(defn clean [_]
  (println "🧹 Cleaning build artifacts...")
  (b/delete {:path "target"})
  (b/delete {:path version-file-path}))

(defn uber [_]
  (println "📦 Creating uberjar...")
  (clean nil)
  (.mkdirs (io/file release-dir))
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src"] 
                  :class-dir class-dir 
                  :ns-compile '[obsidize.core]}) 
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'obsidize.core})
  (println (str "✅ Uberjar created: " uber-file)))

(defn native-image [_]
  (println "🚀 Building native image... (This may take a while)")
  ;; For native image, we need a basis with the :native alias to get graal-build-time on the classpath
  (let [native-basis (b/create-basis {:project "deps.edn"
                                      :aliases [:native]})]
    (println "📦 Creating uberjar for native image...")
    (clean nil)
    (.mkdirs (io/file release-dir))
    (write-version-file nil)
    (b/copy-dir {:src-dirs ["src" "resources"]
                 :target-dir class-dir})
    (b/compile-clj {:basis native-basis
                    :src-dirs ["src"]
                    :class-dir class-dir
                    :ns-compile '[obsidize.core]})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis native-basis
             :main 'obsidize.core}))
  (println "Starting GraalVM native-image build...")
  (b/process {:command-args ["native-image"
                             "-jar" uber-file
                             "--no-fallback"
                             "-o" (format "%s/%s" release-dir (name lib))
                             "-march=native" ; Optimize for build machine's CPU
                             "--features=clj_easy.graal_build_time.InitClojureClasses" ; Modern flag for features
                             "--initialize-at-build-time=com.fasterxml.jackson.core"]})) ; Initialize entire Jackson core package
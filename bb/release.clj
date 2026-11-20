(ns release
  "Babashka helper to cut a release under the tag-is-truth versioning scheme.

  The script updates README.md and pom.xml to the new version and verifies
  CHANGELOG.md already mentions it (the changelog is curated by hand). The
  full release then commits the changes, creates an annotated tag `v<version>`
  at HEAD, and pushing the tag triggers CI to build and deploy that version."
  (:require
   [clojure.string :as str]))

(def ^:private version-re #"\d+\.\d+\.\d+")

;; --- File bumping ----------------------------------------------------------

(defn- bump-readme! [version]
  (let [path    "README.md"
        content (slurp path)]
    (spit path (str/replace content
                            #"\{:mvn/version \"[^\"]+\"\}"
                            (str "{:mvn/version \"" version "\"}")))))

(defn- bump-pom! [version]
  (let [path    "pom.xml"
        content (slurp path)
        content (str/replace content
                             #"(?s)(<artifactId>postgres-extras-clj</artifactId>\s*<version>)[^<]+(</version>)"
                             (str "$1" version "$2"))
        content (str/replace content
                             #"(?s)(<tag>)[^<]+(</tag>)"
                             (str "$1v" version "$2"))]
    (spit path content)))

(defn- check-changelog! [version]
  (let [path    "CHANGELOG.md"
        content (slurp path)
        entry   (str "# " version)]
    (when-not (str/includes? content entry)
      (throw (ex-info (str "CHANGELOG.md does not mention " entry
                           ". Add a `## " entry " - YYYY-MM-DD` section manually.")
                      {})))))

(defn- bump-files!
  "Bump README.md and pom.xml to `version`, and verify CHANGELOG.md already
  mentions it. No git operations."
  [version]
  (bump-readme! version)
  (bump-pom! version)
  (check-changelog! version)
  (println "• Updated README.md and pom.xml; CHANGELOG.md already mentions " version))

(defn bump!
  "Bump README.md and pom.xml to `<version>` (and verify CHANGELOG.md already
  mentions it) without committing, tagging, or pushing.
  Usage: bb release:bump <major.minor.patch>"
  [& args]
  (let [versions (filterv #(re-matches version-re %) args)]
    (when-not (= 1 (count versions))
      (throw (ex-info "Usage: bb release:bump <major.minor.patch>" {})))
    (let [version (first versions)]
      (println (str "• Bumping version files to " version))
      (bump-files! version))))

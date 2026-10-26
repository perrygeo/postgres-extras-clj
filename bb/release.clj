(ns release
  "Babashka helper to cut a release under the tag-is-truth versioning scheme.

  Usage:
    bb release:bump 0.2.22        # bump version files only (no commit/tag/push)
    bb release 0.2.22             # bump files, commit, tag v0.2.22, push
    bb release 0.2.22 --no-push   # same, but skip the push

  The script updates README.md and pom.xml to the new version and verifies
  CHANGELOG.md already mentions it (the changelog is curated by hand). The
  full release then commits the changes, creates an annotated tag `v<version>`
  at HEAD, and pushing the tag triggers CI to build and deploy that version."
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(def ^:private tag-re #"v\d+\.\d+\.\d+")
(def ^:private version-re #"\d+\.\d+\.\d+")

;; --- Low-level helpers -----------------------------------------------------

(defn- run
  "Run a command, capturing trimmed stdout/stderr. Never throws on non-zero exit."
  [& argv]
  (let [{:keys [out err] :as res}
        @(p/process (vec argv) {:out :string :err :string})]
    (-> res
        (assoc :out (some-> out str/trim)
               :err (some-> err str/trim)))))

(defn- git!
  "Run git, returning trimmed stdout. Throws on non-zero exit."
  [& args]
  (let [{:keys [exit out err]} (apply run "git" args)]
    (if (zero? exit)
      out
      (throw (ex-info (str "git " (str/join " " args) " failed: " err) {})))))

(defn- shell!
  "Run a command, streaming output to the terminal. Throws on non-zero exit."
  [& args]
  (apply p/shell {:out :inherit :err :inherit} args))

;; --- Version helpers -------------------------------------------------------

(defn- parse-version [v]
  (mapv #(Long/parseLong %) (str/split v #"\.")))

(defn- version-gt? [a b]
  (pos? (compare (parse-version a) (parse-version b))))

(defn- latest-tag-version
  "Highest `v<major>.<minor>.<patch>` tag, stripped of its `v`, or nil."
  []
  (let [tag (first (filter #(re-matches tag-re %)
                           (str/split-lines (git! "tag" "--sort=-v:refname"))))]
    (some-> tag (str/replace #"^v" ""))))

;; --- File bumping ----------------------------------------------------------

(defn- bump-readme! [version]
  (let [path "README.md"
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
        entry   (str "[" version "]")]
    (when-not (str/includes? content entry)
      (throw (ex-info (str "CHANGELOG.md does not mention " entry
                           ". Add a `## " entry " - YYYY-MM-DD` section manually.")
                      {})))))

;; --- Validation ------------------------------------------------------------

(defn- validate!
  "Validate the release preconditions and return the current branch."
  [version]
  (let [latest (latest-tag-version)]
    (when (and latest (not (version-gt? version latest)))
      (throw (ex-info (str "Version " version " must be greater than the latest tag v" latest) {}))))
  (let [existing (git! "tag" "--list" (str "v" version))]
    (when (seq existing)
      (throw (ex-info (str "Tag v" version " already exists") {}))))
  (let [dirty (git! "status" "--porcelain")]
    (when (seq dirty)
      (throw (ex-info "Working tree is dirty. Commit or stash changes first." {}))))
  (let [branch (git! "rev-parse" "--abbrev-ref" "HEAD")]
    (when-not (= "main" branch)
      (throw (ex-info (str "Releases must be cut from main (current branch: " branch ")") {})))
    branch))

;; --- File bumping (no git) -------------------------------------------------

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

;; --- Entry point -----------------------------------------------------------

(defn cut!
  "Cut a release. Usage: bb release <major.minor.patch> [--no-push]"
  [& args]
  (let [versions (filterv #(re-matches version-re %) args)
        push?    (not (contains? (set args) "--no-push"))]
    (when-not (= 1 (count versions))
      (throw (ex-info "Usage: bb release <major.minor.patch> [--no-push]" {})))
    (let [version (first versions)
          branch  (validate! version)
          tag     (str "v" version)
          msg     (str "Release " tag)]
      (println (str "• Cutting release " version " (tag " tag ")"))
      (bump-files! version)
      (shell! "git" "add" "README.md" "pom.xml")
      (shell! "git" "commit" "-m" msg)
      (shell! "git" "tag" "-a" tag "-m" msg)
      (when-not (str/includes? (git! "tag" "--points-at" "HEAD") tag)
        (throw (ex-info (str "Tag " tag " was not created at HEAD") {})))
      (if push?
        (do (shell! "git" "push" "origin" branch tag)
            (println (str "• Pushed " branch " and " tag " — CI will build and deploy " version)))
        (println (str "• Committed and tagged " tag " (push skipped — run `git push origin " branch " " tag "`)"))))))

(ns tortilla.main
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [cemerick.pomegranate :refer [add-dependencies]]
            [cemerick.pomegranate.aether :refer [maven-central]]
            [expound.alpha :as expound]
            [fipp.clojure :as fipp]
            [orchestra.spec.test :as st]
            [tortilla.wrap :as w :refer [defwrapper]]
            [tortilla.spec])
  (:gen-class))

(defprotocol Coercer
  (coerce [val typ]))

(extend-protocol Coercer
  Object
  (coerce [val _] val)

  Number
  (coerce [val typ]
    (condp = typ
      Integer (int val)
      Float   (float val)
      val)))

(defn parse-coords
  [coord-str]
  (if (re-matches #"\[.*\]" coord-str)
    (edn/read-string coord-str)
    (let [parts (str/split coord-str #":")]
      (vector (symbol (str/join "/" (butlast parts)))
              (last parts)))))

(def cli-options
  [["-c" "--class CLASS"
    :desc "Class to generate a wrapper. May be specified multiple times."
    :default []
    :assoc-fn (fn [m k v] (update m k conj v))]

   ["-m" "--members"
    :desc "Print list of class members instead of wrapper code"]

   ["-i" "--include REGEX"
    :desc "Only wrap members that match REGEX. Match members in format name(arg1.type,arg2.type):return.type"
    :parse-fn re-pattern]

   ["-x" "--exclude REGEX"
    :desc "Exclude members that match REGEX from wrapping"
    :parse-fn re-pattern]

   [nil "--[no-]metadata"
    :desc "Include metadata in output."
    :default true]

   [nil "--[no-]instrument"
    :desc "Instrument specs."
    :default true]

   [nil "--[no-]coerce"
    :desc "Include coercion function."
    :default true]

   ["-w" "--width CHARS"
    :desc "Limit output width."
    :default 100
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be positive"]]

   ["-d" "--dep COORD"
    :desc (str "Add jars to classpath. May be specified multiple times. "
               "COORD may be in leiningen format ('[group/artifact \"version\"]') "
               "or maven format (group:artifact:version). "
               "In both cases the group part is optional, and defaults to the artifact ID.")
    :parse-fn parse-coords
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]

   ["-h" "--help"]])

(defn message
  [summary & [error]]
  (->> [(when error "Error:")
        error
        (when error "")
        "Usage: tortilla [options]"
        ""
        "Options:"
        summary]
       (remove nil?)
       (str/join \newline)))

(defn validate-args
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit 0
       :message (message summary)}

      errors
      {:exit 1
       :message (message summary (str/join \newline errors))}

      (seq arguments)
      {:exit 1
       :message (message summary "Options must start with a hyphen")}

      (not (seq (:class options)))
      {:exit 1
       :message (message summary "Must supply at least one class to wrap")}

      :else
      options)))

(defn ensure-compiler-loader
  "Ensures the clojure.lang.Compiler/LOADER var is bound to a DynamicClassLoader,
  so that we can add to Clojure's classpath dynamically."
  []
  (when-not (bound? Compiler/LOADER)
    (.bindRoot Compiler/LOADER (clojure.lang.DynamicClassLoader. (clojure.lang.RT/baseLoader)))))

(defn member-str
  [member]
  (str (:name member)
       \(
       (str/join \, (map #(w/class-name %)
                         (take (cond-> (w/parameter-count member)
                                 (w/member-varargs? member) inc)
                               (w/parameter-types member))))
       (when (w/member-varargs? member) "...")
       \)
       \:
       (w/class-name (:return-type member))))

(def ^:dynamic *filter-in* nil)
(def ^:dynamic *filter-out* nil)

(defn filter-fn
  [member]
  (let [mstr (member-str member)]
    ;; Normally using dynamic vars wouldn't work here,
    ;; as this function would be called at compile time.
    ;; It only works here because we explicitly call macroexpand-1 at run time
    (and (or (nil? *filter-in*)
             (re-find *filter-in* mstr))
         (or (nil? *filter-out*)
             (not (re-find *filter-out* mstr))))))

(defn exit
  [code message]
  (when message (println message))
  (System/exit code))

(defn -main
  [& args]
  (let [options (validate-args args)]
    (when-let [code (:exit options)]
      (exit code (:message options)))
    (when (:instrument options)
      (st/instrument))
    (when-let [dep (:dep options)]
      (println "Adding dependencies to classpath: " dep)
      (ensure-compiler-loader)
      (add-dependencies :coordinates dep
                        :repositories (merge maven-central
                                             {"clojars" "https://clojars.org/repo"})
                        :classloader @clojure.lang.Compiler/LOADER))

    (let [options (update options :class
                          (partial mapv #(or (try (Class/forName %) (catch ClassNotFoundException _))
                                             (exit 1 (str "Invalid class: " %)))))]
      (doseq [cls (:class options)]
        (println "\n;; ====" cls "====")
        (binding [*filter-in* (:include options)
                  *filter-out* (:exclude options)
                  s/*explain-out* (expound/custom-printer {:show-valid-values? true})]
          (if (:members options)
            (doseq [member (w/class-members cls {:filter-fn filter-fn})]
              (println (member-str member)))
            (fipp/pprint (if (:coerce options)
                           (macroexpand-1 `(defwrapper ~cls {:coerce coerce
                                                             :filter-fn filter-fn}))
                           (macroexpand-1 `(defwrapper ~cls {:coerce nil
                                                             :filter-fn filter-fn})))
                         {:print-meta (:metadata options)
                          :width (:width options)})))))))

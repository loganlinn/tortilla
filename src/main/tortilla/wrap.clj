(ns tortilla.wrap
  (:require [clojure.string :as str])
  (:import [java.lang.reflect Executable Constructor Method Modifier]))

(set! *warn-on-reflection* true)

(defn class-methods [^Class klazz]
  (seq (.getMethods klazz)))

(defn class-constructors [^Class klazz]
  (seq (.getConstructors klazz)))

(defn member-varargs? [^Executable member]
  (.isVarArgs member))

(defn member-static? [^Executable member]
  (Modifier/isStatic (.getModifiers member)))

(defn member-class ^Class [^Executable member]
  (.getDeclaringClass member))

(defn vararg-type [^Executable member]
  (when (member-varargs? member)
    (.getComponentType ^Class (last (.getParameterTypes member)))))

(defn class-name [^Class klazz]
  (.getName klazz))

(defprotocol MemberInfo
  (member-name* [_])
  (member-invocation* [_])
  ;; Minimum number of parameters a member accepts. It could take more if it has varargs
  (parameter-count* [_])
  ;; Possibly infinite (if member has varargs) list of parameter types accepted by member
  (parameter-types* [_])
  (return-type* [_]))

;; Wrap protocol members in functions so we get spec instrumentation
(defn member-name [m]
  (member-name* m))

(defn member-invocation [m]
  (member-invocation* m))

(defn parameter-count [m]
  (parameter-count* m))

(defn parameter-types [m]
  (parameter-types* m))

(defn return-type [m]
  (return-type* m))

(extend-protocol MemberInfo
  Method
  (member-name* [^Method m]
    (.getName m))
  (member-invocation* [^Method m]
    (if (member-static? m)
      (symbol (-> m member-class class-name) (member-name m))
      (symbol (str "." (member-name m)))))
  (parameter-count* [^Method m]
    (cond-> (.getParameterCount m)
      (not (member-static? m)) inc
      (member-varargs? m)      dec))
  (parameter-types* [^Method m]
    (concat (when-not (member-static? m)
              [(member-class m)])
            (cond-> (.getParameterTypes m)
              (member-varargs? m) butlast)
            (when (member-varargs? m)
              (repeat (vararg-type m)))))
  (return-type* [^Method m]
    (.getReturnType m))

  Constructor
  (member-name* [^Constructor c]
    (-> c member-class .getSimpleName))
  (member-invocation* [^Constructor c]
    (-> c member-class class-name (str ".") symbol))
  (parameter-count* [^Constructor c]
    (cond-> (.getParameterCount c)
      (member-varargs? c) dec))
  (parameter-types* [^Constructor c]
    (concat (cond-> (.getParameterTypes c)
              (member-varargs? c) butlast)
            (when (member-varargs? c)
              (repeat (vararg-type c)))))
  (return-type* [^Constructor c]
    (.getDeclaringClass c)))

(defn camel->kebab
  [string]
  (-> string
      (str/replace #"(.)([A-Z][a-z]+)" "$1-$2")
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/lower-case)))

(defn array-class [klz]
  (class (into-array klz [])))

(defn ensure-boxed [t]
  (let [sym (symbol (class-name t))]
    (get '{byte java.lang.Byte
           short java.lang.Short
           int java.lang.Integer
           long java.lang.Long
           float java.lang.Float
           double java.lang.Double
           char java.lang.Character
           boolean java.lang.Boolean
           void java.lang.Object}
         sym sym)))

(defn ensure-boxed-long-double
  "Allow long and double, box everything else."
  [t]
  (let [sym (symbol (class-name t))]
    (get '{byte java.lang.Byte
           short java.lang.Short
           int java.lang.Integer
           float java.lang.Float
           char java.lang.Character
           boolean java.lang.Boolean
           void java.lang.Object}
         sym sym)))

(defn tagged [value tag]
  (vary-meta value assoc :tag (ensure-boxed-long-double tag)))

(defn tagged-local [value tag]
  (let [tag (ensure-boxed-long-double tag)]
    (cond
      (= 'long tag)
      `(long ~value)

      (= 'double tag)
      `(double ~value)

      :else
      (vary-meta value assoc :tag tag))))

;; Generate form for one arity of a member
(defn ^:no-gen arity-wrapper-form [arity uniadics variadics {:keys [coerce]}]
  (let [arg-vec (mapv #(gensym (str "p" % "_")) (range arity))
        members (concat uniadics variadics)
        ret     (if (apply = (map return-type members))
                  (return-type (first members))
                  java.lang.Object)]
    `(~(tagged `[~@arg-vec] ret)
      ~(if (and (zero? arity)
                (= 1 (count members)))
         `(~(member-invocation (first members)))
         `(cond
            ~@(mapcat
               (fn [member]
                 `[(and ~@(map (fn [sym ^Class klz]
                                 `(instance? ~(ensure-boxed klz) ;; TODO: or nil
                                             ~(if coerce
                                                `(~coerce ~sym ~(ensure-boxed klz))
                                                sym)))
                               arg-vec
                               (parameter-types member)))
                   (~(member-invocation member)
                    ~@(map (fn [sym ^Class klz]
                             (tagged-local (if coerce `(~coerce ~sym ~(ensure-boxed klz)) sym) klz))
                           arg-vec
                           (parameter-types member)))])
               uniadics)
            ~@(mapcat
               (fn [member]
                 `[(and ~@(map (fn [sym ^Class klz]
                                 `(instance? ~(ensure-boxed klz)
                                             ~(if coerce
                                                `(~coerce ~sym ~(ensure-boxed klz))
                                                sym)))
                               arg-vec
                               (parameter-types member)))
                   (~(member-invocation member)
                    ~@(map (fn [sym ^Class klz]
                             (tagged-local (if coerce `(~coerce ~sym ~(ensure-boxed klz)) sym) klz))
                           (take (parameter-count member) arg-vec)
                           (parameter-types member))
                    ~(tagged-local `(into-array ~(vararg-type member)
                                                ~(mapv (fn [sym]
                                                         (if coerce
                                                           `(~coerce ~sym ~(ensure-boxed (vararg-type member)))
                                                           sym))
                                                       (drop (parameter-count member) arg-vec)))

                                   (array-class (vararg-type member))))])
               variadics)
            :else (throw (IllegalArgumentException.
                          ^String
                          (str ~(str "Unrecognised types for " (-> members first member-class class-name)
                                     \. (-> members first member-name) ": ")
                               ~@(mapcat (fn [p#] [`(.getName ^Class (type ~p#)) ", "]) (butlast arg-vec))
                               (.getName ^Class (type ~(last arg-vec)))))))))))

;; Generate form for the highest/variadic arity of a member
(defn ^:no-gen variadic-wrapper-form [min-arity members {:keys [coerce]}]
  (let [more-arg (gensym "more_")
        arg-vec (into (mapv #(gensym (str "p" % "_")) (range min-arity))
                      ['& more-arg])
        ret (if (apply = (map return-type members))
              (return-type (first members))
              java.lang.Object)]
    `(~(tagged `[~@arg-vec] ret)
      (cond
        ~@(mapcat
           (fn [member]
             `[(and ~@(map (fn [sym ^Class klz]
                             `(instance? ~(ensure-boxed klz)
                                         ~(if coerce
                                            `(~coerce ~sym ~(ensure-boxed klz))
                                            sym)))
                           (take min-arity arg-vec)
                           (parameter-types member))
                    (every? (partial instance? ~(ensure-boxed (vararg-type member)))
                            ~(if coerce
                               `(map #(~coerce % ~(ensure-boxed (vararg-type member)))
                                     ~more-arg)
                               more-arg)))
               (~(member-invocation member)
                ~@(map (fn [sym ^Class klz]
                         (tagged-local (if coerce
                                         `(~coerce ~sym ~(ensure-boxed klz))
                                         sym)
                                       klz))
                       (take (parameter-count member) arg-vec)
                       (parameter-types member))
                ~(tagged-local `(into-array ~(vararg-type member)
                                            (into ~(mapv (fn [sym]
                                                           (if coerce
                                                             `(~coerce ~sym ~(ensure-boxed (vararg-type member)))
                                                             sym))
                                                         (subvec arg-vec
                                                                 (parameter-count member)
                                                                 min-arity))
                                                  ~(if coerce
                                                     `(map #(~coerce % ~(ensure-boxed (vararg-type member)))
                                                          ~more-arg)
                                                    more-arg)))
                               (array-class (vararg-type member))))])
           members)
        :else (throw (IllegalArgumentException.
                      ^String
                      (str ~(str "Unrecognised types for " (-> members first member-class class-name)
                                 \. (-> members first member-name) ": ")
                           ~@(mapcat (fn [p#] [`(.getName ^Class (type ~p#)) ", "]) (take min-arity arg-vec))
                           (str/join ", " (map (fn [p#] (.getName ^Class (type p#))) ~more-arg)))))))))

;; Generate defn form for all arities of a named member
(defn member-wrapper-form [fname members opts]
  (let [arities (group-by parameter-count members)]
    `(defn ~fname
       {:arglists '~(map (fn [member]
                           (cond-> (vec (take (parameter-count member)
                                              (parameter-types member)))
                             (member-varargs? member) (conj '& [(vararg-type member)])))
                         members)}
       ~@(loop [[[arity membs] & more] (sort arities)
                variadics []
                results []
                last-arity -1]
           (if (nil? arity) ;; no more members, generate variadic form if necessary
             (if (seq variadics)
               (conj results (variadic-wrapper-form last-arity variadics opts))
               results)
             (if (and (seq variadics) (> arity (inc last-arity)))
               (recur [[arity membs] more]
                      variadics
                      (conj results (arity-wrapper-form (inc last-arity) [] variadics opts))
                      (inc last-arity))
               (let [{vararg true fixarg false} (group-by member-varargs? membs)
                     variadics (into variadics vararg)]
                 (recur more
                        variadics
                        (conj results (arity-wrapper-form arity fixarg variadics opts))
                        (long arity)))))))))

(defn compile-time-fn
  [fun]
  (let [fun (if (symbol? fun)
              (resolve fun)
              fun)
        fun (if (var? fun)
              (if (bound? fun)
                @fun
                (throw (IllegalArgumentException.
                        "compile-time-fn: var must be bound at macro-expansion time")))
              fun)]
    (cond
      (nil? fun) (constantly true)
      (fn? fun)  fun
      :else      (throw (IllegalArgumentException.
                         ^String
                         (str "compile-time-fn: expecting a (compile-time) function or nil, got: " fun))))))

(defn class-members
  [klazz {:keys [filter-fn]}]
  (->> klazz
       resolve
       ((juxt class-constructors class-methods))
       (apply concat)
       (remove (set (class-methods Object)))
       (filter (compile-time-fn filter-fn))))

(defmacro defwrapper [klazz {:keys [prefix coerce] :as opts}]
  (let [members (group-by member-name
                          (class-members klazz opts))]
    `(do
       ~@(for [[mname membs] members
               :let [fname (symbol (str prefix (camel->kebab mname)))]]
           (member-wrapper-form fname membs (assoc opts :coerce (if (symbol? coerce)
                                                                  (resolve coerce)
                                                                  coerce)))))))

(defn- defwrapperfn
  "Wrap macro in a function so it gets picked up by the automatic spec test.check generation"
  [cls]
  (macroexpand-1 `(defwrapper ~cls {})))

(def just-for-testing
  ;; prevent linter complaining about unused private var
  [defwrapperfn])

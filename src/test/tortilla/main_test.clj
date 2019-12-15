(ns tortilla.main-test
  (:require [clojure.test :refer [deftest is testing]]
            [tortilla.main :as m]))

(deftest coercer-test
  (is (instance? Integer (m/coerce 42   Integer)))
  (is (instance? Float   (m/coerce 3.14 Float)))
  (is (instance? Long    (m/coerce 99   String)))
  (is (instance? String  (m/coerce "99" Integer))))

(deftest parse-coords-test
  (is (= '[foo/bar "1.2.3"]
         (m/parse-coords "[foo/bar \"1.2.3\"]")
         (m/parse-coords "foo:bar:1.2.3")))
  (is (= '[baz "2.3.4"]
         (m/parse-coords "[baz \"2.3.4\"]")
         (m/parse-coords "baz:2.3.4"))))

(deftest validate-args-test
  (testing "Help option exits with 0"
    (is (= 0 (:exit (m/validate-args ["-h"])))))
  (testing "No options causes an error"
    (is (= 1 (:exit (m/validate-args [])))))
  (testing "Invalid option causes an error"
    (is (= 1 (:exit (m/validate-args ["--invalid-option"])))))
  (testing "Non-option argument causes an error"
    (is (= 1 (:exit (m/validate-args ["invalid"])))))
  (testing "Valid options don't cause an error"
    (is (= {:class ['Number 'String]
            :metadata true
            :instrument true
            :coerce true
            :width 80
            :dep '[[foo "1.0"]
                   [bar/baz "2.0"]]}
           (m/validate-args ["-c" "Number" "-c" "String" "-w" "80"
                             "-d" "foo:1.0" "-d" "[bar/baz \"2.0\"]"])))))

(deftest main-test
  (let [coerce-check
        #"(?m)\(clojure.core/instance[?] java.lang.Number \(#'tortilla.main/coerce p[0-9_]+ java.lang.Number\)\)"
        non-coerce-check
        #"(?m)\(clojure.core/instance[?] java.lang.Number p[0-9_]+\)"]
    (testing "With coercer"
      (let [stdout (with-out-str (m/-main "-w" "200" "--no-metadata"
                                          "-c" "Object" "-c" "java.lang.Number"))]
        (is (re-find #"(?m)^;; =+ Object =+$" stdout))
        (is (re-find #"(?m)^;; =+ java.lang.Number =+$" stdout))
        (is (re-find #"(?m)^ \(clojure.core/defn" stdout))
        (is (re-find #"(?m)\bint-value\b" stdout))
        (is (re-find coerce-check stdout))
        (is (not (re-find non-coerce-check stdout)))))
    (testing "Without coercer"
      (let [stdout (with-out-str (m/-main "-w" "200" "--no-metadata" "--no-coerce"
                                          "-c" "Object" "-c" "java.lang.Number"))]
        (is (re-find #"(?m)^;; =+ Object =+$" stdout))
        (is (re-find #"(?m)^;; =+ java.lang.Number =+$" stdout))
        (is (re-find #"(?m)^ \(clojure.core/defn" stdout))
        (is (re-find #"(?m)\bint-value\b" stdout))
        (is (not (re-find coerce-check stdout)))
        (is (re-find non-coerce-check stdout))))
    (testing "Dynamically adding to classpath"
      (let [stdout (with-out-str (m/-main "-d" "org.jblas:jblas:1.2.4"
                                          "-c" "org.jblas.ComplexDoubleMatrix"))]
        (is (re-find #"(?m)^;; =+ org.jblas.ComplexDoubleMatrix =+$" stdout))))))

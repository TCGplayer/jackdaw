(ns kafka.test.fs-test
  (:require
   [kafka.test.fs :as fs]
   [clojure.java.io :as io]
   [clojure.test :refer :all]))

;; It is suprisingly non-straightforward to delete a directory in Java/Clojure. There
;; are ways to do it using the new nio file api but that is only supported in Java 7+
;; so clojure core is reluctant to built it into clojure.java.io

(deftest try-delete!-test
  (let [root-dir (fs/tmp-dir "root")
        depth-1-dir (fs/tmp-dir "root" "depth-1")
        depth-2-dir (fs/tmp-dir "root" "depth-1" "depth-2")]

    (io/make-parents (str depth-2-dir "/" "foo.txt"))

    (spit (str root-dir "/" "foo.txt") "yolo")
    (spit (str depth-1-dir "/" "foo.txt") "yolo")
    (spit (str depth-2-dir "/" "foo.txt") "yolo")

    (fs/try-delete! root-dir)

    (is (not (fs/tmp-dir-exists? (fs/tmp-dir "root" "depth-1" "depth-2"))))
    (is (not (fs/tmp-dir-exists? (fs/tmp-dir "root" "depth-1"))))
    (is (not (fs/tmp-dir-exists? (fs/tmp-dir "root"))))))
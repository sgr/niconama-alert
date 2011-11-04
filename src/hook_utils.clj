;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Utility macro for adding hook features easily."}
    hook-utils
  (:import (clojure.lang Keyword)))

(defmacro defhook
  "EXAMPLE:
   (defhook :countdown :fetching :fetched)
   -> (let [hook-countdown (atom '())
            hook-fetching (atom '())
            hook-fetched (atom '())]
        (defn add-hook [^Keyword kind f]
          (swap! (condp = kind
                   :countdown hook-countdown
                   :fetching hook-fetching
                   :fetched hook-fetched) conj f))
        (defn- run-hooks [^Keyword kind & args]
          (doseq [f (deref (condp = kind
                             :countdown hook-countdown
                             :fetching hook-fetching
                             :fetched hook-fetched))]
            (apply f args))))"
  [hook & hooks]
  (let [hks (conj hooks hook)]
    (doseq [a hks] (when-not (= Keyword (type a)) (throw (IllegalArgumentException.))))
    (let [h# (reduce #(assoc %1 %2 (symbol (str "hooks-" (name %2)))) {} hks)]
      `(let [~@(mapcat #(list (first %) (second %))
		       (for [n (vals h#)] (list (symbol n) '(atom '()))))]
	 (defn ~'add-hook [^Keyword kind# f#]
	   (swap! (condp = kind#
		      ~@(mapcat #(list (first %) (second %)) h#)) conj f#))
	 (defn- ~'run-hooks [^Keyword kind# & args#]
	   (doseq [f# (deref (condp = kind#
				 ~@(mapcat #(list (first %) (second %)) h#)))]
	     (apply f# args#)))))))


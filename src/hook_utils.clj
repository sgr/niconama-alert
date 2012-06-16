;; -*- coding: utf-8-unix -*-
(ns #^{:author "sgr"
       :doc "Utility macro for adding hook features easily."}
    hook-utils
  (:import [clojure.lang Keyword]))

(defmacro defhook
  "EXAMPLE:
   (defhook name :countdown :fetching :fetched)
   -> (let [hook-countdown (atom '())
            hook-fetching (atom '())
            hook-fetched (atom '())]
        (defn add-name-hook [^Keyword kind f]
          (swap! (condp = kind
                   :countdown hook-countdown
                   :fetching hook-fetching
                   :fetched hook-fetched) conj f))
        (defn- run-name-hooks [^Keyword kind & args]
          (doseq [f (deref (condp = kind
                             :countdown hook-countdown
                             :fetching hook-fetching
                             :fetched hook-fetched))]
            (apply f args))))"
  [name hook & hooks]
  (let [hks (conj hooks hook)]
    (doseq [a hks] (when-not (= Keyword (type a)) (throw (IllegalArgumentException.))))
    (let [h (reduce #(assoc %1 %2 (symbol (str "hook-" (clojure.core/name %2)))) {} hks)
          hook-vars (mapcat #(list (first %) (second %))
                            (for [n (vals h)] (list (symbol n) '(atom '()))))
          conds-hooks (mapcat #(list (first %) (second %)) h)
          add-func-name (symbol (str "add-" name "-hook"))
          run-func-name (symbol (str "run-" name "-hooks"))]
      `(let [~@hook-vars]
	 (defn ~add-func-name [^Keyword kind# f#]
	   (swap! (condp = kind# ~@conds-hooks) conj f#))
	 (defn- ~run-func-name [^Keyword kind# & args#]
	   (doseq [f# (deref (condp = kind# ~@conds-hooks))]
	     (apply f# args#)))))))


(ns obsidize.error
  "Pure error handling utilities for functional result composition.")

(defn success
  "Create a successful result with data."
  [data]
  {:success? true :data data :errors []})

(defn failure
  "Create a failure result with errors."
  [errors]
  {:success? false
   :data nil
   :errors (if (string? errors) [errors] errors)})

(defn combine-results
  "Combine multiple results, succeeding only if all succeed."
  [results]
  (let [errors (mapcat :errors results)
        all-data (map :data results)]
    (if (empty? errors)
      (success all-data)
      (failure errors))))

(defn map-success
  "Transform the data in a successful result, preserving failures."
  [result f]
  (if (:success? result)
    (success (f (:data result)))
    result))

(defn bind
  "Monadic bind for chaining operations that can fail."
  [result f]
  (if (:success? result)
    (f (:data result))
    result))
(ns b-plus-tree.core
  "Primary functions for interacting with the B+ Tree."
  (:require [b-plus-tree io nodes util]
            [b-plus-tree.util :refer [dbg verbose]]))


(defn next-node
  "Returns the next node when searching the tree for key in node."
  ([key node raf]
     (loop [key-ptrs (dbg (b-plus-tree.nodes/key-ptrs node))]
       (when-let [[k ptr] (dbg (first key-ptrs))]
         (println (map type [key k]))
         (if (neg? (dbg (.compareTo key k)))
           (b-plus-tree.io/read-node raf ptr)
           (if-let [key-ptrs (next key-ptrs)]
             (recur key-ptrs)
             (b-plus-tree.io/read-node raf (last (:children node)))))))))

(defn next-ptr
  "Returns the next pointer when searching the tree for key in node."
  ([key node raf]
     (loop [key-ptrs (dbg (b-plus-tree.nodes/key-ptrs node))]
       (when-let [[k ptr] (dbg (first key-ptrs))]
         (println (map type [key k]))
         (if (neg? (dbg (.compareTo key k)))
           ptr
           (if-let [key-ptrs (next key-ptrs)]
             (recur key-ptrs)
             (-> node :children last)))))))

(defn find-leaf
  "Recursively finds the leaf node associated with key by traversing node's
  subtree. If the given node is itself a leaf node, returns the node if it
  contains key, else returns nil."
  ([key node raf]
     (if (contains? b-plus-tree.nodes/leaf-types (:type node))
       (when (dbg (b-plus-tree.util/in? (:keys node) key))
         node)
       (recur key (next-node key node raf) raf))))

(defn find-record
  "Finds the record in leaf's children which goes to key, or nil if not found."
  ([key leaf raf]
     {:pre [leaf
            (b-plus-tree.util/in? b-plus-tree.nodes/leaf-types (:type leaf))]}
     (->> leaf
          b-plus-tree.nodes/key-ptrs
          (map (fn get-record [[k ptr]]
                 (when (= k key)
                   (b-plus-tree.io/read-node raf ptr))))
          (filter identity)
          first)))

(defn find-type
  "Returns the next node of the given type while searching the tree for key."
  ([key type node raf]
     (case (:type node)
       :leaf (find-record key node raf)
       :record nil
       (when-let [nxt (next-node key node raf)]
         (if (= type (:type nxt))
           nxt
           (recur key type nxt raf))))))

(defn find
  "Returns the value associated with key by traversing the entire tree, or
  nil if not found."
  ([key raf]
     (let [root (b-plus-tree.io/read-root raf)]
       (when-let [record (find-type key :record root raf)]
         (:data record)))))

(defn insert
  "Inserts key-value pair into the B+ Tree."
  ([key val order raf]
     (let [; find the leaf to insert into, while building a stack of
           ; parent pointers
           [leaf stack]
           (loop [node-ptr 0
                  stack    []]
             (let [node  (b-plus-tree.io/read-node node-ptr raf)
                   stack (conj stack node-ptr)]
               (if (b-plus-tree.util/in? b-plus-tree.nodes/leaf-types node)
                 ; found leaf
                 node
                 ; keep searching
                 (recur (next-ptr key node raf) stack))))]
       (when-not (find-record key leaf raf)
         ; record doesn't exist already, so we can insert
         (comment (do-insertion))))))

(defn traverse
  "Returns a lazy sequence of the key value pairs contained in the B+ Tree,
  assuming that leaf contains start, the key from which traversal begins.
  The sequence ends before stop is reached, or if no stop is given ends when
  there are no more leaves left.

  Not working."
  ([leaf start raf]
     (let [next-fn
           (fn next-fn [leaf start raf found?]
             (let [next-ptr (:nextleaf leaf)]
               (if found?
                 (lazy-cat
                  (->> leaf
                       b-plus-tree.nodes/key-ptrs
                       (map (fn [[k ptr]]
                              [k (:data (b-plus-tree.io/read-node raf ptr))])))
                  (when (not= next-ptr -1)
                    (let [next-leaf (b-plus-tree.io/read-node raf next-ptr)]
                      (lazy-seq (next-fn next-leaf start raf true)))))
                 (when-let [pairs (->> leaf
                                   b-plus-tree.nodes/key-ptrs
                                   (filter #(-> % (.compareTo start) neg? not))
                                   #(when-not found? (contains? % start)))]
                   (lazy-cat
                    (map (fn [[k ptr]]
                           [k (:data (b-plus-tree.io/read-node raf ptr))])
                         pairs)
                    (when (not= next-ptr -1)
                      (let [next-leaf (b-plus-tree.io/read-node raf next-ptr)]
                        (next-fn next-leaf start raf true))))))))]
       (lazy-seq (next-fn leaf start raf false))))
  ([leaf start stop raf]
     (take-while (fn [[k v]] (-> k (.compareTo stop) neg?))
                 (traverse leaf start raf))))

(defn find-slice
  ""
  ([start raf]
     (when-let [leaf (find-type start :leaf (b-plus-tree.io/read-root raf) raf)]
       (println "leaf:" leaf)
       (traverse start leaf raf)))
  ([start stop raf]
     (when-let [leaf (find-type start :leaf (b-plus-tree.io/read-root raf) raf)]
       (println "leaf:" leaf)
       (traverse start stop leaf raf))))

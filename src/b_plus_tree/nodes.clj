(ns b-plus-tree.nodes
  "Defines encodings for the different types of nodes used in the B+ Tree."
  (:require [gloss core]
            [b-plus-tree.util :refer [dbg verbose]]))

(gloss.core/defcodec- node-types
  (gloss.core/enum :byte
                   :root-leaf :root-nonleaf :internal :leaf :record))

(gloss.core/defcodec- raf-offset
  :int64)

(gloss.core/defcodec- C-string
  (gloss.core/string :ascii :delimiters ["\0"]))

(gloss.core/defcodec- key-list
  (gloss.core/repeated C-string))

(gloss.core/defcodec- child-list
  (gloss.core/repeated raf-offset))

(defn- node-map
  "Turns a node's :keys and :ptrs into a map :key-ptrs, removing the original
fields."
  ([{:keys [keys ptrs] :as node}]
     (-> node
         (assoc :key-ptrs (into (sorted-map) (zipmap keys ptrs)))
         (dissoc :keys :ptrs))))

(defn- node-unmap
  "Turns a node's :key-ptrs into :keys and :ptrs, removing the original field."
  ([{:keys [key-ptrs] :as node}]
     (-> node
         (assoc :keys (keys key-ptrs)
                :ptrs (vals key-ptrs))
         (dissoc :key-ptrs))))

(gloss.core/defcodec header-node
  (gloss.core/ordered-map
   :count     :int32
   :free      raf-offset
   :order     :int16
   :key-size  :int32
   :val-size  :int32
   :page-size :int32
   :root      raf-offset))

(def root-leaf-node
  (gloss.core/compile-frame
   (gloss.core/ordered-map
    :type :root-leaf
    :keys key-list
    :ptrs child-list)
   node-unmap
   node-map))

(def root-nonleaf-node
  (gloss.core/compile-frame
   (gloss.core/ordered-map
    :type :root-nonleaf
    :keys key-list
    :ptrs child-list
    :last raf-offset)
   node-unmap
   node-map))

(def internal-node
  (gloss.core/compile-frame
   (gloss.core/ordered-map
    :type :internal
    :keys key-list
    :ptrs child-list
    :last raf-offset)
   node-unmap
   node-map))

(def leaf-node
  (gloss.core/compile-frame
   (gloss.core/ordered-map
    :type :leaf
    :keys key-list
    :ptrs child-list
    :prev raf-offset
    :next raf-offset)
   node-unmap
   node-map))

(gloss.core/defcodec record-node
  (gloss.core/ordered-map
   :type :record
   :data C-string))

(def node
  (gloss.core/compile-frame
   (gloss.core/header node-types
                      {:root-leaf    root-leaf-node
                       :root-nonleaf root-nonleaf-node
                       :internal     internal-node
                       :leaf         leaf-node
                       :record       record-node}
                      :type)))

(defn new-root
  "Returns a vector containing a new leaf root, and a recor"
  ([key val free page-size]
     (let [root-ptr   free
           record-ptr (+ free page-size)]
       [{:type     :root-leaf
         :key-ptrs (sorted-map key record-ptr)
         :offset   root-ptr
         :altered? true},
        {:type     :record
         :data     val
         :offset   record-ptr
         :altered? true}])))

(def leaf-types
  #{:root-leaf :leaf})

(defn leaf?
  "Returns true if node is a leaf-type, else nil."
  ([node] (b-plus-tree.util/in? leaf-types (:type node))))

(defn key-ptrs
  "Given a node, returns key-ptr pairs, where each ptr points to the node
  which is less-than key, or in the case of leaf-nodes, contains key's value"
  ([node] (map list (:keys node) (:children node))))

(defn leaf-assoc
  "Given a leaf node, returns that node with key and ptr inserted at the
  correct position in :keys and :children."
  {:arglists '([key ptr leaf])}
  ([key ptr {:keys [key-ptrs] :as leaf}]
     (assoc leaf
       :key-ptrs (assoc key-ptrs key ptr)
       :altered? true)))

(defn count-children
  "Returns the number of children node has"
  ([node]
     (let [N (count (:key-ptrs node))]
       (if (:last node)
         (inc N)
         N))))

(defn min-children
  "Returns the minimum number of children a given node is allowed to have."
  ([node order]
     (case (:type node)
       :root-leaf    1
       :root-nonleaf 2
       :internal     (-> order inc (quot 2))
       :leaf         (-> order (quot 2))
       nil)))

(defn max-children
  "Returns the maximum number of children a given node is allowed to have."
  ([node order]
     (case (:type node)
       :root-leaf    order
       :root-nonleaf order
       :internal     order
       :leaf         (dec order)
       nil)))

(defn full?
  "Returns true if the node is full."
  ([node order]
     (>= (count-children node)
         (max-children node order))))

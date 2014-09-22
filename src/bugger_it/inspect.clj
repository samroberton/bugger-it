(ns bugger-it.inspect
  "Functions to inspect state on a debuggee and replicate that state in the
   local VM."
  (:require [bugger-it.core :as bugger])
  (:import
   [com.sun.jdi ArrayReference BooleanValue ByteValue CharValue DoubleValue
    FloatValue IntegerValue LongValue ObjectReference PrimitiveValue ShortValue
    StringReference Value]))

(defn- get-remote-field
  [object-ref field-name]
  (let [field (.fieldByName (.referenceType object-ref) field-name)]
    (.getValue object-ref field)))

(defn- remote-type
  "Dispatch function for remote-value; returns the class if the type is on the
   classpath (so we can do type-hierarchy-based dispatch), or otherwise the
   class's fully-qualified name, for cases when the remote class isn't available
   locally."
  [^Value value]
  (when value
    (condp instance? value
      PrimitiveValue :primitive
      ArrayReference :array
      StringReference :string
      ObjectReference (let [type-name (-> value .referenceType .name)]
                        (try
                          (Class/forName type-name)
                          (catch ClassNotFoundException e type-name))))))

(defmulti remote-value remote-type)

(defmethod remote-value :default [v] v)
(defmethod remote-value nil [v] nil)
(defmethod remote-value :primitive [v] (.value v))
(defmethod remote-value :string [^StringReference v] (.value v))

(defmethod remote-value :array
  [^ArrayReference v]
  (map remote-value (.getValues v)))

(defmethod remote-value java.lang.Boolean
  [v]
  (.value ^BooleanValue (get-remote-field v "value")))

(defmethod remote-value java.lang.Byte
  [v]
  (.value ^ByteValue (get-remote-field v "value")))

(defmethod remote-value java.lang.Character
  [v]
  (.value ^CharValue (get-remote-field v "value")))

(defmethod remote-value java.lang.Double
  [v]
  (.value ^DoubleValue (get-remote-field v "value")))

(defmethod remote-value java.lang.Float
  [v]
  (.value ^FloatValue (get-remote-field v "value")))

(defmethod remote-value java.lang.Integer
  [v]
  (.value ^IntegerValue (get-remote-field v "value")))

(defmethod remote-value java.lang.Long
  [v]
  (.value ^LongValue (get-remote-field v "value")))

(defmethod remote-value java.lang.Short
  [v]
  (.value ^ShortValue (get-remote-field v "value")))

(defmethod remote-value clojure.lang.Symbol
  [v]
  (let [ns   (if-let [ns-val (get-remote-field v "ns")]
               (.value ^StringReference ns-val))
        name (if-let [name-val (get-remote-field v "name")]
               (.value ^StringReference name-val))]
    (symbol ns name)))

(defmethod remote-value clojure.lang.Keyword
  [v]
  (keyword (remote-value (get-remote-field v "sym"))))

(defmethod remote-value clojure.lang.PersistentArrayMap
  [v]
  (let [array (.getValues ^ArrayReference (get-remote-field v "array"))]
    (into {} (map (fn [[k v]] [(remote-value k) (remote-value v)])
                  (partition 2 array)))))

(defmethod remote-value clojure.lang.PersistentHashMap
  [v]
  (let [m (remote-value (get-remote-field v "root"))]
    (if (remote-value (get-remote-field v "hasNull"))
      (assoc m nil (get-remote-field v "nullValue"))
      m)))

(defmethod remote-value clojure.lang.PersistentHashMap$INode
  [v]
  "PersistentHashMap has three inner classes: ArrayNode, BitmapIndexedNode, and
   HashCollisionNode -- they all have an 'array' field which is where the map
   contents actually go, they just differ in how they chose where to put each
   pair. Thankfully, we don't care where they put them."
  (let [array (.getValues ^ArrayReference (get-remote-field v "array"))
        pairs (partition 2 array)
        keyvals (into {} (map (fn [[k v]] [(remote-value k) (remote-value v)])
                              (filter (fn [[k _]] (not (nil? k))) pairs)))
        subnodes (map (fn [[_ v]] (remote-value v))
                      (filter (fn [[k v]] (and (nil? k) v)) pairs))]
    (apply merge keyvals subnodes)))

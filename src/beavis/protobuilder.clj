(ns beavis.protobuilder
  (:require [schema.core :as s]
            [clj-time.core :as t]
            [clojure.tools.logging :as log]
            [clj-time.coerce :as c]
            [clj-time.format :as f])
  (:import (com.google.protobuf GeneratedMessage$Builder WireFormat$JavaType Descriptors$FieldDescriptor ByteString GeneratedMessage ProtocolMessageEnum Descriptors$Descriptor Descriptors$EnumDescriptor)
           (java.nio ByteBuffer)
           (io.netty.buffer ByteBuf)
           (clojure.lang Reflector)
           (co.opsee.proto Any Timestamp BastionProto)
           (org.joda.time DateTime)))

(defn- byte-string [buf]
  (cond
    (instance? ByteBuffer buf) (ByteString/copyFrom ^ByteBuffer buf)
    (instance? ByteBuf buf) (ByteString/copyFrom (.nioBuffer buf))
    true (ByteString/copyFrom (bytes buf))))

(defn- enum-type [^Descriptors$FieldDescriptor field v]
  (let [enum (.getEnumType field)]
    (cond
      (integer? v) (.findValueByNumber enum v)
      (string? v) (.findValueByName enum v)
      (symbol? v) (.findValueByName enum (name v)))))

(defmacro case-enum
  "Like `case`, but explicitly dispatch on Java enum ordinals."
  [e & clauses]
  (letfn [(enum-ordinal [e] `(let [^Enum e# ~e] (.ordinal e#)))]
    `(case ~(enum-ordinal e)
       ~@(concat
           (mapcat (fn [[test result]]
                     [(eval (enum-ordinal test)) result])
                   (partition 2 clauses))
           (when (odd? (count clauses))
             (list (last clauses)))))))

(declare hash->proto)
(declare proto->hash)

(defmulti ^GeneratedMessage$Builder into-builder class)
(defmethod into-builder Class [^Class c] (Reflector/invokeStaticMethod c "newBuilder" (to-array nil)))
(defmethod into-builder GeneratedMessage$Builder [b] b)

(defn hash->anyhash
  "Searches co.opsee.proto.* for the type element of the hash, brings back its builder, builds it and delivers
  it marshalled into the value element of the hash"
  [hash]
  (let [clazz (Class/forName (str "co.opsee.proto." (:type_url hash)))
        proto (hash->proto clazz (:value hash))]
    {:type_url (:type_url hash) :value (.toByteArray proto)}))

(defn tim->adder [tim]
  (case tim "d" t/days
            "h" t/hours
            "m" t/minutes
            "s" t/seconds
            "u" t/millis))

(defmulti parse-deadline class)
(defmethod parse-deadline String [value]
  (if-let [[_ dec tim] (re-matches #"([0-9]+)([smhdu])" value)]
    (let [adder (tim->adder tim)
          date (t/plus (t/now) (adder (Integer/parseInt dec)))]
      {:seconds (c/to-epoch date) :nanos 0})
    {:seconds (-> :date-time-no-ms
                  f/formatters
                  (f/parse value)
                  c/to-epoch)
     :nanos 0}))
(defmethod parse-deadline :default [value]
  value)

(defn value-converter [v builder field]
  (case-enum (.getJavaType field)
             WireFormat$JavaType/BOOLEAN (boolean v)
             WireFormat$JavaType/BYTE_STRING (byte-string v)
             WireFormat$JavaType/DOUBLE (double v)
             WireFormat$JavaType/ENUM (enum-type field v)
             WireFormat$JavaType/FLOAT (float v)
             WireFormat$JavaType/INT (int v)
             WireFormat$JavaType/LONG (long v)
             WireFormat$JavaType/STRING (str v)
             WireFormat$JavaType/MESSAGE (case (.getName (.getMessageType field))
                                           "Any" (hash->proto (.newBuilderForField builder field) (hash->anyhash v))
                                           "Timestamp" (hash->proto (.newBuilderForField builder field) (parse-deadline v))
                                           (hash->proto (.newBuilderForField builder field) v))))

(defn- enum-keyword [^ProtocolMessageEnum enum]
  (let [enum-type (.getValueDescriptor enum)]
    (keyword (.getName enum-type))))

(defn hash->proto [proto msg]
  (let [builder (into-builder proto)
        descriptor (.getDescriptorForType builder)]
    (doseq [[k v] msg
            :let [name (name k)]]
      (when-let [field (.findFieldByName descriptor name)]
        (if (.isRepeated field)
          (doseq [va (flatten [v])]
            (.addRepeatedField builder field (value-converter va builder field)))
          (.setField builder field (value-converter v builder field)))))
    (.build builder)))

(def format-map (atom {}))
(defn set-format [type format]
  (swap! format-map assoc type format))

(defn- format-timestamp [^Timestamp t]
  (case (get @format-map "Timestamp")
    "int64" (.getSeconds t)
    (f/unparse (f/formatters :date-time-no-ms) (DateTime. (* 1000 (.getSeconds t))))))

(defn- any->hash [^Any any]
  (let [type (.getTypeUrl any)
        clazz (Class/forName (str "co.opsee.proto." type))
        proto (Reflector/invokeStaticMethod clazz "parseFrom" (to-array [(.getValue any)]))]
    {:type_url type
     :value (proto->hash proto)}))


(defn- unpack-value [^Descriptors$FieldDescriptor field value]
  (case-enum (.getJavaType field)
             WireFormat$JavaType/BOOLEAN value
             WireFormat$JavaType/BYTE_STRING value
             WireFormat$JavaType/DOUBLE value
             WireFormat$JavaType/ENUM (enum-keyword value)
             WireFormat$JavaType/FLOAT value
             WireFormat$JavaType/INT value
             WireFormat$JavaType/LONG value
             WireFormat$JavaType/STRING value
             WireFormat$JavaType/MESSAGE (case (.getName (.getMessageType field))
                                           "Any" (any->hash value)
                                           "Timestamp" (format-timestamp value)
                                           (proto->hash value))))

(defn- unpack-repeated-or-single [^Descriptors$FieldDescriptor field value]
  (if (.isRepeated field)
    (mapv (partial unpack-value field) value)
    (unpack-value field value)))

(defn proto->hash [^GeneratedMessage proto]
  (into {}
        (map (fn [[^Descriptors$FieldDescriptor desc value]]
               [(keyword (.getName desc)) (unpack-repeated-or-single desc value)]))
        (.getAllFields proto)))


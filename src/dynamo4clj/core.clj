(ns dynamo4clj.core
  (:use [clojure.algo.generic.functor :only (fmap)]
        [clojure.walk :only (stringify-keys keywordize-keys)])
  (:import [com.amazonaws.auth AWSCredentials PropertiesCredentials]
           [com.amazonaws.services.dynamodb AmazonDynamoDBClient]
           [com.amazonaws.services.dynamodb.model AttributeValue AttributeValueUpdate AttributeAction PutItemRequest QueryRequest Key GetItemRequest DeleteItemRequest ScanRequest UpdateItemRequest ReturnValue Condition ComparisonOperator]
           [com.amazonaws AmazonServiceException ClientConfiguration Protocol]
           [java.util HashMap]))

(defn get-client []
  (let [creds (PropertiesCredentials. (.getResourceAsStream (clojure.lang.RT/baseLoader) "aws.properties"))
        config (ClientConfiguration.)]
    (. config (setProtocol Protocol/HTTP))
    (. config (setMaxErrorRetry 3))
    (AmazonDynamoDBClient. creds config)))

(def client (get-client))

(defn- to-attr-value [value]
  "Convert a value into an AttributeValue object."
  (cond
   (string? value) (doto (AttributeValue.) (.setS value))
   (number? value) (doto (AttributeValue.) (.setN (str value))))) ;;TODO handle sets

(defn- to-attr-value-update [value]
  "Convert a value into an AttributeValueUpdate object. Value is a tuple like [1 \"add\"]"
  (cond
   (= (get value 1) "add") (doto (AttributeValueUpdate.) (.withValue (to-attr-value (get value 0))) (.withAction AttributeAction/ADD))))

(defn- get-value [attr-value]
  "Get the value of an AttributeValue object."
  (or (.getS attr-value)
      (.getN attr-value)
      (.getNS attr-value)
      (.getSS attr-value)))

(defn- to-map [item]
  "Turn a item in DynamoDB into a Clojure map."
  (if item
    (fmap get-value (into {} item))))

(defn get-item [table hash-key]
  "Retrieve an item from a table by its hash key."
  (to-map
   (.getItem
    (. client (getItem (doto (GetItemRequest.) (.withTableName table)
                             (.withKey (Key. (to-attr-value hash-key)))))))))

(defn delete-item [table hash-key]
  "Delete an item from a table by its hash key."  
  (. client (deleteItem (DeleteItemRequest. table (item-key hash-key)))))


(defn insert-item [table item]
  "Insert item (map) in table"
  (let [req (doto (PutItemRequest.) (.withTableName table) (.withItem (fmap to-attr-value (stringify-keys item))))]      
    (. client (putItem req))))


(defn update-item [table key attr]
  "Update item (map) in table with optional attributes"
  (let [key (doto (Key.) (.withHashKeyElement (to-attr-value key)))
        attrupd (fmap to-attr-value-update (stringify-keys attr))
        req (doto (UpdateItemRequest.) (.withTableName table) (.withKey key) (.withReturnValues ReturnValue/ALL_NEW) (.withAttributeUpdates attrupd))] 
    (keywordize-keys (to-map (.getAttributes (. client (updateItem req)))))))

(defn create-condition [c]
  (let [[operator param1 param2] c]
    (cond
     (= operator "between") (doto (Condition.) (.withComparisonOperator ComparisonOperator/BETWEEN) (.withAttributeValueList (vector (to-attr-value param1) (to-attr-value param2))))
     (= operator "begins-with") (doto (Condition.) (.withComparisonOperator ComparisonOperator/BEGINS_WITH) (.withAttributeValueList (vector (to-attr-value param1))))
     (= operator "contains") (doto (Condition.) (.withComparisonOperator ComparisonOperator/CONTAINS) (.withAttributeValueList (vector (to-attr-value param1))))
     (= operator "eq") (doto (Condition.) (.withComparisonOperator ComparisonOperator/EQ) (.withAttributeValueList (vector (to-attr-value param1))))
     (= operator "ge") (doto (Condition.) (.withComparisonOperator ComparisonOperator/GE) (.withAttributeValueList (vector (to-attr-value param1))))
     (= operator "gt") (doto (Condition.) (.withComparisonOperator ComparisonOperator/GT) (.withAttributeValueList (vector (to-attr-value param1))))
     (= operator "le") (doto (Condition.) (.withComparisonOperator ComparisonOperator/LE) (.withAttributeValueList (vector (to-attr-value param1))))
     (= operator "lt") (doto (Condition.) (.withComparisonOperator ComparisonOperator/LT) (.withAttributeValueList (vector (to-attr-value param1))))
     (= operator "ne") (doto (Condition.) (.withComparisonOperator ComparisonOperator/NE) (.withAttributeValueList (vector (to-attr-value param1))))
     (= operator "not-contains") (doto (Condition.) (.withComparisonOperator ComparisonOperator/NOT_CONTAINS) (.withAttributeValueList (vector (to-attr-value param1))))
     (= operator "not-null") (doto (Condition.) (.withComparisonOperator ComparisonOperator/NOT_NULL) (.withAttributeValueList (vector (to-attr-value param1))))
     (= operator "null") (doto (Condition.) (.withComparisonOperator ComparisonOperator/NULL) (.withAttributeValueList (vector (to-attr-value param1))))
     (= operator "in") (doto (Condition.) (.withComparisonOperator ComparisonOperator/IN) (.withAttributeValueList (vector (to-attr-value param1)))))))

(defn find-items [table key consistent & range]
  "Find items with key and optional range. Range has the form [operator param1 param2] or [operator param1]"
  (let [condition (create-condition (first range))       
        req (cond
             (empty? range) (doto (QueryRequest.) (.withTableName table) (.withHashKeyValue (to-attr-value key)) (.withConsistentRead consistent))
             (not (empty? range)) (doto (QueryRequest.) (.withTableName table) (.withHashKeyValue (to-attr-value key)) (.withRangeKeyCondition condition) (.withConsistentRead consistent)))]
    (map to-map (.getItems (. client (query req))))))

(defn scan [table & conditions]
  "Return the items in a DynamoDB table. Conditions is vector of tuples like [field operator param1 param2] or [field operator param1]"
  (let [conds (loop [c (first conditions) res {}]
                (if (empty? c)
                  res
                  (recur (rest c) (assoc res (first (first c)) (create-condition (vec (rest (first c))))))))]
    (let [req (cond
                (empty? conds) (doto (ScanRequest.) (.withTableName table))
                (not (empty? conds)) (doto (ScanRequest.) (.withTableName table) (.withScanFilter conds)))]
      (map to-map (.getItems (. client (scan req)))))))
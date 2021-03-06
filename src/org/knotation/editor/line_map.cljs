(ns org.knotation.editor.line-map
  (:require [clojure.set :as set]

            [org.knotation.state :as st]

            [org.knotation.editor.util :as util]))

(def editors (atom []))

(defn assign-ix! [ed]
  (when  (not (number? (.-ix (.-knotation ed))))
    (set! (.-ix (.-knotation ed)) (count @editors))
    (swap! editors conj ed))
  nil)

(defn ed->ix [ed] (.-ix (.-knotation ed)))
(defn ix->ed [ix] (get @editors ix))

(defn line-map! [] (atom {}))
(defn clear! [atm] (reset! atm {}))

(defn partition-graphs
  [processed]
  (let [a (volatile! 0)]
    (partition-by
     (fn [elem]
       (let [res @a]
         (when (= ::st/graph-end (::st/event elem))
           (vswap! a inc))
         res))
     processed)))

(defn -update-map
  [line-map in-k out-k in out in-ct out-ct]
  (reduce
   (fn [m in]
     (reduce
      (fn [m out]
        (update-in
         (update-in m [in-k in] #(conj (or % #{}) [out-k out]))
         [out-k out] #(conj (or % #{}) [in-k in])))
      m (map dec (range out (+ out out-ct)))))
   line-map (map dec (range in (+ in in-ct)))))

(defn compiled->line-map
  ([line-map compiled input-editors out-key]
   (reduce
    (fn [memo [ed blocks]]
      (reduce
       (fn [m elem]
         (let [i (::st/input elem) o (::st/output elem)
               in (::st/line-number i)
               out (::st/line-number o)]
           (if (and (or in (zero? in)) (or out (zero? out)))
             (-update-map m ed out-key in out (count (::st/lines i)) (count (::st/lines o)))
             m)))
       memo blocks))
    line-map (util/zip input-editors (partition-graphs compiled)))))

(defn update-line-map!
  [atm compiled input-editors output-editor]
  (reset! atm (compiled->line-map @atm compiled (map ed->ix input-editors) (ed->ix output-editor))))

(defn lookup
  [line-map editor line-ix]
  (map
   (fn [[ed-ix ln-ix]] [(ix->ed ed-ix) ln-ix])
   (get-in line-map [(ed->ix editor) line-ix] #{})))

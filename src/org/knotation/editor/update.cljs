(ns org.knotation.editor.update
  (:require [clojure.string :as string]
            [crate.core :as crate]

            [org.knotation.api :as api]
            [org.knotation.state :as st]

            [org.knotation.editor.util :as util]
            [org.knotation.editor.line-map :as ln]
            [org.knotation.editor.highlight :as high]))

(defn compiled->content
  [compiled]
  (->> compiled
       (mapcat #(->> % ::st/output ::st/lines))
       (filter identity)
       (string/join "\n")))

(defn clear-line-errors!
  [eds]
  (high/clear-line-highlights! eds ["line-error"])
  (doseq [ed eds]
    (.forEach (.querySelectorAll (.getWrapperElement ed) ".line-error-message")
              #(.remove %))
    (doseq [i (util/line-range ed)]
      (.setGutterMarker ed i "line-errors" nil)))
  nil)

(defn mark-line-errors!
  [compiled editors]
  (let [cur-ed (atom 0)]
    (doseq [elem compiled]
      (case (::st/event elem)
        ::st/graph-end
        (swap! cur-ed inc)

        ::st/error
        (let [ed (get editors @cur-ed)
              in (::st/input elem)
              ln-num (dec (::st/line-number in))]
          (high/highlight-line! ed ln-num "line-error")
          (.addWidget
           ed (clj->js {:line ln-num :ch 0})
           (crate/html
            [:pre {:class (str "line-error-message hidden line-" ln-num)}
             (->> elem ::st/error ::st/error-message)]))
          (.setGutterMarker ed ln-num "line-errors" (crate/html [:div {:style "color: #822"} "▶"])))

        nil))))

(defn compile-content-to
  [line-map-atom & {:keys [env input format output] :or {format :ttl}}]
  (let [inputs (conj env input)
        processed (api/run-operations
                   (conj
                    (vec (map #(api/env :kn (.getValue %)) env))
                    (api/input :kn (.getValue input))
                    (api/output format)))
        result (compiled->content processed)]
    (ln/update-line-map! line-map-atom processed inputs output)
    (clear-line-errors! (conj env input))
    (mark-line-errors! processed (conj env input))
    (.setValue output result)
    (doseq [[ed graph] (util/zip inputs (ln/partition-graphs processed))]
      (set! (.-graph (.-knotation ed)) graph)
      (.signal js/CodeMirror ed "compiled-from" output result))
    (.signal js/CodeMirror output "compiled-to" output result)
    processed))

(defn cross->update!
  [line-map-atom & {:keys [env input   output format]}]
  (compile-content-to line-map-atom :env env :input input :output output :format format)
  (doseq [e (conj env input)]
    (.on e "changes"
         (util/debounce
          (fn [cs]
            (ln/clear! line-map-atom)
            (compile-content-to line-map-atom :env env :input input :output output :format format))
          500))))

(defn cross->>update!
  [line-map-atom & {:keys [env input   ttl nq rdfa]}]
  (let [out! (fn []
               (doseq [[out format] [[ttl :ttl] [nq :nq] [rdfa :rdfa]]]
                 (when out (compile-content-to line-map-atom :env env :input input :output out :format format))))]
    (out!)
    (doseq [in (conj env input)]
      (.on in "changes"
           (util/debounce
            (fn [cs]
              (ln/clear! line-map-atom)
              (out!))
            500)))))

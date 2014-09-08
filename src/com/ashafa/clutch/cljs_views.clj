(ns com.ashafa.clutch.cljs-views
  (:require
    [com.ashafa.clutch :as clutch]
    [cljs.closure :as closure]))

(defn- expand-anon-fn
  "Compiles a single anonymous function body in a dummy namespace, with a gensym'ed
   name. Separate from the general case because cljs doesn't include cljs.core and
   the goog stuff if a namespace is not specified, and advanced gClosure
   optimization drops top-level anonymous fns."
  [fnbody]
  (when-not (and (seq fnbody)
                 ('#{fn fn*} (first fnbody)))
    (throw (IllegalArgumentException. "Simple ClojureScript views must be an anonymous fn, e.g. (fn [doc] …) or #(...)")))
  (let [namespace (gensym)
        name (with-meta (gensym) {:export true})]
    [{:main (symbol (str namespace) (str name))}
     [(list 'ns namespace)
      (list 'def name fnbody)]]))

(defn- view*
  [options body]
  (let [[options' body] (if (and (list? body) ('#{fn fn*} (first body)))
                          (expand-anon-fn body)
                          [nil (vec body)])
        options (merge {:optimizations :advanced :pretty-print false}
                       options'
                       options)]
    (when-not (:main options)
      (throw (IllegalArgumentException. "Must specify a fully-qualified entry point fn via :main option")))
    (str (closure/build body options)
         "return " (-> options :main namespace) \. (-> options :main name))))

(defn- closure
  "Wraps the provided string of code in a closure.  This isn't strictly needed right now
   (i.e. circa couchdb ~1.0.2), but couchdb 1.2 will begin to require that view/filter/validation
   code be defined in a single _expression_.  This is necessary to ensure that we're producing a
   single expression, given Google Closure's penchant for lifting
   closures to the top level of advanced-optimized code (even if the code provided to it is entirely
   contained within a closure itself, making lifting somewhat pointless AFAICT)."
  [code]
  (str "(function () {"
       code
       "})()"))

(def view
  "Compiles a body of ClojureScript into a single Javascript expression, suitable for use in a
   CouchDB view.  First argument may be a map of options; remaining arguments must be either
   (a) a single anonymous function, or (b) a series of top-level ClojureScript forms, starting
   with an `ns` declaration.  If (b), the map of options must include a `:main` entry to identify
   the \"entry point\" for the CouchDB view/filter/validator/etc.

   Contrived examples:

   (view nil '(fn [doc]
                (js/emit (aget doc \"_id\") nil)))

   (view {:main 'some-view/main}
     '(ns some-view)
     '(defn date-components [date]
        (-> (re-seq #\"(\\d{4})-(\\d{2})-(\\d{2})\" date)
          first
          rest))
     '(defn main [doc]
        (js/emit (apply array (-> doc (aget \"date\") date-components)) nil)))

   If using clutch, you should never have to touch this function.  It is registered with clutch
   as a view-transformer; just use the view-server-fns macro, indicating a view server language of
   :cljs.

   You can also include ClojureScript/Google Closure compiler options in the options map, e.g.
   :optimizations, :pretty-print, etc.  These options default to :advanced compilation, no
   pretty-printing."
  (comp closure view*))

(defmethod clutch/view-transformer :cljs
  [_]
  {:language :javascript
   :compiler (fn [options]
               (partial view options))})

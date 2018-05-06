# Clutch  [![Travis CI status](https://secure.travis-ci.org/clojure-clutch/clutch.png)](http://travis-ci.org/#!/clojure-clutch/clutch/builds)

Clutch is a [Clojure](http://clojure.org) library for [Apache CouchDB](http://couchdb.apache.org/).

## "Installation"

To include Clutch in your project, simply add the following to your `project.clj` dependencies:

```clojure
[com.ashafa/clutch "0.4.0"]
```

Or, if you're using Maven, add this dependency to your `pom.xml`:

```
<dependency>
    <groupId>com.ashafa</groupId>
    <artifactId>clutch</artifactId>
    <version>0.4.0</version>
</dependency>
```

Clutch is compatible with Clojure 1.2.0+, and requires Java 1.5+.

## Status

Although it's in an early stage of development (Clutch API subject to change), Clutch supports most of the Apache CouchDB API:

* Essentially all of the [core document API](http://wiki.apache.org/couchdb/HTTP_Document_API)
* [Bulk document APIs](http://wiki.apache.org/couchdb/HTTP_Bulk_Document_API)
* Most of the [Database API](http://wiki.apache.org/couchdb/HTTP_database_API), including `_changes` and a way to easily monitor/react to `_changes` events using Clojure's familiar watch mechanism
* [Views](http://wiki.apache.org/couchdb/HTTP_view_API), including access, update, and a Clojure view server implementation

Read the [documentation](doc/index.md) to learn the basics of Clutch. You can also look at the source or introspect the docs once you've loaded Clutch in your REPL.  

Clutch does not currently provide any direct support for the various couchapp-related APIs, including update handlers and validation, shows and lists, and so on.

That said, it is very easy to call whatever CouchDB API feature that Clutch doesn't support using the lower-level `com.ashafa.clutch.http-client/couchdb-request` function.

## Usage

First, a basic REPL interaction:

```clojure
=> (get-database "clutch_example")  ;; creates database if it's not available yet
#cemerick.url.URL{:protocol "http", :username nil, :password nil, :host "localhost", :port -1,
:path "clutch_example", :query nil, :disk_format_version 5, :db_name "clutch_example", :doc_del_count 0,
:committed_update_seq 0, :disk_size 79, :update_seq 0, :purge_seq 0, :compact_running false,
:instance_start_time "1323701753566374", :doc_count 0}

=> (bulk-update "clutch_example" [{:test-grade 10 :_id "foo"}
                                  {:test-grade 20}
                                  {:test-grade 30}])
[{:id "foo", :rev "1-8a15da0db077cd05b45ec93b3a207d09"}
 {:id "0896fbf57128d7f1a1b238a52b0ec372", :rev "1-796ebf042b42fa3585332c3aa4a6f706"}
 {:id "0896fbf57128d7f1a1b238a52b0ecda8", :rev "1-01f063c5aeb1b63992c90c72c7a515ed"}]
=> (get-document "clutch_example" "foo")
{:_id "foo", :_rev "1-8a15da0db077cd05b45ec93b3a207d09", :test-grade 10}
```

All Clutch functions accept a first argument indicating the database
endpoint for that operation.  This argument can be:

* a `cemerick.url.URL` record instance (from the
  [url](http://github.com/cemerick/url) library)
* a string URL
* the name of the database to target on `http://localhost:5984`

You can `assoc` in whatever you like to a `URL` record, which is handy for keeping database URLs and
credentials separate:

```clojure
=> (def db (assoc (cemerick.url/url "https://XXX.cloudant.com/" "databasename")
                    :username "username"
                    :password "password"))
#'test-clutch/db
=> (put-document db {:a 5 :b [0 6]})
{:_id "17e55bcc31e33dd30c3313cc2e6e5bb4", :_rev "1-a3517724e42612f9fbd350091a96593c", :a 5, :b [0 6]}
```

Of course, you can use a string containing inline credentials as well:

```clojure
=> (put-document "https://username:password@XXX.cloudant.com/databasename/" {:a 5 :b 6})
{:_id "36b807aacf227f921aa256b06ab094e5", :_rev "1-d4d04a5b59bcd73893a84de2d9595c4c", :a 5, :b 6}
```

Finally, you can optionally provide configuration using dynamic scope via `with-db`:

```clojure
=> (with-db "clutch_example"
     (put-document {:_id "a" :a 5})
     (put-document {:_id "b" :b 6})
     (-> (get-document "a")
       (merge (get-document "b"))
       (dissoc-meta)))
{:b 6, :a 5}
```

### Experimental: a Clojure-idiomatic CouchDB type

Clutch provides a pretty comprehensive API, but 95% of database
interactions require using something other than the typical Clojure vocabulary of
`assoc`, `conj`, `dissoc`, `get`, `seq`, `reduce`, etc, even though those semantics are entirely appropriate
(modulo the whole stateful database thing).

This is (the start of) an attempt to create a type to provide most of the
functionality of Clutch with a more pleasant, concise API (it uses the Clutch API
under the covers, and rare operations will generally remain accessible only
at that lower level).

Would like to eventually add:

* support for views (aside from `_all_docs` via `seq`)
* support for `_changes` (via a `seque`?), maybe a more natural place
  than the (free-for-all) pool of watches in Clutch's current API
* support for bulk update, maybe via `IReduce`?
* Other CouchDB types: ** to provide specialized query interfaces e.g.
  cloudant indexes ** to return custom map and vector types to support
  e.g.

```clojure
(assoc-in! db ["ID" :key :key array-index] x)
(update-in! db ["ID" :key :key array-index] assoc :key y)
```

Feedback wanted on the mailing list: http://groups.google.com/group/clojure-clutch

This part of the API is subject to change at any time, so no detailed examples.  For now, just a REPL interaction will do:

```clojure
=> (use 'com.ashafa.clutch)     ;; My apologies for the bare `use`!
nil
=> (def db (couch "test"))
#'user/db
=> (create! db)
#<CouchDB user.CouchDB@3f460a4a>
=> (:result (meta *1))
#com.ashafa.clutch.utils.URL{:protocol "http", :username nil, :password nil,
:host "localhost", :port -1, :path "test", :query nil, :disk_format_version 5,
:db_name "test", :doc_del_count 0, :committed_update_seq 0, :disk_size 79,
:update_seq 0, :purge_seq 0, :compact_running false, :instance_start_time
"1324037686108297", :doc_count 0}
=> (reduce conj! db (for [x (range 5000)]
                      {:_id (str x) :a [1 2 x]}))
#<CouchDB user.CouchDB@71d1be4e>
=> (count db)
5000
=> (get-in db ["68" :a 2])
68
=> (def copy (into {} db))
#'user/copy
=> (get-in copy ["68" :a 2])
68
=> (first db)
["0" {:_id "0", :_rev "1-79fe783154bff972172bc30732783a68", :a [1 2 0]}]
=> (dissoc! db "68")
#<CouchDB user.CouchDB@48f50903>
=> (get db "68")
nil
=> (assoc! db :foo {:a 6 :b 7})
#<CouchDB user.CouchDB@79d7999e>
=> (:result (meta *1))
{:_rev "1-ac3fe57a7604cfd6dcca06b25204b590", :_id ":foo", :a 6, :b 7}
```

### Using ClojureScript to write CouchDB views <a name="cljsviews"/>

You can write your views/filters/validators in Clojure(Script) —
avoiding the use of any special view server, special configuration, or
JavaScript!

Depending on the requirements of your view functions (e.g. if your views
have no specific dependencies on Clojure or JVM libraries), then writing
your views in ClojureScript can have a number of benefits:

1. No need to configure CouchDB instances to use the Clojure/Clutch view
   server.
2. Therefore, flexibility to use hosted CouchDB services like
   [Cloudant](http://cloudant.com), [Iris Couch](http://www.iriscouch.com/), et al.
3. Did we say 'no JavaScript'? Yup, no JavaScript. :-)

#### "Installation"

Clutch provides everything necessary to use ClojureScript to define
CouchDB views, but it does not declare a specific ClojureScript
dependency.  This allows you to bring your own revision of ClojureScript
into your project, and manage it without worrying about dependency
management conflicts and such.

You can always look at Clutch's `project.clj` to see which version of
ClojureScript it is currently using to test its view support (
`[org.clojure/clojurescript "0.0-1011"]` as of this writing).

**Note that while Clutch itself only requires Clojure >= 1.2.0 ClojureScript
requires Clojure >= 1.4.0.**

The above requirement applies only if you are _saving_ ClojureScript
views.  A Clutch client using Clojure 1.2.0 can _access_ views written
in ClojureScript (i.e. via `get-view`) without any dependence on
ClojureScript at all.

If you attempt to save a ClojureScript view but ClojureScript is not
available (or you are using Clojure 1.2.x), an error will result.

#### Usage

Use Clutch's `save-view` per usual, but instead of providing a string of
JavaScript (and specifying the language to be `:javascript`), provide a
snippet of ClojureScript (specifying the language to be `:cljs`):

```clojure
(with-db "your_database"
  (save-view "design_document_name"
    (view-server-fns :cljs
      {:your-view-name {:map (fn [doc]
                               (js/emit (aget doc "_id") nil))}})))
```

(Note that `view-server-fns` is a macro, so you do not need to quote
your ClojureScript forms.)

That's an example of a silly view, but should demonstrate the general
pattern.  Note the `js/emit` function; after ClojureScript compilation,
this results in a call to the `emit` function defined by the standard
CouchDB Javascript view server for emitting an entry into the view
result.  Follow the same conventions for reduce functions, filter
functions, validator functions, etc.

Your views can utilize larger codebases; just include your "top-level"
ClojureScript forms in a vector:

```clojure
(with-db "your_database"
  (save-view "design_document_name"
    (view-server-fns {:language :cljs
                      :main 'couchview/main}
      {:your-view-name {:map [(ns couchview)
                              (defn concat
                                [id rev]
                                (str id rev))
                              (defn ^:export main
                                [doc]
                                (js/emit (concat (aget doc "_id") (aget doc "_rev")) nil))]}})))
```

The `ns` form here can require other ClojureScript files on your
classpath, refer to macros, etc.  When using this longer form, remember
to do three things:

1. You must provide a map of options to `view-server-fns`; `:cljs`
   becomes the `:language` value here.
2. Specify the "entry point" for the view function via the `:main` slot,
   `'couchview/main` here.  This must correspond to an exported, defined
function loaded by some ClojureScript, either in your vector literal of
in-line ClojureScript, or in some ClojureScript loaded via a `:require`.
3. Ensure that your "entry point" function is exported; here, `main` is
   our entry point, exported via the `^:export` metadata.

These last two points are required because of the default ClojureScript
compilation option of `:advanced` optimizations.

#### Compilation options

The `view-server-fns` macro provided by Clutch takes as its first
argument some options to pass along to the view transformer specified in
that options map's `:language` slot.  The `:cljs` transformer passes
this options map along to the ClojureScript/Google Closure compiler,
with defaults of:

```clojure
{:optimizations :advanced
 :pretty-print false}
```

So you can e.g. disable `:advanced` optimizations and turn on
pretty-printing by passing this options map to `view-server-fns`:

```clojure
{:optimizations :simple
 :pretty-print true
 :language :cljs}
```

#### Internals

If you really want to see what Javascript ClojureScript is generating
for your view function(s), call `com.ashafa.clutch.cljs-views/view` with
an options map as described above (`nil` to accept the defaults) and
either an anonymous function body or vector of ClojureScript top-level
forms. 

#### Caveats

* ClojureScript / Google Closure produces a _very_ large code footprint,
  even for the simplest of view functions.  This is apparently an item
of active development in ClojureScript.
  * In any case, the code size of a view function string should have
little to no impact on runtime performance of that view.  The only
penalty to be paid should be in view server initialization, which should
be relatively infrequent.  Further, the vast majority of view runtime is
dominated by IO and actual document processing, not the loading of a
handful of JavaScript functions.
* The version of Spidermonkey that is used by CouchDB (and Cloudant at
  the moment) does not treat regular expression literals properly — they
work fine as arguments, e.g. `string.match(/foo/)`, but e.g.
`/foo/.exec("string")` fails.  Using the `RegExp()` function with a
string argument _does_ work.  [This is reportedly fixed in CouchDB
1.2.0](https://issues.apache.org/jira/browse/COUCHDB-577), though I
haven't verified that.
* If you are familiar with writing CouchDB views in JavaScript, you must
  keep a close eye on your ClojureScript/JavaScript interop.  e.g.
`(js/emit [1 2] true)` will do _nothing_, because `[1 2]` is a
ClojureScript vector, not a JavaScript array.  Similarly, the values
passed to view functions are JavaScript objects and arrays, not
ClojureScript maps and vectors.  A later release of Clutch will likely
include a set of ClojureScript helper functions and macros that will
make the necessary conversions automatic.

### Configuring your CouchDB installation to use the Clutch view server

_This section is only germane if you are going to use Clutch's
**Clojure** (i.e. JVM Clojure) view server.  If the views you need to
write can be expressed using ClojureScript — i.e. they have no JVM or
Clojure library dependencies — using Clutch's ClojureScript support to
write views is generally recommended._

CouchDB needs to know how to exec Clutch's view server.  Getting this command string together can be tricky, especially given potential classpath complexity.  You can either (a) produce an uberjar of your project, in which case the exec string will be something like:

```
java -cp <path to your uberjar> clojure.main -m com.ashafa.clutch.view-server
```

or, (b) you can use the `com.ashafa.clutch.utils/view-server-exec-string` function to dump a likely-to-work exec string.  For example:

```clojure
user=> (use '[com.ashafa.clutch.view-server :only (view-server-exec-string)])
nil
user=> (println (view-server-exec-string))
java -cp "clutch/src:clutch/test:clutch/classes:clutch/resources:clutch/lib/clojure-1.3.0-beta1.jar:clutch/lib/clojure-contrib-1.2.0.jar:clutch/lib/data.json-0.1.1.jar:clutch/lib/tools.logging-0.1.2.jar" clojure.main -m com.ashafa.clutch.view-server
```

This function assumes that `java` is on CouchDB's PATH, and it's entirely possible that the classpath might not be quite right (esp. on Windows — the above only tested on OS X and Linux so far).  In any case, you can test whether the view server exec string is working properly by trying it yourself and attempting to get it to echo back a log message:

```
[catapult:~/dev/clutch] chas% java -cp "clutch/src:clutch/test:clutch/classes:clutch/resources:clutch/lib/clojure-1.3.0-beta1.jar:clutch/lib/clojure-contrib-1.2.0.jar:clutch/lib/data.json-0.1.1.jar:clutch/lib/tools.logging-0.1.2.jar" clojure.main -m com.ashafa.clutch.view-server
["log" "echo, please"]
["log",["echo, please"]]
```

Enter the first JSON array, and hit return; the view server should immediately reply with the second JSON array.  Anything else, and your exec string is flawed, or something else is wrong.

Once you have a working exec string, you can use Clojure for views and filters by adding a view server configuration to CouchDB.  This can be as easy as passing the exec string to the `com.ashafa.clutch/configure-view-server` function:

```clojure
(configure-view-server (view-server-exec-string))
```

Alternatively, use Futon to add the `clojure` query server language to your CouchDB instance's config.

In the end, both of these methods add the exec string you provide it to the `local.ini` file of your CouchDB installation, which you can modify directly if you like (this is likely what you'll need to do for non-local/production CouchDB instances):

```
  [query_servers]
  clojure = java -cp …rest of your exec string…
```

#### View server configuration & view API usage

```clojure
=> (configure-view-server "clutch_example" (com.ashafa.clutch.view-server/view-server-exec-string))
""
=> (save-view "clutch_example" "demo_views" (view-server-fns :clojure
                                              {:sum {:map (fn [doc] [[nil (:test-grade doc)]])
                                                     :reduce (fn [keys values _] (apply + values))}}))
{:_rev "1-ddc80a2c95e06b62dd2923663dc855aa", :views {:sum {:map "(fn [doc] [[nil (:test-grade doc)]])", :reduce "(fn [keys values _] (apply + values))"}}, :language :clojure, :_id "_design/demo_views"}
=> (-> (get-view "clutch_example" "demo_views" :sum) first :value)
60
=> (get-view "clutch_example" "demo_views" :sum {:reduce false})
({:id "0896fbf57128d7f1a1b238a52b0ec372", :key nil, :value 20}
 {:id "0896fbf57128d7f1a1b238a52b0ecda8", :key nil, :value 30}
 {:id "foo", :key nil, :value 10})
=> (map :value (get-view "clutch_example" "demo_views" :sum {:reduce false}))
(20 30 10)
```

Note that all view access functions (i.e. `get-view`, `all-documents`, etc) return a lazy seq of their results (corresponding to the `:rows` slot in the data that couchdb returns in its view data).  Other values (e.g. `total_rows`, `offset`, etc) are added to the returned lazy seq as metadata. 

```clojure
=> (meta (all-documents "databasename"))
{:total_rows 20000, :offset 0}
```

### `_changes` support

Clutch provides comprehensive support for CouchDB's `_changes` feature.
There is a `com.ashafa.clutch/changes` function that provides direct
access to it, but most uses of `_changes` will benefit from using the
`change-agent` feature.  This configures a Clojure agent to receive
updates from the `_changes` feed; its state will be updated to be the
latest event (change notification), and so it is easy to hook up however
many functions as necessary to the agent as watches (a.k.a. callbacks).

Here's a REPL interaction demonstrating this functionality:

```clojure
=> (require '[com.ashafa.clutch :as couch])
nil
=> (couch/create-database "demo")
#cemerick.url.URL{:protocol "http", :username nil, :password nil,
                  :host "localhost", :port 5984, :path "/demo",
                  :query nil, :anchor nil}
=> (def a (couch/change-agent "demo"))
#'user/a

   ;; `start-changes` hooks the agent up to the database's `_changes` feed
=> (couch/start-changes a)
#<Agent@693a1324: nil>
=> (couch/put-document "demo" {:name "Chas"})
{:_id "259239233e2c2d06f3e311ce5f5271c1", :_rev "1-24ccfd9600c215e32ceefdd06b25f62d", :name "Chas"}

   ;; each change becomes a new state within the agent:
=> @a
{:seq 1, :id "259239233e2c2d06f3e311ce5f5271c1", :changes [{:rev "1-24ccfd9600c215e32ceefdd06b25f62d"}]}

   ;; use Clojure's watch facility to have functions called on each change
=> (add-watch a :echo (fn [key agent previous-change change]
                        (println "change received:" change)))
#<Agent@693a1324: {:seq 1, :id "259239233e2c2d06f3e311ce5f5271c1", :changes [{:rev "1-24ccfd9600c215e32ceefdd06b25f62d"}]}>
=> (couch/put-document "demo" {:name "Roger"})
{:_id "259239233e2c2d06f3e311ce5f527a9d", :_rev "1-0c3db91854f26486d1c3922f1a651d86", :name "Roger"}
change received: {:seq 2, :id 259239233e2c2d06f3e311ce5f527a9d, :changes [{:rev 1-0c3db91854f26486d1c3922f1a651d86}]}
=> (couch/bulk-update "demo" [{:x 1} {:y 2} {:z 3 :_id "some-id"}])
[{:id "259239233e2c2d06f3e311ce5f527cd4", :rev "1-0785e9eb543380151003dc452c3a001a"} {:id "259239233e2c2d06f3e311ce5f527fa6", :rev "1-ef91d626f27dc5d224fd534e7b47da82"} {:id "some-id", :rev "1-178dbe6c7346ffc3af8811327d1336ff"}]
change received: {:seq 3, :id 259239233e2c2d06f3e311ce5f527cd4, :changes [{:rev 1-0785e9eb543380151003dc452c3a001a}]}
change received: {:seq 4, :id 259239233e2c2d06f3e311ce5f527fa6, :changes [{:rev 1-ef91d626f27dc5d224fd534e7b47da82}]}
change received: {:seq 5, :id some-id, :changes [{:rev 1-178dbe6c7346ffc3af8811327d1336ff}]}
=> (couch/delete-document "demo" (couch/get-document "demo" "some-id"))
{:ok true, :id "some-id", :rev "2-7a128852666329025f1fba1114628251"}
change received: {:seq 6, :id some-id,
                  :changes [{:rev 2-7a128852666329025f1fba1114628251}], :deleted true}

   ;; if you want to stop the flow of changes through the agent, use
   ;; `stop-changes`
=> (couch/stop-changes a)
#<Agent@693a1324: {:seq 6, :id "some-id", :changes [{:rev "2-7a128852666329025f1fba1114628251"}], :deleted true}>
```

`changes` and `change-agent` pass along all of the parameters accepted
by `_changes`, so you can get changes since a given point in time,
filter changes based on a view server function, get the full content of
changed documents included in the feed, etc.  See the official [CouchDB
API documentation for `_changes`](http://wiki.apache.org/couchdb/HTTP_database_API#Changes) for details.

## (Partial) Changelog

##### 0.4.0

* **API change**: `watch-changes`, `stop-changes`, and `changes-error`
  have been removed.  See the usage section on changes above.
  The `_changes` API support now consists of:
  * `changes` to obtain a lazy seq of updates from `_changes` directly
  * `change-agent`, `start-changes`, and `stop-changes` for creating and
    then controlling the activity of a Clojure agent whose state
    reflects the latest row from a continuous or longpoll view of
    `_changes`.
* **API change**: `com.ashafa.clutch.http-client/*response-code*` has
  been replaced by `*response*`. Rather than just being optionally bound
  to the response code provided by CouchDB, this var is `set!`ed to its
  complete clj-http response.
* Added `document-exists?` function; same as `(boolean (get-document db "key"))`,
  but uses a `HEAD` request instead of a `GET` (handy for checking for the
  existence of very large documents).
* Write CouchDB views in ClojureScript! All of the functionality of
  [clutch-clojurescript](https://github.com/clojure-clutch/clutch-clojurescript)
  has been merged into Clutch proper.
* [cheshire](https://github.com/dakrone/cheshire) is now being used for
  all JSON operations.
* [clj-http](https://github.com/dakrone/clj-http) is now being used for
  all HTTP operations.

##### 0.3.1

* Added the CouchDB "type", providing a higher-level and more
  Clojuresque abstraction for most CouchDB operations.
* byte arrays may now be used with `put-attachment` et al.
* Clutch may now be used with Java 1.5 (in addition to 1.6+)

##### 0.3.0

Many breaking changes to refine/simplify the API, clean up the implementation, and add additional features:

Core API:

* Renamed `create-document` => `put-document`; `put-document` now supports both creation and update of a document depending upon whether  `:_id` and `:_rev` slots are present in the document you are saving.
* Renamed `update-attachment` => `put-attachment`; `filename` and `mime-type` arguments now kwargs, `InputStream` can now be provided as attachment data
* `update-document` semantics have been simplified for the case where an "update function" and arguments are supplied to work well with core Clojure functions like `update-in` and `assoc` (fixes issue #8) — e.g. can be used like `swap!` et al.
* Optional `:id` and `:attachment` arguments to `put-document` (was `create-document`) are now specified via keyword arguments
* Removed "update map" argument from `bulk-update` fn (replace with e.g. `(bulk-update db (map #(merge % update-map) documents)`)
* Renamed `get-all-documents-meta` => `all-documents`
* `com.ashafa.clutch.http-client/*response-code*` is no longer assumed to be an atom. Rather, it is `set!`-ed directly when it is thread-bound. (Fixes issue #29)

View-related API:

* All views (`get-view`, `all-documents`, etc) now return lazy seqs corresponding to the `:rows` slot in the view data returned by couch. Other values (e.g. `total_rows`, `offset`, etc) are added to the returned lazy seq as metadata.
* elimination of inconsistency between APIs between `save-view` and `save-filter`.  The names of individual views and filters are now part of the map provided to these functions, instead of sometimes being provided separately.
* `:language` has been eliminated as part of the dynamically-bound configuration map
* `with-clj-view-server` has been replaced by the more generic `view-server-fns` macro, which takes a `:language` keyword or map of options that includes a `:language` slot (e.g. `:clojure`, `:javascript`, etc), and a map of view/filter/validator names => functions.
* A `view-transformer` multimethod is now available, which opens up clutch to dynamically support additional view server languages. 
* Moved `view-server-exec-string` to `com.ashafa.clutch.view-server` namespace

## Contributors

Appreciations go out to:

* [Chas Emerick](http://cemerick.com)
* [Tunde Ashafa](http://ashafa.com/)
* [Pierre Larochelle](http://github.com/pierrel)
* [Matt Wilson](http://github.com/mattdw)
* [Patrick Sullivan](http://github.com/WizardofWestmarch)
* [Toni Batchelli](http://tbatchelli.org)
* [Hugo Duncan](http://github.com/hugoduncan)
* [Ryan Senior](http://github.com/senior)

## License

BSD.  See the LICENSE file at the root of this repository.



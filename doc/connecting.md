# Connecting to CouchDB

All Clutch functions accept a first argument indicating the database endpoint, for example:

```clojure
(require '[com.ashafa.clutch :as couch])

(couch/get-document "wiki" "home-page")
(couch/get-document "http://localhost:5984/wiki" "blogs")
```

However, putting a connection argument in every call to Clutch is a lot of typing and violates the Don't Repeat Yourself principle (DRY), let's improve that...

...

We can go one step further and use a simple macro and `with-db`...

...


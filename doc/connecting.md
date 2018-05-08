# Connecting to CouchDB

All Clutch functions accept a first argument indicating the database endpoint, for example:

```clojure
(require '[com.ashafa.clutch :as couch])

(couch/get-document "wiki" "home-page")
(couch/get-document "http://localhost:5984/wiki" "blogs")
```

However, putting a connection string in every call to Clutch is a lot of typing and it's not good practice to include these kinds of details in your code. So let's improve this by using an environment variable.

First define an environment variable in your `profiles.clj` file:

```
{:profiles/dev  {:env {:database-url "wiki"}}}
```

Now you can use this for the connection string:

```clojure
(require '[com.ashafa.clutch :as couch])
(require '[environ.core :refer [env]])

(define db (env :database-url))
(couch/get-document db "home-page")
```

You can find out more about using environment variables here: https://github.com/weavejester/environ

We can go one step further and use a simple macro to factor out the `db` parameter altogether:

```clojure
(defmacro with-db
  [& body]
  `(couch/with-db (env :database-url)
    ~@body))
```

Now we can write code like this:

```clojure
(with-db (couch/get-document "blogs"))
```

Next: [Getting documents](get-doc.md)
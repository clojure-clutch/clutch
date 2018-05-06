# Getting documents

Following on from [connecting to the database](connecting.md) let's look at how we can retrieve documents from CouchDB.

## All documents

To get a sequence of all documents in the database use `all-documents`. With no parameters this returns just the document `id`, `key` and latest revision.

```clojure
> (with-db (couch/all-documents))
({:id "_design/page_graph",
  :key "_design/page_graph",
  :value {:rev "9-78833b3c2b993ab70729fe09416b787d"}}
 {:id "_design/pages",
  :key "_design/pages",
  :value {:rev "17-04590ec8d06f0af6dc37a8f209e6edd2"}}
 {:id "about",
  :key "about",
  :value {:rev "10-4379c9bff126a5854779d177b48c8d2f"}}
 {:id "about-wikis",
  :key "about-wikis",
  :value {:rev "7-b7a842842bbf0059b421f522851afeaf"}}
 {:id "blogs",
  :key "blogs",
  :value {:rev "5-a3bdf7a1d156acae6ae69530394acff9"}}
 {:id "ideal-wiki",
  :key "ideal-wiki",
  :value {:rev "7-9b321eca6d2aab8e934b089684662342"}}

 ;; ...
 )
```

[//]: # (Add info about include more, eg. include_docs)

## Getting a single document

The simplest form is `get-document` with a document id:

```clojure
(with-db (couch/get-document "ideal-wiki"))
```

This returns a hash-map like the one below (clearly dependent on the data in your database):

```clojure
{:_id "ideal-wiki", :_rev "7-9b321eca6d2aab8e934b089684662342", :content "What's the Ideal Wiki?\r\n================\r\n\r\n## Essentials\r\n\r\nAs an editor...\r\n\r\n* Markdown or similar syntax. WYSIWYG is too complex and error prone.\r\n* Really easy to make new pages, e.g. with `[[Links Like This]]` or maybe LikeThis.\r\n* Version history so that changes are safe.\r\n* Adding images is easy enough.\r\n* Wiki sections, e.g. Recipes, HomeEd.\r\n\r\nAs a reader of the wiki...\r\n\r\n* Nice default presentation.\r\n* Good search.\r\n* My own navigation bar of favourite pages.\r\n\r\n## Nice to have\r\n\r\n\r\nAs an editor...\r\n\r\n* Page rename doesn't break existing links.\r\n* Tagging, and pages styled by tag\r\n* Broken links, or links to pages that don't yet exist, are highlighted.\r\n* Auto-resize of images + image gallery.", :tags ["todo"]}
```

You can retrieve parts of the document in the usual Clojure style:

```clojure
> (:tags (with-db (couch/get-document "ideal-wiki")))
["todo"]
```

## Document Revisions

Notice that the document contains information on the revision. CouchDB always returns the latest revision, unless you specify an earlier one.

You can get the list of revisions by adding `:revs true` to `get-document`:

```clojure
> (with-db (couch/get-document "ideal-wiki" :revs true))

{:_id "ideal-wiki",
 :_rev "7-9b321eca6d2aab8e934b089684662342",
 :content
 "What's the Ideal Wiki? ...",
 :tags ["todo"],
 :_revisions
 {:start 7,
  :ids
  ["9b321eca6d2aab8e934b089684662342"
   "fcb4d3cc5a8e683cc35eb64d8d1f8ba2"
   "88624c792c8d1d0824d7423427034b23"
   "39c4436858fde5dda4839118f41ef722"
   "360401f1e5899a1368db7eee06213a1f"
   "ca013f24088b896b21ab792a5a0d9b90"
   "9e64f6ca68a253ef2bf24dee536356bf"]}}
```

And to get just the revisions -- these are newest first:

```clojure
> (:ids (:_revisions (with-db (couch/get-document "ideal-wiki" :revs true))))
["9b321eca6d2aab8e934b089684662342" "fcb4d3cc5a8e683cc35eb64d8d1f8ba2" "88624c792c8d1d0824d7423427034b23" "39c4436858fde5dda4839118f41ef722" "360401f1e5899a1368db7eee06213a1f" "ca013f24088b896b21ab792a5a0d9b90" "9e64f6ca68a253ef2bf24dee536356bf"]
```

To retrieve a revision use the option `:rev` with a revision ID:

```clojure
> (with-db (couch/get-document "ideal-wiki"
                               :rev "fcb4d3cc5a8e683cc35eb64d8d1f8ba2"))
```

Did you notice that this threw an error? Specifying revision IDs is not as simple as it looks, before we fix this let's take a little diversion into error reporting...

## Error reporting

In Emacs with Cider (and other environments?), errors generate a 400 response from the CouchDB server. There's a lot of detail and it's difficult to see the cause, but look in the body and you'll see reason, in this case: `bad_request` and `Invalid rev format`:

```clojure
2. Unhandled clojure.lang.ExceptionInfo

1. Caused by clojure.lang.ExceptionInfo
   clj-http: status 400
   {:status 400, :headers {"server" "CouchDB/1.6.1 (Erlang OTP/19)", "date" "Wed, 02 May 2018 13:12:37 GMT", "content-type" "text/plain; charset=utf-8", "content-length" "54", "cache-control" "must-revalidate"}, :body "{\"error\":\"bad_request\",\"reason\":\"Invalid rev format\"}\n", :request {:path "/wiki/ideal-wiki", :user-info nil, :follow-redirects true, :body-type nil, :protocol "http", :password nil, :conn-timeout 5000, :as :json, :username nil, :http-req #object[clj_http.core.proxy$org.apache.http.client.methods.HttpEntityEnclosingRequestBase$ff19274a 0x313052b1 "GET http://localhost:5984/wiki/ideal-wiki?rev=fcb4d3cc5a8e683cc35eb64d8d1f8ba2 HTTP/1.1"], ...}}
```

You can get the `body` within the `data` of the last exception like this, which is a bit easier than searching through the exception output:

```clojure
> (:body (.data *e))
"{\"error\":\"bad_request\",\"reason\":\"Invalid rev format\"}\n"
```

## Back to document revisions

So to retrieve a revision, you must specify the version index and the revision number. In the previous example the index starts at 7, and descends through the revision list as (7 6 5 4 3 2 1). Here's the fixed code:

```clojure
> (with-db (couch/get-document "ideal-wiki"
                               :rev "6-fcb4d3cc5a8e683cc35eb64d8d1f8ba2"))
```

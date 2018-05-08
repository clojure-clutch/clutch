# Putting documents

CouchDB is an immutable database, which means that you cannot update existing documents, rather you create new versions to record changes. Most CouchDB operations return the latest revision of documents.

## Putting a new document

Use the `put-document` function to put a new document - notice how you specify the `_id` and then any extra fields you wish to add. 

```clojure
(with-db (couch/put-document {:_id "a-new-page"
                              :content "lorem ipsum"
                              :tags (list "tag1" "tag2")}))
```

To see what immutable means, run that command again:

```clojure
> ;;; repeat command above
ExceptionInfo clj-http: status 409  slingshot.support/stack-trace (support.clj:201)
> (:body (.data *e))
"{\"error\":\"conflict\",\"reason\":\"Document update conflict.\"}\n"
```

## Updating a document

To update a document you put a new revision, so first find the current revision with `get-document`:

```clojure
> (with-db (couch/get-document "a-new-page"))
{:_id "a-new-page",
 :_rev "1-0d1b7dedd3123e3d349748f2acce65d5",
 :content "lorem ipsum",
 :tags ["tag1" "tag2"]}
```

Now we can use this info to create a new version -- make sure you use the `_rev` value from your own output (not from the output above):

```clojure
(with-db (couch/put-document {:_id "a-new-page"
                              :_rev "1-0d1b7dedd3123e3d349748f2acce65d5"
                              :content "Some new content"
                              :tags (list "tag1" "tag2" "tag3")}))
```

The `put-document` command outputs the document written:

```clojure
{:_id "a-new-page", :_rev "2-baef2d1090628e045129c9f0513ce782", :content "Some new content", :tags ("tag1" "tag2" "tag3")}
```

Next: Finding documents with [views](views.md)
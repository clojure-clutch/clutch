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
> > (:body (.data *e))
"{\"error\":\"conflict\",\"reason\":\"Document update conflict.\"}\n"
```


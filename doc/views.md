# Finding documents with views

Use views to index or summarise your data so that you can find what you need. The CouchDB database has a good [guide to views](http://guide.couchdb.org/draft/views.html), read that first then follow the rest of this document to see how to use them from Clojure.

## Create your views

Use the admin page on your database, often here: http://127.0.0.1:5984/_utils/ to create a new view. 

Select your database, then click the 'View' dropdown, then 'Temporary View...', this opens up a split pane JavaScript editor, with space for a 'map' function on the left and a 'reduce' function on the right.

Here's an example that produces a map from tags to document ids:

```javascript
function(doc) {
  if ('tags' in doc) {
    doc.tags.forEach( function(tag) {
      emit(tag, doc._id );
    });
  }
}
```

Make sure you name and save your temporary view. As a guide, the design document can be named by the entity (in this case 'pages') and the view by the key (in this case 'by_tag').

## Calling views from your code

Once you've created, tested and saved your new view, call it using `get-view`:

```clojure
> (with-db (couch/get-view "pages" "by_tag" {:key "tag1"}))
({:id "a-new-page", :key "tag1", :value "a-new-page"}
 {:id "a-new-page-2", :key "tag1", :value "a-new-page-2"})
```

As you can see, `get-view` returns a list of hash-maps that identify the matching documents. 
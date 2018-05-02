# Getting documents

Following on from [connecting to the database](connecting.md) let's look at how we can retrieve documents from CouchDB.

The simplest form is:

```clojure
(with-db (couch/get-document "ideal-wiki"))
```

Which returns a hash-map like this (clearly dependent on the data in your database):

```clojure
{:_id "ideal-wiki", :_rev "7-9b321eca6d2aab8e934b089684662342", :content "What's the Ideal Wiki?\r\n================\r\n\r\n## Essentials\r\n\r\nAs an editor...\r\n\r\n* Markdown or similar syntax. WYSIWYG is too complex and error prone.\r\n* Really easy to make new pages, e.g. with `[[Links Like This]]` or maybe LikeThis.\r\n* Version history so that changes are safe.\r\n* Adding images is easy enough.\r\n* Wiki sections, e.g. Recipes, HomeEd.\r\n\r\nAs a reader of the wiki...\r\n\r\n* Nice default presentation.\r\n* Good search.\r\n* My own navigation bar of favourite pages.\r\n\r\n## Nice to have\r\n\r\n\r\nAs an editor...\r\n\r\n* Page rename doesn't break existing links.\r\n* Tagging, and pages styled by tag\r\n* Broken links, or links to pages that don't yet exist, are highlighted.\r\n* Auto-resize of images + image gallery.", :tags ["todo"]}
```

You can retrieve parts of the document in the usual clojure style:

```clojure
> (:tags (with-db (couch/get-document "ideal-wiki")))
["todo"]
```


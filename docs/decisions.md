# Design decisions

Each entry records what was chosen, why, and what was rejected.

## Phase 0: project setup

### Target Java 21 with `--release`, test on 21 and 25

The compiler runs with `--release 21`, which checks the code against the Java 21 API as well as emitting Java 21 bytecode. Developers can build with any newer JDK without accidentally using a newer API. CI runs the build on JDK 21 and JDK 25, the two current LTS releases, to prove the "21 or newer" claim.

Rejected: Maven toolchains. They pin an exact JDK installation, which is more precise but forces every contributor to install JDK 21 specifically.

### Unchecked, sealed exception hierarchy

Every engine error extends `MiniDbException`, a sealed `RuntimeException` with four subclasses: `SyntaxException` (with a 1-based line and column), `CatalogException`, `ConstraintViolationException` and `StorageException`. Because the hierarchy is sealed, the REPL can render errors with an exhaustive `switch`, and the compiler flags any new error type that is not handled.

Rejected: checked exceptions. Errors raised deep in storage would have to be declared on every iterator `next()` and every planner method in between, even though only the REPL ever handles them.

### No runtime dependencies

The lexer, parser and data structures are hand-written, so the only dependency is JUnit, in test scope. The runnable jar needs nothing but a JRE. The compiler enables every lint except `serial` and fails on any warning, and plugin versions are pinned so builds are reproducible.

## Phase 1: storage

### LRU list of unpinned frames only

The buffer pool finds cached pages through a `HashMap` from page id to frame, and keeps a hand-written doubly linked list of the frames nobody has pinned, ordered from least to most recently released. A page joins the list when its last pin is released and leaves it when it is pinned again. The victim is always the head of the list, so eviction is O(1) and never has to walk past pinned pages. When the list is empty and the pool is full, the fetch fails with a clear error instead of waiting.

Rejected: `LinkedHashMap` in access order, which hides the very structure this project is meant to show. Also rejected: clock, which avoids relinking on every hit but only approximates LRU and is harder to reason about in tests.

### Every page write goes through the pin handle

Pages are read and written through `PageHandle`, the `AutoCloseable` returned by a fetch. Any write marks the frame dirty, so forgetting to mark a page dirty is impossible. Any access after `close()` throws instead of silently reading a frame that may already hold another page. Phase 5 needs to capture a page's before-image the first time a transaction modifies it, and this gives it a single place to do so.

Rejected: handing out the raw `ByteBuffer` and asking callers to call `markDirty()`. It is faster to write, but every missed call is a lost update that only shows up after an eviction.

### The header is an ordinary page

Page 0 (page count, free list, catalog root) is fetched and modified through the buffer pool like any other page. The write-ahead log in phase 5 logs whole page images, so allocating a page inside a transaction will be logged and undone with no special case.

Rejected: keeping the header as fields in the pager and writing it separately, which would need its own recovery path.

### Slotted pages compact lazily and keep slot numbers

Deleting a row only empties its slot. Compaction happens when an insert needs space that exists only in scattered holes, and it rewrites the records without renumbering slots. Indexes store RIDs, so RIDs must stay stable while records move. Empty slots are reused by later inserts, which keeps the slot directory from growing without bound.

Rejected: compacting on every delete, which costs a full page rewrite for space that may never be needed.

### First-fit inserts into heap files

An insert walks the table's page chain and uses the first page with room, appending a new page only when none has space. Space freed by deletes anywhere in the table is reused, at the cost of O(pages) per insert.

Rejected: a free space map, which makes inserts constant time but adds another on-disk structure to keep consistent. It is the natural upgrade if tables grow large.

### Scans hold no pin between rows

A heap scan copies the live records of one page while it is pinned, releases the pin, and then returns those records one at a time. An operator that stops early, such as `LIMIT`, can drop the iterator without leaking a pinned page.

Rejected: keeping the current page pinned for the life of the iterator. It avoids a copy, but it ties correctness to every caller closing the scan.

### SQL `NULL` is a value, not a Java `null`

`Value` is a sealed interface with one record per type plus `NullValue`. Code that handles values must deal with `NULL` explicitly in an exhaustive `switch`, and a Java `null` inside a tuple is rejected on construction. This matters for three-valued logic in later phases.

Rejected: storing values as `Object` with Java `null` for SQL `NULL`. It is shorter, but every missing check becomes a `NullPointerException` far from its cause.

### One process per database file

The pager takes an exclusive `FileLock` when it opens a file, so a second process, or a second open in the same JVM, fails right away instead of corrupting pages that both believe they own.

## Phase 2: first queries

### The catalog is a heap of records, cached in memory

Each table is one binary record in a heap of `CATALOG` pages reachable from the header. On open the whole catalog is read into a sorted map; `CREATE TABLE` and `DROP TABLE` update the map and the record together. The catalog reuses the slotted page and heap file code, so it gets free space reuse and page chaining for free.

Rejected: storing the original `CREATE TABLE` text and parsing it again on open, as SQLite does. It is compact and self-documenting, but it makes opening a database depend on the SQL parser.

### A hand-written recursive descent parser with positions on every node

Each precedence level of the expression grammar is one method, from `OR` down to primary expressions, which keeps the grammar readable in the code itself. Every token records its line and column, and every AST node keeps the position of the token that best explains an error about it: the operator of a binary expression, the name of an unknown column. Select-list aliases require `AS`, because accepting a bare word turns a typo such as `SELECT name FORM t` into an alias and moves the error somewhere confusing.

Rejected: a parser generator. It would be a dependency, and the project's point is to show the parser.

### Binding is a separate step between parsing and execution

The parser produces names; the binder turns them into column indexes and checks types against the input attributes before any row is read. A type error such as `name > 3` is reported with a caret under the operator, and evaluation never has to look up names or check types per row. The SQL AST and the bound expressions are different sealed types, so a bound plan cannot contain an unresolved name.

Rejected: resolving names lazily during evaluation. It is less code, but errors would appear only when a row reaches the bad expression, or never on an empty table.

### Errors carry an optional source position

Any `MiniDbException` may point at a line and column; `SyntaxException` always does. Unknown tables and columns (`CatalogException`), type errors (`QueryException`) and bad `INSERT` values (`ConstraintViolationException`) all get the caret. `QueryException` was added for type errors and evaluation failures such as division by zero, which none of the original four types described.

### `INSERT` validates every row before writing any

Types, `NOT NULL`, `VARCHAR` lengths and row size are checked for all rows first, so a constraint error in the tenth row leaves the table untouched. Real atomicity, including against crashes, arrives with the write-ahead log in phase 5.

### Pages are written after every statement until the WAL exists

After each statement the dirty pages are written to the file, followed by an `fsync` only when something was written. This keeps the window for losing work small without a log. Phase 5 replaces it with no-steal and commit records.

### The REPL has no line editor

Line editing and history would need a library such as JLine, which breaks the zero-dependency rule. The README suggests `rlwrap` instead. Colors appear only on a terminal and respect `NO_COLOR`, borders fall back to ASCII when the output is not UTF-8, errors go to standard error, and a non-interactive run exits with status 1 if any statement failed.

## Phase 3: B+ tree

### Every index orders entries by (key, RID)

Leaves store `(key, RID)` pairs, and both leaves and separators compare by key first and RID second. Duplicate keys in a secondary index therefore still have a total order: inserting and deleting one exact entry follows a single path, and the entries for one key come out in RID order. The primary key uses the same structure and checks for an existing key before inserting.

Rejected: storing a list of RIDs per key. It saves space for very repetitive keys, but a long list can outgrow its page and needs overflow handling that the composite key avoids.

### The root page never moves

When the root splits, its contents are copied to a freshly allocated page and the root page is rewritten as the new internal node above both halves. The catalog records each index's root page once, at creation, and never has to change it.

Rejected: allocating a new root and updating the catalog record on every root split. It is the textbook version, but it ties every index insert to a possible catalog write.

### Nodes are decoded, changed and written back, with no pins held while descending

Each step reads a page into a small node object, releases the pin, and writes the node back only if it changed. An insert remembers the nodes on its path in memory rather than keeping their pages pinned, so it holds at most three pages at once however tall the tree is. That matters because tests run trees of order 3, about ten levels deep, inside a 16-page buffer pool.

Rejected: holding every page on the path pinned until the insert finishes. It avoids re-reading a page, but a tall tree would exhaust a small buffer pool.

### Deletes only remove the leaf entry

A delete takes the entry out of its leaf and never merges or redistributes nodes, so leaves can end up sparse or empty. Every search and range scan still works, because separators remain valid bounds and scans follow the leaf chain past empty leaves. Rebalancing on delete is listed as an extra.

### The order is a runtime option

By default a node holds as many entries as fit in a page: 408 in a leaf, 292 in an internal node. The order can be lowered as far as 3 when opening a database (`--btree-order`). Tests and demos use that to see splits after a handful of rows. The order is not stored in the file, since it only decides when a node splits, so a tree built with one order stays valid under another.

### Tested against a TreeMap

A randomized test with fixed seeds runs 40,000 inserts and deletes for each of four orders, with keys drawn from a small range to force duplicates. After each batch it compares range queries with a `TreeMap` and runs an integrity check: every node sorted, every entry within its parent's bounds, every leaf at the same depth, and the leaf chain visiting the leaves in key order.

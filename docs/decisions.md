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

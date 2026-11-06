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

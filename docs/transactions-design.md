# Versioning:

each table gets a version number that a committed `COPY` or `DELETE` increments. Where does the counter live, and what exactly is a version in your metadata: one rewritten catalog file, one catalog file per version, or metadata held in memory only and flushed when the engine exits? The in-memory variant keeps all live versions of all tables in data structures that are safe under concurrent modification. It is the cheapest to commit and the one that loses the most if the engine crashes.

# Commit:

a commit swaps the table's partition list and increments the version, atomically. Atomic how, in one JVM and on disk? (Writing a temporary file and renaming it over `catalog.json` is atomic on POSIX; an in-memory swap is one reference assignment.)

# Snapshot:

a statement pins the version it reads when it starts and never re-reads metadata. What exactly does it pin: a reference to a catalog version, or its own copy of the partition list with offsets and counts? And when: at statement start, or when the plan opens?

# Retention:

how many versions back does the engine keep? A version must not be dropped while a `SELECT` or a `DELETE` still reads it, so how does the engine know that no statement still uses it? Reference counting, a list of running statements, and a fixed number of retained versions are all defensible. Write down which one you pick, and what a statement sees when it asks for a version that is gone.

# The rewrite set:

`DELETE` must rewrite exactly the partitions that may contain matches, which is the complement of week 4's pruning decision. Where does the shared helper live so that the planner and `DELETE` decide from the same code? And what happens to a partition that may contain a match but turns out to contain none: do you write an identical copy, or carry the original over?

# New version:

does a `DELETE` that matches nothing still create a new version?

# Partition placement:

do new partitions go at the end of the table's single data file, or into a file of their own? Either answer can be defended; write yours down.

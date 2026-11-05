# Versioning:

One global catalog file which is thread safe.

# Commit:

Write a temporary file named `catalog_temp_<id>.json` and rename it over `catalog.json` when done.

# Snapshot:

Read the catalog file when planning, pinning the read version.

# Retention:

Since each statements keeps its pinned catalog in memory, it is therby fine to only keep the newest version on disk.

# The rewrite set:

For `DELETE`, active partitions are divided using the same min/max
`partitionMayContain` helper as query pruning. Partitions that cannot contain a
match are carried over by reference. Candidate partitions are read, filtered,
and written as new partitions with fresh metadata; the original files are not
overwritten. Replaced metadata is retained as deprecated history, while normal
scans expose only active partitions.

# New version:

After every candidate has been rewritten successfully, the table version is
incremented and the catalog is atomically replaced. If writing a replacement
fails, table metadata is not changed. A crash after replacement files are
written but before the catalog commit leaves those files orphaned and invisible.

# Partition placement:

Our design already creates a new file per partition, so new partitions go in their own file. Before a new partition is committed we create a temporary file and rename them on commit such that concurrent writes of partitions dont interfere with each other.

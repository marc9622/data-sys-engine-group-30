# Versioning:

One global catalog file which is thread safe.

# Commit:

Write a temporary file named `catalog_temp_<id>.json` and rename it over `catalog.json` when done.

# Snapshot:

Read the catalog file when planning, pinning the read version.

# Retention:

Since each statements keeps its pinned catalog in memory, it is therby fine to only keep the newest version on disk.

# The rewrite set:

A `partitionMayContain` has been added to `StorageEngine`, and is used both by the `Planner` and for deletion.
All partitions without deleted partitions are left unaffected and remain in the catalog.

# New version:

A delete statement that doesn't match any rows leave the partitions unchanged, but it still increments the version number and creates a new catalog file.

# Partition placement:

Our design already creates a new file per partition, so new partitions go in their own file. Before a new partition is committed we create a temporary file and rename them on commit such that concurrent writes of partitions dont interfere with each other.

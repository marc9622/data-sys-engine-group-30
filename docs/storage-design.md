
# Catalog Storage
One catalog file for all tables.
JSON format. (We want to take advantage of how readable JSON is for humans, and then maybe move the a custom binary format later on.)
The file is just located in the provided directory, i.e. not in any sub-directory.

# Catalog Contents
The schema for each table and a list of data files (i.e. partitions) belonging to it. 
We currently also store row counts in the catalog, such that once we allow writing more rows to a table, we can know whether there is more space in a partition purely by looking in the catalog file.

# Where the Min/Max Summaries Live
In the header per column per partition.
The choice is rather arbitrary but the rationale is that it is more intuitive and that it will yield better performance for OLAP queries (which we have chosen to focus on), since each partition will have its own min/max summary.
(It would probably also make sense to have a global min/max for each table in the catalog.)
We also store row count for each partition.

# Restart
It has to read the Catalog file which holds table name and column info, then it will read the partitions headers to find the relevant partitions using the min/max summaries.

# Layout Inside a Partition
The header has row/column counts and min/max values stored where min/max values for each column is stored pairwise, i.e. min1, max1, min2, max2, etc.
After the header, each partition stores its rows in a columnar format.

# Partition Size
By default, it is 1000 rows.
It uses a system property, that the tests can override.

# Value Encodings and Framing
Strings are stored as a 4 byte length followed by the string stored using ASCII.
We've made the fairly arbitrary assumption that our database won't support strings larger than 2^31-1 bytes, which is the maximum size of a Java array.
Longs and doubles are both stored as 8 bytes.

# Byte Order
We use big-endian byte order for all values, as this is the default for Java's DataOutputStream and DataInputStream classes.
It is also what is typically used in network protocols, which might be relevant if we ever want to support a distributed database.


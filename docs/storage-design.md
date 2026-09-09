
# Catalog Storage
One catalog file for all tables.
JSON format. (We want to take advantage of how readable JSON is for humans, and then maybe move the a custom binary format later on.)
The file is just located in the provided directory, i.e. not in any sub-directory.

# Catalog Contents
The schema for each table, list of data files and partitions belonging to it. 

# Where the Min/Max Summaries Live
In the header per column per partition.
The choice is rather arbitrary but the rationale is that it is more intuitive and that it will yield better performance for OLAP queries which will try to focus on.
We also store row count.

# Restart
It has to read the Catalog file which holds table name and column info, then it will read the partitions headers to find the relevant partitions using the min/max summaries.

# Layout Inside a Partition
Each table is separated into partitions, and each partition is stored as columnar data.

# Partition Size
By default, it is 1000.
It uses a system property, that the tests can override.

# Value Encodings and Framing
Strings are stored as a four byte length followed by the string stored using ASCII.
Longs and doubles are both stored as 8 bytes.

# Byte Order
We use big-endian byte order for all values, as this is the default for Java's DataOutputStream and DataInputStream classes.


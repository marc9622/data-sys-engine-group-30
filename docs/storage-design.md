
# Catalog Storage
One catalog file for all tables,
Format: JSON,
Where? 
# Catalog Contents
The schema for each table, list of data files and partittions belonging to it. 

# Where the Min/Max Summaries Live
In the header per collumn per partition. The choice is rather arbitrary but the rationale is that it is more intuitive and that it will yield better performance for OLAP queries which will try to focus on.

# Restart
It has to read the Catalog file which holds global statitics for all tables, and the header containing statistics per partition per  collumn. 
# Layout Inside a Partition
Columnar format 
# Partition Size

# Value Encodings and Framing

# Byte Order


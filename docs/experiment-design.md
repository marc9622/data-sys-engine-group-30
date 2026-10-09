# Factors and levels:
The experiment will have two factors; maximum number of rows per partition (x axis) and the fraction of partitions read after pruning (y axis). The fraction of partitions read after pruning will be measured as; partitions read / (partitions read + partitions pruned). The x axis will have 10 levels: 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024.

# Procedure
Test data will be generated with a script and a fixed seed. The data will be unsorted.
We will record the mean across 10 runs for all of the levels excluding a singular warmup run.

# Hypothesis
We hypothesize that the fraction of read partitions after pruning will increase as the amount of rows per partition increases.






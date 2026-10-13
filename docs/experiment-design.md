# Factors and levels:
The experiment will have two factors; maximum number of rows per partition (x axis) and the fraction of partitions read after pruning (y axis). The fraction of partitions read after pruning will be measured as; partitions read / (partitions read + partitions pruned). The x axis will have 10 levels: 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024.

# Workload
`scripts/generate_experiment_data.py` generates a 32,768-row `trips` CSV by
default, using seed `30`. Rows are shuffled after generation so the input is
unsorted. The predicate is `distance > 900000`, and approximately 10% of rows
satisfy it.

Generate the workload with:

```sh
python3 scripts/generate_experiment_data.py
```

This command writes the workload to `data/experiment/` by default. That
directory is the input to the experiment:

- `trips.csv` is the generated, shuffled input table. It has the columns
  `city`, `distance`, and `price`, without a header row.
- `experiment.sql` is the SQL script executed by the runner. It creates the
  table, copies in `trips.csv`, and runs the filtered `SELECT`:

  ```sql
  CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
  COPY trips FROM '.../data/experiment/trips.csv';
  SELECT * FROM trips WHERE distance > 900000;
  ```

- `metadata.json` records how the workload was generated, including its row
  count, seed, predicate, and file paths.

For each x-axis level, the runner loads the CSV with
`-DmaxRowsPerPartition=<level>` and runs the SQL script. It uses a fresh
temporary data directory for each run because the engine stores its catalog
and partitions there.

# Procedure
Test data will be generated with a script and a fixed seed. The data will be unsorted.
We will record the mean and population standard deviation across 10 measured
runs for all of the levels, excluding a singular warmup run.

Run the complete sweep from the repository root with:

```sh
python3 scripts/generate_experiment_data.py
python3 scripts/run_experiment.py
```

The runner packages the engine, performs one warmup and then 10 measured runs for
each level, and uses a fresh data directory for every run. It reads the
`READ`/`PRUNED` partition decisions from the engine log. Results are written to
`results/experiment/means.csv` and
`results/experiment/raw.csv` (one row per measured run). The run conditions,
including the commit, OS, machine, Python, and JVM versions, are recorded in
`results/experiment/metadata.json`.

The results directory is separate from `data/experiment/`: `data/experiment/`
contains the input workload, while `results/experiment/` contains measurements
from running that workload.

- `raw.csv` contains one row for every measured run. Its columns include the
  partition-size level, run number, elapsed/planning/execution time, and the
  number and fraction of partitions read or pruned. This is the detailed data
  used to calculate the summary.
- `means.csv` contains one row per partition-size level. It contains the mean
  and standard deviation for elapsed time, planning time, execution time, and
  read fraction, plus the partition counts. For example, its columns include:

  ```text
  partition_rows,runs,mean_elapsed_ms,stddev_elapsed_ms,...
  ```

- `metadata.json` records the conditions of the measurement run, such as the
  commit, operating system, Java/Python versions, warmup count, measured run
  count, and tested partition-size levels.


The elapsed time reported for each run is the sum of the `SELECT` planning and
execution times. It excludes process startup, SQL parsing, `CREATE TABLE`, CSV
loading, partition creation, and process shutdown. The raw results also record
the planning and execution components separately.

Test that is works using a smaller workload and fewer levels/runs:

```sh
python3 scripts/generate_experiment_data.py --rows 1000
python3 scripts/run_experiment.py --runs 2 --levels 2 16 --skip-build
```

# Hypothesis
We hypothesize that the fraction of read partitions after pruning will increase as the amount of rows per partition increases.

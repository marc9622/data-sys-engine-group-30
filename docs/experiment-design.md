# Factors and levels:
The experiment will have two factors; maximum number of rows per partition (x axis) and the fraction of partitions read after pruning (y axis). The fraction of partitions read after pruning will be measured as; partitions read / (partitions read + partitions pruned). The x axis will have 10 levels: 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024.

# Workload
`scripts/generate_experiment_data.py` generates a fixed 1,048,576-row `trips` CSV using seed `30`. Rows are shuffled after generation so the input is unsorted. The predicate is `distance > 900000`; exactly 104,858 rows satisfy it (approximately 10%).

Generate the workload with:

```sh
python3 scripts/generate_experiment_data.py
```

The output directory contains `trips.csv`, `experiment.sql`, and `metadata.json`. For each x-axis level, load the CSV with `-DmaxRowsPerPartition=<level>` and run the generated SQL script. Use a fresh `data/` directory for each level because the engine currently stores its catalog and partitions there and applies the partition size during the copy.

# Procedure
Test data will be generated with a script and a fixed seed. The data will be unsorted.
We will record the mean across 10 runs for all of the levels excluding a singular warmup run.

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

For a quick smoke test, use a smaller workload and fewer levels/runs:

```sh
python3 scripts/generate_experiment_data.py --rows 1000
python3 scripts/run_experiment.py --runs 2 --levels 2 16 --skip-build
```

# Hypothesis
We hypothesize that the fraction of read partitions after pruning will increase as the amount of rows per partition increases.



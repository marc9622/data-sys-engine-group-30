#!/usr/bin/env python3
"""Generate the deterministic CSV workload used by the pruning experiment."""

from __future__ import annotations

import argparse
import csv
import json
import random
from pathlib import Path


DEFAULT_ROWS = 1_048_576
DEFAULT_SEED = 30
MATCHING_FRACTION = 0.10
DISTANCE_CUTOFF = 900_000
CITY_NAMES = ("Aalborg", "Aarhus", "Copenhagen", "Esbjerg", "Odense", "Roskilde")


def generate(output_dir: Path, rows: int, seed: int) -> None:
    if rows <= 0:
        raise ValueError("rows must be positive")

    output_dir.mkdir(parents=True, exist_ok=True)
    rng = random.Random(seed)
    matching_rows = round(rows * MATCHING_FRACTION)
    records: list[tuple[str, int, float]] = []

    for row_number in range(rows):
        city = rng.choice(CITY_NAMES)
        if row_number < matching_rows:
            distance = rng.randint(DISTANCE_CUTOFF + 1, 1_000_000)
        else:
            distance = rng.randint(0, DISTANCE_CUTOFF)
        price = round(10.0 + distance * 0.02 + rng.random() * 100.0, 2)
        records.append((city, distance, price))

    rng.shuffle(records)

    csv_path = output_dir / "trips.csv"
    with csv_path.open("w", newline="", encoding="ascii") as csv_file:
        csv.writer(csv_file, lineterminator="\n").writerows(records)

    sql_path = output_dir / "experiment.sql"
    sql_path.write_text(
        "CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);\n"
        f"COPY trips FROM '{csv_path.resolve()}';\n"
        f"SELECT * FROM trips WHERE distance > {DISTANCE_CUTOFF};\n",
        encoding="ascii",
    )

    metadata = {
        "rows": rows,
        "seed": seed,
        "matchingRows": matching_rows,
        "matchingFraction": matching_rows / rows,
        "predicate": f"distance > {DISTANCE_CUTOFF}",
        "partitionRowLevels": [2**power for power in range(1, 11)],
        "csv": str(csv_path),
        "sql": str(sql_path),
    }
    (output_dir / "metadata.json").write_text(
        json.dumps(metadata, indent=2) + "\n", encoding="ascii"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=Path("data/experiment"))
    parser.add_argument("--rows", type=int, default=DEFAULT_ROWS)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    args = parser.parse_args()
    generate(args.output_dir, args.rows, args.seed)


if __name__ == "__main__":
    main()

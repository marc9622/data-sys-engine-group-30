"""Run the partition-pruning experiment and write CSV results."""

from __future__ import annotations

import argparse
import csv
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path


DEFAULT_LEVELS = (2, 4, 8, 16, 32, 64, 128, 256, 512, 1024)
DECISION_RE = re.compile(r"decision=(READ|PRUNED)")
WORK_DIR_CLEANUP_ATTEMPTS = 3


def run_engine(
    engine: Path, sql: Path, run_dir: Path, level: int
) -> dict[str, float | int]:
    run_dir.mkdir(parents=True, exist_ok=False)
    command = [str(engine), "-f", str(sql)]
    environment = os.environ.copy()
    environment["ENGINE_JAVA_OPTS"] = (
        f"{environment.get('ENGINE_JAVA_OPTS', '')} "
        f"-DmaxRowsPerPartition={level}"
    ).strip()
    start = time.perf_counter()
    completed = subprocess.run(
        command,
        cwd=run_dir,
        env=environment,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    elapsed_ms = (time.perf_counter() - start) * 1000
    if completed.returncode != 0:
        raise RuntimeError(
            f"engine failed for level {level} in {run_dir}:\n{completed.stderr}"
        )

    log_path = run_dir / "logs" / "engine.log"
    decisions = DECISION_RE.findall(log_path.read_text(encoding="utf-8"))
    partitions_read = decisions.count("READ")
    partitions_pruned = decisions.count("PRUNED")
    partitions_total = partitions_read + partitions_pruned
    if partitions_total == 0:
        raise RuntimeError(f"no partition decisions found in {log_path}")

    return {
        "partition_rows": level,
        "partitions_total": partitions_total,
        "partitions_read": partitions_read,
        "partitions_pruned": partitions_pruned,
        "read_fraction": partitions_read / partitions_total,
        "elapsed_ms": elapsed_ms,
    }


def write_csv(path: Path, rows: list[dict[str, object]], fields: list[str]) -> None:
    with path.open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def mean_rows(raw_rows: list[dict[str, object]]) -> list[dict[str, object]]:
    result = []
    for level in sorted({int(row["partition_rows"]) for row in raw_rows}):
        rows = [row for row in raw_rows if int(row["partition_rows"]) == level]
        result.append(
            {
                "partition_rows": level,
                "runs": len(rows),
                "mean_elapsed_ms": sum(float(row["elapsed_ms"]) for row in rows)
                / len(rows),
                "partitions_total": rows[0]["partitions_total"],
                "partitions_read": rows[0]["partitions_read"],
                "partitions_pruned": rows[0]["partitions_pruned"],
                "mean_read_fraction": sum(float(row["read_fraction"]) for row in rows)
                / len(rows),
            }
        )
    return result


def collect_metadata(
    repo_root: Path, runs: int, warmup: int, levels: tuple[int, ...]
) -> dict[str, object]:
    commit = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=repo_root,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    java_process = subprocess.run(
        ["java", "-version"],
        capture_output=True,
        text=True,
        check=False,
    )
    java_version_lines = (
        java_process.stderr.splitlines() or java_process.stdout.splitlines()
    )
    java_version = java_version_lines[0] if java_version_lines else "unknown"
    return {
        "commit": commit,
        "os": platform.platform(),
        "machine": platform.machine(),
        "python": sys.version.split()[0],
        "java": java_version,
        "runs": runs,
        "warmup": warmup,
        "partition_rows_levels": list(levels),
    }


def clean_work_dir(work_dir: Path) -> None:
    for attempt in range(WORK_DIR_CLEANUP_ATTEMPTS):
        try:
            shutil.rmtree(work_dir)
            return
        except OSError as error:
            if attempt == WORK_DIR_CLEANUP_ATTEMPTS - 1:
                raise RuntimeError(
                    f"could not remove existing work directory {work_dir}; "
                    "close applications viewing it and try again"
                ) from error
            time.sleep(0.1)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run one warmup and the measured runs for every partition-size level."
    )
    parser.add_argument("--runs", type=int, default=10, help="measured runs per level")
    parser.add_argument("--warmup", type=int, default=1, help="warmup runs per level")
    parser.add_argument(
        "--levels",
        type=int,
        nargs="+",
        default=DEFAULT_LEVELS,
        help="rows per partition to test",
    )
    parser.add_argument("--output-dir", type=Path, default=Path("results/experiment"))
    parser.add_argument("--work-dir", type=Path, default=None)
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="do not run `mvn -q package` before the experiment",
    )
    args = parser.parse_args()

    if args.runs <= 0 or args.warmup < 0:
        parser.error("--runs must be positive and --warmup cannot be negative")
    if any(level <= 0 for level in args.levels):
        parser.error("all --levels values must be positive")

    repo_root = Path(__file__).resolve().parents[1]
    engine = repo_root / "engine"
    workload_dir = repo_root / "data" / "experiment"
    sql = workload_dir / "experiment.sql"
    if not sql.exists():
        raise SystemExit(
            "workload is missing; run `python3 scripts/generate_experiment_data.py` first"
        )
    if not args.skip_build:
        subprocess.run(["mvn", "-q", "package"], cwd=repo_root, check=True)

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    work_dir = (args.work_dir or output_dir / "runs").resolve()
    if work_dir.exists():
        clean_work_dir(work_dir)
    work_dir.mkdir(parents=True)

    raw_rows: list[dict[str, object]] = []
    for level in args.levels:
        for run_number in range(1, args.warmup + args.runs + 1):
            is_warmup = run_number <= args.warmup
            run_dir = work_dir / f"partition-{level}" / f"run-{run_number}"
            result = run_engine(engine, sql, run_dir, level)
            if not is_warmup:
                raw_rows.append(
                    {
                        "partition_rows": level,
                        "run": run_number - args.warmup,
                        **result,
                    }
                )
            print(
                f"level={level} run={run_number} "
                f"{'warmup' if is_warmup else 'measured'}"
            )

    raw_fields = [
        "partition_rows",
        "run",
        "elapsed_ms",
        "partitions_total",
        "partitions_read",
        "partitions_pruned",
        "read_fraction",
    ]
    means = mean_rows(raw_rows)
    write_csv(output_dir / "raw.csv", raw_rows, raw_fields)
    write_csv(
        output_dir / "means.csv",
        means,
        [
            "partition_rows",
            "runs",
            "mean_elapsed_ms",
            "partitions_total",
            "partitions_read",
            "partitions_pruned",
            "mean_read_fraction",
        ],
    )
    (output_dir / "metadata.json").write_text(
        json.dumps(
            collect_metadata(repo_root, args.runs, args.warmup, tuple(args.levels)),
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"Wrote {output_dir / 'means.csv'}")


if __name__ == "__main__":
    main()

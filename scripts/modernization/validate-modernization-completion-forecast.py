#!/usr/bin/env python3

import argparse
import csv
from decimal import Decimal
from pathlib import Path


EXPECTED_PACKAGES = {
    "5g-1c",
    "5g-1d",
    "5g-1e",
    "5g-1f",
    "5g-2",
    "5g-3",
    "5g-4",
    "5g-5",
    "5g-6",
    "5g-7",
    "5h",
    "6",
    "7",
}
SCENARIOS = ("low", "base", "high")
HISTORICAL_CALENDAR_DAYS = Decimal("16.158483")
SMALL_TEAM_FACTOR = Decimal("0.70")


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--ledger",
        default="docs/modernization/modernization-completion-forecast.csv",
    )
    parser.add_argument(
        "--history",
        default="docs/modernization/modernization-effort-by-pr.csv",
    )
    return parser.parse_args()


def decimal(row, field):
    try:
        return Decimal(row[field])
    except (KeyError, ValueError) as exc:
        raise ValueError(f"{row.get('work_package', '<unknown>')}: invalid {field}") from exc


def validate_rows(rows):
    if not rows:
        raise ValueError("forecast ledger is empty")
    if any(None in row for row in rows):
        raise ValueError("forecast ledger contains unquoted extra CSV fields")

    packages = [row["work_package"] for row in rows]
    if len(packages) != len(set(packages)):
        raise ValueError("forecast ledger contains duplicate work_package rows")

    missing = EXPECTED_PACKAGES - set(packages)
    extra = set(packages) - EXPECTED_PACKAGES
    if missing or extra:
        raise ValueError(
            f"forecast coverage mismatch: missing={sorted(missing)}, extra={sorted(extra)}"
        )

    for row in rows:
        for metric in ("pr", "credits", "solo_weeks"):
            values = [decimal(row, f"{metric}_{scenario}") for scenario in SCENARIOS]
            if not values[0] <= values[1] <= values[2]:
                raise ValueError(
                    f"{row['work_package']}: {metric} must satisfy low <= base <= high"
                )
            if values[0] <= 0:
                raise ValueError(f"{row['work_package']}: {metric} values must be positive")

        reuse = [
            decimal(row, "reuse_low_scenario_pct"),
            decimal(row, "reuse_base_scenario_pct"),
            decimal(row, "reuse_high_scenario_pct"),
        ]
        if not reuse[0] >= reuse[1] >= reuse[2]:
            raise ValueError(
                f"{row['work_package']}: reuse must fall from low to high scenario"
            )


def historical_totals(path):
    with path.open(newline="") as handle:
        rows = list(csv.DictReader(handle))
    prs = [row for row in rows if row["pr_number"].isdigit()]
    if len(prs) != 30:
        raise ValueError(f"historical ledger must contain 30 PR rows, found {len(prs)}")
    credits = sum(Decimal(row["ai_credits"]) for row in rows)
    return len(prs), credits


def main():
    args = parse_args()
    ledger_path = Path(args.ledger)
    history_path = Path(args.history)

    with ledger_path.open(newline="") as handle:
        rows = list(csv.DictReader(handle))
    validate_rows(rows)
    historical_prs, historical_credits = historical_totals(history_path)
    credits_per_week = historical_credits / (HISTORICAL_CALENDAR_DAYS / Decimal(7))

    print(
        "scenario\tremaining_prs\tremaining_credits\tsolo_weeks\t"
        "solo_sprints\tsmall_team_weeks\tsmall_team_sprints\t"
        "raw_throughput_floor_weeks\tcost_weighted_complete_pct"
    )
    for scenario in SCENARIOS:
        prs = sum(decimal(row, f"pr_{scenario}") for row in rows)
        credits = sum(decimal(row, f"credits_{scenario}") for row in rows)
        solo_weeks = sum(decimal(row, f"solo_weeks_{scenario}") for row in rows)
        solo_sprints = solo_weeks / Decimal(2)
        small_team_weeks = solo_weeks * SMALL_TEAM_FACTOR
        small_team_sprints = small_team_weeks / Decimal(2)
        raw_floor = credits / credits_per_week
        complete_pct = historical_credits / (historical_credits + credits) * Decimal(100)
        print(
            f"{scenario}\t{prs:.0f}\t{credits:.0f}\t{solo_weeks:.2f}\t"
            f"{solo_sprints:.2f}\t{small_team_weeks:.2f}\t"
            f"{small_team_sprints:.2f}\t{raw_floor:.2f}\t{complete_pct:.2f}"
        )

    print(
        f"validated {len(rows)} remaining packages against "
        f"{historical_prs} PRs and {historical_credits:.2f} historical credits"
    )


if __name__ == "__main__":
    main()

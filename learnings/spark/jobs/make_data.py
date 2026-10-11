"""Generate a synthetic orders CSV to feed orders_report.py.

    uv run jobs/make_data.py data/orders.csv --rows 1000000

Plain Python, no Spark: the point is to have something to read. Seeded, so
the same --rows always gives the same file and the same report.
"""

import argparse
import csv
import random
from datetime import datetime, timedelta
from pathlib import Path

COUNTRIES = ["ID", "SG", "MY", "JP", "US", "DE", "NL", "AU"]
# Skewed on purpose: a few countries hold most orders, which shows up as
# uneven partitions after a groupBy -- worth spotting in the Spark UI.
COUNTRY_WEIGHTS = [40, 15, 12, 10, 10, 6, 4, 3]
PRODUCTS = {
    "keyboard": 49.0,
    "mouse": 19.0,
    "monitor": 199.0,
    "laptop-stand": 35.0,
    "usb-hub": 25.0,
    "webcam": 59.0,
    "headset": 79.0,
    "desk-lamp": 29.0,
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--rows", type=int, default=100_000)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    rng = random.Random(args.seed)
    start = datetime(2026, 1, 1)
    products = list(PRODUCTS)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["order_id", "ordered_at", "country", "product", "quantity", "unit_price"])
        for order_id in range(1, args.rows + 1):
            product = rng.choice(products)
            writer.writerow([
                order_id,
                (start + timedelta(seconds=rng.randrange(365 * 24 * 3600))).isoformat(),
                rng.choices(COUNTRIES, COUNTRY_WEIGHTS)[0],
                product,
                rng.randint(1, 5),
                PRODUCTS[product],
            ])

    print(f"wrote {args.rows:,} rows to {args.output}")


if __name__ == "__main__":
    main()

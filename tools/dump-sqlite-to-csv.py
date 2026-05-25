import csv
import sqlite3
import sys
from pathlib import Path

def quote_ident(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'

if len(sys.argv) < 2:
    print("Usage: python tools/dump-sqlite-to-csv.py <database-file>")
    sys.exit(1)

db_path = Path(sys.argv[1]).resolve()

if not db_path.exists():
    print(f"Database not found: {db_path}")
    sys.exit(1)

out_dir = db_path.parent / f"{db_path.stem}_csv"
out_dir.mkdir(exist_ok=True)

conn = sqlite3.connect(str(db_path))
conn.row_factory = sqlite3.Row

tables = [
    row["name"]
    for row in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
    )
]

schema_file = out_dir / "_schema.txt"
with schema_file.open("w", encoding="utf-8") as f:
    for row in conn.execute(
        "SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type, name"
    ):
        f.write(f"\n-- {row['type']} {row['name']}\n")
        f.write(row["sql"] + ";\n")

summary_file = out_dir / "_summary.csv"
with summary_file.open("w", newline="", encoding="utf-8-sig") as sf:
    writer = csv.writer(sf)
    writer.writerow(["table", "row_count", "csv_file"])

    for table in tables:
        count = conn.execute(f"SELECT COUNT(*) AS c FROM {quote_ident(table)}").fetchone()["c"]
        csv_file = out_dir / f"{table}.csv"

        cur = conn.execute(f"SELECT * FROM {quote_ident(table)}")
        columns = [d[0] for d in cur.description]

        with csv_file.open("w", newline="", encoding="utf-8-sig") as cf:
            w = csv.writer(cf)
            w.writerow(columns)
            for row in cur:
                w.writerow([row[col] for col in columns])

        writer.writerow([table, count, csv_file.name])
        print(f"{table}: {count} rows -> {csv_file}")

print(f"\nExported CSV directory: {out_dir}")
print(f"Schema: {schema_file}")
print(f"Summary: {summary_file}")
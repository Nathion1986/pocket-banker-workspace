"""cake independent HTTP process; native envelopes and own SQLite ledger."""

from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "common"))
from server import serve

if __name__ == "__main__":
    serve("cake", 8005)

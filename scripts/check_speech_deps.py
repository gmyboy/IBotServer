"""Check if speech deps are needed (exit 1) or not (exit 0). Used by run.bat."""
import sys
from pathlib import Path

import yaml

cfg = yaml.safe_load((Path(__file__).resolve().parent.parent / "config.yaml").read_text(encoding="utf-8"))
enabled = bool(cfg.get("speech", {}).get("enabled", False))
sys.exit(1 if enabled else 0)

#!/usr/bin/env python3
"""Analyse a Cindy debug CSV: signal statistics and a re-run of the rep detector
with adjustable EMA alpha and threshold margin. Optional matplotlib plot.

Usage:
  analyze_csv.py recording.csv [--alpha 0.3] [--margin 0.25] [--min-conf 0.5]
                 [--direction peak|trough|auto] [--low X --high Y] [--plot]
"""
import argparse
import csv
import statistics
import sys


def load(path):
    rows = []
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            rows.append({
                "t": float(r["t"] or 0),
                "raw": float(r["raw"]) if r.get("raw") else None,
                "conf": float(r["confidence"] or 0),
                "event": r.get("event", ""),
                "smoothed_app": float(r["smoothed"]) if r.get("smoothed") else None,
            })
    return rows


def ema(rows, alpha, min_conf):
    value = None
    out = []
    for r in rows:
        if r["raw"] is not None and r["conf"] >= min_conf:
            value = r["raw"] if value is None else value + alpha * (r["raw"] - value)
            out.append(value)
        else:
            out.append(None)
    return out


def detect(rows, smoothed, low, high, direction, min_dur=0.5, max_dur=5.0, stable=10, lost=2.0):
    """Mirror of RepDetector.swift."""
    sign = 1 if direction == "peak" else -1
    lo, hi = (low, high) if sign == 1 else (-high, -low)
    armed, phase, stable_n, start, last_ok = False, "rest", 0, None, None
    reps, rejected, events = [], [], []
    for r, s in zip(rows, smoothed):
        t = r["t"]
        if s is None:
            if not armed:
                stable_n = 0
            if last_ok is not None and t - last_ok > lost:
                if armed:
                    events.append((t, "disarmed"))
                armed, phase, stable_n, start, last_ok = False, "rest", 0, None, None
            continue
        last_ok = t
        v = sign * s
        if not armed:
            stable_n = stable_n + 1 if v < lo else 0
            if stable_n >= stable:
                armed, phase = True, "rest"
                events.append((t, "armed"))
            continue
        if phase == "rest" and v >= lo:
            phase, start = "leaving", t
        elif phase == "leaving":
            if v >= hi:
                phase = "peaked"
            elif v < lo:
                phase, start = "rest", None
        elif phase == "peaked" and v < lo:
            phase = "rest"
            d = t - (start if start is not None else t)
            start = None
            (reps if min_dur <= d <= max_dur else rejected).append((t, d))
    return reps, rejected, events


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv")
    ap.add_argument("--alpha", type=float, default=0.3)
    ap.add_argument("--margin", type=float, default=0.25)
    ap.add_argument("--min-conf", type=float, default=0.5)
    ap.add_argument("--direction", default="auto", choices=["auto", "peak", "trough"])
    ap.add_argument("--low", type=float)
    ap.add_argument("--high", type=float)
    ap.add_argument("--plot", action="store_true")
    args = ap.parse_args()

    rows = load(args.csv)
    if not rows:
        sys.exit("empty file")
    smoothed = ema(rows, args.alpha, args.min_conf)
    valid = [s for s in smoothed if s is not None]
    if not valid:
        sys.exit("no confident samples")
    duration = rows[-1]["t"] - rows[0]["t"]
    coverage = len(valid) / len(rows)
    lo_v, hi_v = min(valid), max(valid)
    baseline = statistics.median(valid[: max(5, int(0.5 * len(rows) / max(duration, 1e-6)))])
    direction = args.direction
    if direction == "auto":
        direction = "peak" if hi_v - baseline >= baseline - lo_v else "trough"
    rng = hi_v - lo_v
    low = args.low if args.low is not None else lo_v + args.margin * rng
    high = args.high if args.high is not None else hi_v - args.margin * rng

    print(f"frames: {len(rows)}  duration: {duration:.1f} s  fps: {len(rows)/max(duration,1e-6):.1f}")
    print(f"confident frames: {coverage*100:.1f} %")
    print(f"signal min/max: {lo_v:.5f} / {hi_v:.5f}  range: {rng:.5f}  baseline: {baseline:.5f}")
    print(f"direction: {direction}  thresholds low/high: {low:.5f} / {high:.5f}  (alpha {args.alpha}, margin {args.margin})")
    app_reps = sum(1 for r in rows if r["event"] == "rep")
    reps, rejected, events = detect(rows, smoothed, low, high, direction)
    print(f"reps (simulated): {len(reps)}   rejected: {len(rejected)}   reps logged by app: {app_reps}")
    if reps:
        durs = [d for _, d in reps]
        print(f"rep duration: mean {statistics.mean(durs):.2f} s  min {min(durs):.2f}  max {max(durs):.2f}")
    for t, name in events:
        print(f"  {t:7.2f} s  {name}")
    for t, d in rejected:
        print(f"  {t:7.2f} s  rejected ({d:.2f} s)")

    if args.plot:
        try:
            import matplotlib.pyplot as plt
        except ImportError:
            sys.exit("matplotlib not installed: pip install matplotlib")
        ts = [r["t"] for r in rows]
        plt.figure(figsize=(14, 5))
        plt.plot(ts, [r["raw"] for r in rows], ".", ms=2, alpha=0.4, label="raw")
        plt.plot(ts, smoothed, label=f"ema α={args.alpha}")
        plt.axhline(low, color="tab:blue", ls="--", label="low")
        plt.axhline(high, color="tab:red", ls="--", label="high")
        for t, _ in reps:
            plt.axvline(t, color="green", alpha=0.3)
        for t, _ in rejected:
            plt.axvline(t, color="orange", alpha=0.3)
        plt.legend()
        plt.xlabel("s")
        plt.title(f"{args.csv}: {len(reps)} reps")
        plt.tight_layout()
        plt.show()


if __name__ == "__main__":
    main()

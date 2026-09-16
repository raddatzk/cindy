#!/usr/bin/env python3
"""Analyse a Cindy debug CSV: signal statistics and a re-run of the rep detector
with adjustable EMA alpha and threshold margin. Optional matplotlib plot.

Usage:
  analyze_csv.py recording.csv [--column raw] [--conf-column C] [--alpha 0.3] [--margin 0.25] [--min-conf 0.5]
                 [--direction peak|trough|auto] [--low X --high Y] [--baseline B [--shift]] [--plot]

--column picks the signal: `raw` (what the app used) or a candidate column such as
pose_shoulder_w, pose_hip_y, luma_mean, luma_center. Pose columns use pose_conf as
confidence and a 5-frame median before the EMA, as the app does for body pose; the
other candidates count as confident whenever they have a value. --baseline B makes the
thresholds relative to rest level B (the calibrated baseline): scaled, as the app does for
the face area and the shoulder width, or with --shift moved by the rest difference, as for
the brightness (luma_mean). The app's BodyEvidence check for brightness reps is not simulated.
"""
import argparse
import csv
import statistics
import sys


def load(path, column="raw", conf_column=None):
    rows = []
    with open(path, newline="") as f:
        reader = csv.DictReader(f)
        if column not in (reader.fieldnames or []):
            sys.exit(f"column {column!r} not in {path}")
        for r in reader:
            value = float(r[column]) if r.get(column) else None
            if conf_column:
                conf = float(r[conf_column]) if r.get(conf_column) else 0.0
            elif column == "raw":
                conf = float(r["confidence"] or 0)
            elif column.startswith("pose_"):
                conf = float(r["pose_conf"]) if r.get("pose_conf") else 0.0
            else:
                conf = 1.0 if value is not None else 0.0
            rows.append({
                "t": float(r["t"] or 0),
                "raw": value,
                "conf": conf,
                "event": r.get("event", ""),
                "smoothed_app": float(r["smoothed"]) if r.get("smoothed") else None,
            })
    return rows


def ema(rows, alpha, min_conf, median=1):
    value = None
    window = []
    out = []
    for r in rows:
        if r["raw"] is not None and r["conf"] >= min_conf:
            window = (window + [r["raw"]])[-median:]
            x = statistics.median(window)
            value = x if value is None else value + alpha * (x - value)
            out.append(value)
        else:
            out.append(None)
    return out


def detect(rows, smoothed, low, high, direction, min_dur=0.5, max_dur=5.0, stable=10, lost=2.0,
           baseline=None, tolerance=2.5, tracking=0.03, shift=False, shift_tolerance=0.1):
    """Mirror of RepDetector.swift. With `baseline` the thresholds are relative: adapted to
    the mean of the arming window (scaled, or shifted with `shift`), scaled ones following
    lower rest values while armed, and a cycle open longer than `max_dur` disarms."""
    sign = 1 if direction == "peak" else -1

    def bounds(l, h):
        return (l, h) if sign == 1 else (-h, -l)

    lo, hi = bounds(low, high)
    armed, phase, window, start, last_ok, rest = False, "rest", [], None, None, None
    reps, rejected, events = [], [], []
    for r, s in zip(rows, smoothed):
        t = r["t"]
        if s is None:
            if not armed:
                window = []
            if last_ok is not None and t - last_ok > lost:
                if armed:
                    events.append((t, "disarmed"))
                armed, phase, window, start, last_ok = False, "rest", [], None, None
            continue
        last_ok = t
        v = sign * s
        if not armed:
            window = (window + [s])[-max(stable, 1):]
            if len(window) < max(stable, 1):
                continue
            cand_lo, cand_hi = bounds(low, high)
            if baseline and shift:
                offset = sum(window) / len(window) - baseline
                if abs(offset) > shift_tolerance:
                    continue
                cand_lo, cand_hi = bounds(low + offset, high + offset)
            elif baseline:
                scale = (sum(window) / len(window)) / baseline
                if not (1 / tolerance <= scale <= tolerance):
                    continue
                cand_lo, cand_hi = bounds(low * scale, high * scale)
            if all(sign * w < cand_lo for w in window):
                armed, phase, (lo, hi) = True, "rest", (cand_lo, cand_hi)
                rest = sum(window) / len(window) if baseline else None
                window = []
                events.append((t, "armed"))
            continue
        if phase == "rest" and v >= lo:
            phase, start = "leaving", t
        elif phase == "rest" and rest is not None and not shift and sign * s < sign * rest:
            rest += tracking * (s - rest)
            lo, hi = bounds(low * rest / baseline, high * rest / baseline)
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
        if baseline and phase != "rest" and start is not None and t - start > max_dur:
            armed, phase, window, start, last_ok = False, "rest", [], None, None
            events.append((t, "disarmed (cycle open too long)"))
    return reps, rejected, events


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv")
    ap.add_argument("--column", default="raw")
    ap.add_argument("--conf-column", help="confidence column (default: confidence / pose_conf)")
    ap.add_argument("--alpha", type=float, default=0.3)
    ap.add_argument("--margin", type=float, default=0.25)
    ap.add_argument("--min-conf", type=float, default=0.5)
    ap.add_argument("--direction", default="auto", choices=["auto", "peak", "trough"])
    ap.add_argument("--low", type=float)
    ap.add_argument("--high", type=float)
    ap.add_argument("--baseline", type=float, help="rest level the thresholds refer to (relative thresholds)")
    ap.add_argument("--shift", action="store_true", help="shift relative thresholds instead of scaling them")
    ap.add_argument("--plot", action="store_true")
    args = ap.parse_args()

    rows = load(args.csv, args.column, args.conf_column)
    if not rows:
        sys.exit("empty file")
    smoothed = ema(rows, args.alpha, args.min_conf, median=5 if args.column.startswith("pose_") else 1)
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
    reps, rejected, events = detect(rows, smoothed, low, high, direction,
                                    baseline=args.baseline, shift=args.shift)
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
        plt.plot(ts, [r["raw"] for r in rows], ".", ms=2, alpha=0.4, label=args.column)
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

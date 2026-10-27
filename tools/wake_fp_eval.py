import argparse
import struct
from pathlib import Path

import numpy as np

from noise_probe_java import samples as noise_probe
from qbe_eval import STEP, embeddings, load, segments, subsequence_dtw

NOISE_MARGIN = 0.05
MIN_THRESHOLD = 0.08
REFRACTORY = 1.5
OFFSET = 0.76


def load_templates(path):
    data = Path(path).read_bytes()
    count, pos = struct.unpack_from(">i", data)[0], 4
    out = []
    for _ in range(count):
        n = struct.unpack_from(">i", data, pos)[0]
        pos += 4
        out.append(np.array(struct.unpack_from(f">{n * 96}f", data, pos), dtype=np.float32).reshape(n, 96))
        pos += n * 96 * 4
    return [t for t in out if len(t) >= 3]


def limits(templates, noise, speech, speech_margin):
    out = []
    for t in templates:
        limit = subsequence_dtw(t, noise).min() - NOISE_MARGIN
        for s in speech:
            limit = min(limit, subsequence_dtw(t, s).min() - speech_margin)
        out.append(limit)
    return np.array(out)


def detections(scores, lims, threshold, votes, vote_hops):
    hits_per_hop = scores < np.minimum(threshold, lims)[:, None]
    last = np.full(len(lims), -10 ** 9)
    need = min(votes, len(lims))
    out, last_wake = [], -1e9
    for i in range(scores.shape[1]):
        last[hits_per_hop[:, i]] = i
        count = int(np.sum(i - last < vote_hops))
        t = i * STEP + OFFSET
        if count > 0 and count >= need and t - last_wake > REFRACTORY:
            out.append((t, float(scores[:, i].min()), count))
            last_wake = t
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--templates", required=True)
    p.add_argument("--speech", nargs="*", default=[])
    p.add_argument("--pos", nargs="*", default=[])
    p.add_argument("--neg", nargs="*", default=[])
    p.add_argument("--thresholds", default="0.12,0.14,0.16,0.18")
    p.add_argument("--margins", default="0.0,0.03,0.05")
    p.add_argument("--votes", default="1,2,3")
    a = p.parse_args()

    templates = load_templates(a.templates)
    noise = embeddings(noise_probe())
    speech = [embeddings(load(f)) for f in a.speech]
    pos = []
    for f in a.pos:
        x = load(f)
        e = embeddings(x)
        pos.append((Path(f).stem, np.stack([subsequence_dtw(t, e) for t in templates]), segments(x)))
    neg, neg_seconds = [], 0.0
    for f in a.neg:
        x = load(f)
        neg_seconds += len(x) / 16000
        neg.append((Path(f).stem, np.stack([subsequence_dtw(t, embeddings(x)) for t in templates])))
    print(f"templates {len(templates)}, speech probes {len(speech)}, negatives {neg_seconds / 60:.1f} min")
    margins = [None] + [float(m) for m in a.margins.split(",")] if speech else [None]
    for margin in margins:
        lims = limits(templates, noise, speech if margin is not None else [], margin or 0)
        usable = int(np.sum(lims >= MIN_THRESHOLD))
        label = "no speech probe" if margin is None else f"speech margin {margin:.2f}"
        print(f"\n{label}: limits {' '.join(f'{v:.3f}' for v in lims)} usable {usable}")
        for votes in [int(v) for v in a.votes.split(",")]:
            for threshold in [float(v) for v in a.thresholds.split(",")]:
                parts = []
                for name, sc, truth in pos:
                    hits = detections(sc, lims, threshold, votes, 4)
                    tp = sum(any(s - 0.3 <= h <= e + 1.2 for h, _, _ in hits) for s, e in truth)
                    parts.append(f"{name} {tp}/{len(truth)}")
                fa = [(name, detections(sc, lims, threshold, votes, 4)) for name, sc in neg]
                total = sum(len(h) for _, h in fa)
                detail = " ".join(f"{n}:{len(h)}" for n, h in fa if h)
                print(f"  votes {votes} thr {threshold:.2f}: {' '.join(parts)} | FA {total} {detail}")


main()

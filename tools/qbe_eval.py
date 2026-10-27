import sys
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort

OWW = Path("build/oww")
mel_model = ort.InferenceSession(str(OWW / "melspectrogram.onnx"))
emb_model = ort.InferenceSession(str(OWW / "embedding_model.onnx"))
RATE = 16000
STEP = 0.08


def load(path):
    with wave.open(str(path)) as w:
        assert w.getframerate() == RATE
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32)


def embeddings(samples):
    mel = mel_model.run(None, {"input": samples[None, :]})[0].squeeze()
    mel = mel / 10 + 2
    windows = [mel[i:i + 76] for i in range(0, len(mel) - 76 + 1, 8)]
    if not windows:
        return np.zeros((0, 96), dtype=np.float32)
    out = emb_model.run(None, {"input_1": np.stack(windows)[..., None]})[0].reshape(len(windows), 96)
    return out / (np.linalg.norm(out, axis=1, keepdims=True) + 1e-9)


def segments(samples, min_len=0.4, max_len=2.5, gap=0.35):
    frame = int(0.03 * RATE)
    rms = np.array([np.sqrt(np.mean(samples[i:i + frame] ** 2)) for i in range(0, len(samples) - frame, frame)])
    level = np.percentile(rms, 95) * 0.15
    active = rms > level
    segs, start, last = [], None, None
    for i, a in enumerate(active):
        if a:
            if start is None:
                start = i
            last = i
        elif start is not None and (i - last) * 0.03 > gap:
            segs.append((start * 0.03, (last + 1) * 0.03))
            start = None
    if start is not None:
        segs.append((start * 0.03, (last + 1) * 0.03))
    return [(a, b) for a, b in segs if min_len <= b - a <= max_len]


def template(samples, seg, pad=0.5):
    a = max(0, int((seg[0] - pad) * RATE))
    b = min(len(samples), int((seg[1] + 0.2) * RATE))
    return embeddings(samples[a:b])


def subsequence_dtw(query, stream):
    cost = 1 - query @ stream.T
    n, m = cost.shape
    acc = np.full((n, m), np.inf)
    acc[0] = cost[0]
    for i in range(1, n):
        prev = acc[i - 1]
        shifted = np.concatenate([[np.inf], prev[:-1]])
        shifted2 = np.concatenate([[np.inf, np.inf], prev[:-2]])
        acc[i] = cost[i] + np.minimum(np.minimum(prev, shifted), shifted2)
    return acc[-1] / n


def scores(templates, stream):
    return np.min([subsequence_dtw(t, stream) for t in templates if len(t) >= 3], axis=0)


def detections(score, threshold, refractory=1.5):
    hits, last = [], -1e9
    for i, s in enumerate(score):
        t = i * STEP + 0.76
        if s < threshold and t - last > refractory:
            window = score[i:i + int(0.5 / STEP)]
            hits.append((t + np.argmin(window) * STEP, float(window.min())))
            last = t
    return hits


def main():
    enroll_path, positives, negatives = sys.argv[1], sys.argv[2].split(","), sys.argv[3].split(",")
    enroll = load(enroll_path)
    segs = segments(enroll)
    templates = [template(enroll, s) for s in segs]
    print(f"enrollment: {len(segs)} utterances")
    pos = []
    for p in positives:
        x = load(p)
        truth = segments(x)
        pos.append((Path(p).name, scores(templates, embeddings(x)), truth))
        print(f"{Path(p).name}: {len(truth)} spoken phrases (energy segments)")
    neg = []
    total_neg = 0.0
    for p in negatives:
        x = load(p)
        total_neg += len(x) / RATE
        neg.append(scores(templates, embeddings(x)))
    print(f"negatives: {total_neg / 60:.1f} min")
    for name, sc, truth in pos:
        best = [float(sc[max(0, int((a - 0.76) / STEP)):int((b + 1.2 - 0.76) / STEP) + 1].min()) for a, b in truth]
        print(f"  {name} best score per phrase: {' '.join(f'{v:.3f}' for v in best)}")
    print(f"  negatives lowest scores: {' '.join(f'{v:.3f}' for v in sorted(np.concatenate(neg))[:8])}")
    for threshold in [0.06, 0.08, 0.10, 0.12, 0.14, 0.16, 0.18]:
        line = []
        for name, sc, truth in pos:
            hits = detections(sc, threshold)
            tp = sum(any(a - 0.3 <= h <= b + 1.2 for h, _ in hits) for a, b in truth)
            line.append(f"{tp}/{len(truth)}")
        fa = sum(len(detections(sc, threshold)) for sc in neg)
        print(f"threshold {threshold:.2f}: positives {' '.join(line)}  false accepts {fa} ({fa / (total_neg / 60):.1f}/min)")


if __name__ == "__main__":
    main()

import json
import re
import sys
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort

RATE = 16000
FRAME = 160
HOP = 1280
STEP = 0.08
THRESHOLD = 0.14
GATE_RMS = 0.0012
OWW = Path("build/oww")
CAPTURES = Path("build/captures")
ENROLL = CAPTURES / "capture-1791577468544-enroll-builtin_mic.wav"
POSITIVES = [
    CAPTURES / "capture-1791577512885-wake-A-builtin_mic.wav",
    CAPTURES / "capture-1791577581536-wake-B-builtin_mic.wav",
    CAPTURES / "capture-1791576448819-builtin_mic.wav",
    CAPTURES / "capture-1791578255598-enroll-builtin_mic.wav",
]
NEGATIVES = [Path("build/stt/rec.wav"), Path("build/neg/en.wav"), Path("build/neg/es.wav")]
LEVELS = [6, 0, -6, -12, -18, -24]

opts = ort.SessionOptions()
opts.intra_op_num_threads = 4
mel_model = ort.InferenceSession(str(OWW / "melspectrogram.onnx"), opts)
emb_model = ort.InferenceSession(str(OWW / "embedding_model.onnx"), opts)


def load(path):
    with wave.open(str(path)) as w:
        assert w.getframerate() == RATE
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768


def db(x):
    return 10 * np.log10(np.mean(x.astype(np.float64) ** 2) + 1e-12)


def frame_levels(x, frame=FRAME):
    n = len(x) // frame
    f = x[: n * frame].reshape(n, frame).astype(np.float64)
    return 10 * np.log10(np.mean(f * f, axis=1) + 1e-12)


def floor_noise(seconds):
    parts = []
    for p in CAPTURES.glob("*builtin_mic.wav"):
        x = load(p)
        lv = frame_levels(x)
        quiet = np.where(lv < -70)[0]
        parts += [x[i * FRAME:(i + 1) * FRAME] for i in quiet]
    noise = np.concatenate(parts)
    reps = int(np.ceil(seconds * RATE / len(noise)))
    return np.tile(noise, reps)


def quantize(x):
    return np.round(np.clip(x, -1, 32767 / 32768) * 32768) / 32768


def boost(gain):
    return lambda x: np.clip(x * gain, -1, 1)


def limit(x, ceiling=0.89):
    a = np.abs(x)
    over = a > ceiling
    y = x.copy()
    y[over] = np.sign(x[over]) * (ceiling + (1 - ceiling) * np.tanh((a[over] - ceiling) / (1 - ceiling)))
    return y


def leveler(target=-26.0, max_gain=30.0, min_gain=-12.0, rise=12.0, fall=30.0, margin=10.0, noise_cap=-62.0,
            floor_rise=2.0, tau=0.4, initial=0.0, peak=False):
    def run(x):
        n = len(x) // FRAME
        out = np.empty(n * FRAME, dtype=np.float32)
        levels = frame_levels(x)
        floor = None
        speech = None
        gain = initial
        a = 1 - np.exp(-FRAME / RATE / tau)
        for i in range(n):
            lvl = levels[i]
            if floor is None or lvl < floor:
                floor = lvl
            else:
                floor += floor_rise * FRAME / RATE
            if lvl > floor + margin and lvl > -80:
                if speech is None:
                    speech = lvl
                elif peak:
                    speech = speech + (lvl - speech) * (0.3 if lvl > speech else a)
                else:
                    speech += (lvl - speech) * a
            desired = gain if speech is None else float(np.clip(target - speech, min_gain, max_gain))
            desired = min(desired, noise_cap - floor)
            if desired > gain:
                new = min(desired, gain + rise * FRAME / RATE)
            else:
                new = max(desired, gain - fall * FRAME / RATE)
            ramp = 10 ** (np.linspace(gain, new, FRAME, endpoint=False) / 20)
            out[i * FRAME:(i + 1) * FRAME] = x[i * FRAME:(i + 1) * FRAME] * ramp
            gain = new
        return limit(out)
    return run


def compressor(threshold=-30.0, ratio=3.0, attack=0.005, release=0.12, makeup=0.0, block=32):
    def run(x):
        n = len(x) // block
        out = np.empty(n * block, dtype=np.float32)
        b = x[: n * block].reshape(n, block)
        peaks = 20 * np.log10(np.abs(b).max(axis=1) + 1e-9)
        at = 1 - np.exp(-block / RATE / attack)
        rl = 1 - np.exp(-block / RATE / release)
        env = -100.0
        prev = makeup
        for i in range(n):
            p = peaks[i]
            env += (p - env) * (at if p > env else rl)
            over = env - threshold
            g = makeup - (over * (1 - 1 / ratio) if over > 0 else 0.0)
            ramp = 10 ** (np.linspace(prev, g, block, endpoint=False) / 20)
            out[i * block:(i + 1) * block] = b[i] * ramp
            prev = g
        return limit(out)
    return run


def chain(*stages):
    def run(x):
        for s in stages:
            x = s(x)
        return x
    return run


def mel(x):
    m = mel_model.run(None, {"input": (x * 32768)[None, :].astype(np.float32)})[0].squeeze()
    return m / 10 + 2


def windows(m):
    return np.stack([m[i:i + 76] for i in range(0, len(m) - 76 + 1, 8)]) if len(m) >= 76 else np.zeros((0, 76, 32), np.float32)


def melnorm(ref=-1.0, up=3.0, down=1.2, pct=90):
    def run(w):
        energy = w.mean(axis=2)
        level = np.percentile(energy, pct, axis=1)
        shift = np.clip(ref - level, -down, up)
        return w + shift[:, None, None]
    return run


def embed(w):
    if len(w) == 0:
        return np.zeros((0, 96), np.float32)
    outs = [emb_model.run(None, {"input_1": w[i:i + 512][..., None].astype(np.float32)})[0].reshape(-1, 96) for i in range(0, len(w), 512)]
    out = np.concatenate(outs)
    return out / (np.linalg.norm(out, axis=1, keepdims=True) + 1e-9)


def segments(samples, min_len=0.4, max_len=2.5, gap=0.35):
    frame = int(0.03 * RATE)
    rms = np.array([np.sqrt(np.mean(samples[i:i + frame] ** 2)) for i in range(0, len(samples) - frame, frame)])
    level = np.sort(rms)[min(int(len(rms) * 0.95), len(rms) - 1)] * 0.15
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
            hits.append(t)
            last = t
    return hits


def gate_open(x):
    n = len(x) // HOP
    h = x[: n * HOP].reshape(n, HOP).astype(np.float64)
    loud = np.sqrt(np.mean(h * h, axis=1)) >= GATE_RMS
    quiet_run, opened = 10 ** 9, 0
    for v in loud:
        quiet_run = 0 if v else quiet_run + 1
        opened += quiet_run <= 10
    return opened / max(n, 1)


def features(x, proc, norm):
    y = quantize(proc(x))
    w = windows(mel(y))
    if norm is not None:
        w = norm(w)
    return y, embed(w)


CHAINS = {
    "raw": (lambda x: x, None),
    "boost2": (boost(2), None),
    "boost4": (boost(4), None),
    "leveler": (leveler(), None),
    "leveler_peak": (leveler(peak=True, target=-20.0), None),
    "lev+comp": (chain(leveler(), compressor()), None),
    "comp+makeup": (compressor(threshold=-50.0, ratio=4.0, makeup=24.0), None),
    "melnorm": (lambda x: x, melnorm()),
}


def wake(names):
    enroll = load(ENROLL)
    positives = [(p.name, load(p)) for p in POSITIVES]
    truths = {name: segments(x) for name, x in positives}
    negatives = [(p.name, load(p)) for p in NEGATIVES]
    negatives = [(n, x * 10 ** ((-41 - np.percentile(frame_levels(x), 95)) / 20)) if n != "rec.wav" else (n, x) for n, x in negatives]
    noise = floor_noise(120)
    total = sum(len(t) for t in truths.values())
    neg_minutes = sum(len(x) for _, x in negatives) / RATE / 60
    report = {}
    for name in names:
        proc, norm = CHAINS[name]
        _, enroll_emb = features(enroll, proc, norm)
        templates = [features(enroll[max(0, int((a - 0.5) * RATE)):min(len(enroll), int((b + 0.2) * RATE))], proc, norm)[1] for a, b in segments(enroll)]
        templates = [t for t in templates if len(t) >= 3]
        rows = []
        for level in LEVELS:
            g = 10 ** (level / 20)
            found, best, out_levels, gates = 0, [], [], []
            for pname, x in positives:
                y, emb = features(x * g + noise[: len(x)], proc, norm)
                sc = scores(templates, emb)
                hits = detections(sc, THRESHOLD)
                for a, b in truths[pname]:
                    lo, hi = max(0, int((a - 0.76) / STEP)), int((b + 1.2 - 0.76) / STEP) + 1
                    best.append(float(sc[lo:hi].min()))
                    found += any(a - 0.3 <= h <= b + 1.2 for h in hits)
                lv = frame_levels(y)
                out_levels.append(np.percentile(lv, 95))
                gates.append(gate_open(y))
            fa, neg_min = 0, []
            for nname, x in negatives:
                y, emb = features(x * g + noise[: len(x)], proc, norm)
                sc = scores(templates, emb)
                fa += len(detections(sc, THRESHOLD))
                neg_min.append(float(sc.min()))
                gates.append(gate_open(y))
            row = dict(level=level, detected=f"{found}/{total}", median_best=round(float(np.median(best)), 3),
                       p90_best=round(float(np.percentile(best, 90)), 3), false_accepts=fa, fa_per_min=round(fa / neg_minutes, 2),
                       min_negative=round(min(neg_min), 3), speech_p95_dbfs=round(float(np.mean(out_levels)), 1),
                       gate_open=round(float(np.mean(gates)), 3))
            rows.append(row)
            print(name, json.dumps(row), flush=True)
        silence = quantize(proc(noise[: 30 * RATE]))
        report[name] = dict(templates=len(templates), rows=rows, silence_gate_open=round(gate_open(silence), 3),
                            silence_dbfs=round(db(silence), 1))
        print(name, "templates", len(templates), "silence gate open", report[name]["silence_gate_open"], "silence dBFS", report[name]["silence_dbfs"], flush=True)
    Path("build/level_eval.json").write_text(json.dumps(report, indent=1))


def stt(names):
    import unicodedata
    import jiwer
    import sherpa_onnx as so
    d = "build/models/stt/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/"
    rec = so.OfflineRecognizer.from_transducer(d + "encoder.int8.onnx", d + "decoder.int8.onnx", d + "joiner.int8.onnx", d + "tokens.txt",
                                               num_threads=4, model_type="nemo_transducer", modeling_unit="bpe")

    def norm(t):
        t = "".join(c for c in unicodedata.normalize("NFD", t.lower()) if unicodedata.category(c) != "Mn")
        t = re.sub(r"[^a-z0-9 ]+", " ", t)
        return re.sub(r"\s+", " ", t).strip()

    x = load("build/stt/rec.wav")
    ref = norm(Path("build/stt/ref.txt").read_text())
    lv = frame_levels(x)
    active = lv > np.percentile(lv, 10) + 15
    segs, start, last = [], None, 0
    for i, a in enumerate(active):
        if a:
            start = i if start is None else start
            last = i
        elif start is not None and i - last > 40:
            segs.append((max(0, start - 20) * FRAME, min(len(x), (last + 20) * FRAME)))
            start = None
    if start is not None:
        segs.append((max(0, start - 20) * FRAME, len(x)))
    noise = floor_noise(60)
    for name in names:
        proc, _ = CHAINS[name]
        for level in [0, -12, -24, -30]:
            y = quantize(proc(x * 10 ** (level / 20) + noise[: len(x)]))
            hyp = []
            for a, b in segs:
                s = rec.create_stream()
                s.accept_waveform(RATE, y[a:b])
                rec.decode_stream(s)
                hyp.append(s.result.text)
            print(name, level, "WER", round(jiwer.wer(ref, norm(" ".join(hyp))) * 100, 1), flush=True)


def streaming_scores(templates, emb, loud_hops):
    tracks = [(t, np.full(len(t), np.inf), np.full(len(t), np.inf)) for t in templates if len(t) >= 3]
    out = np.full(len(emb), np.inf)
    quiet = 10 ** 9
    for i in range(len(emb)):
        hop = i + 9
        quiet = 0 if hop < len(loud_hops) and loud_hops[hop] else quiet + 1
        if quiet > 10:
            for _, prev, before in tracks:
                prev.fill(np.inf)
                before.fill(np.inf)
            continue
        best = np.inf
        for k, (t, prev, before) in enumerate(tracks):
            cost = 1 - t @ emb[i]
            col = np.empty(len(t))
            col[0] = cost[0]
            for j in range(1, len(t)):
                col[j] = cost[j] + min(col[j - 1], prev[j - 1], before[j - 1])
            tracks[k] = (t, col, prev)
            best = min(best, col[-1] / len(t))
        out[i] = best
    return out


def hop_rms(y):
    n = len(y) // HOP
    return np.sqrt(np.mean(y[: n * HOP].reshape(n, HOP).astype(np.float64) ** 2, axis=1))


def loud_hops(spec, y):
    rms = hop_rms(y)
    parts = spec.split(":")
    absolute = float(parts[-1] if parts[0] != "rel" else parts[1])
    if parts[0] != "rel":
        return rms >= absolute
    margin, rise, lowest = float(parts[2]), float(parts[3]), float(parts[4])
    out = np.zeros(len(rms), bool)
    floor = None
    for i, r in enumerate(rms):
        level = 20 * np.log10(r + 1e-9)
        out[i] = r >= absolute or (floor is not None and level >= max(floor, lowest) + margin)
        floor = level if floor is None or level < floor else floor + rise * STEP
    return out


def open_share(loud):
    q, opened = 10 ** 9, 0
    for v in loud:
        q = 0 if v else q + 1
        opened += q <= 10
    return opened / max(len(loud), 1)


def gate(specs):
    enroll = load(ENROLL)
    templates = [features(enroll[max(0, int((a - 0.5) * RATE)):min(len(enroll), int((b + 0.2) * RATE))], lambda x: x, None)[1] for a, b in segments(enroll)]
    positives = [(p.name, load(p)) for p in POSITIVES]
    truths = {name: segments(x) for name, x in positives}
    total = sum(len(t) for t in truths.values())
    noise = floor_noise(120)
    pink = np.cumsum(np.random.default_rng(1).standard_normal(120 * RATE)).astype(np.float32)
    pink -= np.convolve(pink, np.ones(400) / 400, mode="same")
    pink *= 10 ** (-60 / 20) / np.sqrt(np.mean(pink ** 2))
    talk = load(NEGATIVES[0])
    talk = talk * 10 ** ((-60 - np.percentile(frame_levels(talk), 95)) / 20)
    feats = {}
    for level in [0, -12, -18, -24]:
        g = 10 ** (level / 20)
        for pname, x in positives:
            y = quantize(x * g + noise[: len(x)])
            feats[level, pname] = (y, embed(windows(mel(y))))
    base = noise[: len(pink)]
    rooms = [("quiet", base[: 60 * RATE]), ("pink-61", (base + pink)[: 60 * RATE]), ("pink-51", (base + pink * 3.16)[: 60 * RATE]),
             ("talk-60", base[: len(talk)] + talk)]
    for spec in specs:
        line = []
        for level in [0, -12, -18, -24]:
            found = 0
            for pname, _ in positives:
                y, emb = feats[level, pname]
                sc = streaming_scores(templates, emb, loud_hops(spec, y))
                hits = detections(sc, THRESHOLD)
                found += sum(any(a - 0.3 <= h <= b + 1.2 for h in hits) for a, b in truths[pname])
            line.append(f"{level}dB {found}/{total}")
        idle = [f"{name} {open_share(loud_hops(spec, quantize(y))):.2f}" for name, y in rooms]
        print(f"gate {spec}: {' '.join(line)} | idle gate open: {' '.join(idle)}", flush=True)


if __name__ == "__main__":
    if sys.argv[1:2] == ["gate"]:
        gate(sys.argv[2:] or ["0.0012", "0.0006", "0.0003", "rel:0.0012:9:2:-80"])
    elif sys.argv[1:2] == ["stt"]:
        stt(sys.argv[2:] or ["raw", "boost4", "leveler"])
    else:
        wake(sys.argv[1:] or list(CHAINS))

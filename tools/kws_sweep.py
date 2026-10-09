import itertools
import sys
import wave

import numpy as np
import sherpa_onnx

MODEL = "spike/src/main/assets/kws"


def load(path):
    with wave.open(path) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768, w.getframerate()


def spectrum_report(samples, rate):
    spec = np.abs(np.fft.rfft(samples))
    freqs = np.fft.rfftfreq(len(samples), 1 / rate)
    total = spec.sum()
    high = spec[freqs > 4000].sum()
    peak = 20 * np.log10(np.abs(samples).max() + 1e-9)
    return f"peak {peak:.1f} dBFS, energy above 4 kHz {100 * high / total:.1f}%"


def detect(spotter, keywords, samples, rate):
    stream = spotter.create_stream(keywords)
    hits = []
    chunk = rate // 10
    padded = np.concatenate([samples, np.zeros(rate, dtype=np.float32)])
    for start in range(0, len(padded), chunk):
        stream.accept_waveform(rate, padded[start:start + chunk])
        while spotter.is_ready(stream):
            spotter.decode_stream(stream)
            kw = spotter.get_result(stream)
            if kw:
                hits.append(round(start / rate, 1))
                spotter.reset_stream(stream)
    return hits


def tokenize(phrases):
    import sentencepiece as spm
    sp = spm.SentencePieceProcessor(model_file=f"{MODEL}/bpe.model")
    return [" ".join(sp.encode(p.upper(), out_type=str)) for p in phrases]


def main():
    phrases = sys.argv[1].split("|")
    files = sys.argv[2:]
    token_lines = tokenize(phrases)
    for p, t in zip(phrases, token_lines):
        print(f"{p} -> {t}")
    spotter = sherpa_onnx.KeywordSpotter(
        tokens=f"{MODEL}/tokens.txt",
        encoder=f"{MODEL}/encoder.onnx",
        decoder=f"{MODEL}/decoder.onnx",
        joiner=f"{MODEL}/joiner.onnx",
        keywords_file="spike/src/main/assets/kws-default.txt",
        num_threads=1,
    )
    audio = {f: load(f) for f in files}
    for f, (s, r) in audio.items():
        print(f"{f.split('/')[-1]}: {len(s) / r:.1f}s, {spectrum_report(s, r)}")
    for boost, threshold in itertools.product([1.0, 2.0, 3.0, 4.0], [0.05, 0.1, 0.15, 0.25]):
        kw = "/".join(f"{t} :{boost} #{threshold} @X" for t in token_lines)
        counts = [len(detect(spotter, kw, s, r)) for s, r in audio.values()]
        print(f"boost {boost} threshold {threshold}: {counts}")


main()

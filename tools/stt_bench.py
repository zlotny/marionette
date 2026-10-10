import sys, time, re, unicodedata, wave, json
import numpy as np, sherpa_onnx as so, jiwer
M = "build/models/stt/"
WAV = sys.argv[1]
REF = open(sys.argv[2]).read()
TERMS = ["youtube music","arctic monkeys","bohemian rhapsody","queen","whatsapp","javier","deploy","kubernetes","oauth","api gateway","logs","grafana","pull request","github","sdk","stripe","schiphol","amsterdam","dishoom","shoreditch","silverstone","verstappen","hamilton","devops","postgresql","terraform"]
NUM = {"9":"nueve","20":"veinte","4:30":"cuatro y media","4":"cuatro","30":"treinta"}
def norm(t):
    t = t.lower().replace("centraal","central")
    for k in sorted(NUM, key=len, reverse=True): t = re.sub(rf"(?<![\d:]){re.escape(k)}(?![\d:])", NUM[k], t)
    t = "".join(c for c in unicodedata.normalize("NFD", t) if unicodedata.category(c) != "Mn")
    t = re.sub(r"[^a-z0-9 ]+", " ", t)
    return re.sub(r"\s+", " ", t).strip()
with wave.open(WAV) as w: pcm = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768
dur = len(pcm) / 16000
cfg = so.VadModelConfig(); cfg.silero_vad.model = "build/models/silero_vad.onnx"
cfg.silero_vad.min_silence_duration = 0.35; cfg.silero_vad.max_speech_duration = 14; cfg.sample_rate = 16000
vad = so.VoiceActivityDetector(cfg, buffer_size_in_seconds=60)
for i in range(0, len(pcm), 512): vad.accept_waveform(pcm[i:i+512])
vad.flush(); segs = []
while not vad.empty(): segs.append(np.array(vad.front.samples, np.float32)); vad.pop()
T = 4
def mk(name):
    d = M + name + "/"
    if "parakeet" in name: return so.OfflineRecognizer.from_transducer(d+"encoder.int8.onnx", d+"decoder.int8.onnx", d+"joiner.int8.onnx", d+"tokens.txt", num_threads=T, model_type="nemo_transducer", modeling_unit="bpe")
    if "canary" in name: return so.OfflineRecognizer.from_nemo_canary(d+"encoder.int8.onnx", d+"decoder.int8.onnx", d+"tokens.txt", src_lang="es", tgt_lang="es", num_threads=T)
    if "whisper" in name: return so.OfflineRecognizer.from_whisper(d+"turbo-encoder.int8.onnx", d+"turbo-decoder.int8.onnx", d+"turbo-tokens.txt", language="es", num_threads=T)
    if "omnilingual" in name: return so.OfflineRecognizer.from_omnilingual_asr_ctc(d+"model.int8.onnx", d+"tokens.txt", num_threads=T)
    if "qwen3" in name: return so.OfflineRecognizer.from_qwen3_asr(d+"conv_frontend.onnx", d+"encoder.int8.onnx", d+"decoder.int8.onnx", d+"tokenizer", num_threads=T)
out = []
for name in sys.argv[3:]:
    t0 = time.time(); r = mk(name); load = time.time() - t0
    t0 = time.time(); parts = []; worst = 0
    for s in segs:
        a = time.time(); st = r.create_stream(); st.accept_waveform(16000, s); r.decode_stream(st); parts.append(st.result.text.strip()); worst = max(worst, time.time() - a)
    el = time.time() - t0; hyp = " ".join(parts)
    h = norm(hyp); terms = sum(1 for x in TERMS if x in h)
    out.append(dict(model=name, wer=round(jiwer.wer(norm(REF), h) * 100, 1), terms=terms, rtf=round(el / dur, 3), worst_segment_s=round(worst, 2), load_s=round(load, 1), text=hyp))
    print(json.dumps(out[-1], ensure_ascii=False), flush=True)
print("segments", len(segs), [round(len(s)/16000,1) for s in segs])

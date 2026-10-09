import sys
import wave

import numpy as np
import sherpa_onnx

model = sys.argv[1]
rec = sherpa_onnx.OnlineRecognizer.from_transducer(
    tokens=f"{model}/tokens.txt",
    encoder=f"{model}/encoder-epoch-99-avg-1.int8.onnx",
    decoder=f"{model}/decoder-epoch-99-avg-1.onnx",
    joiner=f"{model}/joiner-epoch-99-avg-1.int8.onnx",
    num_threads=2,
    decoding_method="modified_beam_search",
)
for path in sys.argv[2:]:
    with wave.open(path) as w:
        rate = w.getframerate()
        samples = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    stream = rec.create_stream()
    stream.accept_waveform(rate, samples)
    stream.accept_waveform(rate, np.zeros(rate, dtype=np.float32))
    stream.input_finished()
    while rec.is_ready(stream):
        rec.decode_stream(stream)
    print(path.split("/")[-1], "->", rec.get_result(stream))

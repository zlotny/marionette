#!/usr/bin/env python3
"""Export ReDimNet2-B0 to app/src/lab/assets/speaker/redimnet2_b0.onnx.

Usage: export_speaker_model.py REDIMNET2_SRC b0-vox2-lm.pt
REDIMNET2_SRC: checkout of https://github.com/PalabraAI/redimnet2
Weights: https://github.com/PalabraAI/redimnet2/releases/download/v1.0.0/b0-vox2-lm.pt
Needs torch and onnx. Keep the float model: dynamic int8 quantization breaks the embeddings.
"""
import os
import sys

import torch

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "lab", "assets", "speaker", "redimnet2_b0.onnx")


def main():
    source, weights = sys.argv[1:3]
    sys.path.insert(0, os.path.join(source, "src"))
    from redimnet2.redimnet2 import ReDimNet2Wrap

    state = torch.load(weights, map_location="cpu", weights_only=True)
    model = ReDimNet2Wrap(**state["model_config"])
    model.load_state_dict(state["state_dict"])
    model.eval()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    torch.onnx.export(
        model,
        (torch.randn(1, 16000),),
        OUT,
        input_names=["wave"],
        output_names=["emb"],
        dynamic_axes={"wave": {1: "n"}},
        opset_version=18,
        dynamo=False,
    )


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
import argparse
import json
import os
import queue
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PKG = "dev.marionette.debug"
FILES = f"/sdcard/Android/data/{PKG}/files"
SHOW_WINDOW_MS = 400
IDLE_OVERLAY_MS = 6000
CANCEL_HIDE_MS = 1500
SETTLE_S = 7.5
IDLE_PHASES = {"OFF", "PAUSED", "WAITING"}

REPLIES = [
    ("qué hora es", {"text": "Son las diez y cuarto."}),
    ("tokio", {"text": "En Tokio son las seis de la tarde."}),
    ("temporizador", {"tool": "set_timer", "args": {"seconds": 300}, "after": "Temporizador de cinco minutos en marcha."}),
    ("sms", {"tool": "send_sms", "args": {"to": "Lucía Martínez", "text": "Llego tarde"}, "after": "Vale, no lo envío."}),
    ("cuento", {"text": " ".join(["Había una vez un pueblo pequeño junto a un río muy largo, donde cada mañana la gente se despertaba con el canto de los pájaros."] * 6)}),
]

SCENARIOS = [
    {"name": "simple", "trigger": "WAKE_WORD", "stt": ["qué hora es"], "inject": 1, "no_boost": True},
    {"name": "assist_key", "trigger": "ASSIST", "stt": ["qué hora es"], "inject": 1},
    {"name": "launch_then_follow_up", "trigger": "WAKE_WORD", "stt": ["pon un temporizador de cinco minutos"], "inject": 1},
    {"name": "follow_up_speech", "trigger": "WAKE_WORD", "stt": ["qué hora es", "y en Tokio"], "inject": 2},
    {"name": "confirm_by_voice", "trigger": "WAKE_WORD", "stt": ["mándale un sms a Lucía que llego tarde", "no"], "inject": 2},
    {"name": "cancel_while_speaking", "trigger": "WAKE_WORD", "stt": ["cuéntame un cuento"], "inject": 1, "cancel_after_speak_s": 1.5},
    {"name": "no_speech", "trigger": "WAKE_WORD", "stt": [], "inject": 0},
    {"name": "car_speaker_keeps_channel", "trigger": "WAKE_WORD", "stt": ["qué hora es"], "inject": 1,
     "route": ["--ez", "car", "true"], "expect_route": "android-auto 12 null", "expect_cues": True},
    {"name": "car_bluetooth_level", "trigger": "WAKE_WORD", "stt": ["qué hora es"], "inject": 1,
     "route": ["--ez", "car", "true", "--es", "a2dp", "E8:8C"], "expect_route": "bt:E8:8C 1 8", "expect_cues": True, "min_level_db": -18},
    {"name": "bluetooth_level", "trigger": "WAKE_WORD", "stt": ["qué hora es"], "inject": 1,
     "route": ["--es", "a2dp", "E8:8C"], "expect_route": "bt:E8:8C 16 8", "expect_cues": True, "min_level_db": -18},
    {"name": "car_android_auto_level", "trigger": "WAKE_WORD", "stt": ["qué hora es"], "inject": 1,
     "route": ["--ez", "car", "true"], "expect_route": "android-auto 12 null", "expect_cues": True, "min_level_db": -18},
    {"name": "device_volume_lowers", "trigger": "WAKE_WORD", "stt": ["qué hora es"], "inject": 1,
     "route": ["--es", "a2dp", "E8:8C", "--ei", "volume", "-50"], "expect_route": "bt:E8:8C 16 8", "out_db": [-18, -13]},
    {"name": "device_volume_mutes", "trigger": "WAKE_WORD", "stt": ["qué hora es"], "inject": 1,
     "route": ["--es", "a2dp", "E8:8C", "--ei", "volume", "-100"], "expect_route": "bt:E8:8C 16 8", "out_db": [-120, -90]},
    {"name": "phone_volume_raises", "trigger": "WAKE_WORD", "stt": ["qué hora es"], "inject": 1, "phone_volume": 100, "out_db": [-14, -10]},
    {"name": "car_call_link_phone_speaker", "trigger": "WAKE_WORD", "stt": ["qué hora es"], "inject": 1,
     "route": ["--ez", "car", "true", "--es", "sco", "AA:BB", "--ez", "aa_phone", "true"], "expect_route": "android-auto 13 2", "expect_cues": False},
]


class Mock:
    def __init__(self):
        self.transcripts = []
        self.lock = threading.Lock()
        self.log = []

    def next_transcript(self):
        with self.lock:
            return self.transcripts.pop(0) if self.transcripts else ""


MOCK = Mock()


def reply_for(messages):
    last = messages[-1] if messages else {}
    users = [m for m in messages if m.get("role") == "user"]
    text = users[-1].get("content", "") if users else ""
    if isinstance(text, list):
        text = " ".join(part.get("text", "") for part in text if isinstance(part, dict))
    if "</phone_context>" in text:
        text = text.split("</phone_context>", 1)[1]
    text = text.lower()
    for key, reply in REPLIES:
        if key in text:
            if last.get("role") == "tool":
                return {"text": reply.get("after", "Hecho.")}
            return reply
    return {"text": "Vale."}


def sse(handler, chunks):
    handler.send_response(200)
    handler.send_header("Content-Type", "text/event-stream")
    handler.send_header("Cache-Control", "no-cache")
    handler.end_headers()
    for chunk in chunks:
        handler.wfile.write(b"data: " + json.dumps(chunk).encode() + b"\n\n")
        handler.wfile.flush()
        time.sleep(0.02)
    handler.wfile.write(b"data: [DONE]\n\n")
    handler.wfile.flush()


def completion_chunks(reply):
    base = {"id": "mock", "object": "chat.completion.chunk", "model": "mock"}
    if "tool" in reply:
        call = {"index": 0, "id": f"call_{int(time.time() * 1000)}", "type": "function",
                "function": {"name": reply["tool"], "arguments": json.dumps(reply["args"], ensure_ascii=False)}}
        yield {**base, "choices": [{"index": 0, "delta": {"role": "assistant", "tool_calls": [call]}, "finish_reason": None}]}
        yield {**base, "choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}]}
    else:
        text = reply["text"]
        for i in range(0, len(text), 24):
            yield {**base, "choices": [{"index": 0, "delta": {"content": text[i:i + 24]}, "finish_reason": None}]}
        yield {**base, "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]}
    yield {**base, "choices": [], "usage": {"prompt_tokens": 100, "completion_tokens": 10, "total_tokens": 110}}


def completion_full(reply):
    message = {"role": "assistant", "content": reply.get("text")}
    if "tool" in reply:
        message["tool_calls"] = [{"id": "call_1", "type": "function",
                                  "function": {"name": reply["tool"], "arguments": json.dumps(reply["args"], ensure_ascii=False)}}]
    return {"id": "mock", "object": "chat.completion", "model": "mock",
            "choices": [{"index": 0, "message": message, "finish_reason": "tool_calls" if "tool" in reply else "stop"}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 10, "total_tokens": 110}}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def send_json(self, body, status=200):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            return self.send_json({"object": "list", "data": [{"id": "mock", "object": "model"}]})
        self.send_json({"error": "not found"}, 404)

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        if self.path.endswith("/audio/transcriptions"):
            text = MOCK.next_transcript()
            MOCK.log.append(("stt", text))
            return self.send_json({"text": text})
        if self.path.endswith("/chat/completions"):
            request = json.loads(body or b"{}")
            reply = reply_for(request.get("messages", []))
            MOCK.log.append(("llm", reply.get("tool") or reply.get("text", "")[:40]))
            if request.get("stream"):
                self.close_connection = True
                return sse(self, completion_chunks(reply))
            return self.send_json(completion_full(reply))
        self.send_json({"error": "not found"}, 404)


def adb(*args, check=False, capture=True):
    result = subprocess.run(["adb", *args], capture_output=capture, text=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {result.stderr.strip()}")
    return result.stdout if capture else ""


def broadcast(action, *extras):
    adb("shell", "am", "broadcast", "-p", PKG, "-a", f"dev.marionette.{action}", *extras)


class Events:
    def __init__(self):
        self.items = []
        self.lock = threading.Lock()
        self.cond = threading.Condition(self.lock)
        self.proc = None

    def start(self):
        adb("logcat", "-c")
        self.proc = subprocess.Popen(["adb", "logcat", "-v", "raw", "-s", "MarionetteUX:I"], stdout=subprocess.PIPE, text=True)
        threading.Thread(target=self.read, daemon=True).start()

    def read(self):
        for line in self.proc.stdout:
            parts = line.strip().split(" ", 3)
            if len(parts) < 3 or parts[0] != "UXE":
                continue
            with self.cond:
                self.items.append((int(parts[1]), parts[2], parts[3] if len(parts) > 3 else ""))
                self.cond.notify_all()

    def stop(self):
        if self.proc:
            self.proc.terminate()

    def snapshot(self):
        with self.lock:
            return list(self.items)


def check(events, end_ms):
    failures = []
    shown = False
    shown_since = None
    phase = "WAITING"
    idle_since = None
    cancelled = False
    launched = False
    shows = [t for t, e, _ in events if e == "overlay_show"]
    turns_open = 0
    hides = [t for t, e, _ in events if e == "overlay_hide"]

    def overlay_idle_check(t):
        if shown and phase in IDLE_PHASES and idle_since is not None:
            start = max(idle_since, shown_since)
            if t - start > IDLE_OVERLAY_MS:
                failures.append(f"overlay visible {t - start} ms in idle phase from {start}")
                return True
        return False

    reported_idle = False
    for t, event, detail in events:
        if not reported_idle:
            reported_idle = overlay_idle_check(t)
        if event == "overlay_show":
            shown, shown_since, launched = True, t, False
        elif event == "overlay_hide":
            shown = False
        elif event == "phase":
            if detail in IDLE_PHASES and phase not in IDLE_PHASES:
                idle_since = t
            phase = detail
        elif event == "launch":
            launched = True
        elif event == "turn_start":
            cancelled = False
            turns_open += 1
        elif event == "turn_end":
            turns_open -= 1
        elif event == "cancel":
            cancelled = True
            if shown and not any(t <= h <= t + CANCEL_HIDE_MS for h in hides):
                failures.append(f"overlay still visible {CANCEL_HIDE_MS} ms after cancel at {t}")
        elif event == "earcon" and detail == "WAKE":
            if cancelled:
                failures.append(f"listening earcon at {t} after cancel")
            if not shown and not any(t <= s <= t + SHOW_WINDOW_MS for s in shows):
                failures.append(f"listening earcon at {t} without overlay")
        elif event == "speak_start":
            if not shown and not launched and not any(t <= s <= t + SHOW_WINDOW_MS for s in shows):
                failures.append(f"speech at {t} without overlay")
    if not reported_idle:
        overlay_idle_check(end_ms)
    if turns_open != 0:
        failures.append(f"{turns_open} turn(s) without turn_end")
    return failures


def check_route(sc, events):
    failures = []
    routes = {d for _, e, d in events if e == "route"}
    if "expect_route" in sc and routes != {sc["expect_route"]}:
        failures.append(f"route {routes}, expected {sc['expect_route']}")
    levels = [d for _, e, d in events if e == "speech_level"]
    if sc.get("no_boost") and levels:
        failures.append(f"phone speaker boosted: {levels}")
    if "out_db" in sc and (not levels or not sc["out_db"][0] <= int(levels[-1].split()[5]) <= sc["out_db"][1]):
        failures.append(f"speech level {levels}, expected out in {sc['out_db']}")
    if "min_level_db" in sc and (not levels or int(levels[-1].split()[5]) < sc["min_level_db"]):
        failures.append(f"speech level {levels}, expected at least {sc['min_level_db']} dB")
    if "expect_cues" not in sc:
        return failures
    opened = [t for t, e, _ in events if e == "cue_open"]
    closed = [t for t, e, _ in events if e == "cue_close"]
    if not sc["expect_cues"]:
        if opened:
            failures.append("cue channel opened on the phone speaker")
        return failures
    if not opened or not closed:
        return failures + ["cue channel did not open and close"]
    for t, e, d in events:
        if e in ("earcon", "speak_start") and not opened[0] <= t <= closed[-1]:
            failures.append(f"{e} {d} at {t} outside the cue channel")
    return failures


def device_ms():
    out = adb("shell", "cat", "/proc/uptime").split()
    return int(float(out[0]) * 1000)


def screencap(path):
    with open(path, "wb") as f:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f)


def run_scenario(sc, out_dir, events):
    MOCK.transcripts = list(sc["stt"])
    broadcast("DEBUG_ROUTE", *sc.get("route", []))
    broadcast("DEBUG_BACKEND", "--ei", "phone_volume", str(sc.get("phone_volume", 0)))
    MOCK.log.clear()
    shots = os.path.join(out_dir, sc["name"])
    os.makedirs(shots, exist_ok=True)
    adb("shell", "am", "start", "-n", f"{PKG}/dev.marionette.ui.MainActivity")
    broadcast("DEBUG_RESET")
    time.sleep(1.5)
    begin = len(events.snapshot())
    if sc["trigger"] == "ASSIST":
        adb("shell", "input", "keyevent", "219")
    else:
        broadcast("DEBUG_TALK", "--es", "source", sc["trigger"])
    injected = 0
    seen = begin
    deadline = time.time() + 60
    ended_at = None
    cancel_at = None
    shot = 0
    while time.time() < deadline:
        items = events.snapshot()
        for t, event, detail in items[seen:]:
            if event == "earcon" and detail == "WAKE":
                time.sleep(0.3)
                shot += 1
                screencap(os.path.join(shots, f"{shot:02d}_listen.png"))
                if "route" in sc:
                    with open(os.path.join(shots, f"{shot:02d}_audio.txt"), "w") as f:
                        f.write(adb("shell", "dumpsys", "audio"))
                if injected < sc["inject"]:
                    injected += 1
                    broadcast("DEBUG_AUDIO", "--es", "path", "ux_speech.wav")
            elif event == "speak_start":
                shot += 1
                screencap(os.path.join(shots, f"{shot:02d}_speak.png"))
                if sc.get("cancel_after_speak_s") and cancel_at is None:
                    cancel_at = time.time() + sc["cancel_after_speak_s"]
            elif event == "turn_end":
                ended_at = time.time()
        seen = len(items)
        if cancel_at and time.time() >= cancel_at:
            cancel_at = float("inf")
            broadcast("DEBUG_CANCEL")
        if ended_at and time.time() - ended_at >= SETTLE_S:
            break
        time.sleep(0.05)
    shot += 1
    screencap(os.path.join(shots, f"{shot:02d}_end.png"))
    scenario_events = events.snapshot()[begin:]
    failures = check(scenario_events, device_ms())
    failures += check_route(sc, scenario_events)
    if ended_at is None:
        failures.append("turn did not end within 60 s")
    with open(os.path.join(shots, "events.txt"), "w") as f:
        f.writelines(f"{t} {e} {d}\n" for t, e, d in scenario_events)
        f.writelines(f"mock {kind} {text}\n" for kind, text in MOCK.log)
    return failures


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--out", default="build/ux")
    parser.add_argument("--only", default="")
    parser.add_argument("--serve", action="store_true")
    args = parser.parse_args()
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    if args.serve:
        print(f"mock on :{args.port}", flush=True)
        threading.Event().wait()
    url = f"http://10.0.2.2:{args.port}/v1"
    broadcast("DEBUG_BACKEND", "--es", "provider", "CUSTOM", "--es", "url", url, "--es", "model", "mock", "--es", "key", "mock",
              "--ez", "onboarded", "true", "--es", "stt_engine", "CLOUD", "--es", "stt_cloud", "OPENAI", "--es", "stt_model", "gpt-4o-mini-transcribe",
              "--es", "stt_url", url, "--es", "tts_engine", "SYSTEM", "--ei", "follow_up", "4", "--ez", "overlay", "true")
    broadcast("DEBUG_KEY", "--es", "credential", "OPENAI", "--es", "key", "mock")
    events = Events()
    events.start()
    os.makedirs(args.out, exist_ok=True)
    results = []
    for sc in SCENARIOS:
        if args.only and sc["name"] not in args.only.split(","):
            continue
        failures = run_scenario(sc, args.out, events)
        results.append((sc["name"], failures))
        print(f"{'PASS' if not failures else 'FAIL'} {sc['name']}", flush=True)
        for failure in failures:
            print(f"  - {failure}", flush=True)
    events.stop()
    server.shutdown()
    sys.exit(0 if all(not f for _, f in results) else 1)


if __name__ == "__main__":
    main()

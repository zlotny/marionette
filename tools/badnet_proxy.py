import argparse
import asyncio
import random
import time

parser = argparse.ArgumentParser(description="HTTP CONNECT proxy that simulates a bad mobile network")
parser.add_argument("--port", type=int, default=8899)
parser.add_argument("--up", type=float, default=0, help="upload limit in kbit/s, 0 = none")
parser.add_argument("--down", type=float, default=0, help="download limit in kbit/s, 0 = none")
parser.add_argument("--latency", type=float, default=0, help="extra delay per chunk in ms")
parser.add_argument("--drop", type=float, default=0, help="chance that a connection is cut mid-stream")
parser.add_argument("--outage", type=float, default=0, help="refuse every connection for this many seconds after start")
args = parser.parse_args()
started = time.monotonic()


def log(*parts):
    print(f"{time.monotonic() - started:7.1f}", *parts, flush=True)


async def pipe(reader, writer, kbps, cut_after, label):
    queue = asyncio.Queue()

    async def receive():
        sent = 0
        free_at = time.monotonic()
        try:
            while True:
                data = await reader.read(4096)
                if not data:
                    break
                if cut_after is not None and sent + len(data) > cut_after:
                    log("cut", label, "after", sent, "bytes")
                    break
                if kbps:
                    free_at = max(free_at, time.monotonic()) + len(data) * 8 / (kbps * 1000)
                    await asyncio.sleep(max(0, free_at - time.monotonic()))
                await queue.put((time.monotonic() + args.latency / 1000, data))
                sent += len(data)
        except (ConnectionError, asyncio.CancelledError):
            pass
        await queue.put((time.monotonic() + args.latency / 1000, None))

    async def deliver():
        try:
            while True:
                due, data = await queue.get()
                await asyncio.sleep(max(0, due - time.monotonic()))
                if data is None:
                    break
                writer.write(data)
                await writer.drain()
        except (ConnectionError, asyncio.CancelledError):
            pass
        finally:
            writer.close()

    await asyncio.gather(receive(), deliver())


async def handle(client_reader, client_writer):
    try:
        request = await client_reader.readuntil(b"\r\n\r\n")
    except Exception:
        client_writer.close()
        return
    line = request.split(b"\r\n", 1)[0].decode()
    method, target, _ = line.split(" ", 2)
    if args.outage and time.monotonic() - started < args.outage:
        log("outage", target)
        client_writer.close()
        return
    if method != "CONNECT":
        client_writer.write(b"HTTP/1.1 405 Method Not Allowed\r\n\r\n")
        client_writer.close()
        return
    host, port = target.rsplit(":", 1)
    try:
        upstream_reader, upstream_writer = await asyncio.open_connection(host, int(port))
    except Exception as e:
        log("upstream failed", target, e)
        client_writer.close()
        return
    client_writer.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
    await client_writer.drain()
    cut = random.randint(2000, 20000) if random.random() < args.drop else None
    log("open", target, "cut" if cut else "")
    await asyncio.gather(
        pipe(client_reader, upstream_writer, args.up, cut, f"{target} up"),
        pipe(upstream_reader, client_writer, args.down, None, f"{target} down"),
    )


async def main():
    server = await asyncio.start_server(handle, "0.0.0.0", args.port)
    log("listening", args.port, vars(args))
    async with server:
        await server.serve_forever()


asyncio.run(main())

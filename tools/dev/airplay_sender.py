#!/usr/bin/env python3
"""
Fake AirPlay screen-mirroring sender, for testing the muffuqOS receiver without an iPhone.

It speaks the same RTSP/stream protocol as iOS, except for one thing nobody outside Apple can do: produce a valid
FairPlay-wrapped key. Instead it sends a random "ekey" and reads the key the receiver unwrapped from logcat, so it
needs a DEBUG build of the launcher (release builds never log keys). Everything after FairPlay is real: video
encryption, NAL framing, the hardware decoder, the screen.

    pip install cryptography
    python airplay_sender.py --tv 192.168.1.50 --serial 192.168.1.50:5555 --video test.h264 --seconds 20

The .h264 file must be Annex-B without B-frames, e.g.:
    ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30 -t 20 -c:v libx264 -bf 0 -g 60 -f h264 test.h264
"""
import argparse
import hashlib
import os
import plistlib
import re
import socket
import struct
import subprocess
import threading
import time

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

SILENCE_MARKER = bytes([0x00, 0x68, 0x34, 0x00])
VOLUME_MUTE = "volume: -144.000000".encode() + bytes([13, 10])


class Rtsp:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=10)
        self.cseq = 0
        self.host = host

    def request(self, method, uri, body=b"", content_type=None):
        self.cseq += 1
        lines = [f"{method} {uri} RTSP/1.0", f"CSeq: {self.cseq}", "User-Agent: AirPlay/690.7.1",
                 "X-Apple-ProtocolVersion: 1", f"Content-Length: {len(body)}"]
        if content_type:
            lines.append(f"Content-Type: {content_type}")
        self.sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode() + body)
        return self._read()

    def _read(self):
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("receiver closed the connection")
            data += chunk
        head, rest = data.split(b"\r\n\r\n", 1)
        lines = head.decode("latin-1").split("\r\n")
        status = int(lines[0].split()[1])
        headers = {k.strip().lower(): v.strip() for k, v in (l.split(":", 1) for l in lines[1:] if ":" in l)}
        length = int(headers.get("content-length", 0))
        while len(rest) < length:
            rest += self.sock.recv(length - len(rest))
        return status, headers, rest[:length]

    def plist(self, method, uri, value):
        status, headers, body = self.request(method, uri, plistlib.dumps(value, fmt=plistlib.FMT_BINARY),
                                             "application/x-apple-binary-plist")
        parsed = plistlib.loads(body) if body and "plist" in headers.get("content-type", "") else None
        return status, parsed


def ntp_now():
    t = time.time() + 2208988800
    return (int(t) << 32) | int((t % 1) * (1 << 32))


def timing_responder(sock, stop):
    """Answer the receiver's NTP-style timing requests like an iPhone does."""
    sock.settimeout(0.5)
    while not stop.is_set():
        try:
            data, addr = sock.recvfrom(128)
        except socket.timeout:
            continue
        if len(data) >= 32 and data[1] & 0x7F == 0x52:
            reply = bytearray(32)
            reply[0], reply[1], reply[3] = 0x80, 0xD3, 0x07
            reply[8:16] = data[24:32]
            struct.pack_into(">Q", reply, 16, ntp_now())
            struct.pack_into(">Q", reply, 24, ntp_now())
            sock.sendto(bytes(reply), addr)


def split_annexb(data):
    starts = [m.end() for m in re.finditer(b"\x00\x00\x01", data)]
    nals = []
    for i, s in enumerate(starts):
        end = starts[i + 1] - 3 if i + 1 < len(starts) else len(data)
        nal = data[s:end]
        while nal.endswith(b"\x00"):
            nal = nal[:-1]
        nals.append(nal)
    return nals


def access_units(nals):
    """Group NAL units into frames: non-VCL units (SEI...) travel with the next slice, like iOS does."""
    pending = []
    for nal in nals:
        t = nal[0] & 0x1F
        if t in (7, 8):
            continue
        pending.append(nal)
        if t in (1, 5):
            yield pending, t == 5
            pending = []


def session_key_from_logcat(serial, timeout=5):
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = subprocess.run(["adb", "-s", serial, "logcat", "-d"], capture_output=True, text=True,
                             encoding="utf-8", errors="replace").stdout
        found = re.findall(r"session key ([0-9a-f]{32}) iv ([0-9a-f]{32})", out)
        if found:
            key, iv = found[-1]
            return bytes.fromhex(key), bytes.fromhex(iv)
        time.sleep(0.3)
    raise RuntimeError("no session key in logcat: is a DEBUG build installed?")


def send_audio(host, port, key, iv, path, spf, seconds):
    """RTP audio like iOS: AES-CBC per packet (whole blocks only), every packet sent three times (AAC-ELD)."""
    data = open(path, "rb").read()
    frames = []
    pos = 0
    while pos + 2 <= len(data):
        n = struct.unpack(">H", data[pos:pos + 2])[0]
        frames.append(data[pos + 2:pos + 2 + n])
        pos += 2 + n
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    def packet(seq, payload):
        whole = len(payload) // 16 * 16
        enc = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
        body = (enc.update(payload[:whole]) + enc.finalize() if whole else b"") + payload[whole:]
        return struct.pack(">BBHII", 0x80, 0x60, seq & 0xFFFF, (seq * spf) & 0xFFFFFFFF, 0) + body

    for seq in range(3):  # silence markers before audio starts
        sock.sendto(packet(seq, SILENCE_MARKER), (host, port))
    start = time.time()
    seq = 3
    sent = []
    while time.time() - start < seconds:
        payload = frames[(seq - 3) % len(frames)]
        sent.append(packet(seq, payload))
        for p in sent[-3:]:
            sock.sendto(p, (host, port))
        seq += 1
        delay = start + (seq - 3) * spf / 44100 - time.time()
        if delay > 0:
            time.sleep(delay)
    print(f"audio: sent {seq - 3} frames x3")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tv", required=True)
    ap.add_argument("--port", type=int, default=7000)
    ap.add_argument("--serial", required=True, help="adb serial of the TV (to read the debug session key)")
    ap.add_argument("--video", required=True)
    ap.add_argument("--seconds", type=float, default=15)
    ap.add_argument("--fps", type=float, default=30)
    ap.add_argument("--size", type=int, nargs=2, default=(1280, 720), metavar=("W", "H"))
    ap.add_argument("--rotate-to", help="second .h264 to switch to mid-stream, like turning the phone")
    ap.add_argument("--rotate-size", type=int, nargs=2, default=(720, 1280), metavar=("W", "H"))
    ap.add_argument("--rotate-at", type=float, default=5)
    ap.add_argument("--teardown-streams", action="store_true", help="end with a per-stream TEARDOWN first")
    ap.add_argument("--no-key", action="store_true", help="release build: don't read the key from logcat")
    ap.add_argument("--volumes", type=lambda s: [float(x) for x in s.split(",")],
                    help="dB values to send as volume presses, e.g. --volumes=-27,-25.125,-144")
    ap.add_argument("--volume-gap", type=float, default=1.0, help="seconds between volume presses")
    ap.add_argument("--name", default="Test iPhone")
    ap.add_argument("--audio", help="ELD frames as [u16 length][frame]... (see EldTestEncoder in debug builds)")
    ap.add_argument("--spf", type=int, default=480, help="samples per ELD frame in --audio (480 = iOS, 512 = Android encoder)")
    ap.add_argument("--mute", action="store_true", help="send volume -144 so nothing is heard")
    ap.add_argument("--ct", type=int, default=8, help="audio codec: 8 = AAC-ELD (mirroring), 2 = ALAC (music)")
    args = ap.parse_args()

    subprocess.run(["adb", "-s", args.serial, "logcat", "-c"], check=False)
    rtsp = Rtsp(args.tv, args.port)

    status, info = rtsp.plist("GET", "/info", {"qualifier": ["txtAirPlay"]})
    print("GET /info (txtAirPlay):", status, "txt bytes:", len(info.get("txtAirPlay", b"")))
    status, _, body = rtsp.request("GET", "/info")
    info = plistlib.loads(body)
    print("GET /info:", status, info["name"], info["model"], hex(info["features"]), "initialVolume", info.get("initialVolume"),
          info["displays"][0]["width"],
          "x", info["displays"][0]["height"])

    status, _, reply = rtsp.request("POST", "/fp-setup", bytes.fromhex("46504c590301010000000004020002bb"),
                                    "application/octet-stream")
    assert status == 200 and len(reply) == 142, (status, len(reply))
    key_message = b"FPLY" + bytes([3, 1, 3, 0, 0, 0, 0, 0x98]) + os.urandom(152)
    status, _, reply = rtsp.request("POST", "/fp-setup", key_message, "application/octet-stream")
    assert status == 200 and len(reply) == 32 and reply[12:] == key_message[144:], (status, reply.hex())
    print("fp-setup: ok (142 + 32 bytes)")

    timing = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    timing.bind(("", 0))
    stop = threading.Event()
    threading.Thread(target=timing_responder, args=(timing, stop), daemon=True).start()

    eiv = os.urandom(16)
    status, setup = rtsp.plist("SETUP", f"rtsp://{args.tv}/1234567890", {
        "ekey": os.urandom(72), "eiv": eiv, "timingProtocol": "NTP",
        "timingPort": timing.getsockname()[1], "name": args.name, "model": "iPhone15,2",
        "deviceID": "AA:BB:CC:DD:EE:FF", "isScreenMirroringSession": True,
    })
    print("SETUP 1:", status, setup)
    if args.no_key:
        # Release builds never log the key: the stream can't be decoded, but every protocol step still runs.
        key = os.urandom(16)
        print("no session key (release build): video will be dropped as undecodable")
    else:
        key, iv = session_key_from_logcat(args.serial)
        print("session key from receiver:", key.hex())

    stream_id = 0xF00DCAFE12345678  # > 2^63 on purpose: must survive as an unsigned 64-bit id
    streams = [{"type": 110, "streamConnectionID": stream_id, "timestampInfo": []}]
    control = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    control.bind(("", 0))
    if args.audio:
        streams.append({"type": 96, "ct": args.ct, "spf": args.spf, "audioFormat": 0x1000000,
                        "controlPort": control.getsockname()[1], "isMedia": False, "usingScreen": True})
    status, setup = rtsp.plist("SETUP", f"rtsp://{args.tv}/1234567890", {"streams": streams})
    print("SETUP 2:", status, setup)
    data_port = next(s["dataPort"] for s in setup["streams"] if s["type"] == 110)
    if args.mute:
        status, _, _ = rtsp.request("SET_PARAMETER", f"rtsp://{args.tv}/1234567890", VOLUME_MUTE,
                                    "text/parameters")
        print("SET_PARAMETER volume -144:", status)
    if args.audio:
        audio_port = next(s["dataPort"] for s in setup["streams"] if s["type"] == 96)
        threading.Thread(target=send_audio, args=(args.tv, audio_port, key, eiv, args.audio, args.spf, args.seconds),
                         daemon=True).start()
    status, _, _ = rtsp.request("RECORD", f"rtsp://{args.tv}/1234567890")
    print("RECORD:", status)

    volume_thread = None
    if args.volumes:
        def send_volumes():
            # Like pressing the iPhone's volume buttons: one SET_PARAMETER per press.
            for db in args.volumes:
                time.sleep(args.volume_gap)
                status, _, _ = rtsp.request("SET_PARAMETER", f"rtsp://{args.tv}/1234567890",
                                            f"volume: {db:.6f}\r\n".encode(), "text/parameters")
                print(f"SET_PARAMETER volume {db}: {status}")
        volume_thread = threading.Thread(target=send_volumes, daemon=True)
        volume_thread.start()

    skey = hashlib.sha512(f"AirPlayStreamKey{stream_id}".encode() + key).digest()[:16]
    siv = hashlib.sha512(f"AirPlayStreamIV{stream_id}".encode() + key).digest()[:16]
    ctr = Cipher(algorithms.AES(skey), modes.CTR(siv)).encryptor()

    def load(path):
        nals = split_annexb(open(path, "rb").read())
        sps = next(n for n in nals if n[0] & 0x1F == 7)
        pps = next(n for n in nals if n[0] & 0x1F == 8)
        return sps, pps, list(access_units(nals))

    def send_config(sps, pps, size):
        avcc = bytes([1, sps[1], sps[2], sps[3], 0xFF, 0xE1]) + struct.pack(">H", len(sps)) + sps + \
            bytes([1]) + struct.pack(">H", len(pps)) + pps
        header = bytearray(128)
        struct.pack_into("<I", header, 0, len(avcc))
        header[4], header[6], header[7] = 1, 0x16, 0x01
        struct.pack_into("<Q", header, 8, ntp_now())
        for off, val in ((16, size[0]), (20, size[1]), (40, size[0]), (44, size[1]), (56, size[0]), (60, size[1])):
            struct.pack_into("<f", header, off, val)
        video.sendall(bytes(header) + avcc)

    sps, pps, frames = load(args.video)
    video = socket.create_connection((args.tv, data_port))
    send_config(sps, pps, args.size)
    rotated = False

    start = time.time()
    sent = 0
    i = 0
    base = 0
    while time.time() - start < args.seconds:
        if args.rotate_to and not rotated and time.time() - start >= args.rotate_at:
            sps, pps, frames = load(args.rotate_to)
            send_config(sps, pps, args.rotate_size)
            base = i
            rotated = True
            print(f"rotated at frame {i}")
        frame, idr = frames[(i - base) % len(frames)]
        payload = b"".join(struct.pack(">I", len(n)) + n for n in frame)
        header = bytearray(128)
        struct.pack_into("<I", header, 0, len(payload))
        header[4] = 0
        header[5] = 0x10 if idr else 0
        struct.pack_into("<Q", header, 8, ntp_now())
        try:
            video.sendall(bytes(header) + ctr.update(payload))
        except OSError as e:
            print(f"receiver closed the video stream after {sent} frames ({e.__class__.__name__})")
            break
        sent += 1
        i += 1
        delay = start + i / args.fps - time.time()
        if delay > 0:
            time.sleep(delay)
    print(f"sent {sent} frames in {time.time() - start:.1f}s")

    if volume_thread:
        volume_thread.join()
    try:
        if args.teardown_streams:
            # What iOS often does: stop the streams but keep the control connection.
            status, _ = rtsp.plist("TEARDOWN", f"rtsp://{args.tv}/1234567890", {"streams": [{"type": 110}, {"type": 96}]})
            print("TEARDOWN (streams only):", status)
            time.sleep(2)
        status, _, _ = rtsp.request("TEARDOWN", f"rtsp://{args.tv}/1234567890")
        print("TEARDOWN:", status)
    except OSError as e:
        print("TEARDOWN: control connection already closed", e.__class__.__name__)
    stop.set()
    video.close()


if __name__ == "__main__":
    main()

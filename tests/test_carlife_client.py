"""Host-only worker/socket fault checks; no ADB, Android process or HU claim."""
import socket
import io
import hashlib
import json
import struct
import tempfile
import threading
import time
import unittest
from pathlib import Path
from fractions import Fraction
from unittest.mock import patch

from hu_lab.client import HUClient, MediaChunk, ReferenceStartGate, Trace, replay
from hu_lab.protocol import Packet, CMD, VIDEO, VIDEO_DATA, VIDEO_INIT_DONE, VERSION_MATCH, protobuf, ProtocolError
from hu_lab.protocol import MD_INFO, MEDIACODEC_AU_MARKER


def exact(sock, length):
    result = b""
    while len(result) < length:
        chunk = sock.recv(length - len(result))
        if not chunk:
            raise EOFError()
        result += chunk
    return result


def read_packet(sock):
    channel, length = struct.unpack(">II", exact(sock, 8))
    return channel, exact(sock, length)


def wait(predicate, timeout=3):
    deadline = time.monotonic() + timeout
    while not predicate():
        if time.monotonic() >= deadline:
            raise AssertionError("timed out waiting for local test worker")
        time.sleep(.01)


def socket_pressure_probe():
    """OFFLINE_INTEGRATION: kernel pressure, actual HU writer, bounded stop."""
    started = time.monotonic()
    report = {"classification": "OFFLINE_INTEGRATION", "device_access": False,
              "pressure_precondition": "NOT_EXERCISED", "stop_verdict": "NOT_EXERCISED"}
    left, right = socket.socketpair()
    left.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
    right.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
    left.setblocking(False)
    filled = 0
    would_block = False
    try:
        # Peer deliberately does not read. EWOULDBLOCK is measured, not inferred
        # from elapsed time or a synthetic queue exception.
        while filled < 1024 * 1024 and time.monotonic() - started < 1:
            try:
                filled += left.send(b"x" * 8192)
            except BlockingIOError:
                would_block = True
                break
        left.setblocking(True)
        report.update(kernel_send_would_block=would_block, kernel_prefill_bytes=filled)
        if not would_block:
            return report
        with tempfile.TemporaryDirectory() as output:
            client = HUClient("127.0.0.1", 1, output)
            client.sock = left
            worker = threading.Thread(target=client._guard, args=(client._writer,),
                                      name="offline-pressure-writer", daemon=True)
            client.threads = [worker]
            client.send(Packet(CMD, MD_INFO, b"p" * 8192))
            worker.start()
            wait(lambda: client.pipeline_stages.get(worker.name, {}).get("stage") == "socket_write", 1)
            for _ in range(client.commands.maxsize):
                client.send(Packet(CMD, MD_INFO, b"q"))
            queue_full = False
            try:
                client.send(Packet(CMD, MD_INFO, b"overflow"))
            except ProtocolError as error:
                queue_full = "command queue full" in str(error)
            before = client.progress_snapshot()
            blocked = worker.is_alive() and client.progress_counts["socket_write"] == 0
            report.update(command_queue_full=queue_full, writer_blocked=blocked,
                          progress_before_stop=before)
            if queue_full and blocked:
                report["pressure_precondition"] = "PASS"
            stop_start = time.monotonic()
            client.stop()
            report["stop_seconds"] = time.monotonic() - stop_start
            right.settimeout(.5)
            peer_eof = False
            drained = 0
            while time.monotonic() - stop_start < 2:
                chunk = right.recv(65536)
                if not chunk:
                    peer_eof = True
                    break
                drained += len(chunk)
            report.update(peer_eof=peer_eof, peer_drained_bytes=drained,
                          workers_alive=client.summary()["workers_alive"],
                          client_error=client.error)
            if report["pressure_precondition"] == "PASS":
                report["stop_verdict"] = "PASS" if peer_eof and not worker.is_alive() and report["stop_seconds"] < 2 else "FAIL"
        return report
    finally:
        left.close()
        right.close()
        report["elapsed_seconds"] = time.monotonic() - started


class ClientTests(unittest.TestCase):
    def test_first_large_au_with_sps_is_not_rejected_as_missing_sps(self):
        fixture = Path(__file__).resolve().parents[1] / "android-md/tests/fixtures/mode-qcom-captured-1920x1080-l40.csd.hex"
        csd = bytes.fromhex(fixture.read_text())
        with tempfile.TemporaryDirectory() as output:
            client = HUClient("127.0.0.1", 1, output)
            client.session.accepted = {"width": 1920, "height": 1080, "fps": 30}
            client._inspect_sps(csd + b"\x00\x00\x00\x01\x65" + b"\x55" * (1024 * 1024))
            self.assertEqual(client.sps["display_width"], 1920)
            self.assertFalse(client.sps_validation["failures"])
            self.assertFalse(client._sps_prefix)
            client.stop()

    def test_large_bootstrap_without_sps_remains_bounded(self):
        with tempfile.TemporaryDirectory() as output:
            client = HUClient("127.0.0.1", 1, output)
            with self.assertRaisesRegex(ProtocolError, "no complete SPS"):
                client._inspect_sps(b"\x55" * (1024 * 1024 + 1))
            client.stop()

    def test_real_socket_pressure_and_queue_full_stop(self):
        result = socket_pressure_probe()
        if result["pressure_precondition"] != "PASS":
            self.skipTest("NOT_EXERCISED: kernel blocked-write and queue-full pressure not established")
        self.assertEqual(result["stop_verdict"], "PASS", result)
        self.assertFalse(result["workers_alive"])

    def test_progress_snapshot_bounded_ring_and_actual_queue_highwater(self):
        with tempfile.TemporaryDirectory() as output:
            client = HUClient("127.0.0.1", 1, output)
            client._progress("socket_read_bytes", 120)
            client.framing_pending_bytes = 9
            client.media.put(MediaChunk(0, b"abcd"))
            client.media.get()
            for _ in range(70):
                client.last_snapshot_ns = 0
                snapshot = client.progress_snapshot()
            self.assertEqual(len(client.progress_history), 65)
            self.assertEqual(snapshot["counts"]["socket_read_bytes"], 120)
            self.assertEqual(snapshot["framing_pending_bytes"], 9)
            self.assertEqual(snapshot["media_queue"]["queued_bytes"], 0)
            self.assertEqual(snapshot["media_queue"]["high_water_bytes"], 4)
            self.assertEqual(snapshot["media_queue"]["maximum_age_ms"], 1500)
            self.assertEqual(snapshot["session_id"], client.summary()["session_id"])
            client.halt.set()
            client.stop()
            events = [json.loads(row) for row in (Path(output) / "trace.jsonl").read_text().splitlines()]
            self.assertEqual(events[0]["session_id"], client.session_id)
            self.assertFalse(json.loads((Path(output) / "summary.json").read_text())["workers_alive"])

    def test_blocked_diagnostic_file_does_not_block_trace_callers(self):
        entered, release = threading.Event(), threading.Event()
        class BlockingFile:
            def __init__(self, target): self.target = target
            def write(self, value):
                entered.set()
                if not release.wait(2): raise AssertionError("test release timeout")
                return self.target.write(value)
            def flush(self): return self.target.flush()
            def close(self): return self.target.close()
        with tempfile.TemporaryDirectory() as output:
            trace = Trace(output)
            trace.logfile = BlockingFile(trace.logfile)
            caller_start = time.monotonic_ns()
            trace.event("blocked_file")
            try:
                self.assertTrue(entered.wait(1))
                for _ in range(20):
                    trace.wire("rx", b"x" * 65536)
                trace.event("caller_remains_live")
                snapshot = trace.progress_snapshot()
                self.assertLessEqual(snapshot["queued_bytes"], snapshot["maximum_bytes"])
                self.assertTrue(snapshot["active_io"])
                self.assertIn("rx", trace.raw_truncated)
                self.assertLess(time.monotonic_ns() - caller_start, 1_000_000_000)
            finally:
                release.set()
                trace.close()
            event = json.loads((Path(output) / "trace.jsonl").read_text().splitlines()[0])
            self.assertGreaterEqual(event["monotonic_ns"], caller_start)
            self.assertLess(event["monotonic_ns"], snapshot["active_io"]["started_ns"])
            self.assertFalse(trace.writer.is_alive())

    def test_first_worker_queue_error_closes_peer_and_records_age_reason(self):
        left, right = socket.socketpair()
        right.settimeout(.25)
        with tempfile.TemporaryDirectory() as temp:
            client = HUClient("127.0.0.1", 1, temp)
            client.sock = left
            client.media.put(MediaChunk(0, b"old"))
            with client.media.condition:
                _, item = client.media.items.popleft()
                client.media.items.append((time.monotonic() - 2, item))
            client._guard(lambda: client.media.put(MediaChunk(0, b"new")))
            # No stop(), Qt interaction or future timeout is needed for peer EOF.
            self.assertEqual(right.recv(1), b"")
            first = client.error
            client._guard(lambda: (_ for _ in ()).throw(ProtocolError("later failure")))
            self.assertEqual(client.error, first)
            self.assertEqual(client.session.state, "ERROR")
            client.stop()
            events = [json.loads(line) for line in (Path(temp) / "trace.jsonl").read_text().splitlines()]
            errors = [r for r in events if r["event"] == "error"]
            self.assertEqual(len(errors), 1)
            self.assertTrue(errors[0]["socket_shutdown"])
            self.assertEqual(errors[0]["queue_evidence"]["reason"], "age")
            self.assertEqual(errors[0]["queue_evidence"]["queued_bytes"], 3)
            self.assertGreaterEqual(errors[0]["queue_evidence"]["oldest_age_ms"], 2000)
            self.assertEqual(errors[0]["queue_evidence"]["maximum_age_ms"], 1500)
        right.close()

    def test_slow_png_does_not_hold_decoder_state_and_pending_snapshots_are_bounded(self):
        import av
        entered, release = threading.Event(), threading.Event()
        real = av.VideoFrame(1280, 720, "yuv420p")
        for plane in real.planes:
            plane.update(bytes(plane.buffer_size))
        class SlowFrame:
            width, height, key_frame, pts = 1280, 720, False, None
            def reformat(self, **kwargs):
                return real.reformat(**kwargs)
            def to_image(self):
                entered.set()
                if not release.wait(2):
                    raise AssertionError("PNG worker release timeout")
                return real.to_image()
        with tempfile.TemporaryDirectory() as temp:
            client = HUClient("127.0.0.1", 1, temp)
            with client.state_lock:
                client._frame(SlowFrame())
            self.assertTrue(entered.wait(1))
            # A second decoder picture publishes while first PNG IO is blocked.
            with client.state_lock:
                client._frame(real)
            self.assertEqual(client.latest_frame[-1], 2)
            for index in range(30, 630, 30):
                client._queue_png("last-frame.png", real, {"frame_index": index, "generation": 0})
            self.assertEqual(len(client.png_pending), 1)
            self.assertEqual(client.png_counts["coalesced"], 19)
            release.set()
            client.halt.set()
            client.stop()
            self.assertTrue((Path(temp) / "first-frame.png").exists())
            self.assertTrue((Path(temp) / "last-frame.png").exists())
            self.assertEqual(client.png_counts["written"], 2)
            self.assertFalse(client.summary()["workers_alive"])
            self.assertIn("evidence_png_write", client.summary()["pipeline_timing_metrics"])

    def test_decoded_observer_completes_before_gui_frame_publication(self):
        import av
        entered, release = threading.Event(), threading.Event()
        correlation = {}
        def callback(record, pixels, width, height, stride):
            correlation[record["frame_index"]] = record
            entered.set()
            if not release.wait(2):
                raise AssertionError("callback test release timeout")
        with tempfile.TemporaryDirectory() as temp:
            client = HUClient("127.0.0.1", 1, temp, on_decoded_frame=callback)
            frame = av.VideoFrame(1280,720,"yuv420p")
            for plane in frame.planes: plane.update(bytes(plane.buffer_size))
            worker = threading.Thread(target=client._guard,args=(lambda: client._frame(frame),))
            worker.start()
            self.assertTrue(entered.wait(2))
            with client.frame_lock: self.assertIsNone(client.latest_frame)
            self.assertIn(1,client.frame_records)
            self.assertEqual(correlation[1]["generation"],0)
            release.set();worker.join(2)
            self.assertFalse(worker.is_alive())
            self.assertIsNone(client.error)
            self.assertEqual(client.latest_frame[-1],1)
            self.assertIn(client.latest_frame[-1],correlation)
            client.halt.set();client.stop()


    @staticmethod
    def baseline_au():
        """Independent libx264 output, not our H.264 parser's own generator."""
        import av
        codec = av.CodecContext.create("libx264", "w")
        codec.width, codec.height, codec.pix_fmt = 1280, 720, "yuv420p"
        codec.time_base, codec.framerate = Fraction(1, 30), Fraction(30, 1)
        codec.thread_count = 1
        codec.options = {"profile": "baseline", "level": "3.1", "preset": "ultrafast", "tune": "zerolatency"}
        frame = av.VideoFrame(1280, 720, "yuv420p")
        for index, plane in enumerate(frame.planes):
            plane.update(bytes([16 if index == 0 else 128]) * plane.buffer_size)
        frame.pts = 0
        packets = codec.encode(frame) + codec.encode(None)
        if len(packets) != 1:
            raise AssertionError("fixture must contain one encoder access unit")
        return bytes(packets[0])

    def test_complete_au_first_static_picture_before_eof(self):
        data = self.baseline_au()
        left, right = socket.socketpair()
        allow_eof = threading.Event()
        errors = []

        def cmd(message, payload):
            return struct.pack(">IIHHI", 1, len(payload) + 8, len(payload), 0, message) + payload

        def peer():
            try:
                read_packet(right)
                right.sendall(bytes.fromhex("000000010000000a00020000000100020801"))
                read_packet(right)
                read_packet(right)
                # Explicit optional DeviceInfo.product field15, one length-delimited value.
                marker = MEDIACODEC_AU_MARKER.encode()
                right.sendall(cmd(0x10004, bytes([0x7a, len(marker)]) + marker))
                right.sendall(bytes.fromhex("0000000100000010000800000001000808800a10d005181e"))
                read_packet(right)
                inner = struct.pack(">III", len(data), 17000, 0x20001) + data
                right.sendall(struct.pack(">II", 2, len(inner)) + inner)
                # Keep the peer open. No next AU, end marker, flush or EOF is supplied.
                allow_eof.wait(3)
            except Exception as error:
                errors.append(error)
            finally:
                right.close()

        thread = threading.Thread(target=peer)
        thread.start()
        with tempfile.TemporaryDirectory() as output:
            client = HUClient("127.0.0.1", 1, output)
            with patch("hu_lab.client.socket.create_connection", return_value=left):
                client.start()
            try:
                wait(lambda: client.decoded_frames == 1 or client.error)
                self.assertFalse(errors)
                self.assertIsNone(client.error)
                self.assertFalse(client.peer_eof_seen)
                self.assertFalse(allow_eof.is_set())
                self.assertTrue(client.summary()["first_frame_decoded_before_eof"])
                self.assertEqual(client.sps_validation["status"], "PASS_BASELINE_L31_MAXFS")
                self.assertEqual(client.sps_validation["coded_macroblocks"], 3600)
                self.assertEqual(client.sps_validation["level_max_mbps"], 108000)
                self.assertEqual(client.frame_records[1]["encoder_pts_ms"], 17000)
                record = client.frame_records[1]
                client.mark_presented(1, record["host_decode_ns"] + 20000000)
                self.assertEqual(client.host_timing_metrics()["decode_to_qt_paint"]["max_ms"], 20)
                self.assertEqual(client.pts_metrics()[0]["idr_access_units"], 1)
            finally:
                client.stop()
                allow_eof.set()
                thread.join(timeout=2)

    def test_complete_au_requires_explicit_profile_and_idr_reference(self):
        with tempfile.TemporaryDirectory() as output:
            client = HUClient("127.0.0.1", 1, output)
            client.session.state = "STREAMING"
            client._receive_packet(Packet(CMD, MD_INFO, protobuf(f15=MEDIACODEC_AU_MARKER)), io.BytesIO())
            self.assertEqual(client.video_packetization, "ANNEXB_COMPLETE_AU")
            with self.assertRaisesRegex(ProtocolError, "SPS/PPS"):
                client._receive_packet(Packet(VIDEO, VIDEO_DATA, bytes.fromhex("000000014199")), io.BytesIO())
            client.halt.set()
            client.stop()

    def test_pts_wrap_and_monotonicity(self):
        data = self.baseline_au()
        with tempfile.TemporaryDirectory() as output:
            client = HUClient("127.0.0.1", 1, output)
            client.session.state = "STREAMING"
            client._receive_packet(Packet(CMD, MD_INFO, protobuf(f15=MEDIACODEC_AU_MARKER)), io.BytesIO())
            for timestamp in (0xFFFFFFF0, 17, 50):
                client._receive_packet(Packet(VIDEO, VIDEO_DATA, data, timestamp), io.BytesIO())
            metric = client.pts_metrics()[0]
            self.assertTrue(metric["strictly_increasing"])
            self.assertEqual(metric["pts_active_interval_ms"], 66)
            self.assertEqual(metric["idr_access_units"], 3)
            with self.assertRaisesRegex(ProtocolError, "backwards"):
                client._receive_packet(Packet(VIDEO, VIDEO_DATA, data, 49), io.BytesIO())
            client.halt.set()
            client.stop()

    def test_mbps_quantization_boundary_not_false_pass(self):
        with tempfile.TemporaryDirectory() as output:
            client = HUClient("127.0.0.1", 1, output)
            client.sps_validation = {"coded_macroblocks": 3600, "level_max_mbps": 108000}
            client.au_records = [{"generation": 0, "pts_ms": n * 1000 // 30, "idr": n == 0} for n in range(301)]
            for record in client.au_records:
                client._record_pts(record)
            metric = client.pts_metrics()[0]
            self.assertEqual(metric["encoder_output_fps_from_pts_ms"], 30)
            self.assertEqual(metric["mbps_check"], "BORDERLINE_1MS_QUANTIZATION_USE_MD_PTS_US")
            client.halt.set()
            client.stop()



    def test_peer_truncated_eof(self):
        left, right = socket.socketpair()
        with tempfile.TemporaryDirectory() as output:
            client = HUClient("127.0.0.1", 1, output)
            with patch("hu_lab.client.socket.create_connection", return_value=left):
                client.start()
            read_packet(right)  # ensure initial send completed before peer close
            right.sendall(bytes.fromhex("000000010000000a0002"))
            right.close()
            wait(lambda: client.error is not None)
            self.assertIn("truncated EOF", client.error)
            client.stop()
            self.assertFalse(client.summary()["workers_alive"])

    def test_handshake_stall_timeout(self):
        left, right = socket.socketpair()
        with tempfile.TemporaryDirectory() as output:
            client = HUClient("127.0.0.1", 1, output, timeout=.05)
            with patch("hu_lab.client.socket.create_connection", return_value=left):
                client.start()
            wait(lambda: client.error is not None)
            self.assertIn("handshake timeout", client.error)
            client.stop()
            right.close()
            self.assertFalse(client.summary()["workers_alive"])

    def test_decode_failure_visible(self):
        import av
        with tempfile.TemporaryDirectory() as output:
            client = HUClient("127.0.0.1", 1, output)
            context = av.CodecContext.create("h264", "r")
            # IDR lacking any SPS/PPS. Must fail rather than invent an image.
            client._guard(lambda: context.decode(av.Packet(bytes.fromhex("0000000165ff"))))
            self.assertTrue(client.error)
            self.assertEqual(client.session.state, "ERROR")
            client.stop()

    def test_reset_inflight_video_and_decoder_generation_regression(self):
        data = self.baseline_au()
        with tempfile.TemporaryDirectory() as output:
            client = HUClient("127.0.0.1", 1, output, width=1280, height=720)
            client.session.start()
            client.session.receive(Packet(CMD, VERSION_MATCH, b"\x08\x01"))
            init = Packet(CMD, VIDEO_INIT_DONE, protobuf(f1=1280, f2=720, f3=30))
            client.session.receive(init)
            retained = io.BytesIO()
            client._receive_packet(Packet(VIDEO, VIDEO_DATA, data), retained)
            self.assertGreater(client.media.size, 0)
            client.reset()
            self.assertEqual(client.media.size, 0)
            self.assertEqual(client.decoder_generation, 1)
            # Reproduce observed old video arriving after local RESET and before INIT_DONE.
            dependent = Packet(VIDEO, VIDEO_DATA, bytes.fromhex("0000000141deadbeef"))
            client._receive_packet(dependent, retained)
            self.assertEqual(client.old_generation_chunks_dropped, 1)
            self.assertEqual(client.session.state, "WAIT_INIT")
            self.assertEqual(client.media.size, 0)
            client._receive_packet(init, retained)
            self.assertEqual(client.session.state, "STREAMING")
            # Even if a prior-dependent chunk slips after ACK, no decode before SPS/PPS/IDR.
            client._receive_packet(dependent, retained)
            self.assertEqual(client.media.size, 0)
            # Simulate a decoder worker having retrieved an old-generation queued item.
            client.media.put(MediaChunk(0, b"not a valid H264 stream"))
            client._receive_packet(Packet(VIDEO, VIDEO_DATA, data), retained)
            client.media.close()
            client._decoder()
            self.assertEqual(client.decoded_frames, 1)
            self.assertEqual(client.generation_frames, {1: 1})
            self.assertIsNone(client.error)
            client.halt.set()
            client.stop()

    def test_reference_gate_fragmentation_and_no_dependent_frames(self):
        sps = bytes.fromhex("000000016764001f")
        pps = bytes.fromhex("0000000168ce")
        old = bytes.fromhex("00000001419988")
        idr = bytes.fromhex("000000016588")
        gate = ReferenceStartGate()
        self.assertIsNone(gate.feed(old))
        output = None
        for byte in sps + pps + old + idr:
            value = gate.feed(bytes([byte]))
            if value is not None:
                output = (output or b"") + value
        self.assertEqual(output, sps + pps + idr)
        self.assertTrue(gate.ready)
        self.assertEqual(gate.feed(b"tail"), b"tail")

    def test_reference_gate_is_bounded(self):
        with self.assertRaisesRegex(ProtocolError, "SPS/PPS/IDR"):
            ReferenceStartGate(maximum=4).feed(b"12345")


if __name__ == "__main__":
    unittest.main()

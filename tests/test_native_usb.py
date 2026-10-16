import dataclasses
import hashlib
import json
from pathlib import Path
import queue
import tempfile
import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from hu_lab.client import HUClient
from hu_lab.audio import MEDIA_INIT, MEDIA_DATA
from hu_lab.native_usb import ScopedUSBAdmission, NativeUSBTransportFactory, USBStream
from hu_lab.protocol import (Packet, FrameParser, CMD, TOUCH, VIDEO, MEDIA, VIDEO_DATA, VIDEO_HEARTBEAT, HU_VERSION, VERSION_MATCH,
    MD_INFO, VIDEO_INIT, VIDEO_INIT_DONE, VIDEO_START, VIDEO_PAUSE, VIDEO_RESET, PAUSE_MEDIA,
    HARD_KEY, HOME_KEY, BACK_KEY, protobuf, fields, NATIVE_MEDIACODEC_AU_MARKER,
    MEDIACODEC_AU_MARKER, ProtocolError)
from hu_lab.usb import USBIdentity, USBHandle, RecoveryBlocked, BulkPair, write_all, USBError


class NativeUSBTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.ident = USBIdentity(233, (98, 7), 0x18d1, 0x4ee1, 3, "a" * 64)
        evidence = self.root / "review.json"
        evidence.write_text('{"scope":"offline-test"}')
        ref = {"path": str(evidence), "sha256": hashlib.sha256(evidence.read_bytes()).hexdigest()}
        self.admission = ScopedUSBAdmission(self.ident.physical_key, self.ident.serial_sha256,
            time.time() - 1, time.time() + 600, ref, ref, ref, ref,
            "owner-declared suitable spare HU", "manual route not an automatic recovery PASS")

    def test_admission_checks_expiry_identity_and_hashed_evidence(self):
        self.admission.require(self.ident)
        invalid = [dataclasses.replace(self.admission, expires_unix=time.time() - 2),
                   dataclasses.replace(self.admission, physical_key="1:2"),
                   dataclasses.replace(self.admission, recovery_kind="UNIVERSAL_PASS"),
                   dataclasses.replace(self.admission, current_session_owner="DHU"),
                   dataclasses.replace(self.admission, usb_serial_sha256="b" * 64)]
        for gate in invalid:
            with self.assertRaises(RecoveryBlocked):
                gate.require(self.ident)
        (self.root / "review.json").write_text("changed")
        with self.assertRaises(RecoveryBlocked):
            self.admission.require(self.ident)

    def test_actual_usb_serial_is_hashed_without_exposing_value(self):
        def serial(handle, index, data, capacity):
            self.assertEqual(index, 4)
            data[:len(b"private-fixture")] = b"private-fixture"
            return len(b"private-fixture")
        usb = SimpleNamespace(lib=SimpleNamespace(libusb_get_string_descriptor_ascii=serial))
        handle = USBHandle(usb, 1, self.ident, {"serial_index": 4})
        self.assertEqual(handle.serial_sha256(), hashlib.sha256(b"private-fixture").hexdigest())
        handle.descriptor = {}
        with self.assertRaises(RecoveryBlocked):
            handle.serial_sha256()

    def test_usb_bulk_tail_survives_small_recv_and_cancel_is_idempotent(self):
        reads = []
        cancel = threading.Event()
        def read(capacity, timeout):
            reads.append(capacity)
            return b"abcdefgh"
        handle = SimpleNamespace(read=read, close=cancel.set, cancel=cancel)
        stream = USBStream(SimpleNamespace(close=lambda: None), handle,
                           open(self.root / "stream-lock", "a+"), {})
        try:
            self.assertEqual(stream.recv(3), b"abc")
            self.assertEqual(stream.recv(3), b"def")
            self.assertEqual(stream.recv(3), b"gh")
            self.assertEqual(reads, [16384])
            self.assertEqual(stream.rx_bytes, 8)
            stream.shutdown()
            self.assertTrue(cancel.is_set())
            stream.close()
            stream.close()
            self.assertEqual(stream.recv(3), b"")
        finally:
            stream.close()

    def simulated_writer(self, transfer):
        """Exercise the actual offset/deadline loop without USB or wall waits."""
        clock = [100.0]
        calls, budgets, events = [], [], []
        cancel = threading.Event()
        def write(data, timeout):
            budgets.append(timeout)
            def attempt(part, timeout_ms):
                calls.append((bytes(part), timeout_ms))
                return transfer(part, timeout_ms, clock, cancel)
            return write_all(attempt, data, cancel, clock[0] + timeout)
        stream = USBStream(SimpleNamespace(close=lambda: None),
                           SimpleNamespace(write=write, cancel=cancel, close=cancel.set),
                           open(self.root / "write-lock", "a+"), {},
                           observer=lambda stage, **details: events.append({"stage": stage, **details}))
        self.addCleanup(stream.close)
        return stream, clock, calls, budgets, events

    def test_first_write_waits_for_md_reader_then_preserves_timeout_partial_offset(self):
        delivered = bytearray()
        def transfer(part, timeout_ms, clock, cancel):
            if clock[0] < 102.4:
                clock[0] += timeout_ms / 1000
                return -7, 0
            count = 5 if not delivered else len(part)
            delivered.extend(part[:count])
            clock[0] += .001
            return (-7 if count < len(part) else 0), count
        stream, clock, calls, budgets, events = self.simulated_writer(transfer)
        wire = Packet(CMD, HU_VERSION, protobuf(f1=1, f2=0)).encode()
        self.assertEqual(len(wire), 20)
        with patch("hu_lab.usb.time.monotonic", side_effect=lambda: clock[0]):
            stream.sendall(wire)
            stream.sendall(b"next-command")
        self.assertGreater(clock[0] - 100, 2)
        self.assertEqual(bytes(delivered), wire + b"next-command")
        self.assertEqual(budgets, [6, 2])
        self.assertIn(wire[5:], [part for part, _ in calls])
        self.assertEqual(stream.tx_bytes, len(delivered))
        self.assertEqual([e["stage"] for e in events], ["usb_first_write_begin", "usb_first_write_complete"])
        self.assertEqual(events[0]["payload_sha256"], hashlib.sha256(wire).hexdigest())

    def test_first_write_never_ready_expires_once_and_cannot_replay(self):
        def transfer(part, timeout_ms, clock, cancel):
            clock[0] += timeout_ms / 1000
            return -7, 0
        stream, clock, calls, budgets, events = self.simulated_writer(transfer)
        with patch("hu_lab.usb.time.monotonic", side_effect=lambda: clock[0]):
            with self.assertRaisesRegex(TimeoutError, "0/20 bytes"):
                stream.sendall(b"x" * 20)
        self.assertGreaterEqual(clock[0] - 100, 6)
        self.assertLess(clock[0] - 100, 6.002)
        count = len(calls)
        with self.assertRaisesRegex(OSError, "replay prohibited"):
            stream.sendall(b"x" * 20)
        self.assertEqual(len(calls), count)
        self.assertEqual(budgets, [6])
        self.assertTrue(stream.handle.cancel.is_set())
        self.assertFalse(stream.first_write_complete)
        self.assertEqual(events[-1]["stage"], "usb_first_write_failed")
        self.assertFalse(events[-1]["replay"])

    def test_after_first_success_normal_write_still_expires_in_two_seconds(self):
        def transfer(part, timeout_ms, clock, cancel):
            if part == b"version":
                return 0, len(part)
            clock[0] += timeout_ms / 1000
            return -7, 0
        stream, clock, calls, budgets, events = self.simulated_writer(transfer)
        with patch("hu_lab.usb.time.monotonic", side_effect=lambda: clock[0]):
            stream.sendall(b"version")
            with self.assertRaises(TimeoutError):
                stream.sendall(b"later")
        self.assertEqual(budgets, [6, 2])
        self.assertLess(clock[0] - 100, 2.002)
        self.assertEqual(stream.tx_bytes, 7)

    def test_fatal_partial_first_write_is_not_replayed(self):
        def transfer(part, timeout_ms, clock, cancel):
            return -4, 3
        stream, clock, calls, budgets, events = self.simulated_writer(transfer)
        with patch("hu_lab.usb.time.monotonic", side_effect=lambda: clock[0]):
            with self.assertRaisesRegex(USBError, "after 3 bytes"):
                stream.sendall(b"abcdef")
        with self.assertRaisesRegex(OSError, "replay prohibited"):
            stream.sendall(b"abcdef")
        self.assertEqual(len(calls), 1)
        self.assertTrue(stream.handle.cancel.is_set())
        self.assertEqual(stream.tx_bytes, 0)  # Whole logical writes only.

    def test_cancel_interrupts_startup_window_without_replay(self):
        def transfer(part, timeout_ms, clock, cancel):
            clock[0] += timeout_ms / 1000
            cancel.set()
            return -7, 0
        stream, clock, calls, budgets, events = self.simulated_writer(transfer)
        with patch("hu_lab.usb.time.monotonic", side_effect=lambda: clock[0]):
            with self.assertRaises(InterruptedError):
                stream.sendall(b"version")
        self.assertLess(clock[0] - 100, .201)
        self.assertEqual(len(calls), 1)
        stream.close()
        with self.assertRaises(OSError):
            stream.sendall(b"version")

    def fake_factory(self, *, accessory=False, start_error=False):
        parent = self
        state = {"accessory": accessory, "starts": 0, "claims": [], "closed": 0, "opened": []}
        accessory_identity = dataclasses.replace(self.ident, pid=0x2d01, address=8, serial_sha256="")

        class Handle:
            pair = None
            cancel = threading.Event()
            def __init__(self, identity): self.identity = identity
            def start_accessory(self, strings, admission, observer=None):
                admission.require(self.identity)
                state["starts"] += 1
                if start_error: raise OSError("unknown control result")
                state["accessory"] = True
                if observer: observer("aoa_request_complete", request=53)
                return 2
            def claim_accessory(self, admission):
                admission.require(self.identity)
                state["claims"].append(0)
                self.pair = BulkPair(0, 0, 0x81, 2, 512, 512)
            def close(self): pass

        class USB:
            def discover(self):
                identity = accessory_identity if state["accessory"] else parent.ident
                return [{"identity": dataclasses.asdict(identity)}]
            def open_exact(self, identity):
                parent.assertEqual(identity.serial_sha256, "a" * 64)
                state["opened"].append(identity)
                return Handle(identity)
            def wait_accessory(self, original, cancel, timeout):
                parent.assertTrue(state["accessory"])
                return self.discover()[0]
            def close(self): state["closed"] += 1
        factory = NativeUSBTransportFactory(self.ident, self.admission, self.root / "native",
                                            usb_factory=USB, allow_aoa_start=True)
        return factory, state

    def test_normal_aoa_then_bulk_only_reconnect_no_second_start(self):
        factory, state = self.fake_factory()
        stream = factory()
        self.assertEqual(stream.evidence["entry"], "HOST_INITIATED_51_52_53")
        self.assertFalse(stream.evidence["adb_data_plane"])
        stream.close()
        reconnect = factory()
        self.assertEqual(reconnect.evidence["entry"], "ALREADY_ACCESSORY")
        reconnect.close()
        self.assertEqual(state["starts"], 1)
        self.assertEqual(state["claims"], [0, 0])
        self.assertEqual(state["closed"], 2)

    def test_unknown_start_outcome_never_retries(self):
        factory, state = self.fake_factory(start_error=True)
        with self.assertRaises(OSError): factory()
        with self.assertRaises(RecoveryBlocked): factory()
        self.assertEqual(state["starts"], 1)
        self.assertEqual(state["claims"], [])

    def test_new_factory_and_output_cannot_replay_same_trial_but_new_trial_can_start(self):
        first, state = self.fake_factory(start_error=True)
        with self.assertRaises(OSError): first()
        restarted, restarted_state = self.fake_factory()
        restarted.output_dir = self.root / "restarted-output"
        restarted.output_dir.mkdir()
        with self.assertRaisesRegex(RecoveryBlocked, "intent already exists"):
            restarted()
        self.assertEqual(restarted_state["starts"], 0)
        self.assertEqual(restarted_state["claims"], [])
        armed = self.root / "second-armed-result.json"
        armed.write_text('{"nonce":"second-legitimate-trial","guard_pid":42}')
        new_ref = {"path": str(armed), "sha256": hashlib.sha256(armed.read_bytes()).hexdigest()}
        new_trial, new_state = self.fake_factory()
        new_trial.admission = dataclasses.replace(self.admission, transition_evidence=new_ref)
        stream = new_trial()
        stream.close()
        self.assertEqual(new_state["starts"], 1)

    def test_shared_physical_port_lock_blocks_competing_owner(self):
        factory, state = self.fake_factory(accessory=True)
        first = factory()
        try:
            second_factory, _ = self.fake_factory(accessory=True)
            with self.assertRaises(RecoveryBlocked): second_factory()
            self.assertEqual(state["claims"], [0])
        finally:
            first.close()

    def test_initial_accessory_is_not_claimed_host_transition_pass(self):
        factory, state = self.fake_factory(accessory=True)
        stream = factory()
        try:
            self.assertEqual(stream.evidence["entry"], "ALREADY_ACCESSORY")
            self.assertIsNone(stream.evidence["aoa_version"])
            self.assertFalse(stream.evidence["universal_recovery_verified"])
            self.assertEqual(state["starts"], 0)
        finally:
            stream.close()

    def test_native_client_reuses_handshake_input_and_reset_without_socket(self):
        parent = self
        class Handle:
            def __init__(self):
                self.cancel = threading.Event()
                self.parser = FrameParser()
                self.incoming = queue.Queue()
                self.sent = []
            def read(self, capacity, timeout):
                parent.assertGreaterEqual(capacity, 16384)
                try: return self.incoming.get(timeout=min(timeout, .02))
                except queue.Empty:
                    if self.cancel.is_set(): raise InterruptedError("cancel")
                    raise TimeoutError("idle USB")
            def write(self, data, timeout):
                for packet in self.parser.feed(data):
                    self.sent.append(packet)
                    responses = []
                    if packet.message == HU_VERSION:
                        responses = [Packet(CMD, VERSION_MATCH, protobuf(f1=1)),
                            Packet(CMD, MD_INFO, protobuf(f15=NATIVE_MEDIACODEC_AU_MARKER))]
                    elif packet.message == VIDEO_INIT:
                        responses = [Packet(CMD, VIDEO_INIT_DONE, protobuf(f1=1280, f2=720, f3=30))]
                    for response in responses:
                        wire = response.encode()
                        for offset in range(0, len(wire), 3): self.incoming.put(wire[offset:offset + 3])
                return len(data)
            def close(self): self.cancel.set()
        handle = Handle()
        owner = open(self.root / "lock", "a+")
        stream = USBStream(SimpleNamespace(close=lambda: None), handle, owner,
                           {"backend": "DIRECT_USB_AOA", "adb_data_plane": False})
        client = HUClient("direct-usb", 0, self.root / "client", backend="DIRECT_USB_AOA",
                          transport_factory=lambda: stream)
        self.addCleanup(client.stop)
        def wait_for(predicate):
            deadline = time.monotonic() + 2
            while not predicate() and time.monotonic() < deadline: time.sleep(.005)
            self.assertTrue(predicate(), client.error)
        with patch("hu_lab.client.socket.create_connection", side_effect=AssertionError("socket forbidden")):
            client.start()
            wait_for(lambda: client.session.state == "STREAMING")
            self.assertEqual(client.video_packetization, "ANNEXB_COMPLETE_AU")
            client.home()
            client.back()
            client.pause()
            client.resume()
            client.reset()
            wait_for(lambda: client.session.state == "STREAMING")
            wait_for(lambda: len([p for p in handle.sent if p.message == HARD_KEY]) == 2)
            codes = [fields(p.payload)[1] for p in handle.sent if p.message == HARD_KEY]
            self.assertEqual(codes, [HOME_KEY, BACK_KEY])
            self.assertIn(VIDEO_RESET, [p.message for p in handle.sent])
            self.assertGreater(client.progress_counts["usb_bulk_read_bytes"], 0)
            self.assertEqual(client.progress_counts["socket_read_bytes"], 0)
            client.stop()
        self.assertTrue(stream.closed)
        self.assertGreater(stream.tx_bytes, 0)
        self.assertTrue(client.native_shutdown_drain["quiescent"])
        self.assertGreaterEqual(client.native_shutdown_drain["elapsed_seconds"], .2)
        self.assertLess(client.native_shutdown_drain["elapsed_seconds"], 3.5)
        self.assertFalse(client.summary()["workers_alive"])

    def test_native_stop_pauses_live_media_before_video_and_drains_final_packet(self):
        # VIDEO_PAUSE alone does not stop PCM. Model an MD that keeps sending
        # valid audio until PAUSE_MEDIA, including a fragmented final packet.
        for already_paused in (False, True):
            with self.subTest(already_paused=already_paused):
                class Handle:
                    def __init__(self):
                        self.cancel = threading.Event()
                        self.audio_active = threading.Event()
                        self.parser = FrameParser()
                        self.incoming = queue.Queue()
                        self.sent = []
                        self.audio_packets = 0

                    def read(self, capacity, timeout):
                        try:
                            return self.incoming.get(timeout=min(timeout, .01))
                        except queue.Empty:
                            if self.cancel.is_set():
                                raise InterruptedError("cancel")
                            if self.audio_active.is_set():
                                self.audio_packets += 1
                                return Packet(MEDIA, MEDIA_DATA, b"\x01\x00" * 960,
                                              self.audio_packets * 10).encode()
                            raise TimeoutError("idle USB")

                    def write(self, data, timeout):
                        for packet in self.parser.feed(data):
                            self.sent.append(packet)
                            responses = []
                            if packet.message == HU_VERSION:
                                responses = [Packet(CMD, VERSION_MATCH, protobuf(f1=1)),
                                    Packet(CMD, MD_INFO, protobuf(f15=NATIVE_MEDIACODEC_AU_MARKER))]
                            elif packet.message == VIDEO_INIT:
                                responses = [Packet(CMD, VIDEO_INIT_DONE, protobuf(f1=1280, f2=720, f3=30))]
                            elif packet.message == VIDEO_START:
                                responses = [Packet(MEDIA, MEDIA_INIT, protobuf(f1=48000, f2=2, f3=16))]
                            elif packet.message == PAUSE_MEDIA:
                                self.audio_active.clear()
                                tail = Packet(MEDIA, MEDIA_DATA, b"\x02\x00" * 960, 9999).encode()
                                self.incoming.put(tail[:8])
                                self.incoming.put(tail[8:])
                            for response in responses:
                                self.incoming.put(response.encode())
                            if packet.message == VIDEO_START:
                                self.audio_active.set()
                        return len(data)

                    def close(self):
                        self.cancel.set()

                handle = Handle()
                owner = open(self.root / f"audio-lock-{already_paused}", "a+")
                stream = USBStream(SimpleNamespace(close=lambda: None), handle, owner,
                                   {"backend": "DIRECT_USB_AOA", "adb_data_plane": False})
                client = HUClient("direct-usb", 0, self.root / f"audio-stop-{already_paused}",
                                  backend="DIRECT_USB_AOA", transport_factory=lambda: stream)
                self.addCleanup(client.stop)

                def wait_for(predicate):
                    deadline = time.monotonic() + 2
                    while not predicate() and time.monotonic() < deadline:
                        time.sleep(.005)
                    self.assertTrue(predicate(), client.error)

                def audio_only_decoder(media=client.media):
                    # This fixture sends no video. Keep the real queue/worker
                    # shutdown contract without importing optional PyAV.
                    self.assertIsNone(media.get(), "audio-only fixture received video")

                with patch.object(client, "_decoder", audio_only_decoder):
                    client.start()
                wait_for(lambda: client.audio.summary()["lanes"]["MEDIA"]["data_packets"] >= 3)
                if already_paused:
                    client.pause()
                    wait_for(lambda: any(p.message == VIDEO_PAUSE for p in handle.sent))
                    before = client.audio.summary()["lanes"]["MEDIA"]["data_packets"]
                    wait_for(lambda: client.audio.summary()["lanes"]["MEDIA"]["data_packets"] > before)
                stop_command_index = len(handle.sent)
                client.stop()
                stopping = [p.message for p in handle.sent[stop_command_index:]]
                self.assertEqual(stopping, [PAUSE_MEDIA] if already_paused else [PAUSE_MEDIA, VIDEO_PAUSE])
                self.assertTrue(stream.closed)
                self.assertIsNone(client.error)
                self.assertTrue(client.native_shutdown_drain["quiescent"])
                self.assertEqual(client.framing_pending_bytes, 0)
                self.assertLess(client.native_shutdown_drain["elapsed_seconds"], 3.5)
                audio = client.audio.summary()["lanes"]["MEDIA"]
                self.assertEqual(audio["last_timestamp_raw"], 9999)
                self.assertEqual(audio["errors"], 0)
                self.assertFalse(client.summary()["workers_alive"])

    def test_native_stop_accepts_complete_boundary_when_call_pcm_continues(self):
        # Call PCM intentionally survives MusicPause. Every PCM frame arrives in
        # two reads; close must consume its tail rather than discard a prefix.
        class Handle:
            def __init__(self):
                self.cancel = threading.Event()
                self.parser = FrameParser()
                self.incoming = queue.Queue()
                self.active = False
                self.tail = None
                self.frames = 0
                self.closed_with_partial_frame = None
                self.sent = []

            def read(self, capacity, timeout):
                if self.tail is not None:
                    result, self.tail = self.tail, None
                    return result
                try:
                    return self.incoming.get(timeout=min(timeout, .01))
                except queue.Empty:
                    if self.cancel.is_set():
                        raise InterruptedError("cancel")
                    if self.active:
                        self.frames += 1
                        wire = Packet(MEDIA, MEDIA_DATA, b"\x01\x00" * 1920, self.frames * 20).encode()
                        self.tail = wire[8:]
                        return wire[:8]
                    raise TimeoutError("idle")

            def write(self, data, timeout):
                for packet in self.parser.feed(data):
                    self.sent.append(packet.message)
                    replies = []
                    if packet.message == HU_VERSION:
                        replies = [Packet(CMD, VERSION_MATCH, protobuf(f1=1)),
                                   Packet(CMD, MD_INFO, protobuf(f15=NATIVE_MEDIACODEC_AU_MARKER))]
                    elif packet.message == VIDEO_INIT:
                        replies = [Packet(CMD, VIDEO_INIT_DONE, protobuf(f1=1280, f2=720, f3=30))]
                    elif packet.message == VIDEO_START:
                        replies = [Packet(MEDIA, MEDIA_INIT, protobuf(f1=48000, f2=2, f3=16))]
                        self.active = True
                    elif packet.message == VIDEO_PAUSE:
                        # Native pause keepalive is not an encoded access unit.
                        replies = [Packet(VIDEO, VIDEO_DATA, b"")]
                    for reply in replies:
                        self.incoming.put(reply.encode())
                return len(data)

            def close(self):
                self.closed_with_partial_frame = self.tail is not None
                self.cancel.set()

        handle = Handle()
        owner = open(self.root / "live-call-lock", "a+")
        stream = USBStream(SimpleNamespace(close=lambda: None), handle, owner, {})
        client = HUClient("direct-usb", 0, self.root / "live-call", backend="DIRECT_USB_AOA",
                          transport_factory=lambda: stream)
        self.addCleanup(client.stop)
        with patch.object(client, "_decoder", lambda: client.media.get()):
            client.start()
        deadline = time.monotonic() + 2
        while client.audio.summary()["lanes"]["MEDIA"]["data_packets"] < 3 and time.monotonic() < deadline:
            time.sleep(.005)
        self.assertIsNone(client.error)
        client.stop()
        self.assertIsNone(client.error)
        self.assertIn(PAUSE_MEDIA, handle.sent)
        self.assertIn(VIDEO_PAUSE, handle.sent)
        self.assertFalse(handle.closed_with_partial_frame)
        self.assertEqual(client.framing_pending_bytes, 0)
        drain = client.native_shutdown_drain
        self.assertTrue(drain["complete_frame_boundary"])
        self.assertFalse(drain["quiescent"], "continuing call PCM is not silence")
        self.assertEqual(drain["boundary_reason"], "live_audio_frame_boundary")
        self.assertGreaterEqual(drain["continuing_audio_packets"], 4)
        self.assertEqual(drain["transport_pending_bytes"], 0)
        self.assertGreaterEqual(drain["elapsed_seconds"], .2)
        self.assertLess(drain["elapsed_seconds"], 3.5)
        self.assertFalse(client.summary()["workers_alive"])

    def test_live_audio_close_never_accepts_parser_or_transport_tail(self):
        client = HUClient("direct-usb", 0, self.root / "live-boundary", backend="DIRECT_USB_AOA",
                          transport_factory=lambda: None)
        try:
            client.session.state = "PAUSED"
            client.native_drain_since = 100
            client.native_drain_audio_packets = 10
            client.native_drain_last_audio_ns = 100_990_000_000
            client.last_progress_ns["usb_bulk_read_bytes"] = 100_990_000_000
            client.sock = SimpleNamespace(pending=bytearray(b"partial transport tail"))
            with patch("hu_lab.client.time.monotonic", return_value=101):
                self.assertFalse(client._native_rx_boundary_ready(0))
                client.sock.pending.clear()
                self.assertFalse(client._native_rx_boundary_ready(3))
                client.commands.put_nowait(Packet(CMD, VIDEO_PAUSE))
                self.assertFalse(client._native_rx_boundary_ready(0), "close controls still pending")
                client.commands.get_nowait()
                client.commands.task_done()
                self.assertTrue(client._native_rx_boundary_ready(0))
                self.assertEqual(client.native_shutdown_boundary_reason, "live_audio_frame_boundary")
        finally:
            client.sock = None
            client.stop()

    def test_native_marker_cannot_masquerade_as_dev_and_vice_versa(self):
        for backend, marker in (("DIRECT_USB_AOA", MEDIACODEC_AU_MARKER),
                                ("DEV_STREAM_OVER_ADB", NATIVE_MEDIACODEC_AU_MARKER)):
            client = HUClient("127.0.0.1", 0, self.root / backend, backend=backend,
                              transport_factory=(lambda: None) if backend == "DIRECT_USB_AOA" else None)
            try:
                with self.assertRaises(ProtocolError):
                    client._receive_packet(Packet(CMD, MD_INFO, protobuf(f15=marker)), None)
            finally:
                client.stop()

    def test_reconnect_discards_only_old_framed_media_before_version_match(self):
        client = HUClient("direct-usb", 0, self.root / "tail", backend="DIRECT_USB_AOA",
                          transport_factory=lambda: None)
        try:
            client.session.start()
            client._receive_packet(Packet(VIDEO, VIDEO_DATA, b"old tail"), None)
            client._receive_packet(Packet(VIDEO, VIDEO_HEARTBEAT, b""), None)
            self.assertEqual(client.native_stale_media_packets, 2)
            self.assertEqual(client.native_stale_media_bytes, 8)
            self.assertEqual(client.video_chunks, 0)
            client._receive_packet(Packet(CMD, VERSION_MATCH, protobuf(f1=1)), None)
            self.assertEqual(client.session.state, "WAIT_INIT")
            with self.assertRaises(ProtocolError):
                client._receive_packet(Packet(VIDEO, VIDEO_DATA, b"late old tail"), None)
            # A new awaiting-version native session still must reject mandatory auth.
            client.session.state = "WAIT_VERSION"
            with self.assertRaisesRegex(ProtocolError, "AUTH_PROVIDER_REQUIRED"):
                client._receive_packet(Packet(CMD, 0x18048, b""), None)
        finally:
            client.stop()

    def test_continuous_old_video_does_not_extend_absolute_native_deadline(self):
        client = HUClient("direct-usb", 0, self.root / "absolute-deadline", backend="DIRECT_USB_AOA",
                          transport_factory=lambda: None, timeout=8)
        try:
            client.session.start()
            with patch("hu_lab.client.time.monotonic", return_value=100):
                client._receive_packet(Packet(VIDEO, VIDEO_HEARTBEAT), None)
            with patch("hu_lab.client.time.monotonic", return_value=107):
                client._receive_packet(Packet(VIDEO, VIDEO_HEARTBEAT), None)
            with patch("hu_lab.client.time.monotonic", return_value=109):
                with self.assertRaisesRegex(ProtocolError, "absolute handshake timeout"):
                    client._receive_packet(Packet(VIDEO, VIDEO_HEARTBEAT), None)
            self.assertEqual(client.native_stale_media_packets, 2)
        finally:
            client.stop()

    def test_native_shutdown_quiet_never_accepts_partial_frame_or_recent_receive(self):
        client = HUClient("direct-usb", 0, self.root / "drain", backend="DIRECT_USB_AOA",
                          transport_factory=lambda: None)
        try:
            client.native_drain_since = 100
            client.last_progress_ns["usb_bulk_read_bytes"] = 100_900_000_000
            with patch("hu_lab.client.time.monotonic", return_value=101):
                self.assertFalse(client._native_rx_boundary_ready(0))
            with patch("hu_lab.client.time.monotonic", return_value=102):
                self.assertFalse(client._native_rx_boundary_ready(3))
                client.commands.put_nowait(Packet(CMD, VIDEO_PAUSE))
                self.assertFalse(client._native_rx_boundary_ready(0), "quiet RX does not prove TX is complete")
                client.commands.get_nowait()
                client.commands.task_done()
                self.assertTrue(client._native_rx_boundary_ready(0))
        finally:
            client.stop()


if __name__ == "__main__": unittest.main()

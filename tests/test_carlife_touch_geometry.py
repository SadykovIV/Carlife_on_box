"""Independent golden touch contracts, geometry inversion and long-run bounds."""
import io
import json
import math
from pathlib import Path
import struct
import tempfile
import unittest
from hu_lab.protocol import Packet, ProtocolError, TOUCH, HUSession, CMD, VERSION_MATCH, VIDEO_INIT_DONE, protobuf
from hu_lab.touch import *
from hu_lab.geometry import Rect, ViewportTransform, SourceDisplayTransform
from hu_lab.client import HUClient, Trace


class TouchContractTests(unittest.TestCase):
    def test_action3_independent_literal_golden_and_zero_coordinate(self):
        d = TouchDecoder("action3-multi")
        # Literal bytes from documented sender's BE fillByteArray operations.
        down = bytes.fromhex("000000000700000000")
        second = bytes.fromhex("0000010507000000000904ff02cf")
        self.assertEqual(encode_touch(TouchFrame(DOWN, (Pointer(7, 0, 0),)), "action3-multi").payload, down)
        self.assertEqual(d.decode(Packet(TOUCH, ACTION3, down)).pointers[0].id, 7)
        f = d.decode(Packet(TOUCH, ACTION3, second))
        self.assertEqual(f.action, POINTER_DOWN)
        self.assertEqual(f.action_index, 1)
        self.assertEqual(f.pointers, (Pointer(7, 0, 0), Pointer(9, 1279, 719)))
        self.assertEqual(encode_touch(f, "action3-multi").payload, second)

    def test_legacy_dual_zero_is_real_second_contact_and_first_lift(self):
        decoder = TouchDecoder("legacy-dual")
        frames = [TouchFrame(DOWN, (Pointer(0, 12, 34),)),
                  TouchFrame(POINTER_DOWN, (Pointer(0, 12, 34), Pointer(1, 0, 0)), 1),
                  TouchFrame(MOVE, (Pointer(0, 13, 35), Pointer(1, 0, 0))),
                  TouchFrame(POINTER_UP, (Pointer(0, 13, 35), Pointer(1, 0, 0)), 0),
                  TouchFrame(MOVE, (Pointer(1, 1, 2),)), TouchFrame(UP, (Pointer(1, 1, 2),))]
        self.assertEqual(encode_touch(frames[0], "legacy-dual").payload.hex(), "0800100c182220002800")
        for frame in frames:
            got = decoder.decode(encode_touch(frame, "legacy-dual"))
            self.assertEqual(got.pointers, frame.pointers)
            self.assertEqual(got.action_index, frame.action_index)
        self.assertFalse(decoder.tracker.active)
        with self.assertRaisesRegex(ProtocolError, "DOWN history"):
            TouchDecoder("legacy-dual").decode(encode_touch(frames[2], "legacy-dual"))

    def test_all_script_shapes_release_orders_and_profiles(self):
        for profile in ("legacy-dual", "action3-multi"):
            for kind in ("pinch-in", "pinch-out", "pan", "rotate"):
                for release in (0, 1):
                    decoder = TouchDecoder(profile)
                    frames = list(two_pointer_gesture(1280, 720, kind, release, 7))
                    for frame in frames:
                        received = decoder.decode(encode_touch(frame, profile))
                        self.assertEqual([p.id for p in received.pointers], [p.id for p in frame.pointers])
                    self.assertFalse(decoder.tracker.active)

    def test_invalid_lengths_count_ids_indices_and_profile(self):
        invalid = [b"", b"\0" * 4, b"\0" * 8, b"\0" * 10, b"\0" * 59,
                   bytes.fromhex("0000020500000100020100030004"),
                   bytes.fromhex("0000010500000100020000030004")]
        for payload in invalid:
            with self.assertRaises(ProtocolError):
                TouchDecoder("action3-multi").decode(Packet(TOUCH, ACTION3, payload))
        with self.assertRaises(ProtocolError):
            encode_touch(TouchFrame(MOVE, (Pointer(0, 1, 2), Pointer(1, 3, 4))), "legacy-single")
        with self.assertRaises(ProtocolError):
            TouchFrame(DOWN, (Pointer(0, -1, 2),)).validate()



class GeometryTests(unittest.TestCase):
    def test_full_source_video_qt_roundtrip_integer_fit(self):
        maximum = 0
        for size in ((800,480),(1024,600),(1280,720),(1280,480),(1920,720)):
            for source_rotation in (0,90,180,270):
                source = SourceDisplayTransform(1920,1080,*size,source_rotation)
                for window_rotation in (0,90,180,270):
                    qt = ViewportTransform(*size,Rect(0,0,913,573),rotation=window_rotation)
                    for x in (0,1919,50,400,960,1500):
                        for y in (0,1079,60,300,540,900):
                            vx,vy=source.source_to_video(x,y)
                            # At source edges a downscaled pixel centre projects
                            # fractionally outside the content's first centre.
                            vx=max(source.content.x,min(source.content.x+source.content.width-1,vx))
                            vy=max(source.content.y,min(source.content.y+source.content.height-1,vy))
                            wire=qt.view_to_video(*qt.video_to_view(vx,vy))
                            self.assertIsNotNone(wire)
                            back=source.video_to_source(*wire)
                            self.assertIsNotNone(back)
                            maximum=max(maximum,abs(back[0]-x),abs(back[1]-y))
        self.assertLessEqual(maximum,2)
        wide=SourceDisplayTransform(1920,1080,1280,480)
        self.assertEqual(wide.content,Rect(213,0,853,480))
        self.assertIsNotNone(wide.video_to_source(213,0))
        self.assertIsNone(wide.video_to_source(212,0))

    def test_grid_inverse_all_rotations_aspects_retina_and_crop(self):
        maximum = 0
        for size in ((800, 480), (1024, 600), (1280, 720), (1280, 480), (1920, 720)):
            for rotation in (0, 90, 180, 270):
                for dpr in (1, 2):
                    for policy in ("fit", "crop"):
                        t = ViewportTransform(*size, Rect(17, 29, 913, 573), policy, rotation, backing_scale=dpr)
                        for ix in range(1, 20):
                            for iy in range(1, 20):
                                x, y = (size[0] - 1) * ix / 20, (size[1] - 1) * iy / 20
                                view = t.video_to_view(x, y)
                                back = t.view_to_video(*view)
                                if back is None:
                                    self.assertEqual(policy, "crop")
                                    continue
                                error = max(abs(back[0] - x) * 1920 / size[0], abs(back[1] - y) * 1080 / size[1])
                                maximum = max(maximum, error)
                        self.assertIsNone(t.view_to_video(-1, -1))
        self.assertLessEqual(maximum, 2)

    def test_fit_bars_rejected_crop_inverse_and_dpr_is_not_applied_twice(self):
        a = ViewportTransform(1920, 1080, Rect(0, 0, 800, 480))
        b = ViewportTransform(1920, 1080, Rect(0, 0, 800, 480), backing_scale=2)
        self.assertIsNone(a.view_to_video(400, 0))
        self.assertEqual(a.view_to_video(400, 240), (960, 540))
        self.assertEqual(a.affine, b.affine)
        crop = ViewportTransform(1920, 1080, Rect(0, 0, 800, 480), crop=Rect(100, 100, 800, 480))
        self.assertEqual(crop.view_to_video(400, 240), (500, 340))


class IntegrationAndBoundsTests(unittest.TestCase):
    def test_cancellation_reset_and_no_outside_clamping(self):
        with tempfile.TemporaryDirectory() as temp:
            c = HUClient("127.0.0.1", 1, temp, touch_profile="action3-multi")
            c.session.state = "STREAMING"
            frames = list(two_pointer_gesture(1280, 720, steps=1))
            for frame in frames[:2]: c.send_touch_frame(frame)
            with self.assertRaisesRegex(ProtocolError, "outside"):
                c.send_touch_frame(TouchFrame(MOVE, (Pointer(0, -1, 100), Pointer(1, 100, 100))))
            # Invalid numeric input is rejected before lifecycle handling; valid
            # uint16 point outside accepted video cancels active contacts.
            with self.assertRaisesRegex(ProtocolError, "outside active"):
                c.send_touch_frame(TouchFrame(MOVE, (Pointer(0, 1280, 100), Pointer(1, 100, 100))))
            self.assertFalse(c.pending_touch)
            for frame in frames[:2]: c.send_touch_frame(frame)
            c.reset()
            packets = list(c.commands.queue)
            self.assertEqual(struct.unpack_from(">I", packets[-3].payload)[0], CANCEL)
            self.assertFalse(c.pending_touch)
            self.assertEqual(c.decoder_generation, 1)
            c.halt.set(); c.stop()

    def test_actual_accepted_mode_not_invented_and_invalid_modes(self):
        h = HUSession(1280, 720, 30, allow_mode_fallback=True)
        h.start(); h.receive(Packet(CMD, VERSION_MATCH, b"\x08\x01"))
        h.receive(Packet(CMD, VIDEO_INIT_DONE, protobuf(f1=800, f2=480, f3=20)))
        self.assertEqual((h.width, h.height, h.fps), (800, 480, 20))
        self.assertEqual(h.requested["width"], 1280)
        self.assertEqual(h.accepted["provenance"], "MD_VIDEO_INIT_DONE")
        with self.assertRaises(ProtocolError): HUSession(0, 480)

    def test_soak_50000_pts_bounded_aggregates_and_trace_cap(self):
        with tempfile.TemporaryDirectory() as temp:
            c = HUClient("127.0.0.1", 1, temp, capture_limit_mib=.01, trace_mode="sampled")
            for n in range(50000):
                c._record_pts({"generation": 0, "pts_ms": n * 50, "idr": n % 40 == 0})
                c._timing_add("receive_to_decode", n % 15)
            stats = c.pts_metrics()[0]
            self.assertEqual(stats["access_units"], 50000)
            self.assertEqual(stats["encoder_output_fps_from_pts_ms"], 20)
            self.assertLessEqual(len(stats["idr_pts_ms"]), 256)
            self.assertLessEqual(stats["gap_distribution"]["count"], 2048)
            self.assertEqual(c.host_timing_metrics()["receive_to_decode"]["all_session"]["count"], 50000)
            packet = Packet(TOUCH, ACTION3, bytes.fromhex("000000000000000000")).encode()
            for n in range(2000): c.trace.wire("tx", packet)
            self.assertIn("tx", c.trace.raw_truncated)
            c.halt.set(); c.stop()
            from hu_lab.client import replay
            self.assertTrue(replay(Path(temp) / "tx.bin"))

    def test_sampled_trace_keeps_tx_writer_and_input_events_within_budget(self):
        with tempfile.TemporaryDirectory() as temp:
            trace = Trace(temp, maximum=100_000, mode="sampled")
            trace.started -= 6
            packet = Packet(TOUCH, ACTION3, bytes.fromhex("000000000000000000"))
            trace.packet("rx", packet)
            trace.packet("tx", packet)
            trace.event("touch_frame", payload_hex=packet.payload.hex())
            trace.event("scheduled_gui_action", name="tap")
            trace.packet("rx", packet)
            trace.packet("tx", packet)
            self.assertEqual(trace.omitted["packet"], 2)
            trace.close()
            rows = [json.loads(line) for line in (Path(temp) / "trace.jsonl").read_text().splitlines()]
            self.assertEqual([r["event"] for r in rows], ["packet", "touch_frame", "scheduled_gui_action", "packet"])
            self.assertTrue(all(r["direction"] == "tx" for r in rows if r["event"] == "packet"))
        with tempfile.TemporaryDirectory() as temp:
            trace = Trace(temp, maximum=128, mode="sampled")
            trace.started -= 6
            trace.packet("tx", packet)
            self.assertEqual(trace.omitted["packet"], 1)
            self.assertEqual(trace.json_bytes, 0)
            trace.close()


if __name__ == "__main__":
    unittest.main()

"""Offline cases use literal golden bytes, not two copies of our encoder.

Golden source provenance: official pinned CarlifeProtocolVersionProto.proto,
CarlifeVideoEncoderInfoProto.proto, CarlifeCarHardKeyCodeProto.proto and
CTranRecvPackageProcess.h IDs. Proto2 varints independently calculated by hand.
Outer framing source: HeadSwap Protocol.kt pinned 6835e50d, Wire.outerHeader.
These test protocol structure, not independent endpoint interoperability.
"""
import random
import struct
import time
import unittest

from hu_lab.protocol import *
from hu_lab.client import BoundedMedia
from hu_lab.gui import map_to_video


VERSION = bytes.fromhex("000000010000000c000400000001800108011000")
INIT = bytes.fromhex("0000000100000010000800000001800708800a10d005181e")
BACK = bytes.fromhex("000000060000000a0002000000068008080e")
VIDEO_GOLD = bytes.fromhex("0000000200000011000000050000007b000200010000000165")


class CodecTests(unittest.TestCase):
    def test_golden_version(self):
        expected = Packet(CMD, HU_VERSION, b"\x08\x01\x10\x00")
        self.assertEqual(FrameParser().feed(VERSION), [expected])
        self.assertEqual(expected.encode(), VERSION)
        self.assertEqual(protobuf(f1=1, f2=0), b"\x08\x01\x10\x00")

    def test_golden_init(self):
        packet = FrameParser().feed(INIT)[0]
        self.assertEqual(packet.message, 0x18007)
        self.assertEqual(fields(packet.payload), {1: 1280, 2: 720, 3: 30})
        self.assertEqual(Packet(CMD, VIDEO_INIT, protobuf(f1=1280, f2=720, f3=30)).encode(), INIT)

    def test_golden_back(self):
        packet = FrameParser().feed(BACK)[0]
        self.assertEqual((packet.channel, packet.message, fields(packet.payload)), (6, 0x68008, {1: 14}))

    def test_golden_media(self):
        packet = FrameParser().feed(VIDEO_GOLD)[0]
        self.assertEqual((packet.channel, packet.timestamp, packet.message, packet.payload),
                         (2, 123, 0x20001, b"\0\0\0\1\x65"))
        self.assertEqual(packet.encode(), VIDEO_GOLD)

    def test_fragmentation_every_boundary(self):
        for split in range(len(INIT) + 1):
            parser = FrameParser()
            result = parser.feed(INIT[:split]) + parser.feed(INIT[split:])
            self.assertEqual(len(result), 1)
            parser.eof()

    def test_coalesced_random_fragmentation(self):
        rng = random.Random(6350)
        wire = (VERSION + INIT + BACK + VIDEO_GOLD) * 100
        parser, result = FrameParser(), []
        while wire:
            n = rng.randrange(1, 400)
            result += parser.feed(wire[:n])
            wire = wire[n:]
        parser.eof()
        self.assertEqual(len(result), 400)

    def test_eof_all_partial_lengths(self):
        for length in range(1, len(VIDEO_GOLD)):
            parser = FrameParser()
            parser.feed(VIDEO_GOLD[:length])
            with self.assertRaises(ProtocolError):
                parser.eof()

    def test_oversized_rejected_from_header(self):
        with self.assertRaises(ProtocolError):
            FrameParser().feed(bytes.fromhex("000000027fffffff"))

    def test_unknown_channel_fails(self):
        with self.assertRaises(ProtocolError):
            FrameParser().feed(bytes.fromhex("0000000700000008"))

    def test_unknown_message_retained(self):
        packet = FrameParser().feed(bytes.fromhex("0000000100000008000000000001ffff"))[0]
        self.assertEqual(packet.message, 0x1FFFF)

    def test_malformed_lengths_reserved_direction(self):
        for wire in (VERSION[:8] + b"\x00\x05" + VERSION[10:],
                     VERSION[:10] + b"\x00\x01" + VERSION[12:],
                     VERSION[:12] + b"\x00\x02\x80\x01" + VERSION[16:]):
            with self.assertRaises(ProtocolError):
                FrameParser().feed(wire)

    def test_command_maximum(self):
        with self.assertRaises(ProtocolError):
            Packet(CMD, HU_INFO, b"a" * 65536).encode()

    def test_proto_truncation_and_overflow(self):
        for malformed in (b"\x08\x80", b"\x0a\x05hi", b"\x00", b"\x08" + b"\xff" * 10,
                          b"\x0b"):
            with self.assertRaises(ProtocolError):
                fields(malformed)

    def test_all_six_channels_distinct(self):
        for channel in range(1, 7):
            raw = bytes.fromhex("0000000100000008000000000001ffff") if channel == 1 else None
            packet = Packet(channel, channel << 16 | 1, b"sample", 17)
            decoded = FrameParser().feed(packet.encode())[0]
            self.assertEqual(decoded.channel, channel)
            self.assertEqual(decoded.payload, b"sample")


class SessionTests(unittest.TestCase):
    def established(self):
        session = HUSession()
        self.assertEqual(session.start()[0].encode(), VERSION)
        self.assertEqual(len(session.receive(Packet(CMD, VERSION_MATCH, b"\x08\x01"))), 2)
        session.receive(Packet(CMD, MD_INFO, protobuf(f1="Android", f14="lab", f16="private")))
        self.assertNotIn("16", session.md_info)
        self.assertEqual(session.receive(Packet(CMD, VIDEO_INIT_DONE, bytes.fromhex("08800a10d005181e"))),
                         [Packet(CMD, VIDEO_START)])
        return session

    def test_handshake_and_duplicates(self):
        session = self.established()
        self.assertEqual(session.state, "STREAMING")
        self.assertEqual(session.receive(Packet(CMD, VERSION_MATCH, b"\x08\x01")), [])
        self.assertEqual(session.receive(Packet(CMD, VIDEO_INIT_DONE, bytes.fromhex("08800a10d005181e"))), [])
        self.assertEqual(session.duplicates, 2)

    def test_pause_start_reset(self):
        session = self.established()
        self.assertEqual(session.pause().message, VIDEO_PAUSE)
        self.assertEqual(session.state, "PAUSED")
        self.assertEqual(session.resume().message, VIDEO_START)
        packets = session.reset()
        self.assertEqual([p.message for p in packets], [VIDEO_RESET, VIDEO_INIT])
        self.assertEqual(session.state, "WAIT_INIT")
        self.assertTrue(session.reset_pending)
        # Previously established session may drain old video until reset acknowledgement.
        self.assertEqual(session.receive(FrameParser().feed(VIDEO_GOLD)[0]), [])
        session.receive(Packet(CMD, VIDEO_INIT_DONE, bytes.fromhex("08800a10d005181e")))
        self.assertFalse(session.reset_pending)
        self.assertEqual(session.state, "STREAMING")

    def test_auth_provider_absent_blocked(self):
        session = HUSession()
        session.start()
        with self.assertRaisesRegex(ProtocolError, "AUTH_PROVIDER"):
            session.receive(Packet(CMD, 0x1006A))
        self.assertEqual(session.state, "BLOCKED_AUTH")

    def test_version_failure(self):
        session = HUSession()
        session.start()
        with self.assertRaises(ProtocolError):
            session.receive(Packet(CMD, VERSION_MATCH, b"\x08\x00"))

    def test_early_video_and_bad_dimensions_fail(self):
        session = HUSession()
        with self.assertRaises(ProtocolError):
            session.receive(FrameParser().feed(VIDEO_GOLD)[0])
        session.start()
        session.receive(Packet(CMD, VERSION_MATCH, b"\x08\x01"))
        with self.assertRaises(ProtocolError):
            session.receive(Packet(CMD, VIDEO_INIT_DONE, protobuf(f1=640, f2=480, f3=30)))

    def test_init_done_missing_required_framerate_fails(self):
        session = HUSession()
        session.start()
        session.receive(Packet(CMD, VERSION_MATCH, b"\x08\x01"))
        with self.assertRaisesRegex(ProtocolError, "required"):
            session.receive(Packet(CMD, VIDEO_INIT_DONE, bytes.fromhex("08800a10d005")))

    def test_unknown_logged(self):
        session = self.established()
        self.assertEqual(session.receive(Packet(CMD, 0x1FFFF)), [])
        self.assertEqual(session.unknown, 1)


class ResourceTests(unittest.TestCase):
    def test_queue_byte_bound(self):
        queue = BoundedMedia(max_bytes=4)
        queue.put(b"1234")
        with self.assertRaisesRegex(ProtocolError, "BACKPRESSURE"):
            queue.put(b"5")
        self.assertEqual(queue.size, 4)

    def test_queue_age_bound_and_eof(self):
        queue = BoundedMedia(max_age=1)
        queue.items.append((time.monotonic() - 2, b"old"))
        queue.size = 3
        with self.assertRaisesRegex(ProtocolError, "STALL"):
            queue.get()
        queue.close()
        self.assertIsNone(queue.get())

    def test_letterbox_mapping(self):
        # 1280x720 fitted into 1000x1000: black bars outside y218.75..781.25.
        rect = (0, 218.75, 1000, 562.5)
        self.assertIsNone(map_to_video(500, 100, rect, 1280, 720))
        self.assertEqual(map_to_video(500, 500, rect, 1280, 720), (640, 360))
        self.assertEqual(map_to_video(999.9, 781.24, rect, 1280, 720), (1279, 719))


if __name__ == "__main__":
    unittest.main()

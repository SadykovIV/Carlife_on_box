"""PCM protocol/evidence checks; no Android, speaker, microphone or car needed."""
from pathlib import Path
import struct
import tempfile
import unittest
import wave

from hu_lab.audio import (AudioReceiver, BoundedWavCapture, PcmFormat,
    MEDIA_INIT, MEDIA_DATA, MEDIA_PAUSE, MEDIA_RESUME, MEDIA_STOP,
    TTS_INIT, TTS_DATA, TTS_STOP, VR_INIT, VR_DATA, VR_STOP)
from hu_lab.protocol import Packet, FrameParser, MEDIA, TTS, VR, VIDEO, VIDEO_HEARTBEAT, protobuf, ProtocolError


def init(channel=MEDIA, rate=48000, channels=2, bits=16):
    return Packet(channel, {MEDIA: MEDIA_INIT, TTS: TTS_INIT, VR: VR_INIT}[channel],
                  protobuf(f1=rate, f2=channels, f3=bits))


class AudioProtocolTest(unittest.TestCase):
    def test_vr_output_is_distinct_from_microphone_and_other_lanes(self):
        receiver = AudioReceiver()
        receiver.receive(init())
        receiver.receive(init(TTS, 16000, 1))
        receiver.receive(init(VR, 16000, 1))
        receiver.receive(Packet(VR, VR_DATA, b"\x01\x00" * 320, 123))
        receiver.receive(Packet(VR, 0x58001, b"microphone input is not assistant output"))
        receiver.receive(Packet(VR, VR_STOP))
        lanes = receiver.summary()["lanes"]
        self.assertEqual((lanes["VR"]["pcm_frames"], lanes["VR"]["state"]), (320, "STOPPED"))
        self.assertEqual(lanes["VR"]["unknown_messages"], 1)
        self.assertEqual((lanes["MEDIA"]["pcm_bytes"], lanes["TTS"]["pcm_bytes"]), (0, 0))
        self.assertEqual(init(VR, 16000, 1).message, 0x50002)
        receiver.close()

    def test_vr_bad_init_and_stop_do_not_disable_tts(self):
        receiver = AudioReceiver()
        receiver.receive(init(TTS, 16000, 1))
        receiver.receive(Packet(VR, VR_DATA, b"\x01\x00"))
        receiver.receive(init(VR, 16000, 1))
        receiver.receive(Packet(VR, VR_STOP, b"invalid"))
        receiver.receive(Packet(TTS, TTS_DATA, b"\x02\x00"))
        lanes = receiver.summary()["lanes"]
        self.assertEqual(lanes["VR"]["errors"], 2)
        self.assertEqual((lanes["TTS"]["errors"], lanes["TTS"]["pcm_frames"]), (0, 1))
        receiver.close()

    def test_primary_schema_and_exact_outer_wire(self):
        self.assertEqual(init().payload.hex(), "0880f70210021810")
        self.assertEqual(init(TTS, 16000, 1).payload.hex(), "08807d10011810")
        self.assertEqual(init().encode().hex(), "00000003000000140000000800000000000300010880f70210021810")
        packet = Packet(MEDIA, MEDIA_DATA, b"\x01\x00\xfe\xff", 0xFFFFFFFF)
        self.assertEqual(FrameParser().feed(packet.encode()), [packet])

    def test_stereo_frames_and_partial_packet_alignment(self):
        receiver = AudioReceiver()
        receiver.receive(init())
        receiver.receive(Packet(MEDIA, MEDIA_DATA, b"\x01\x00\x02", 30))
        self.assertEqual(receiver.summary()["lanes"]["MEDIA"]["pending_bytes"], 3)
        receiver.receive(Packet(MEDIA, MEDIA_DATA, b"\x00\x03\x00\x04\x00", 31))
        lane = receiver.summary()["lanes"]["MEDIA"]
        self.assertEqual((lane["pcm_frames"], lane["pcm_bytes"], lane["pending_bytes"]), (2, 8, 0))
        self.assertEqual(lane["non_silent_bytes"], 4)
        self.assertEqual(lane["last_timestamp_raw"], 31)
        self.assertFalse(receiver.summary()["capture"]["enabled"])
        receiver.close()

    def test_timestamp_wrap_is_raw_not_video_pts(self):
        receiver = AudioReceiver()
        receiver.receive(init())
        for timestamp in (0xFFFFFFFF, 2, 1):
            receiver.receive(Packet(MEDIA, MEDIA_DATA, b"\0" * 4, timestamp))
        self.assertEqual(receiver.summary()["lanes"]["MEDIA"]["errors"], 0)
        self.assertEqual(receiver.summary()["lanes"]["MEDIA"]["last_timestamp_raw"], 1)

    def test_pause_resume_stop_reinit_and_partial_drop(self):
        receiver = AudioReceiver()
        receiver.receive(init())
        receiver.receive(Packet(MEDIA, MEDIA_DATA, b"a"))
        receiver.receive(Packet(MEDIA, MEDIA_PAUSE))
        receiver.receive(Packet(MEDIA, MEDIA_DATA, b"1234"))
        receiver.receive(Packet(MEDIA, MEDIA_RESUME))
        receiver.receive(Packet(MEDIA, MEDIA_DATA, b"5678"))
        receiver.receive(Packet(MEDIA, MEDIA_STOP))
        receiver.receive(Packet(MEDIA, MEDIA_DATA, b"9012"))
        row = receiver.summary()["lanes"]["MEDIA"]
        self.assertEqual((row["pcm_bytes"], row["partial_discarded_bytes"], row["dropped_pcm_bytes"]), (4, 1, 8))
        receiver.receive(Packet(MEDIA, MEDIA_RESUME))
        self.assertEqual(receiver.summary()["lanes"]["MEDIA"]["state"], "DISABLED")
        receiver.receive(init(rate=16000, channels=1))
        receiver.receive(Packet(MEDIA, MEDIA_DATA, b"12"))
        row = receiver.summary()["lanes"]["MEDIA"]
        self.assertEqual((row["state"], row["generation"], row["format"]["channels"]), ("PLAYING", 2, 1))

    def test_invalid_init_is_local_and_good_init_recovers(self):
        events = []
        receiver = AudioReceiver(event=lambda name, **data: events.append((name, data)))
        receiver.receive(init(TTS, 16000, 1))
        receiver.receive(Packet(MEDIA, MEDIA_INIT, b"\x08"))
        receiver.receive(Packet(MEDIA, MEDIA_DATA, b"bad"))
        receiver.receive(Packet(TTS, TTS_DATA, b"\x01\x00"))
        state = receiver.summary()["lanes"]
        self.assertEqual(state["MEDIA"]["state"], "DISABLED")
        self.assertEqual(state["TTS"]["pcm_frames"], 1)
        self.assertEqual(events[-1][0], "audio_lane_error")
        receiver.receive(init())
        receiver.receive(Packet(MEDIA, MEDIA_DATA, b"1234"))
        self.assertEqual(receiver.summary()["lanes"]["MEDIA"]["pcm_frames"], 1)
        self.assertFalse(receiver.receive(Packet(VIDEO, VIDEO_HEARTBEAT)))

    def test_missing_bad_fields_and_data_bounds(self):
        invalid = [b"", protobuf(f1=48000, f2=2), protobuf(f1="48000", f2=2, f3=16),
                   protobuf(f1=0, f2=2, f3=16), protobuf(f1=48000, f2=12, f3=16),
                   protobuf(f1=48000, f2=2, f3=24), b"\0" * 1025]
        for payload in invalid:
            with self.subTest(payload=payload[:20]), self.assertRaises(ProtocolError):
                PcmFormat.parse(payload)
        receiver = AudioReceiver()
        receiver.receive(Packet(MEDIA, MEDIA_DATA, b"1234"))
        self.assertEqual(receiver.summary()["lanes"]["MEDIA"]["state"], "DISABLED")
        receiver.receive(init(TTS, 16000, 1))
        receiver.receive(Packet(TTS, TTS_DATA, b"\0" * (50 * 1024 + 1)))
        self.assertEqual(receiver.summary()["lanes"]["TTS"]["state"], "DISABLED")

    def test_nonempty_control_and_unknown_audio_id(self):
        receiver = AudioReceiver()
        receiver.receive(init())
        receiver.receive(Packet(MEDIA, 0x30005, b"seek"))
        self.assertEqual(receiver.summary()["lanes"]["MEDIA"]["unknown_messages"], 1)
        receiver.receive(Packet(MEDIA, MEDIA_PAUSE, b"unexpected"))
        self.assertEqual(receiver.summary()["lanes"]["MEDIA"]["state"], "DISABLED")

    def test_close_discards_partial_and_refuses_late_data(self):
        receiver = AudioReceiver()
        receiver.receive(init())
        receiver.receive(Packet(MEDIA, MEDIA_DATA, b"123"))
        receiver.close()
        receiver.close()
        receiver.receive(Packet(MEDIA, MEDIA_DATA, b"4567"))
        row = receiver.summary()["lanes"]["MEDIA"]
        self.assertEqual(row["state"], "CLOSED")
        self.assertEqual(row["partial_discarded_bytes"], 3)
        self.assertEqual(row["pending_bytes"], 0)
        self.assertEqual(row["dropped_pcm_bytes"], 4)

    def test_pcm8_unsigned_silence_and_mono(self):
        receiver = AudioReceiver()
        receiver.receive(init(TTS, 8000, 1, 8))
        receiver.receive(Packet(TTS, TTS_DATA, b"\x80\x80\x81"))
        row = receiver.summary()["lanes"]["TTS"]
        self.assertEqual(row["pcm_frames"], 3)
        self.assertEqual(row["non_silent_bytes"], 1)


class WavCaptureTest(unittest.TestCase):
    def test_opt_in_exact_samples_channels_and_reinit_files(self):
        with tempfile.TemporaryDirectory() as directory:
            receiver = AudioReceiver(capture_directory=directory)
            stereo = struct.pack("<hhhh", 123, -456, 789, -1011)
            receiver.receive(init())
            receiver.receive(Packet(MEDIA, MEDIA_DATA, stereo[:3]))
            receiver.receive(Packet(MEDIA, MEDIA_DATA, stereo[3:]))
            receiver.receive(init(rate=16000, channels=1))
            receiver.receive(Packet(MEDIA, MEDIA_DATA, b"\x01\x00"))
            receiver.receive(init(TTS, 16000, 1))
            receiver.receive(Packet(TTS, TTS_DATA, b"\x02\x00"))
            receiver.receive(Packet(TTS, TTS_STOP))
            receiver.close()
            with wave.open(str(Path(directory) / "media-0001.wav"), "rb") as wav:
                self.assertEqual((wav.getnchannels(), wav.getframerate(), wav.getsampwidth(), wav.getnframes()), (2, 48000, 2, 2))
                self.assertEqual(wav.readframes(2), stereo)
            with wave.open(str(Path(directory) / "media-0002.wav"), "rb") as wav:
                self.assertEqual((wav.getnchannels(), wav.getframerate(), wav.getnframes()), (1, 16000, 1))
            capture = receiver.summary()["capture"]
            self.assertEqual(capture["written_pcm_bytes"], 12)
            self.assertEqual(len(capture["files"]), 3)
            self.assertFalse(capture["worker_alive"])
            self.assertIsNone(capture["error"])

    def test_queue_duration_and_age_bound_without_hardware_timing(self):
        now = [10.0]
        with tempfile.TemporaryDirectory() as directory:
            capture = BoundedWavCapture(directory, clock=lambda: now[0], start_worker=False,
                                        max_queue_seconds=.04, max_age_seconds=.05)
            fmt = PcmFormat(48000, 2, 16)
            for _ in range(3):
                capture.submit(MEDIA, 1, fmt, b"\0" * 3840)
            self.assertAlmostEqual(capture.summary()["queued_seconds"], .04)
            self.assertEqual(capture.summary()["dropped_queue_bytes"], 3840)
            now[0] += .1
            capture.submit(MEDIA, 1, fmt, b"\0" * 3840)
            self.assertAlmostEqual(capture.summary()["queued_seconds"], .02)
            self.assertEqual(capture.summary()["dropped_queue_bytes"], 3 * 3840)
            self.assertFalse(capture.submit(MEDIA, 1, fmt, b"\0" * 38400))
            capture.close()
            self.assertEqual(capture.summary()["queued_seconds"], 0)
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_capture_budget_truncates_on_complete_frame(self):
        with tempfile.TemporaryDirectory() as directory:
            capture = BoundedWavCapture(directory, max_bytes=7)
            capture.submit(MEDIA, 1, PcmFormat(48000, 2, 16), b"12345678")
            capture.close()
            self.assertEqual(capture.summary()["written_pcm_bytes"], 4)
            self.assertEqual(capture.summary()["dropped_limit_bytes"], 4)
            with wave.open(str(Path(directory) / "media-0001.wav"), "rb") as wav:
                self.assertEqual(wav.readframes(9), b"1234")

    def test_file_count_is_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            capture = BoundedWavCapture(directory, max_files=1)
            fmt = PcmFormat(48000, 2, 16)
            capture.submit(MEDIA, 1, fmt, b"1234")
            capture.submit(MEDIA, 2, fmt, b"5678")
            capture.close()
            self.assertEqual(len(capture.summary()["files"]), 1)
            self.assertEqual(capture.summary()["dropped_limit_bytes"], 4)

    def test_disk_error_does_not_disable_audio_receiver(self):
        with tempfile.TemporaryDirectory() as directory:
            not_directory = Path(directory) / "file"
            not_directory.write_bytes(b"occupied")
            receiver = AudioReceiver(capture_directory=not_directory)
            receiver.receive(init())
            receiver.receive(Packet(MEDIA, MEDIA_DATA, b"1234"))
            receiver.close()
            result = receiver.summary()
            self.assertIsNotNone(result["capture"]["error"])
            self.assertEqual(result["lanes"]["MEDIA"]["errors"], 0)
            self.assertFalse(result["capture"]["worker_alive"])


class ClientAudioIsolationTest(unittest.TestCase):
    def test_native_audio_tail_before_fresh_match_is_discarded(self):
        from hu_lab.client import HUClient
        from hu_lab.protocol import CMD, VERSION_MATCH
        with tempfile.TemporaryDirectory() as directory:
            client = HUClient(None, None, directory, backend="DIRECT_USB_AOA", transport_factory=lambda: None)
            client.session.start()
            client._receive_packet(init(), None)
            client._receive_packet(Packet(MEDIA, MEDIA_DATA, b"1234"), None)
            self.assertEqual(client.audio_packets["MEDIA"], 0)
            self.assertEqual(client.native_stale_media_packets, 2)
            self.assertEqual(client.summary()["audio"]["lanes"]["MEDIA"]["state"], "IDLE")
            client._receive_packet(Packet(CMD, VERSION_MATCH, protobuf(f1=1)), None)
            client._receive_packet(init(), None)
            self.assertEqual(client.summary()["audio"]["lanes"]["MEDIA"]["generation"], 1)
            client.halt.set()
            client.stop()

    def test_fragmented_wire_through_client_creates_exact_wav(self):
        from hu_lab.client import HUClient
        with tempfile.TemporaryDirectory() as directory:
            client = HUClient("127.0.0.1", 1, directory, audio_capture=True)
            client.session.state = "STREAMING"
            pcm = struct.pack("<hhhh", 100, -100, 200, -200)
            wire = init().encode() + Packet(MEDIA, MEDIA_DATA, pcm, 123).encode()
            wire += Packet(MEDIA, MEDIA_PAUSE).encode()
            wire += Packet(MEDIA, MEDIA_DATA, b"skip", 124).encode()
            wire += Packet(MEDIA, MEDIA_RESUME).encode()
            wire += Packet(MEDIA, MEDIA_DATA, pcm[:4], 125).encode()
            wire += Packet(MEDIA, MEDIA_STOP).encode()
            parser = FrameParser()
            for offset in range(0, len(wire), 7):
                for packet in parser.feed(wire[offset:offset + 7]):
                    client._receive_packet(packet, None)
            parser.eof()
            self.assertEqual(client.summary()["audio"]["lanes"]["MEDIA"]["pcm_frames"], 3)
            self.assertEqual(client.audio_packets["MEDIA"], 7)
            self.assertIsNone(client.error)
            client.halt.set()
            client.stop()
            with wave.open(str(Path(directory) / "audio/media-0001.wav"), "rb") as wav:
                self.assertEqual(wav.readframes(10), pcm + pcm[:4])

    def test_bad_audio_does_not_end_video_session(self):
        from hu_lab.client import HUClient
        with tempfile.TemporaryDirectory() as directory:
            client = HUClient("127.0.0.1", 1, directory)
            client.session.state = "STREAMING"
            client._receive_packet(Packet(MEDIA, MEDIA_INIT, b"\x08"), None)
            client._receive_packet(Packet(VIDEO, VIDEO_HEARTBEAT), None)
            self.assertEqual(client.session.state, "STREAMING")
            self.assertIsNone(client.error)
            self.assertFalse(client.halt.is_set())
            self.assertEqual(client.summary()["audio"]["lanes"]["MEDIA"]["errors"], 1)
            client.halt.set()
            client.stop()
            self.assertEqual(client.summary()["audio"]["lanes"]["MEDIA"]["state"], "CLOSED")


if __name__ == "__main__":
    unittest.main()

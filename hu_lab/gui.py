"""Real native Qt CarLife HU window. All touch/back traverses CarLife packets."""
import json
from pathlib import Path
import time
from collections import deque
from .geometry import ViewportTransform, Rect
from .touch import two_pointer_gesture


class ActionSchedule:
    """One GUI-lifetime clock; an optional first-frame epoch is never reset."""
    def __init__(self, actions, started, after_streaming=False):
        self.pending=sorted(actions or [],key=lambda item:item[0])
        self.after_streaming=after_streaming
        self.epoch=None if after_streaming else started

    def observe(self, now, state, decoded_frames):
        if self.epoch is None and state=='STREAMING' and decoded_frames>0:
            self.epoch=now
            return True
        return False

    def elapsed(self, now):
        return None if self.epoch is None else now-self.epoch

    def pop_due(self, now):
        elapsed=self.elapsed(now)
        result=[]
        while self.pending and elapsed is not None and elapsed>=self.pending[0][0]:
            result.append(self.pending.pop(0))
        return result

    def pending_error(self, reason):
        if not self.after_streaming or not self.pending:return None
        return {'error':'scheduled actions pending at '+reason,'pending_actions':self.pending.copy(),
                'action_epoch_observed':self.epoch is not None}


def map_to_video(x, y, rectangle, width, height):
    left, top, w, h = rectangle
    if w <= 0 or h <= 0 or not (left <= x < left + w and top <= y < top + h):
        return None
    return (min(width - 1, int((x - left) * width / w)),
            min(height - 1, int((y - top) * height / h)))


def begin_mouse_contact(client, point, *, previously_pressed, double_click=False,
                        event_type="unknown", event_source="unknown"):
    """Translate a new host button event without weakening protocol touch state.

    A lost release/new press ends the prior gesture with an actual CarLife CANCEL.
    Qt may route a double-click callback while that button is already down; that
    callback does not start a second concurrent contact.
    """
    client.trace.event("host_mouse_press", event_type=event_type, event_source=event_source,
                       previously_pressed=previously_pressed, active_contact=client.pending_touch,
                       provenance="Qt event metadata; human versus automation not identifiable")
    if double_click and previously_pressed and client.pending_touch:
        client.trace.event("host_mouse_double_click_duplicate_ignored")
        return True
    if client.pending_touch:
        client.cancel_touch("new_host_mouse_press")
        client.trace.event("host_mouse_previous_contact_canceled")
    client.touch(0, *point)
    return True


def run_gui(host, port, output_dir, duration=60, width=1280, height=720, fps=30,
            actions=None, title="Mac HU Lab — CarLife HU", touch_profile="legacy-single",
            capture_limit_mib=None, trace_mode="full", display_policy="fit", rotation=0,
            source_size=None, source_policy="fit", allow_mode_fallback=False, on_decoded_frame=None,
            on_painted_frame=None, on_tick=None, on_action=None, scripted_only=False,
            transport_factory=None, backend="DEV_STREAM_OVER_ADB", on_live_actions=None,
            actions_after_streaming=False):
    from PySide6.QtCore import Qt, QRectF, QTimer, QEventLoop
    from PySide6.QtGui import QColor, QImage, QPainter, QTransform
    from PySide6.QtWidgets import QApplication, QLabel, QMainWindow, QPushButton, QHBoxLayout, QVBoxLayout, QWidget
    from .client import HUClient
    from .protocol import ProtocolError

    app = QApplication.instance() or QApplication([])
    # A session owns its event loop. Closing it must not quit another GUI run
    # sharing this QApplication, nor leave a queued global quit behind.
    event_loop = QEventLoop()
    closed = False
    options = dict(width=width, height=height, fps=fps, touch_profile=touch_profile,
                   capture_limit_mib=capture_limit_mib, trace_mode=trace_mode,
                   display_policy=display_policy, rotation=rotation, source_size=source_size,
                   source_policy=source_policy, allow_mode_fallback=allow_mode_fallback,
                   on_decoded_frame=on_decoded_frame, transport_factory=transport_factory, backend=backend)
    client = HUClient(host, port, output_dir, **options)
    reconnect_count = 0
    session_history = []
    gui_errors = []
    gesture_frames = deque()
    gesture_due = 0
    gesture_interval = .04

    class Video(QWidget):
        def __init__(self):
            super().__init__()
            self.image = None
            self.rectangle = QRectF()
            self.pressed = False
            self.frame_index = 0
            self.generation = -1
            self.transform = None
            self.last_geometry = None
            self.setMinimumSize(640, 360)
            self.setMouseTracking(True)

        def paintEvent(self, event):
            painter = QPainter(self)
            painter.fillRect(self.rect(), QColor("#10151c"))
            if self.image and self.generation == client.decoder_generation:
                self.transform = ViewportTransform(self.image.width(), self.image.height(),
                    Rect(0, 0, self.width(), self.height()), display_policy, rotation,
                    backing_scale=self.devicePixelRatioF())
                described = self.transform.describe()
                if described != self.last_geometry:
                    self.last_geometry = described
                    client.trace.event("viewport_geometry", generation=self.generation, **described)
                rectangle = self.transform.rectangle
                self.rectangle = QRectF(rectangle.x, rectangle.y, rectangle.width, rectangle.height)
                painter.setClipRect(self.rect())
                painter.setTransform(QTransform(*self.transform.affine))
                painter.drawImage(QRectF(0, 0, self.image.width(), self.image.height()), self.image)
                painter.end()
                client.mark_presented(self.frame_index, time.monotonic_ns())
                if on_painted_frame:
                    on_painted_frame(client, self.frame_index)
                return
            else:
                painter.setPen(QColor("#c5d0df"))
                painter.drawText(self.rect(), Qt.AlignCenter, "Waiting for Android display over CarLife…")

        def point(self, event):
            if self.transform is None or self.generation != client.decoder_generation:
                return None
            p = event.position()
            point = self.transform.view_to_video(p.x(), p.y())
            return point if point and client.input_content_contains(*point) else None

        def resizeEvent(self, event):
            if self.pressed:
                invoke(lambda: client.cancel_touch("HU_window_resize"))
                self.pressed = False
            super().resizeEvent(event)

        def mousePressEvent(self, event):
            if scripted_only:
                return
            p = self.point(event)
            if p and event.button() == Qt.LeftButton:
                double_click = event.type().name == "MouseButtonDblClick"
                if not (double_click and self.pressed and client.pending_touch):
                    gesture_frames.clear()
                if invoke(lambda: begin_mouse_contact(client, p, previously_pressed=self.pressed,
                    double_click=double_click, event_type=event.type().name, event_source=event.source().name)):
                    self.pressed = True
                event.accept()

        def mouseDoubleClickEvent(self, event):
            self.mousePressEvent(event)

        def mouseMoveEvent(self, event):
            if scripted_only:
                return
            if self.pressed and not client.pending_touch:
                self.pressed = False  # Home/Reset/timeout may have canceled the protocol contact.
            if self.pressed:
                p = self.point(event)
                if p:
                    invoke(lambda: client.touch(2, *p))
                else:
                    invoke(lambda: client.cancel_touch("pointer_left_viewport"))
                    self.pressed = False

        def mouseReleaseEvent(self, event):
            if scripted_only:
                return
            if self.pressed and not client.pending_touch:
                self.pressed = False
            if self.pressed:
                p = self.point(event)
                invoke(lambda: client.touch(1 if p else 3, *(p or (0, 0))))
                self.pressed = False

    class Window(QMainWindow):
        def closeEvent(self, event):
            nonlocal closed
            closed = True
            timer.stop()
            deadline_timer.stop()
            event.accept()
            event_loop.quit()

    window = Window()
    window.setWindowTitle(title)
    central = QWidget()
    layout = QVBoxLayout(central)
    banner = QLabel(f"CarLife HU  ·  {client.backend}  ·  SELF  ·  {client.touch_profile}")
    banner.setStyleSheet("background:#17324c;color:#ebf3ff;padding:10px;font-weight:bold")
    layout.addWidget(banner)
    codec_banner = QLabel("Capture source negotiation pending · diagnostic decoder · SPS validation pending")
    codec_banner.setStyleSheet("background:#493817;color:#fff0b4;padding:7px")
    layout.addWidget(codec_banner)
    controls = QHBoxLayout()
    status = QLabel("Connecting…")
    video = Video()
    message = QLabel("Video source: Android display → box capture → box MD → CarLife → Mac decoder")

    def invoke(fn):
        try:
            fn()
            return True
        except Exception as error:
            message.setText(str(error))
            gui_errors.append({"session": reconnect_count, "error": str(error)})
            client.trace.event("gui_action_error", error=str(error))
            return False

    for label, name in (("Home", "home"), ("Back", "back"), ("Pause", "pause"), ("Resume", "resume"), ("Reset", "reset")):
        button = QPushButton(label)
        button.clicked.connect(lambda checked=False, action=name: invoke(lambda: getattr(client, action)()))
        controls.addWidget(button)

    def schedule_gesture(kind="pinch-out", release_first=0, steps=12, interval=.04):
        nonlocal gesture_due, gesture_interval
        if client.touch_profile == "LEGACY_SINGLE":
            raise ProtocolError("explicit multi profile required for synthetic two-pointer gesture")
        if gesture_frames or client.pending_touch:
            raise ProtocolError("another gesture is active")
        gesture_frames.extend(two_pointer_gesture(client.session.width, client.session.height,
            kind, release_first, steps, client.decoder_generation))
        gesture_due, gesture_interval = time.monotonic(), interval

    if client.touch_profile != "LEGACY_SINGLE":
        for text, kind in (("Synthetic pinch +", "pinch-out"), ("Synthetic pinch −", "pinch-in"), ("Two-pointer pan", "pan")):
            button = QPushButton(text)
            button.clicked.connect(lambda checked=False, k=kind: invoke(lambda: schedule_gesture(k)))
            controls.addWidget(button)

    def reconnect():
        nonlocal client, reconnect_count, last_frame
        client.stop()
        session_history.append(client.summary())
        reconnect_count += 1
        client = HUClient(host, port, Path(output_dir) / f"reconnect-{reconnect_count}", **options)
        gesture_frames.clear()
        last_frame = 0
        video.image = None
        video.pressed = False
        video.frame_index = 0
        video.update()
        message.setText("Video source: Android display → box capture → box MD → CarLife → Mac decoder")
        codec_banner.setText("Capture source negotiation pending · diagnostic decoder · SPS validation pending")
        client.start()

    disconnect_button = QPushButton("Disconnect")
    disconnect_button.clicked.connect(lambda: invoke(client.stop))
    controls.addWidget(disconnect_button)
    reconnect_button = QPushButton("Reconnect")
    reconnect_button.clicked.connect(lambda: invoke(reconnect))
    controls.addWidget(reconnect_button)
    controls.addStretch()
    controls.addWidget(status)
    if scripted_only:
        for index in range(controls.count()):
            widget = controls.itemAt(index).widget()
            if isinstance(widget, QPushButton):
                widget.setEnabled(False)
        banner.setText(banner.text() + ' · SCRIPTED TEST: manual input disabled')
        client.trace.event('scripted_only_gui', manual_input_enabled=False)
    layout.addLayout(controls)
    layout.addWidget(video, 1)
    layout.addWidget(message)
    window.setCentralWidget(central)
    window.resize(1100, 740)
    window.show()
    start = time.monotonic()
    action_schedule = ActionSchedule(actions,start,actions_after_streaming)
    pending_error_recorded = False
    last_frame = 0
    screenshot_done = False

    def record_pending_actions(reason):
        nonlocal pending_error_recorded
        error=action_schedule.pending_error(reason)
        if error and not pending_error_recorded:
            pending_error_recorded=True
            gui_errors.append({'session':reconnect_count,**error})
            client.trace.event('gui_action_error',**error)

    def finish():
        if closed:
            return
        record_pending_actions('duration expiry')
        try:
            window.grab().save(str(Path(output_dir) / "hu-window.png"))
        finally:
            window.close()

    def dispatch_action(name, args):
        if name == "gesture":
            return invoke(lambda: schedule_gesture(*args))
        if name == "reconnect":
            return invoke(reconnect)
        if name in ("home", "pause", "reset", "cancel_touch"):
            gesture_frames.clear()
            video.pressed = False
        return invoke(lambda: getattr(client, name)(*args))

    def tick():
        nonlocal last_frame, screenshot_done, gesture_due
        now = time.monotonic()
        elapsed = now - start
        if on_tick:
            on_tick(client, elapsed)
        if on_live_actions:
            on_live_actions(client, elapsed, dispatch_action)
        with client.frame_lock:
            frame = client.latest_frame
        if action_schedule.observe(now,client.session.state,client.decoded_frames):
            client.trace.event('action_schedule_epoch',clock='first STREAMING with decoded frame',
                               gui_elapsed_seconds=elapsed,epoch_monotonic_seconds=action_schedule.epoch)
        if frame and frame[-1] != last_frame:
            pixels, w, h, stride, last_frame = frame
            video.frame_index = last_frame
            with client.metrics_lock:
                record = client.frame_records.get(last_frame)
            video.generation = record["generation"] if record else -1
            video.image = QImage(pixels, w, h, stride, QImage.Format_RGB888).copy()
            video.update()
        if video.generation != client.decoder_generation:
            video.image = None
            video.transform = None
            video.pressed = False
            gesture_frames.clear()
            video.update()
        if gesture_frames and time.monotonic() >= gesture_due:
            next_frame = gesture_frames.popleft()
            if not invoke(lambda: client.send_touch_frame(next_frame)):
                gesture_frames.clear()
            gesture_due = time.monotonic() + gesture_interval
        status.setText(f"{client.session.state} · frames {client.decoded_frames} · input {client.input_packets}")
        if client.sps:
            sps = client.sps
            codec_banner.setText(f"{client.source_name} · SPS {sps['profile_idc']}/{sps['level_idc'] / 10:g} · "
                f"{sps['display_width']}×{sps['display_height']} · {client.sps_validation['status']} · "
                "target HU acceptance NOT proven")
        for planned_at, name, args in action_schedule.pop_due(now):
            before_ns = time.monotonic_ns()
            client.trace.event('planned_action_begin', name=name, planned_at_seconds=planned_at,
                               elapsed_seconds=elapsed, action_elapsed_seconds=action_schedule.elapsed(now),
                               action_clock='first_streaming_frame' if actions_after_streaming else 'GUI_start',
                               generation=client.decoder_generation)
            if on_action:
                on_action(name, 'before')
            # Same public protocol methods as physical GUI events. No ADB input.
            dispatch_action(name, args)
            client.trace.event("scheduled_gui_action", name=name, arguments=args,
                               planned_at_seconds=planned_at, started_ns=before_ns,
                               ended_ns=time.monotonic_ns(), generation=client.decoder_generation)
            if on_action:
                on_action(name, 'after')
        if frame and not screenshot_done and elapsed >= min(5, duration / 2):
            window.grab().save(str(Path(output_dir) / "hu-window-live.png"))
            screenshot_done = True
        if client.error:
            message.setText(client.error)
        if elapsed >= duration:
            finish()

    timer = QTimer(window)
    timer.timeout.connect(tick)
    # Static QTimer.singleShot retains its callable after normal completion.
    # A later run can otherwise dispatch the expired callback and quit before
    # its first frame. Both timers are explicitly owned, stopped and detached.
    deadline_timer = QTimer(window)
    deadline_timer.setSingleShot(True)
    deadline_timer.timeout.connect(finish)
    try:
        client.start()
        timer.start(33)
        deadline_timer.start(max(1, int(duration * 1000) + 2000))
        event_loop.exec()
    finally:
        timer.stop()
        deadline_timer.stop()
        timer.timeout.disconnect(tick)
        deadline_timer.timeout.disconnect(finish)
        try:
            record_pending_actions('GUI close')
            client.stop()
        finally:
            window.close()
            timer.deleteLater()
            deadline_timer.deleteLater()
            window.deleteLater()
            event_loop.deleteLater()
    final = client.summary()
    session_history.append(final.copy())
    final["session_history"] = session_history
    final["gui_action_errors"] = gui_errors
    final["action_schedule"] = {"after_streaming":actions_after_streaming,
                                "epoch_monotonic_seconds":action_schedule.epoch,
                                "pending_actions":action_schedule.pending,
                                "epoch_scope":"GUI lifetime; unchanged by RESET/reconnect"}
    final["gui_geometry"] = video.last_geometry
    final["any_session_error"] = any(item["error"] for item in session_history) or bool(gui_errors)
    (Path(output_dir) / "gui-summary.json").write_text(json.dumps(final, indent=2) + "\n")
    return final

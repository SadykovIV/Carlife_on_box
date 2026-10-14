"""Actual Qt event dispatch through the production mouse-to-CarLife adapter."""
import os
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
import tempfile
import unittest
from pathlib import Path
from PySide6.QtCore import Qt, QPoint
from PySide6.QtWidgets import QApplication, QWidget
from PySide6.QtTest import QTest

from hu_lab.client import HUClient
from hu_lab.gui import begin_mouse_contact


class MouseEventsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.client = HUClient("127.0.0.1", 0, Path(self.tmp.name), touch_profile="action3-multi")
        self.client.session.state = "STREAMING"
        client = self.client
        class View(QWidget):
            pressed = False
            errors = []
            def mousePressEvent(self, event):
                try:
                    self.pressed = begin_mouse_contact(client, (50, 60), previously_pressed=self.pressed,
                        double_click=event.type().name == "MouseButtonDblClick",
                        event_type=event.type().name, event_source=event.source().name)
                except Exception as error:
                    self.errors.append(str(error))
                event.accept()
            def mouseDoubleClickEvent(self, event): self.mousePressEvent(event)
            def mouseReleaseEvent(self, event):
                if self.pressed and client.pending_touch: client.touch(1, 50, 60)
                self.pressed = False
                event.accept()
        self.view = View()
        self.view.show()
        self.app.processEvents()

    def tearDown(self):
        self.view.close()
        self.view.deleteLater()
        self.app.processEvents()
        # No transport was opened by this offline event test.
        self.client.halt.set()
        self.client.stop()
        self.tmp.cleanup()

    def actions(self):
        packets = list(self.client.commands.queue)
        return [int.from_bytes(p.payload[:4], "big") for p in packets]

    def test_second_press_cancels_old_contact_before_new_down(self):
        QTest.mousePress(self.view, Qt.LeftButton, pos=QPoint(5, 5))
        QTest.mousePress(self.view, Qt.LeftButton, pos=QPoint(5, 5))
        QTest.mouseRelease(self.view, Qt.LeftButton, pos=QPoint(5, 5))
        self.assertEqual(self.actions(), [0, 3, 0, 1])
        self.assertEqual(self.view.errors, [])
        self.assertFalse(self.client.pending_touch)
        self.assertEqual(self.client.trace.event_counts["host_mouse_previous_contact_canceled"], 1)

    def test_double_click_does_not_duplicate_existing_down_but_fresh_click_works(self):
        QTest.mousePress(self.view, Qt.LeftButton, pos=QPoint(5, 5))
        QTest.mouseDClick(self.view, Qt.LeftButton, pos=QPoint(5, 5))
        QTest.mouseRelease(self.view, Qt.LeftButton, pos=QPoint(5, 5))
        self.assertEqual(self.actions(), [0, 1])
        QTest.mouseDClick(self.view, Qt.LeftButton, pos=QPoint(5, 5))
        QTest.mouseRelease(self.view, Qt.LeftButton, pos=QPoint(5, 5))
        self.assertEqual(self.actions(), [0, 1, 0, 1])
        self.assertEqual(self.view.errors, [])
        self.assertEqual(self.client.trace.event_counts["host_mouse_double_click_duplicate_ignored"], 1)


if __name__ == "__main__": unittest.main()

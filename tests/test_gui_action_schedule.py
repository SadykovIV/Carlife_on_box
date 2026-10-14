import unittest
from hu_lab.gui import ActionSchedule


class ActionScheduleTests(unittest.TestCase):
    def test_delayed_stream_12_seconds_never_sends_early_home(self):
        s=ActionSchedule([[10,'home',[]],[25,'back',[]]],100,True)
        for now,state,count in [(110,'WAIT_VERSION',0),(111,'STREAMING',0),(111.5,'WAIT_INIT',1)]:
            self.assertFalse(s.observe(now,state,count));self.assertEqual(s.pop_due(now),[])
        self.assertTrue(s.observe(112,'STREAMING',1))
        self.assertEqual(s.pop_due(121.999),[])
        self.assertEqual(s.pop_due(122),[[10,'home',[]]])
        self.assertEqual(s.pop_due(137),[[25,'back',[]]])
    def test_default_clock_preserves_absolute_gui_start(self):
        s=ActionSchedule([[10,'home',[]]],100)
        self.assertFalse(s.observe(112,'STREAMING',1))
        self.assertEqual(s.pop_due(110),[[10,'home',[]]])
        self.assertIsNone(ActionSchedule([[99,'home',[]]],100).pending_error('duration expiry'))
    def test_epoch_does_not_reset_after_reset_or_new_client(self):
        s=ActionSchedule([[10,'reset',[]],[25,'home',[]]],100,True)
        s.observe(112,'STREAMING',1);self.assertEqual(s.pop_due(122),[[10,'reset',[]]])
        for now,state,count in [(123,'WAIT_INIT',0),(125,'STREAMING',1),(130,'WAIT_VERSION',0),(133,'STREAMING',1)]:
            self.assertFalse(s.observe(now,state,count))
        self.assertEqual(s.epoch,112);self.assertEqual(s.pop_due(137),[[25,'home',[]]])
    def test_pending_actions_at_deadline_are_explicit_error(self):
        s=ActionSchedule([[50,'home',[]]],100,True)
        self.assertFalse(s.pending_error('duration expiry')['action_epoch_observed'])
        s.observe(120,'STREAMING',1)
        self.assertTrue(s.pending_error('duration expiry')['action_epoch_observed'])
        self.assertIn('duration expiry',s.pending_error('duration expiry')['error'])
        s.pop_due(170);self.assertIsNone(s.pending_error('duration expiry'))
    def test_empty_optin_schedule_does_not_invent_error(self):
        self.assertIsNone(ActionSchedule([],0,True).pending_error('duration expiry'))


if __name__=='__main__':unittest.main()

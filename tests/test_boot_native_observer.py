"""Run the real C observer state machine against mock Linux syscalls on the host."""
import hashlib
import os
import shutil
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'tools/boot_native/observer/uevent-watch.c'
HARNESS=r'''
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#define BOOT_NATIVE_OBSERVER_TEST 1
#include "@SOURCE@"
static const char *scenario;
static char output[100000],marker_bytes[1024];
static int output_n,marker_n,rx,bound,marker_open,ready_before_bind,clock_calls;
static long ticks=10000;
static void add(char **p,const char*s){while(*s)*(*p)++=*s++;*(*p)++=0;}
static long observer_test_call(long n,long a,long b,long c,long d,long e,long f){
 (void)c;(void)d;(void)e;(void)f;
 if(n==113){clock_calls++;if(!strcmp(scenario,"clock_start_fail")||(!strcmp(scenario,"clock_loop_fail")&&clock_calls==2)||(!strcmp(scenario,"clock_event_fail")&&clock_calls==3))return -5;
  struct ts*t=(struct ts*)b;t->sec=ticks/1000;t->ns=ticks%1000*1000000;ticks+=!strcmp(scenario,"timeout")?500:1;return 0;}
 if(n==198)return !strcmp(scenario,"socket_fail")?-13:4;
 if(n==208)return 0;
 if(n==200){if(!strcmp(scenario,"bind_fail"))return -13;bound=1;return 0;}
 if(n==64){if(a==1){if(!bound)ready_before_bind=1;if(output_n+c>=100000)return -5;memcpy(output+output_n,(void*)b,c);output_n+=c;}
  else {memcpy(marker_bytes+marker_n,(void*)b,c);marker_n+=c;}return c;}
 if(n==73)return !strcmp(scenario,"timeout")?0:1;
 if(n==212){
  rx++;struct msg*m=(struct msg*)b;struct sa*s=(struct sa*)m->name;s->family=16;s->pid=(!strcmp(scenario,"foreign_sender")&&rx==1)?9:0;m->namelen=sizeof(*s);
  struct cm*cm=(struct cm*)m->control;cm->size=sizeof(*cm)+sizeof(struct cred);cm->level=1;cm->type=2;
  struct cred*cr=(struct cred*)(cm+1);cr->pid=0;cr->uid=(!strcmp(scenario,"foreign_cred")&&rx==1)?1000:0;cr->gid=0;m->controllen=(cm->size+7)&~7UL;
  if(!strcmp(scenario,"truncated"))m->flags=32;
  char *start=m->iov[0].base,*p=start;add(&p,"change@/devices/virtual/misc/mtp_usb");
  add(&p,(!strcmp(scenario,"foreign_path")&&rx==1)?"DEVPATH=/devices/foreign":"DEVPATH=/devices/virtual/misc/mtp_usb");
  add(&p,(!strcmp(scenario,"many")&&rx<=130)?"USB_STATE=CONFIGURED":"ACCESSORY=START");
  char seq[40];snprintf(seq,sizeof(seq),"SEQNUM=%d",rx);add(&p,seq);return p-start;}
 if(n==56){marker_open++;return 9;}
 if(n==82||n==57)return 0;
 return -99;
}
int main(int argc,char**argv){
 if(argc<3)return 90;scenario=argv[1];char*args[]={"uevent-watch","/owned/no-real-file",argc>3?argv[3]:"0123456789abcdef0123456789abcdef",argv[2],"/devices/virtual/misc/mtp_usb"};
 int rc=run(5,args);int lines=0;for(int i=0;i<output_n;i++)if(output[i]=='\n')lines++;
 printf("{\"rc\":%d,\"ready\":%s,\"ready_before_bind\":%s,\"lines\":%d,\"rx\":%d,\"marker_open\":%d,\"marker_bytes\":%d}",rc,strstr(output,"READY nonce=")?"true":"false",ready_before_bind?"true":"false",lines,rx,marker_open,marker_n);return 0;
}
'''

class ObserverTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory();p=Path(cls.tmp.name)
        source=p/'harness.c';source.write_text(HARNESS.replace('@SOURCE@',str(SOURCE)))
        cls.binary=p/'harness'
        subprocess.run([shutil.which(os.environ.get('CC', 'cc')),'-std=c11','-Wall','-Wextra',str(source),'-o',str(cls.binary)],capture_output=True,text=True,check=True)
    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()
    def run_case(self,scenario='start',seconds='0',nonce=None):
        args=[str(self.binary),scenario,seconds]+([nonce]if nonce else[])
        return json.loads(subprocess.check_output(args,text=True,timeout=2))
    def test_zero_one_sixty_validate_and_handle_start(self):
        for n in ('0','1','60'):
            with self.subTest(n=n):
                r=self.run_case(seconds=n);self.assertEqual(r['rc'],0);self.assertEqual(r['marker_open'],1)
    def test_invalid_seconds_never_open_socket_or_emit_ready(self):
        for n in ('61','999999999999999999999999999999999','-1','1x','', '000'):
            with self.subTest(n=n):
                r=self.run_case(seconds=n);self.assertEqual(r['rc'],64);self.assertFalse(r['ready']);self.assertEqual(r['marker_open'],0)
    def test_ready_only_after_successful_socket_and_bind(self):
        for scenario,rc in [('socket_fail',65),('bind_fail',67)]:
            r=self.run_case(scenario);self.assertEqual(r['rc'],rc);self.assertFalse(r['ready'])
        r=self.run_case();self.assertTrue(r['ready']);self.assertFalse(r['ready_before_bind'])
    def test_persistent_log_cap_does_not_drop_later_start(self):
        r=self.run_case('many','0');self.assertEqual(r['rc'],0);self.assertEqual(r['rx'],131)
        self.assertEqual(r['lines'],129);self.assertEqual(r['marker_open'],1);self.assertGreater(r['marker_bytes'],32)
    def test_positive_probe_event_limit_keeps_old_exit(self):
        r=self.run_case('many','60');self.assertEqual(r['rc'],76);self.assertEqual(r['rx'],129);self.assertEqual(r['marker_open'],0)
    def test_positive_probe_timeout_keeps_exit_two(self):
        for n in ('1','60'):
            r=self.run_case('timeout',n);self.assertEqual(r['rc'],2);self.assertTrue(r['ready'])
    def test_credential_sender_path_guards_ignore_false_start(self):
        for scenario in ('foreign_sender','foreign_cred','foreign_path'):
            r=self.run_case(scenario);self.assertEqual(r['rc'],0);self.assertEqual(r['rx'],2);self.assertEqual(r['marker_open'],1)
    def test_nonce_guard_and_truncated_message_unchanged(self):
        r=self.run_case(nonce='z'*32);self.assertEqual(r['rc'],64);self.assertFalse(r['ready'])
        r=self.run_case('truncated');self.assertEqual(r['rc'],72);self.assertEqual(r['marker_open'],0)
    def test_clock_errors_stop_without_fake_start(self):
        for scenario,rc in [('clock_start_fail',68),('clock_loop_fail',74),('clock_event_fail',74)]:
            r=self.run_case(scenario);self.assertEqual(r['rc'],rc);self.assertEqual(r['marker_open'],0)
    def test_actual_controller_zero_argument_matches_real_parser(self):
        controller=(ROOT/'tools/boot_native/runtime/runtime-controller.sh').read_text()
        self.assertIn('"$OBS_NONCE" >',controller)
        self.assertIn('"$3" "$4" 0 /devices/virtual/misc/mtp_usb',controller)
        self.assertEqual(self.run_case(seconds='0')['rc'],0)

if __name__=='__main__':unittest.main()

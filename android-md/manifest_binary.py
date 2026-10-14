#!/usr/bin/env python3
"""Build a resource-free APK binary XML manifest from a fixed schema (no SDK/aapt)."""
import struct
from pathlib import Path
S=[]
def idx(s):
    if s not in S:S.append(s)
    return S.index(s)
attrs={'label':0x01010001,'name':0x01010003,'hasCode':0x0101000c,'exported':0x01010010,'screenOrientation':0x0101001e,'configChanges':0x0101001f,'minSdkVersion':0x0101020c,'versionCode':0x0101021b,'versionName':0x0101021c,'targetSdkVersion':0x01010270,'allowBackup':0x01010280,'enableOnBackInvokedCallback':0x0101066c,'resizeableActivity':0x010104f6}
for s in attrs:idx(s)
for s in ['android','http://schemas.android.com/apk/res/android','manifest','package','org.carlife.hulab.stimulus','1.0','uses-sdk','application','CarLife HU Lab Stimulus','activity','.MainActivity','intent-filter','action','android.intent.action.MAIN','category','android.intent.category.LAUNCHER']:idx(s)
NS=idx('http://schemas.android.com/apk/res/android');NONE=0xffffffff
p16=lambda x:struct.pack('<H',x)
p32=lambda x:struct.pack('<I',x)
def chunk(t,head,body,hs=8):return p16(t)+p16(hs)+p32(8+len(head)+len(body))+head+body
def node(t,body):return chunk(t,p32(1)+p32(NONE),body,16)
def attr(name,value,typ=3,android=True):
    data=idx(value) if typ==3 else value
    return p32(NS if android else NONE)+p32(idx(name))+p32(data if typ==3 else NONE)+p16(8)+b'\0'+bytes([typ])+p32(data)
def start(tag,ats=[]):return node(0x102,p32(NONE)+p32(idx(tag))+p16(20)+p16(20)+p16(len(ats))+p16(0)*3+b''.join(ats))
def end(tag):return node(0x103,p32(NONE)+p32(idx(tag)))
body=node(0x100,p32(idx('android'))+p32(NS))
body+=start('manifest',[attr('package','org.carlife.hulab.stimulus',android=False),attr('versionCode',1,16),attr('versionName','1.0')])
body+=start('uses-sdk',[attr('minSdkVersion',21,16),attr('targetSdkVersion',35,16)])+end('uses-sdk')
body+=start('application',[attr('label','CarLife HU Lab Stimulus'),attr('hasCode',1,18),attr('allowBackup',0,18),attr('enableOnBackInvokedCallback',0,18)])
body+=start('activity',[attr('name','.MainActivity'),attr('exported',1,18),attr('screenOrientation',0,16),attr('configChanges',0x480,17),attr('resizeableActivity',1,18)])
body+=start('intent-filter')+start('action',[attr('name','android.intent.action.MAIN')])+end('action')+start('category',[attr('name','android.intent.category.LAUNCHER')])+end('category')+end('intent-filter')
body+=end('activity')+end('application')+end('manifest')+node(0x101,p32(idx('android'))+p32(NS))
strings=b'';offsets=[]
for s in S:
    b=s.encode();assert len(b)<128
    offsets.append(len(strings));strings+=bytes([len(s),len(b)])+b+b'\0'
strings+=b'\0'*((-len(strings))%4)
pool=chunk(1,p32(len(S))+p32(0)+p32(0x100)+p32(28+4*len(S))+p32(0),b''.join(p32(o) for o in offsets)+strings,28)
resources=chunk(0x180,b'',b''.join(p32(attrs[s]) for s in attrs))
xml=chunk(3,b'',pool+resources+body)
if __name__=='__main__':
    import sys
    Path(sys.argv[1]).write_bytes(xml)

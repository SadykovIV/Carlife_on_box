#!/usr/bin/env python3
"""Build the data-only early-runtime candidate. Never installs or selects it."""
import argparse,hashlib,io,json,pathlib,tarfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
HERE=pathlib.Path(__file__).resolve().parent
VERSION='boot-native-v1-20261014'
def sha(data):return hashlib.sha256(data).hexdigest()
def build(output):
    output=pathlib.Path(output);output.mkdir(parents=True,exist_ok=True)
    payload={
      'runtime-controller.sh':((HERE/'runtime/runtime-controller.sh').read_bytes(),0o500),
      'uevent-watch':((HERE/'observer/build/uevent-watch').read_bytes(),0o500),
      'md.jar':((HERE/'md/build/md-boot-native.jar').read_bytes(),0o400),
    }
    checks=''.join(f'{sha(data)}  {name}\n'for name,(data,mode)in sorted(payload.items())).encode()
    payload['payload.sha256']=(checks,0o400)
    files={'data/owner':(b'org.carlife.boot-native.v1\n',0o600),'data/current':((VERSION+'\n').encode(),0o600),'data/runtime-control.sh':((HERE/'runtime/runtime-control.sh').read_bytes(),0o500)}
    files.update({f'data/versions/{VERSION}/{name}':v for name,v in payload.items()})
    members={};tarpath=output/'boot-native-runtime.tar'
    dirs=['data','data/logs','data/runtime','data/versions','data/versions/'+VERSION]
    with tarfile.open(tarpath,'w',format=tarfile.USTAR_FORMAT)as tar:
        for name in sorted(dirs+list(files)):
            info=tarfile.TarInfo(name);info.uid=info.gid=0;info.uname=info.gname='';info.mtime=0
            if name in dirs:
                info.type=tarfile.DIRTYPE;info.mode=0o700;tar.addfile(info)
                members[name]={'type':'directory','mode':info.mode,'uid':0,'gid':0}
            else:
                data,mode=files[name];info.mode=mode;info.size=len(data);tar.addfile(info,io.BytesIO(data))
                members[name]={'type':'file','mode':mode,'uid':0,'gid':0,'size':len(data),'sha256':sha(data)}
    manifest={'schema':1,'contract':'BOOT_NATIVE_RUNTIME_V1','version':VERSION,'root':'/data/local/carlife-native','archive_sha256':sha(tarpath.read_bytes()),'members':members,'status':'CODE_READY_NOT_DEPLOYED','selector_included':False,'boot_hook_included':False}
    (output/'bundle-manifest.json').write_text(json.dumps(manifest,indent=2,sort_keys=True)+'\n')
    return manifest
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args();m=build(a.output);print(json.dumps({'archive':str(a.output/'boot-native-runtime.tar'),'sha256':m['archive_sha256'],'members':len(m['members'])}))

#!/usr/bin/env python3
"""Produce exact system overlay mapping; no device, image execution, or flashing."""
import argparse,difflib,hashlib,io,json,pathlib,re,sys,tarfile
HERE=pathlib.Path(__file__).resolve().parent
ROOT=next(p for p in HERE.parents if (p/'tools/boot_native/boot/carlife-owner.rc.in').is_file())
SYSTEM='/system/etc/carlife'
INIT_SHA='77c75a54b66f88e19caf26a011ded17a167a9ef190dab8bacd58d9031d5d6938'
CONSOLE_STARTS='on init && property:ro.debuggable=1\n    start console\n\non init && property:ro.debuggable=0\n    start console\n'

def sha(b):return hashlib.sha256(b).hexdigest()
def require(value,message):
    if not value:raise ValueError(message)
def load_runtime(archive,manifest):
    require(archive.is_file() and not archive.is_symlink() and archive.stat().st_size<16*1024*1024,'regular bounded archive')
    raw=archive.read_bytes();m=json.loads(manifest.read_text());require(sha(raw)==m['archive_sha256'],'archive SHA')
    require((m['schema'],m['contract'],m['root'])==(1,'BOOT_NATIVE_RUNTIME_V1','/data/local/carlife-native'),'runtime contract')
    require(m['version']=='boot-native-v1-20261014','reviewed runtime version')
    out={};entries=m['members']
    prefix='data/versions/boot-native-v1-20261014'
    expected={'data','data/current','data/owner','data/logs','data/runtime','data/runtime-control.sh','data/versions',prefix}|{prefix+'/'+n for n in ('md.jar','runtime-controller.sh','uevent-watch','payload.sha256')}
    require(set(entries)==expected,'exact runtime member set')
    with tarfile.open(fileobj=io.BytesIO(raw),mode='r:*')as t:
        for x in t:
            n=x.name.rstrip('/');require(n in entries and n not in out and n==str(pathlib.PurePosixPath(n))and '..'not in pathlib.PurePosixPath(n).parts,'archive path')
            e=entries[n];require((x.isfile()or x.isdir())and not(x.issym()or x.islnk())and x.uid==x.gid==0 and x.mode==e['mode'],'archive metadata')
            require(e['type']==('file'if x.isfile()else'directory'),'archive type')
            require(n=='data'or n.startswith('data/'),'archive root')
            if x.isfile():
                b=t.extractfile(x).read();require(len(b)==e['size']and sha(b)==e['sha256']and len(b)<8*1024*1024,'archive contents');out[n]=b
            else:out[n]=None
    require(set(out)==set(entries),'complete archive')
    return m,out

def conditional_old_pilot(text):
    old='    file /data/adb/modules/carlife_native_pilot_v3/disable 644 && empty_file /data/adb/modules/carlife_native_pilot_v3/disable || exit 70'
    require(text.count(old)==1,'exact resolver old guard')
    new='''    old=/data/adb/modules/carlife_native_pilot_v3
    if [ -e "$old" ] || [ -L "$old" ]; then
        [ -d "$old" ] && [ ! -L "$old" ] && [ "$(b readlink -f "$old")" = "$old" ] || exit 70
        file "$old/disable" 644 && empty_file "$old/disable" || exit 70
    fi'''
    return text.replace(old,new)

def build(archive,manifest,selector,init_path,oem_path,selection,output):
    require(selection in ('carlife','android_auto','carplay'),'explicit default selection')
    require(not output.exists(),'fresh output required');m,runtime=load_runtime(archive,manifest)
    init=init_path.read_bytes();require(sha(init)==INIT_SHA,'exact E init pin');oem=oem_path.read_bytes()
    require(init.count(b'service sd-reverse /system/bin/sd-reverse.sh\n')==1 and init.count(b'    start sd-reverse\n')==1,'OEM definition/start counts')
    original_resolver=runtime['data/runtime-control.sh']
    require(sha(original_resolver)=='b97b882340ec5e8f16f9e0cbd19aa18eb1cccc5e75bca1a278609d76f02d791a','exact old resolver')
    runtime['data/runtime-control.sh']=conditional_old_pilot(original_resolver.decode()).encode()
    require(sha(runtime['data/runtime-control.sh'])=='6add4df4d3aeb321a813e9d2262d755c7d3615f5ff0883bca08c3cb70fe36481','exact new resolver')
    # Immutable system seed and copied data metadata are separate contracts.
    for n,b in runtime.items():
        if b is not None:m['members'][n].update(size=len(b),sha256=sha(b))
    selector_manifest=json.loads((selector/'manifest.json').read_text());payload={}
    for n in ('selector-daemon.sh','selector-daemon.jar','selector-control.sh'):
        b=(selector/n).read_bytes();require(sha(b)==selector_manifest['payload'][n],'selector pin '+n);payload[n]=b
    payload.update(schema=b'1\n',selected=(selection+'\n').encode())
    output.mkdir(parents=True);files={};modes={}
    def add(path,b,mode=0o644):files[path]=b if isinstance(b,bytes)else b.encode();modes[path]=mode
    for n,b in runtime.items():
        if b is not None:add(SYSTEM+'/runtime-seed/'+n[5:],b)
    for n,b in payload.items():add(SYSTEM+'/selector-seed/'+n,b)
    templates=ROOT/'tools/boot_native/boot'
    pins={'@OEM_SHA@':sha(oem),'@RUNTIME_SHA@':sha(runtime['data/runtime-control.sh']),'@SELECTOR_SHA@':sha(payload['selector-daemon.sh'])}
    for n in ('carlife-early-dispatch.sh','carlife-native-entry.sh','carlife-selector-entry.sh'):
        s=(templates/(n+'.in')).read_text()
        for old,new in pins.items():s=s.replace(old,new)
        # Reject symlink/foreign old pilot; absence is normal on stock firmware.
        s=s.replace('if [ -d /data/adb/modules/carlife_native_pilot_v3 ]; then','if [ -e /data/adb/modules/carlife_native_pilot_v3 ] || [ -L /data/adb/modules/carlife_native_pilot_v3 ]; then\n                [ -d /data/adb/modules/carlife_native_pilot_v3 ] && [ ! -L /data/adb/modules/carlife_native_pilot_v3 ] && [ "$(b readlink -f /data/adb/modules/carlife_native_pilot_v3)" = /data/adb/modules/carlife_native_pilot_v3 ] || exit 70')
        if n=='carlife-early-dispatch.sh':
            s=s.replace('= /data/adb/modules/carlife_native_pilot_v3 ] || exit 70','= /data/adb/modules/carlife_native_pilot_v3 ] || { event OLD_LATE_HOOK_REFUSED; park; }')
            s=s.replace('while :; do\n    # /data/adb','while :; do\n    [ "$(b getprop sys.carlife.data_ready)" = 1 ] || { event DATA_PROVISION_PENDING; sleep 1; continue; }\n    # /data/adb',1)
        else:s=s.replace('umask 077','umask 077\n[ "$(getprop sys.carlife.data_ready)" = 1 ] || exit 70',1)
        add(SYSTEM+'/'+n.replace('carlife-',''),s,0o755)
    verify=[];verify_before=[];copy=[]
    for n,e in sorted(m['members'].items(),key=lambda p:(len(pathlib.PurePosixPath(p[0]).parts),p[0])):
        suffix=n[4:];dest='"$vr'+suffix+'"'
        if e['type']=='directory':
            verify.append(f'    dir {dest} {e["mode"]:o} || return 1');verify_before.append(verify[-1])
            if suffix:copy.append(f'    b mkdir -m {e["mode"]:o} "$PTMP{suffix}" || fail runtime_directory')
        else:
            verify.append(f'    file {dest} {e["mode"]:o} && hash_is {dest} {e["sha256"]} || return 1')
            expected_before=sha(original_resolver)if n=='data/runtime-control.sh'else e['sha256']
            verify_before.append(f'    file {dest} {e["mode"]:o} && hash_is {dest} {expected_before} || return 1')
            copy.append(f'    hash_is "$B/runtime-seed{suffix}" {e["sha256"]} && b cp "$B/runtime-seed{suffix}" "$PTMP{suffix}" && b chmod {e["mode"]:o} "$PTMP{suffix}" && b chown 0:0 "$PTMP{suffix}" || fail runtime_copy')
    sv=[];sc=[]
    for n,b in payload.items():
        mode=0o500 if n.startswith('selector-')else 0o600
        sc.append(f'    hash_is "$B/selector-seed/{n}" {sha(b)} && b cp "$B/selector-seed/{n}" "$STMP/{n}" && b chmod {mode:o} "$STMP/{n}" && b chown 0:0 "$STMP/{n}" || fail selector_copy')
        if n.startswith('selector-'):sv.append(f'    file "$vs/{n}" 500 && hash_is "$vs/{n}" {sha(b)} || return 1')
    gate='    : # Explicit CarLife default needs no OEM selector/property mutation.'
    if selection!='carlife':gate=f'    [ "$(b cat /sys/devices/platform/soc/soc:qpdata_driver/cp_aa)" = {2000 if selection=="android_auto"else 1000} ] || fail default_selection_oem_mismatch'
    provision=(HERE/'provision.sh.in').read_text()
    for token,value in {'@VERIFY_RUNTIME@':'\n'.join(verify),'@VERIFY_RUNTIME_BEFORE_MIGRATION@':'\n'.join(verify_before),'@OLD_RESOLVER_SHA@':sha(original_resolver),'@NEW_RESOLVER_SHA@':sha(runtime['data/runtime-control.sh']),'@COPY_RUNTIME@':'\n'.join(copy),'@VERIFY_SELECTOR@':'\n'.join(sv),'@COPY_SELECTOR@':'\n'.join(sc),'@DEFAULT_SELECTION_GATE@':gate}.items():provision=provision.replace(token,value)
    add(SYSTEM+'/provision.sh',provision,0o755)
    # Reviewable read-only classifier for a second box before installation.
    # It never runs migration, sets readiness, or modifies selection/runtime.
    preflight=provision.split('no_live_native_owner() {',1)[0]+'''
[ "$(b id -u)" = 0 ] && [ "$(b id -g)" = 0 ] || fail root_required
old_pilot_safe || fail existing_late_pilot_not_disabled
if [ ! -e "$P" ] && [ ! -L "$P" ]; then
    if [ -e "$S" ] || [ -L "$S" ]; then
        verify_selector "$S" || fail existing_selector_mismatch_preserved
        printf 'DATA_PROFILE=RUNTIME_SEED_PRESERVE_EXISTING_SELECTOR\\n'
    else printf 'DATA_PROFILE=FRESH_OWN_ROOTS_ABSENT\\n'; fi
elif verify_runtime "$P" && verify_selector "$S"; then
    printf 'DATA_PROFILE=EXACT_CURRENT_SEED\\n'
elif verify_runtime_before_migration "$P" && verify_selector "$S"; then
    printf 'DATA_PROFILE=EXACT_B97_RESOLVER_MIGRATION_ON_NEW_BOOT\\n'
else fail unknown_existing_data_preserved; fi
printf 'READ_ONLY=true\\nHARDWARE_AND_BOOT_COMPATIBILITY=NOT_TESTED_BY_THIS_CHECK\\n'
'''
    rc=(templates/'carlife-owner.rc.in').read_text()
    start=rc.index('service carlife-native ');rc=rc[start:]
    rc=rc.replace('${MAGISKTMP}/carlife-native-entry.sh',SYSTEM+'/native-entry.sh').replace('${MAGISKTMP}/carlife-selector-entry.sh',SYSTEM+'/selector-entry.sh')
    rc=rc.replace('on post-fs-data\n    start carlife-selector','''service carlife-provision /system/bin/sh /system/etc/carlife/provision.sh
    class core
    disabled
    oneshot
    user root
    group root
    seclabel u:r:init:s0

on post-fs-data
    start carlife-provision

on property:sys.carlife.data_ready=1
    start carlife-selector''')
    require(init.count(CONSOLE_STARTS.encode())==1,'exact console start pair')
    baseinit=init.replace(CONSOLE_STARTS.encode(),b'# Serial shell stays disabled; kernel UART consoles are disabled in boot arguments.\n')
    newinit=baseinit.replace(b'service sd-reverse /system/bin/sd-reverse.sh\n',b'service sd-reverse /system/bin/sh /system/etc/carlife/early-dispatch.sh\n')+b'\n# CARLIFE_SYSTEM_BOOT_V1: no Magisk overlay or startup dependency.\n'+rc.encode()
    add('/system/etc/init/hw/init.rc',newinit)
    mapping=[];dirs=set()
    for n,b in sorted(files.items()):
        p=output/'files'/n.lstrip('/');p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
        mapping.append({'source':str(p.resolve()),'path':n,'uid':0,'gid':0,'mode':f'{modes[n]:04o}','selinux':'u:object_r:system_file:s0','sha256':sha(b),'size':len(b)})
        parent=pathlib.PurePosixPath(n).parent
        while str(parent).startswith(SYSTEM):dirs.add(str(parent));parent=parent.parent
    (output/'overlay-map.json').write_text(json.dumps({'files':mapping,'directories':[{'path':p,'uid':0,'gid':0,'mode':'0755','selinux':'u:object_r:system_file:s0'}for p in sorted(dirs)]},indent=2)+'\n')
    (output/'preflight-existing-data.sh').write_text(preflight)
    for name,old,new in [('init.rc',init,newinit),('runtime-control.sh',original_resolver,runtime['data/runtime-control.sh'])]:
        (output/(name+'.patch')).write_text(''.join(difflib.unified_diff(old.decode().splitlines(True),new.decode().splitlines(True),fromfile='original/'+name,tofile='firmware/'+name)))
    result={'scope':'OFFLINE_SYSTEM_INTEGRATION_CANDIDATE_NOT_DEPLOYED','default_selection':selection,'runtime_archive':str(archive.resolve()),'runtime_archive_sha256':sha(archive.read_bytes()),'runtime_manifest_sha256':sha(manifest.read_bytes()),'selector_manifest_sha256':sha((selector/'manifest.json').read_bytes()),'original_init_sha256':sha(init),'new_init_sha256':sha(newinit),'oem_sd_reverse_sha256':sha(oem),'added_regular_bytes_excluding_init':sum(len(b)for n,b in files.items()if n!='/system/etc/init/hw/init.rc'),'mapped_file_count':len(mapping),'runtime_controller_unchanged':sha(runtime['data/versions/boot-native-v1-20261014/runtime-controller.sh'])==m['members']['data/versions/boot-native-v1-20261014/runtime-controller.sh']['sha256'],'policy_changes':False,'magisk_dependency':False,'runtime_validation':'NOT_TESTED'}
    (output/'BUILD.json').write_text(json.dumps(result,indent=2)+'\n');return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--runtime',type=pathlib.Path,required=True);p.add_argument('--runtime-manifest',type=pathlib.Path,required=True);p.add_argument('--selector-release',type=pathlib.Path,required=True);p.add_argument('--init',type=pathlib.Path,required=True);p.add_argument('--oem-script',type=pathlib.Path,required=True);p.add_argument('--default-selection',choices=['carlife','android_auto','carplay'],required=True);p.add_argument('--output',type=pathlib.Path,required=True)
    a=p.parse_args();print(json.dumps(build(a.runtime,a.runtime_manifest,a.selector_release,a.init,a.oem_script,a.default_selection,a.output),indent=2))
if __name__=='__main__':main()

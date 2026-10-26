"""Validate the final signed launcher and the exact bundled cinema payload."""
import hashlib
import io
import json
import struct
import sys
import zipfile
from pathlib import Path, PurePosixPath

def verify(path):
    path=Path(path)
    with zipfile.ZipFile(path) as apk, path.open('rb') as raw:
        names=apk.namelist()
        assert len(names)==len(set(names)), 'Duplicate APK ZIP entries'
        assert {'AndroidManifest.xml','resources.arsc','classes.dex'} <= set(names), 'Missing APK components'
        for name in names:
            p=PurePosixPath(name)
            assert not p.is_absolute() and '..' not in p.parts and '\\' not in name, 'Invalid APK ZIP path'
        assert apk.testzip() is None, 'APK ZIP CRC failure'
        resource=apk.getinfo('resources.arsc')
        assert resource.compress_type==zipfile.ZIP_STORED, 'Compressed Android resource table'
        raw.seek(resource.header_offset+26)
        a,b=struct.unpack('<HH',raw.read(4))
        assert (resource.header_offset+30+a+b)%4==0, 'Unaligned Android resource table'
        raw.seek(apk.start_dir-24)
        size,magic=struct.unpack('<Q16s',raw.read(24))
        assert magic==b'APK Sig Block 42' and size>=24, 'Missing APK signing block'
        raw.seek(apk.start_dir-size-8)
        assert struct.unpack('<Q',raw.read(8))[0]==size, 'Malformed APK signing block'
        cinema=[n for n in names if n.startswith('assets/kirazium/mods/dreamdisplays-') and n.endswith('.jar')]
        expected='assets/kirazium/mods/dreamdisplays-fabric-1.21.11-1.10.4-kirazium-android-vtt1.jar'
        assert cinema==[expected], 'Unexpected or duplicate bundled cinema JAR'
        data=apk.read(expected)
        assert hashlib.sha256(data).hexdigest()=='827aac742642f428c9709a63852e8dba3ff96757f3ba353a3932f0fa1a39db10', 'Wrong cinema payload'
        with zipfile.ZipFile(io.BytesIO(data)) as jar:
            assert jar.testzip() is None, 'Cinema JAR CRC failure'
            expected_natives = {
                'dreamdisplays-natives/linux-aarch64/libdreamdisplays_lav.so': '7e644e354f0935ce46a3e0882f8b47e765bbcc1e76ca162bf8f30ad09ac0bbfa',
                'dreamdisplays-natives/linux-aarch64/libdreamdisplays_native.so': '82c123e930dde8e42bc2897b20c9f6ad9e864d2de7ebff5cab8b848089f930ab'
            }
            for native, digest in expected_natives.items():
                assert hashlib.sha256(jar.read(native)).hexdigest() == digest, 'Desktop native payload in mobile JAR'
            assert 'com/dreamdisplays/media/player/pipeline/AndroidOpenALLine.class' in jar.namelist()
            mod=json.loads(jar.read('fabric.mod.json'))
            assert mod['version']=='1.10.4-kirazium-android-vtt1' and mod['depends']['minecraft']=='1.21.11'
        with zipfile.ZipFile(io.BytesIO(apk.read('assets/kirazium/modpacks/sodium-boost-mx-1.21.11.mrpack'))) as pack:
            assert pack.testzip() is None, 'Modpack CRC failure'
            assert json.loads(pack.read('modrinth.index.json'))['dependencies']=={'minecraft':'1.21.11','fabric-loader':'0.19.5'}
        for abi in ['arm64-v8a','x86_64']:
            assert any(n.startswith('lib/'+abi+'/') and n.endswith('.so') for n in names), 'Missing native ABI '+abi
    print('PASS: final signed APK ZIP/CRC, resources, signing block, native ABIs, DreamDisplays and modpack')

if __name__=='__main__':
    verify(sys.argv[1])

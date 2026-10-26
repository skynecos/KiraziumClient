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
        expected='assets/kirazium/mods/dreamdisplays-fabric-1.21.11-1.10.4-kirazium-vtt1.jar'
        assert cinema==[expected], 'Unexpected or duplicate bundled cinema JAR'
        data=apk.read(expected)
        assert hashlib.sha256(data).hexdigest()=='b1db4768aab5ebab3e8fa81b862ca955d71e7cf77d878547af1b4215a053738c', 'Wrong cinema payload'
        with zipfile.ZipFile(io.BytesIO(data)) as jar:
            assert jar.testzip() is None, 'Cinema JAR CRC failure'
            mod=json.loads(jar.read('fabric.mod.json'))
            assert mod['version']=='1.10.4-kirazium-vtt1' and mod['depends']['minecraft']=='1.21.11'
        with zipfile.ZipFile(io.BytesIO(apk.read('assets/kirazium/modpacks/sodium-boost-mx-1.21.11.mrpack'))) as pack:
            assert pack.testzip() is None, 'Modpack CRC failure'
            assert json.loads(pack.read('modrinth.index.json'))['dependencies']=={'minecraft':'1.21.11','fabric-loader':'0.19.5'}
        for abi in ['arm64-v8a','x86_64']:
            assert any(n.startswith('lib/'+abi+'/') and n.endswith('.so') for n in names), 'Missing native ABI '+abi
    print('PASS: final signed APK ZIP/CRC, resources, signing block, native ABIs, DreamDisplays and modpack')

if __name__=='__main__':
    verify(sys.argv[1])

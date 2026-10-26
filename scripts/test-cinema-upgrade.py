"""Run the actual installer against filesystem-backed Android API stubs."""
from pathlib import Path
import os, subprocess, tempfile, shutil, zipfile, json, hashlib

repo = Path(__file__).resolve().parents[1]
main = repo / 'app_pojavlauncher/src/main'
package = 'net/kdt/pojavlaunch/instances'
old_name = 'dreamdisplays-fabric-1.21.11-1.9.5-kirazium-android-mobilefix1.jar'
new_name = 'dreamdisplays-fabric-1.21.11-1.10.4-kirazium-vtt1.jar'
if subprocess.run(['git','cat-file','-e','43fae062d82184b9300ae1b40d61cf3fbd5fe3ed^{commit}'],cwd=repo,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
    subprocess.run(['git','fetch','--depth=1','origin','43fae062d82184b9300ae1b40d61cf3fbd5fe3ed'],cwd=repo,check=True)
old = subprocess.check_output(['git','show','43fae062d82184b9300ae1b40d61cf3fbd5fe3ed:app_pojavlauncher/src/main/assets/kirazium/mods/'+old_name],cwd=repo)
assert hashlib.sha256(old).hexdigest() == '01f4a9a9fbab1fad686d2d62b8210d7dd28686bca3551537e158f15b100f6fc9'
with tempfile.TemporaryDirectory(prefix='kirazium-cinema-test-') as td:
    root = Path(td)
    def source(name, content):
        p=root/'src'/name
        p.parent.mkdir(parents=True,exist_ok=True)
        p.write_text(content,encoding='utf-8')
    source('android/content/res/AssetManager.java', '''package android.content.res; import java.io.*;
public class AssetManager { final File root; public AssetManager(File r){root=r;} public InputStream open(String n)throws IOException{return new FileInputStream(new File(root,n));}}''')
    source('android/content/Context.java', '''package android.content; import java.io.*; import android.content.res.AssetManager;
public class Context { private final AssetManager assets; public Context(File r){assets=new AssetManager(r);} public AssetManager getAssets(){return assets;}}''')
    source('android/util/Log.java','''package android.util; public class Log { public static int i(String a,String b){return 0;} public static int e(String a,String b){return 0;} public static int w(String a,String b,Throwable c){return 0;} }''')
    source('net/kdt/pojavlaunch/Tools.java','''package net.kdt.pojavlaunch; import java.io.*; import java.nio.file.*; public class Tools { public static File DIR_GAME_HOME; public static void write(File p,String v)throws IOException{Files.writeString(p.toPath(),v);} }''')
    source('net/kdt/pojavlaunch/utils/FileUtils.java','''package net.kdt.pojavlaunch.utils; import java.io.*; public class FileUtils {public static void ensureDirectory(File f)throws IOException{if(!f.isDirectory()&&!f.mkdirs())throw new IOException();} public static void ensureParentDirectory(File f)throws IOException{ensureDirectory(f.getParentFile());}}''')
    source('org/apache/commons/io/FileUtils.java','''package org.apache.commons.io; import java.io.*; import java.nio.file.*; public class FileUtils {public static void deleteDirectory(File f)throws IOException{try(var s=Files.walk(f.toPath())){for(Path p:s.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(p);}}}''')
    source(package+'/Instance.java','''package net.kdt.pojavlaunch.instances; import java.io.*; public class Instance {public String name="Kirazium"; private final File dir; public Instance(File d){dir=d;} public File getGameDirectory(){return dir;}}''')
    source(package+'/Instances.java','''package net.kdt.pojavlaunch.instances; import java.io.*; import java.util.*; public class Instances {public static List<Instance> loadAllInstances()throws IOException{return List.of();}}''')
    source(package+'/KiraziumBootstrap.java','''package net.kdt.pojavlaunch.instances; public class KiraziumBootstrap {public static final String PROFILE_NAME="Kirazium";}''')
    for name in ['KiraziumCinemaInstaller.java','KiraziumDefaultModpackInstaller.java']:
        source(package+'/'+name,(main/'java'/package/name).read_text(encoding='utf-8'))
    source(package+'/CinemaUpgradeTest.java', '''package net.kdt.pojavlaunch.instances;
import java.io.*; import java.nio.file.*; import android.content.Context;
public class CinemaUpgradeTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]); Context context=new Context(new File(args[1]));
  String oldName=args[2],newName=args[3]; Path old=Path.of(args[4]);
  Path existing=root.resolve("existing"),mods=existing.resolve("mods"); Files.createDirectories(mods);
  Files.copy(old,mods.resolve(oldName)); Files.writeString(mods.resolve("custom-mod.jar"),"keep");
  Files.writeString(existing.resolve("options.txt"),"player-settings"); Files.writeString(existing.resolve(".kirazium-default-1.21.11-v1"),"old");
  KiraziumDefaultModpackInstaller.ensureInstalled(context,new Instance(existing.toFile()));
  check(Files.exists(mods.resolve(newName)),"new cinema missing"); check(!Files.exists(mods.resolve(oldName)),"old cinema remains");
  check(Files.readString(mods.resolve("custom-mod.jar")).equals("keep"),"custom mod changed");
  check(Files.readString(existing.resolve("options.txt")).equals("player-settings"),"settings changed");
  check(!Files.exists(existing.resolve("mods.kirazium-pre-1.21.11-backup")),"pack unnecessarily reinstalled");
  KiraziumDefaultModpackInstaller.ensureInstalled(context,new Instance(existing.toFile()));
  check(Files.list(mods).filter(p->p.toString().endsWith(".jar")).count()==2,"duplicate mod on repeat");
  Path custom=root.resolve("custom/mods"); Files.createDirectories(custom); Files.writeString(custom.resolve(oldName),"user-build");
  KiraziumCinemaInstaller.installInto(context,custom.getParent().toFile());
  check(Files.readString(custom.resolve(oldName)).equals("user-build"),"custom build overwritten"); check(!Files.exists(custom.resolve(newName)),"duplicate manual override");
  Path fresh=root.resolve("fresh"); KiraziumDefaultModpackInstaller.ensureInstalled(context,new Instance(fresh.toFile()));
  check(Files.exists(fresh.resolve("mods/"+newName)),"fresh install missing cinema");
  check(Files.list(fresh.resolve("mods")).filter(p->p.toString().endsWith(".jar")).count()==18,"fresh mod count");
  Path bad=root.resolve("bad/mods"); Files.createDirectories(bad); Files.copy(old,bad.resolve(oldName));
  boolean failed=false; try{KiraziumCinemaInstaller.installInto(new Context(root.resolve("bad-assets").toFile()),bad.getParent().toFile());}catch(IOException e){failed=true;}
  check(failed&&Files.exists(bad.resolve(oldName)),"failed update destroyed old mod");
  System.out.println("PASS: managed upgrade, idempotence, other mods/settings, manual override, fresh install, failed update");
 }
}''')
    (root/'old.jar').write_bytes(old)
    java_home=Path(os.environ.get('JAVA_HOME',''))
    javac=str(java_home/'bin/javac') if (java_home/'bin/javac').exists() else shutil.which('javac')
    java=str(java_home/'bin/java') if (java_home/'bin/java').exists() else shutil.which('java')
    subprocess.run([javac,'-encoding','UTF-8','-d',str(root/'classes'),*[str(p) for p in (root/'src').rglob('*.java')]],check=True)
    subprocess.run([java,'-cp',str(root/'classes'),'net.kdt.pojavlaunch.instances.CinemaUpgradeTest',str(root/'data'),str(main/'assets'),old_name,new_name,str(root/'old.jar')],check=True)

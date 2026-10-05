package net.kdt.pojavlaunch.instances;

import android.content.Context;
import android.util.Log;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.FileUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Installs the exact bundled 1.21.11 Kirazium modpack into the default profile. */
public final class KiraziumDefaultModpackInstaller {
    private static final String TAG = "KiraziumModpack";
    private static final String PACK_ASSET =
            "kirazium/modpacks/sodium-boost-mx-1.21.11.mrpack";
    private static final String PACK_SHA256 =
            "08fc89fb4462c19826efc248438190b37c9e202e4108d8183a8a7d1ab06df8f0";
    private static final long PACK_SIZE = 15_709_227L;
    private static final String CINEMA_ASSET =
            "kirazium/mods/dreamdisplays-fabric-1.21.11-1.9.5-kirazium-android-mobilefix1.jar";
    private static final String CINEMA_FILE =
            "dreamdisplays-fabric-1.21.11-1.9.5-kirazium-android-mobilefix1.jar";
    private static final String CINEMA_SHA256 =
            "01f4a9a9fbab1fad686d2d62b8210d7dd28686bca3551537e158f15b100f6fc9";
    private static final long CINEMA_SIZE = 22_983_449L;
    private static final String MARKER_NAME = ".kirazium-default-1.21.11-v1";

    private KiraziumDefaultModpackInstaller() {}

    public static void ensureInstalled(Context context, Instance instance) throws IOException {
        if (context == null || instance == null ||
                !KiraziumBootstrap.PROFILE_NAME.equals(instance.name)) return;

        File gameDirectory = instance.getGameDirectory();
        File marker = new File(gameDirectory, MARKER_NAME);
        File cinema = new File(new File(gameDirectory, "mods"), CINEMA_FILE);
        if (marker.isFile() && isExact(cinema, CINEMA_SIZE, CINEMA_SHA256)) return;

        FileUtils.ensureDirectory(gameDirectory);
        File workDirectory = new File(gameDirectory, ".kirazium-pack-install-v1");
        if (workDirectory.exists()) org.apache.commons.io.FileUtils.deleteDirectory(workDirectory);
        FileUtils.ensureDirectory(workDirectory);

        File cachedPack = new File(workDirectory, "pack.mrpack");
        try {
            copyVerifiedAsset(context, PACK_ASSET, cachedPack, PACK_SIZE, PACK_SHA256);
            extractOverrides(cachedPack, workDirectory);

            File stagedMods = new File(workDirectory, "mods");
            FileUtils.ensureDirectory(stagedMods);
            File stagedCinema = new File(stagedMods, CINEMA_FILE);
            copyVerifiedAsset(context, CINEMA_ASSET, stagedCinema, CINEMA_SIZE, CINEMA_SHA256);

            File activeMods = new File(gameDirectory, "mods");
            File backupMods = nextBackupDirectory(gameDirectory);
            boolean backedUp = false;
            if (activeMods.exists()) {
                if (!activeMods.renameTo(backupMods)) {
                    throw new IOException("Mevcut mod klasoru yedeklenemedi");
                }
                backedUp = true;
            }

            if (!stagedMods.renameTo(activeMods)) {
                if (backedUp && !backupMods.renameTo(activeMods)) {
                    Log.e(TAG, "Mod klasoru kurulamadigi gibi yedegi de geri yuklenemedi: " +
                            backupMods.getAbsolutePath());
                }
                throw new IOException("Kirazium 1.21.11 modpack etkinlestirilemedi");
            }

            Tools.write(marker,
                    "minecraft=1.21.11\n" +
                    "fabric-loader=0.19.2\n" +
                    "modpack-sha256=" + PACK_SHA256 + "\n" +
                    "dreamdisplays-sha256=" + CINEMA_SHA256 + "\n");
            Log.i(TAG, "Installed exact bundled Fabric 1.21.11 modpack and Dream Displays");
        } finally {
            if (workDirectory.exists()) {
                org.apache.commons.io.FileUtils.deleteDirectory(workDirectory);
            }
        }
    }

    private static File nextBackupDirectory(File gameDirectory) {
        File backup = new File(gameDirectory, "mods.kirazium-pre-1.21.11-backup");
        int suffix = 1;
        while (backup.exists()) {
            backup = new File(gameDirectory,
                    "mods.kirazium-pre-1.21.11-backup-" + suffix++);
        }
        return backup;
    }

    private static void extractOverrides(File pack, File destination) throws IOException {
        try (ZipFile zip = new ZipFile(pack)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                String relative = null;
                if (name.startsWith("overrides/")) {
                    relative = name.substring("overrides/".length());
                } else if (name.startsWith("client-overrides/")) {
                    relative = name.substring("client-overrides/".length());
                }
                if (relative == null || relative.isEmpty()) continue;

                File target = safeDestination(destination, relative);
                if (entry.isDirectory()) {
                    FileUtils.ensureDirectory(target);
                    continue;
                }
                FileUtils.ensureParentDirectory(target);
                try (InputStream input = new BufferedInputStream(zip.getInputStream(entry));
                     OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                    copy(input, output);
                }
            }
        }
    }

    private static File safeDestination(File base, String relative) throws IOException {
        File destination = new File(base, relative);
        String basePath = base.getCanonicalPath() + File.separator;
        if (!destination.getCanonicalPath().startsWith(basePath)) {
            throw new IOException("Guvenli olmayan modpack yolu: " + relative);
        }
        return destination;
    }

    private static void copyVerifiedAsset(Context context, String asset, File destination,
                                          long expectedSize, String expectedSha256)
            throws IOException {
        FileUtils.ensureParentDirectory(destination);
        try (InputStream input = new BufferedInputStream(context.getAssets().open(asset));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            copy(input, output);
        }
        if (!isExact(destination, expectedSize, expectedSha256)) {
            destination.delete();
            throw new IOException("Gomulu Kirazium dosyasi dogrulanamadi: " + asset);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private static boolean isExact(File file, long size, String expectedSha256)
            throws IOException {
        return file.isFile() && file.length() == size && expectedSha256.equals(sha256(file));
    }

    private static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) {
            value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return value.toString();
    }
}

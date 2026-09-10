package net.kdt.pojavlaunch.instances;

import android.content.Context;
import android.util.Log;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Installs the embedded Kirazium cinema mod into the game directory Minecraft actually launches. */
public final class KiraziumCinemaInstaller {
    private static final String TAG = "KiraziumCinemaInstaller";
    private static final String FILE_NAME =
            "dreamdisplays-fabric-26.1.2-1.9.5-kirazium-android-stallfix1.jar";
    private static final String ASSET_PATH = "kirazium/mods/" + FILE_NAME;
    private static final String SHA256 =
            "918872694b9fe437b6c412dda287e717d33b654a1bf8885af0ceea0ed38caab1";
    private static final long SIZE = 23_595_235L;

    private KiraziumCinemaInstaller() {}

    public static void ensureInstalled(Context context) {
        if (context == null || Tools.DIR_GAME_HOME == null) return;

        Set<String> gameDirectories = new LinkedHashSet<>();
        // The stock Kirazium profile is sharedData=true, so this is its real game directory.
        gameDirectories.add(Instances.SHARED_DATA_DIRECTORY.getAbsolutePath());

        try {
            List<Instance> instances = Instances.loadAllInstances();
            for (Instance instance : instances) {
                if (KiraziumBootstrap.PROFILE_NAME.equals(instance.name)) {
                    gameDirectories.add(instance.getGameDirectory().getAbsolutePath());
                }
            }
        } catch (IOException exception) {
            Log.w(TAG, "Could not enumerate Kirazium instances; shared directory fallback will be used",
                    exception);
        }

        for (String path : gameDirectories) {
            try {
                installInto(context, new File(path));
            } catch (IOException exception) {
                Log.w(TAG, "Could not install DreamDisplays into " + path, exception);
            }
        }
    }

    private static void installInto(Context context, File gameDirectory) throws IOException {
        File modsDirectory = new File(gameDirectory, "mods");
        FileUtils.ensureDirectory(modsDirectory);

        File destination = new File(modsDirectory, FILE_NAME);
        File manualOverride = findManualDreamDisplays(modsDirectory, destination);
        if (manualOverride != null) {
            // A manually supplied DreamDisplays JAR always wins. If the launcher-managed stable JAR
            // is also present, remove only that exact managed copy so Fabric never sees duplicates.
            if (isExact(destination)) {
                disableManagedEmbedded(destination);
            }
            Log.i(TAG, "Manual DreamDisplays override detected; leaving it untouched: "
                    + manualOverride.getAbsolutePath());
            return;
        }

        // No manual override exists. Keep the exact managed fallback if it is already installed.
        if (isExact(destination)) return;

        // If a file with the managed filename exists but is not our exact payload, treat it as user-owned.
        // Never overwrite an unknown/custom DreamDisplays build merely because the filename matches.
        if (destination.isFile()) {
            Log.i(TAG, "Custom DreamDisplays file uses managed filename; leaving it untouched: "
                    + destination.getAbsolutePath());
            return;
        }

        File temporary = new File(modsDirectory, FILE_NAME + ".tmp");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Could not remove stale cinema temporary file");
        }

        try (InputStream input = context.getAssets().open(ASSET_PATH);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }

        if (!isExact(temporary)) {
            temporary.delete();
            throw new IOException("Embedded DreamDisplays checksum mismatch");
        }

        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not activate DreamDisplays mod");
        }

        Log.i(TAG, "Installed exact DreamDisplays stallfix1 fallback into "
                + destination.getAbsolutePath());
    }

    private static File findManualDreamDisplays(File modsDirectory, File managedDestination)
            throws IOException {
        File[] files = modsDirectory.listFiles();
        if (files == null) return null;

        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jar") || !name.contains("dreamdisplays")) continue;

            // The one exact launcher-managed fallback is not a manual override.
            if (file.equals(managedDestination) && isExact(file)) continue;
            return file;
        }
        return null;
    }

    private static void disableManagedEmbedded(File managedDestination) throws IOException {
        if (!isExact(managedDestination)) return;
        if (managedDestination.delete()) {
            Log.i(TAG, "Removed launcher-managed stallfix1 because a manual DreamDisplays override exists.");
            return;
        }

        File disabled = new File(managedDestination.getParentFile(), managedDestination.getName() + ".disabled");
        if (disabled.exists() && !disabled.delete()) {
            throw new IOException("Could not replace stale managed DreamDisplays disabled file");
        }
        if (!managedDestination.renameTo(disabled)) {
            throw new IOException("Could not disable launcher-managed DreamDisplays fallback");
        }
        Log.i(TAG, "Disabled launcher-managed stallfix1 because a manual DreamDisplays override exists.");
    }

    private static boolean isExact(File file) throws IOException {
        return file.isFile() && file.length() == SIZE && SHA256.equals(sha256(file));
    }

    private static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }

        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }

        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}

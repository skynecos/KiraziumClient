package net.kdt.pojavlaunch.instances;

import android.util.Log;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.FileUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps player-facing settings consistent between isolated launcher instances.
 *
 * Instances intentionally keep mods/worlds/configuration isolated. Only settings that are safe
 * and useful across Minecraft versions are synchronized here.
 */
public final class KiraziumSettingsSync {
    private static final String TAG = "KiraziumSettingsSync";
    private static final File SHARED_SETTINGS =
            new File(Tools.DIR_GAME_HOME, "shared_user_settings");
    private static final File SHARED_OPTIONS = new File(SHARED_SETTINGS, "options.txt");
    private static final String PREF_CONTROLS_MIGRATION = "kiraziumSharedControlsV1";

    private static final String[] SAFE_SHARED_FILES = new String[] {
            "servers.dat",
            "optionsof.txt",
            "config/iris.properties"
    };

    private KiraziumSettingsSync() {}

    public static void prepareForLaunch(Instance instance) {
        if (instance == null) return;

        try {
            FileUtils.ensureDirectory(SHARED_SETTINGS);
            File gameDirectory = instance.getGameDirectory();
            FileUtils.ensureDirectory(gameDirectory);

            migrateLauncherControlsToGlobal(instance);
            seedOptionsIfNeeded(gameDirectory);
            applySharedOptions(gameDirectory);

            for (String relativePath : SAFE_SHARED_FILES) {
                applyOrSeedFile(gameDirectory, relativePath);
            }

            Log.i(TAG, "Applied shared user settings to " + gameDirectory.getName());
        } catch (IOException exception) {
            Log.w(TAG, "Could not apply shared user settings", exception);
        }
    }

    public static void captureAfterLaunch(Instance instance) {
        if (instance == null) return;

        try {
            FileUtils.ensureDirectory(SHARED_SETTINGS);
            File gameDirectory = instance.getGameDirectory();

            captureOptions(gameDirectory);
            for (String relativePath : SAFE_SHARED_FILES) {
                File local = new File(gameDirectory, relativePath);
                if (local.isFile()) {
                    copyFile(local, new File(SHARED_SETTINGS, relativePath));
                }
            }

            Log.i(TAG, "Captured shared user settings from " + gameDirectory.getName());
        } catch (IOException exception) {
            Log.w(TAG, "Could not capture shared user settings", exception);
        }
    }

    private static void seedOptionsIfNeeded(File currentGameDirectory) throws IOException {
        if (SHARED_OPTIONS.isFile() && SHARED_OPTIONS.length() > 0) return;

        File newest = findNewestInstanceFile("options.txt");
        if (newest == null) {
            File current = new File(currentGameDirectory, "options.txt");
            if (current.isFile() && current.length() > 0) newest = current;
        }

        if (newest != null) {
            copyFile(newest, SHARED_OPTIONS);
            Log.i(TAG, "Seeded global options from " + newest.getAbsolutePath());
        }
    }

    private static void applySharedOptions(File gameDirectory) throws IOException {
        if (!SHARED_OPTIONS.isFile()) return;

        File localOptions = new File(gameDirectory, "options.txt");
        LinkedHashMap<String, String> local = readOptions(localOptions);
        LinkedHashMap<String, String> shared = readOptions(SHARED_OPTIONS);

        // Global/player choices win. Local-only keys are retained for version-specific options.
        for (Map.Entry<String, String> entry : shared.entrySet()) {
            local.put(entry.getKey(), entry.getValue());
        }

        writeOptions(localOptions, local);
    }

    private static void captureOptions(File gameDirectory) throws IOException {
        File localOptions = new File(gameDirectory, "options.txt");
        if (!localOptions.isFile() || localOptions.length() == 0) return;

        LinkedHashMap<String, String> shared = readOptions(SHARED_OPTIONS);
        LinkedHashMap<String, String> local = readOptions(localOptions);

        // Keep old/version-specific keys in the vault while updating every key written by the
        // version the user just played.
        for (Map.Entry<String, String> entry : local.entrySet()) {
            shared.put(entry.getKey(), entry.getValue());
        }

        writeOptions(SHARED_OPTIONS, shared);
    }

    private static LinkedHashMap<String, String> readOptions(File file) throws IOException {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (!file.isFile()) return values;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int separator = line.indexOf(':');
                if (separator <= 0) continue;
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }

        return values;
    }

    private static void writeOptions(File file, LinkedHashMap<String, String> values)
            throws IOException {
        FileUtils.ensureParentDirectory(file);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, false))) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                writer.write(entry.getKey());
                writer.write(':');
                writer.write(entry.getValue());
                writer.newLine();
            }
        }
    }

    private static void applyOrSeedFile(File gameDirectory, String relativePath)
            throws IOException {
        File shared = new File(SHARED_SETTINGS, relativePath);
        File local = new File(gameDirectory, relativePath);

        if (!shared.isFile()) {
            File newest = findNewestInstanceFile(relativePath);
            if (newest != null) copyFile(newest, shared);
            else if (local.isFile()) copyFile(local, shared);
        }

        if (shared.isFile()) copyFile(shared, local);
    }

    private static File findNewestInstanceFile(String relativePath) {
        File newest = null;
        long newestTimestamp = Long.MIN_VALUE;

        File instancesRoot = new File(Tools.DIR_GAME_HOME, "instances");
        File[] roots = instancesRoot.listFiles();
        if (roots != null) {
            for (File root : roots) {
                if (!root.isDirectory()) continue;
                File candidate = new File(root, relativePath);
                if (candidate.isFile() && candidate.lastModified() > newestTimestamp) {
                    newest = candidate;
                    newestTimestamp = candidate.lastModified();
                }
            }
        }

        File sharedDataCandidate = new File(Instances.SHARED_DATA_DIRECTORY, relativePath);
        if (sharedDataCandidate.isFile() &&
                sharedDataCandidate.lastModified() > newestTimestamp) {
            newest = sharedDataCandidate;
        }

        return newest;
    }

    private static void migrateLauncherControlsToGlobal(Instance selectedInstance)
            throws IOException {
        if (LauncherPreferences.DEFAULT_PREF == null ||
                LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_CONTROLS_MIGRATION, false)) {
            return;
        }

        String globalPath = LauncherPreferences.PREF_DEFAULTCTRL_PATH;
        boolean globalIsCustom = globalPath != null &&
                !globalPath.equals(Tools.CTRLDEF_FILE) &&
                new File(globalPath).isFile();

        if (!globalIsCustom) {
            Instance source = null;
            if (isValidControlOverride(selectedInstance)) {
                source = selectedInstance;
            } else {
                long newest = Long.MIN_VALUE;
                List<Instance> instances = Instances.loadAllInstances();
                for (Instance instance : instances) {
                    if (!isValidControlOverride(instance)) continue;
                    File metadata = Instances.metadataLocation(instance.mInstanceRoot);
                    long modified = metadata.lastModified();
                    if (modified > newest) {
                        newest = modified;
                        source = instance;
                    }
                }
            }

            if (source != null) {
                globalPath = Tools.CTRLMAP_PATH + "/" + source.controlLayout;
                LauncherPreferences.DEFAULT_PREF.edit()
                        .putString("defaultCtrl", globalPath)
                        .apply();
                LauncherPreferences.PREF_DEFAULTCTRL_PATH = globalPath;
            }
        }

        for (Instance instance : Instances.loadAllInstances()) {
            if (instance.controlLayout == null) continue;
            instance.controlLayout = null;
            instance.write();
        }

        LauncherPreferences.DEFAULT_PREF.edit()
                .putBoolean(PREF_CONTROLS_MIGRATION, true)
                .apply();

        Log.i(TAG, "Migrated per-instance launcher controls to the global control layout");
    }

    private static boolean isValidControlOverride(Instance instance) {
        if (instance == null || !Tools.isValidString(instance.controlLayout)) return false;
        File layout = new File(Tools.CTRLMAP_PATH, instance.controlLayout);
        return layout.isFile();
    }

    private static void copyFile(File source, File destination) throws IOException {
        if (source.getAbsolutePath().equals(destination.getAbsolutePath())) return;
        FileUtils.ensureParentDirectory(destination);

        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }
}

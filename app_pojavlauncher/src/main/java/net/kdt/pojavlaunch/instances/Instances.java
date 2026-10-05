package net.kdt.pojavlaunch.instances;

import android.util.Log;

import com.google.gson.JsonSyntaxException;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.utils.JSONUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class Instances {
    private static final File sInstancePath = new File(Tools.DIR_GAME_HOME, "instances");
    public static final File SHARED_DATA_DIRECTORY = new File(Tools.DIR_GAME_HOME, "shared_dir");
    private static final String PREF_KEY_RENDERER_INHERITANCE_V2 =
            "kiraziumRendererInheritanceV2";
    private static final String PREF_KEY_ISOLATED_INSTANCE_DATA_V1 =
            "kiraziumIsolatedInstanceDataV1";
    private static final String PREF_KEY_DEFAULT_12111_V1 =
            "kiraziumDefault12111V1";
    private static final String LEGACY_KIRAZIUM_RENDERER = "opengles3_ltw";

    public final List<DisplayInstance> list;
    public final int selectedIndex;

    private Instances(List<DisplayInstance> instances, int selectedIndex) {
        this.list = instances;
        this.selectedIndex = selectedIndex;
    }

    private static <T extends DisplayInstance> T read(File instanceRoot, Class<T> tClass) {
        try {
            T instance = JSONUtils.readFromFile(metadataLocation(instanceRoot), tClass);
            if(instance == null) return null;
            instance.mInstanceRoot = instanceRoot;
            return instance;
        }catch (IOException | JsonSyntaxException e) {
            return null;
        }
    }

    protected static File metadataLocation(File instanceDir) {
        return new File(instanceDir, "mojo_instance.json");
    }

    private static File selectedInstanceLocation() {
        String directoryName = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_INSTANCE, "");
        File instanceRoot = new File(sInstancePath, directoryName);
        if(!instanceRoot.exists())
            Log.e("Instances", "New instance dir doesn't exist!");
        if(!metadataLocation(instanceRoot).exists()) return null;
        return instanceRoot;
    }

    private static boolean filterInstanceDirectories(File instanceDir) {
        if(!instanceDir.canRead() || !instanceDir.canWrite()) return false;
        if(!instanceDir.isDirectory()) return false;
        File instanceMetadata = metadataLocation(instanceDir);
        if(!instanceMetadata.isFile()) return false;
        return instanceMetadata.canRead();
    }

    private static <T extends DisplayInstance> List<T> loadInstances(Class<T> tClass, int[] selectionDst) throws IOException {
        synchronized (sInstancePath) {
            FileUtils.ensureDirectory(sInstancePath);
        }
        File[] instanceDirectories = sInstancePath.listFiles(Instances::filterInstanceDirectories);
        if(instanceDirectories == null) throw new IOException("Failed to enumerate instances");
        File selectedInstanceLocation = selectionDst != null ? selectedInstanceLocation() : null;
        ArrayList<T> instances = new ArrayList<>(instanceDirectories.length);

        for(File instanceDir : instanceDirectories) {
            T instance = read(instanceDir, tClass);

            if(instance == null) continue;
            instance.sanitize();
            instances.add(instance);

            if(selectionDst != null && instanceDir.equals(selectedInstanceLocation)) {
                selectionDst[0] = instances.size() - 1;
            }
        }
        instances.trimToSize();
        return instances;
    }

    public static Instances loadDisplay() throws IOException {
        int[] selectionIndex = new int[] { -1 };
        List<DisplayInstance> instances = loadInstances(DisplayInstance.class, selectionIndex);
        if(instances.isEmpty()) {
            createFirstTimeInstance();
            return loadDisplay();
        }else if(selectionIndex[0] == -1) {
            setSelectedInstance(instances.get(0));
            selectionIndex[0] = 0;
        }
        migrateLegacyRendererDefaults();
        migrateToIsolatedInstanceData();
        migrateKiraziumDefaultTo12111(instances);
        KiraziumBootstrap.ensureClientFiles(instances);
        return new Instances(Collections.unmodifiableList(instances), selectionIndex[0]);
    }

    public static List<Instance> loadAllInstances() throws IOException {
        return loadInstances(Instance.class, null);
    }

    /**
     * Older KiraziumClient builds created the bundled Kirazium instance with LTW hard-coded.
     * That silently overrode the renderer selected in global Video settings. Migrate only the
     * launcher-created Kirazium instance(s), once, so they inherit the global renderer again.
     */
    private static void migrateLegacyRendererDefaults() {
        if (LauncherPreferences.DEFAULT_PREF == null ||
                LauncherPreferences.DEFAULT_PREF.getBoolean(
                        PREF_KEY_RENDERER_INHERITANCE_V2, false)) {
            return;
        }

        try {
            int migratedCount = 0;
            for (Instance instance : loadInstances(Instance.class, null)) {
                if (instance.mInstanceRoot == null ||
                        !LEGACY_KIRAZIUM_RENDERER.equals(instance.renderer)) {
                    continue;
                }

                String rootName = instance.mInstanceRoot.getName();
                boolean launcherManagedProfile =
                        rootName.startsWith("kirazium-") ||
                        rootName.startsWith("modpack-");

                if (!launcherManagedProfile) continue;

                instance.renderer = null;
                instance.write();
                migratedCount++;
            }

            LauncherPreferences.DEFAULT_PREF.edit()
                    .putBoolean(PREF_KEY_RENDERER_INHERITANCE_V2, true)
                    .apply();

            if (migratedCount > 0) {
                Log.i("Instances",
                        "Migrated " + migratedCount +
                        " legacy LTW instance override(s) to global renderer inheritance");
            }
        } catch (IOException exception) {
            // Do not mark the migration complete so it can be retried on the next launcher start.
            Log.w("Instances", "Could not migrate legacy renderer overrides", exception);
        }
    }

    /**
     * KiraziumClient 2.7.2 briefly defaulted profiles to the shared game directory. That makes
     * unrelated instances use the same mods, configs, worlds and packs. Flip only the metadata;
     * the old shared directory is deliberately left untouched as a recoverable backup because its
     * already-mixed contents cannot be attributed safely to individual instances.
     */
    private static void migrateToIsolatedInstanceData() {
        if (LauncherPreferences.DEFAULT_PREF == null ||
                LauncherPreferences.DEFAULT_PREF.getBoolean(
                        PREF_KEY_ISOLATED_INSTANCE_DATA_V1, false)) {
            return;
        }

        try {
            int migratedCount = 0;
            for (Instance instance : loadInstances(Instance.class, null)) {
                if (!instance.sharedData) continue;
                instance.sharedData = false;
                instance.write();
                migratedCount++;
            }

            LauncherPreferences.DEFAULT_PREF.edit()
                    .putBoolean(PREF_KEY_ISOLATED_INSTANCE_DATA_V1, true)
                    .apply();
            Log.i("Instances", "Migrated " + migratedCount +
                    " instance(s) from shared data to isolated directories; shared_dir preserved");
        } catch (IOException exception) {
            // Do not mark the migration complete so it can be retried on the next launcher start.
            Log.w("Instances", "Could not migrate instances to isolated data directories", exception);
        }
    }

    /** Moves only the launcher-managed Kirazium profile to the bundled Fabric 1.21.11 pack. */
    private static void migrateKiraziumDefaultTo12111(List<DisplayInstance> displayInstances) {
        if (LauncherPreferences.DEFAULT_PREF == null ||
                LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_KEY_DEFAULT_12111_V1, false)) {
            return;
        }

        try {
            String versionId = KiraziumBootstrap.installFabricProfile();
            int migratedCount = 0;
            for (Instance instance : loadInstances(Instance.class, null)) {
                if (!KiraziumBootstrap.PROFILE_NAME.equals(instance.name)) continue;
                instance.versionId = versionId;
                instance.selectedRuntime = "Internal-21";
                instance.sharedData = false;
                instance.write();
                migratedCount++;
            }
            for (DisplayInstance instance : displayInstances) {
                if (KiraziumBootstrap.PROFILE_NAME.equals(instance.name)) {
                    instance.versionId = versionId;
                }
            }
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putBoolean(PREF_KEY_DEFAULT_12111_V1, true)
                    .apply();
            Log.i("Instances", "Migrated " + migratedCount +
                    " Kirazium default profile(s) to Fabric 1.21.11");
        } catch (IOException exception) {
            Log.w("Instances", "Could not migrate Kirazium default profile to 1.21.11", exception);
        }
    }

    private static File findNewInstanceRoot(String prefix) {
        File instanceRoot;
        do {
            String proposedDirectoryName = UUID.randomUUID().toString();
            if(prefix != null) {
                proposedDirectoryName = prefix + "-" + proposedDirectoryName;
            }
            instanceRoot = new File(sInstancePath, proposedDirectoryName);
        } while(instanceRoot.exists() && instanceRoot.isDirectory());
        return instanceRoot;
    }

    /**
     * Set the currently selected instance and save it in user preferences
     * @param instance new selected instance
     */
    public static void setSelectedInstance(DisplayInstance instance) {
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(
                        LauncherPreferences.PREF_KEY_CURRENT_INSTANCE,
                        instance.mInstanceRoot.getName()
                ).apply();
    }

    /**
     * Remove the instance. This also removes its data storage folder.
     * @param instance the Instance to remove
     * @throws IOException in case of errors during directory removal
     */
    public static void removeInstance(Instance instance) throws IOException {
        File instanceDirectory = instance.mInstanceRoot;
        if(instanceDirectory == null) return;
        org.apache.commons.io.FileUtils.deleteDirectory(instanceDirectory);
    }

    /**
     * Create a new instance intended for first-time launcher users.
     */
    private static void createFirstTimeInstance() throws IOException {
        String versionId = KiraziumBootstrap.installFabricProfile();
        internalCreateInstance((instance)-> {
            instance.sharedData = false;
            instance.name = KiraziumBootstrap.PROFILE_NAME;
            instance.icon = KiraziumBootstrap.PROFILE_ICON;
            instance.versionId = versionId;
            // Renderer follows the global launcher choice unless the user explicitly overrides it.
            instance.renderer = null;
            instance.selectedRuntime = "Internal-21";
        }, "kirazium");
    }

    /**
     * Create a new instance based on a default template.
     * @return the new instance
     */
    public static Instance createDefaultInstance() throws IOException {
        return createInstance((instance)-> {
            instance.sharedData = false;
            instance.versionId = Instance.VERSION_LATEST_RELEASE;
        }, null);
    }

    /**
     * Create an instance without attempting to load the instance list first. Only use this
     * method during initialization.
     */
    private static Instance internalCreateInstance(InstanceSetter instanceSetter, String namePrefix) throws IOException{
        File root = findNewInstanceRoot(namePrefix);
        FileUtils.ensureDirectory(root);
        Instance instance = new Instance();
        instance.mInstanceRoot = root;
        instanceSetter.setInstanceProperties(instance);
        instance.write();
        return instance;
    }

    /**
     * Create a new instance with defaults set by user
     * @param instanceSetter setter function called to set user parameters
     * @param namePrefix a name prefix (for the user to easily distinguish installed instances)
     * @return the created instance
     * @throws IOException if directory creation/instance writing fails
     */
    public static Instance createInstance(InstanceSetter instanceSetter, String namePrefix) throws IOException {
        return internalCreateInstance(instanceSetter, namePrefix);
    }

    /**
     * Load the currently selected instance. Note that this method must not be used along with any code
     * which uses getImmutableInstanceList()
     * @return currently selected instance
     */
    public static Instance loadSelectedInstance() {
        File selectedInstanceLocation = selectedInstanceLocation();
        Instance instance = read(selectedInstanceLocation, Instance.class);
        if(instance == null) return null;
        instance.sanitize();
        return instance;
    }

    /**
     * Rename the provided instance directory. This will apply the new name only if it's unique.
     * If no name provided - using bare UUID. If a name conflict - newName as prefix + UUID.
     * @param instance Instance
     * @param newName New instance name
     */
    public static void renameInstanceDirectory(Instance instance, String newName) {
        if(newName == null) return;
        if(newName.trim().isEmpty())
            newName = String.valueOf(UUID.randomUUID());
        else
            newName = FileUtils.escapeFileName(newName);
        File targetDirectory = new File(sInstancePath, newName);
        if(targetDirectory.exists())
            targetDirectory = findNewInstanceRoot(newName);
        String oldName = instance.mInstanceRoot.getName();
        if(!instance.mInstanceRoot.renameTo(targetDirectory))
            throw new RuntimeException("Failed to rename instance!");
        instance.mInstanceRoot = targetDirectory;
        if(oldName.equals(LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_INSTANCE, "")))
            setSelectedInstance(instance);
    }
}

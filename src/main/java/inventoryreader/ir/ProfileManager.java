package inventoryreader.ir;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Keeps each SkyBlock profile's data apart, in profiles/<account>/<profile>/. Hypixel names the profile in
 * chat ("Profile ID: <uuid>") each time you join SkyBlock; {@link ProfileChatListener} passes it here.
 *
 * <p>The ID only arrives after the new server has loaded, so after every server change tracking waits for
 * it (see {@link #isReady()}): otherwise the new profile's items would be booked to the old one. Tracking
 * never resumes without it; the message is read even when another mod hides it.
 */
public final class ProfileManager {
    /** Files that belong to one profile; everything else (HUD, settings, recipes) is shared. */
    static final String[] PROFILE_FILES = {
        "resources.json", "inventorydata.json", "allcontainerData.json", "sacks.json", "sacks_meta.json",
        "coins.json", "forge.json", "forge_speed.json", "shopping_list.json",
    };
    private static final File PROFILES_DIR = new File(FilePathManager.DATA_DIR, "profiles");
    private static final String LAST_PROFILE_FILE = "last_profile.txt";

    /** Current profile's folder; the old flat layout (the data folder itself) until a profile is known. */
    private static volatile File dir = FilePathManager.DATA_DIR;
    private static volatile String profileId;
    private static volatile boolean ready = false;
    private static Object lastLevel;
    private static volatile boolean initialized = false;

    private ProfileManager() {}

    /** Picks up the profile this account used last, so data shows before the join message arrives. */
    private static void loadLastProfile() {
        File accountDir = accountDir();
        if (accountDir == null) return;
        try {
            File last = new File(accountDir, LAST_PROFILE_FILE);
            if (last.isFile()) {
                String id = Files.readString(last.toPath(), StandardCharsets.UTF_8).trim();
                if (isProfileId(id)) {
                    profileId = id;
                    switchTo(new File(accountDir, id));
                }
            }
        } catch (IOException e) {
            InventoryReader.LOGGER.warn("Could not read the last SkyBlock profile", e);
        }
    }

    static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(ProfileManager::tick);
    }

    /** Folder for the current profile's files. */
    public static File dir() {
        return dir;
    }

    /** True once the last profile has been picked up (first client tick), so profile files may be used. */
    public static boolean isInitialized() {
        return initialized;
    }

    /** True once the current server's profile is known, so tracking may book changes to it. */
    public static boolean isReady() {
        return ready;
    }

    private static void tick(Minecraft client) {
        if (!initialized) {
            // On the first tick rather than at start-up: the logged-in account is known by now.
            initialized = true;
            loadLastProfile();
            // No profile yet: the old flat folder is in use until the first "Profile ID" line.
            if (profileId == null) FilePathManager.seedResources();
        }
        // The dev client runs in singleplayer, where Hypixel sends no profile.
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            ready = true;
            return;
        }
        if (client.level != lastLevel) {
            // New server (or world): wait for its "Profile ID" line before tracking again.
            lastLevel = client.level;
            ready = false;
        }
    }

    /** Called with the ID from Hypixel's "Profile ID: ..." message. Client thread only. */
    static void onProfileId(String id) {
        if (id.equals(profileId)) {
            ready = true;
            return;
        }
        File accountDir = accountDir();
        if (accountDir == null) return;
        File target = new File(accountDir, id);
        boolean firstProfile = !hasProfiles(accountDir);
        if (!target.mkdirs() && !target.isDirectory()) {
            InventoryReader.LOGGER.warn("Could not create profile folder {}", target);
            return;
        }
        // Data from before profiles existed belongs to the first profile seen. Saves still queued for it are
        // written first, so they move along instead of reappearing in the old place.
        if (firstProfile) {
            JsonFiles.flush();
            moveLegacyFiles(target);
        }
        InventoryReader.debug("SkyBlock profile {} -> {}", profileId, id);
        profileId = id;
        switchTo(target);
        ready = true;
        try {
            Files.writeString(new File(accountDir, LAST_PROFILE_FILE).toPath(), id, StandardCharsets.UTF_8);
        } catch (IOException e) {
            InventoryReader.LOGGER.warn("Could not save the last SkyBlock profile", e);
        }
    }

    /**
     * Points the data files at {@code folder}, drops every cached copy of profile data and loads that profile's.
     * One step under the shopping list's lock: the new counts are in place before the new list is loaded, so the
     * HUD update never works out the new list against the old profile's counts.
     */
    private static void switchTo(File folder) {
        // The profile being left keeps its last saves; switching back right away then reads them, not older data.
        JsonFiles.flush();
        SandboxWidget.getInstance().reloadShoppingList(() -> {
            // Under ForgeSpeed's own lock, so a mayor-check update can't fall between the two.
            ForgeSpeed.switchProfile(() -> dir = folder);
            clearCaches();
            ResourcesManager.getInstance().reload();
            FilePathManager.seedResources();
        });
    }

    /** Drops every in-memory copy of profile data, so the next use reads the current profile's files. */
    public static void clearCaches() {
        StorageReader.getInstance().clear();
        SackReader.getInstance().clear();
        CoinTracker.clear();
        ForgeTracker.clear();
        ForgeSpeed.clear();
        InventoryReaderClient.clearInventorySnapshot();
    }

    private static boolean hasProfiles(File accountDir) {
        File[] children = accountDir.listFiles(File::isDirectory);
        return children != null && children.length > 0;
    }

    private static void moveLegacyFiles(File target) {
        for (String name : PROFILE_FILES) {
            File from = new File(FilePathManager.DATA_DIR, name);
            if (!from.isFile()) continue;
            try {
                Files.move(from.toPath(), new File(target, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                InventoryReader.LOGGER.warn("Could not move {} into the profile folder", name, e);
            }
        }
    }

    /** profiles/<account uuid>/, or null if the account is unknown. */
    private static File accountDir() {
        UUID account = Minecraft.getInstance().getUser().getProfileId();
        if (account == null) return null;
        File accountDir = new File(PROFILES_DIR, account.toString());
        accountDir.mkdirs();
        return accountDir;
    }

    static boolean isProfileId(String id) {
        return ProfileChatListener.UUID.matcher(id).matches();
    }
}

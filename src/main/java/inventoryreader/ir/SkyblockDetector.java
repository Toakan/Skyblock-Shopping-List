package inventoryreader.ir;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;

import java.util.Locale;

/**
 * Tells whether the player is on a Hypixel SkyBlock server. Tracking, the HUD and keybinds are off
 * everywhere else, so lobbies and other servers never change the counts.
 *
 * <p>With the official Hypixel Mod API mod installed, its location event decides (and also reports the
 * island). Without it, the sidebar scoreboard title ("SKYBLOCK", "SKYBLOCK CO-OP", ...) is used. Both
 * only read what Hypixel provides.
 */
public final class SkyblockDetector {
    private static final String MOD_API_ID = "hypixel-mod-api";

    private static boolean useModApi = false;
    private static volatile boolean onSkyblock = false;
    private static volatile String island = null;

    private SkyblockDetector() {}

    public static void register() {
        useModApi = FabricLoader.getInstance().isModLoaded(MOD_API_ID);
        if (useModApi) {
            HypixelLocationListener.register();
            InventoryReader.debug("Using the Hypixel Mod API for SkyBlock detection");
        } else {
            InventoryReader.debug("Hypixel Mod API not installed; detecting SkyBlock from the scoreboard");
        }
        ClientTickEvents.START_CLIENT_TICK.register(SkyblockDetector::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> set(false, null));
    }

    public static boolean isOnSkyblock() {
        return onSkyblock;
    }

    /**
     * On SkyBlock and the profile is known, so item changes may be booked to it. Just after a server change
     * this waits for Hypixel's "Profile ID" line (see {@link ProfileManager}); the HUD and keys don't wait.
     */
    public static boolean isTracking() {
        return onSkyblock && ProfileManager.isReady();
    }

    /** The SkyBlock island mode from the Mod API (e.g. "mining_3"), or null if unknown. */
    public static String getIsland() {
        return island;
    }

    /** Called by {@link HypixelLocationListener} on every server change. */
    static void onModApiLocation(boolean skyblock, String mode) {
        set(skyblock, skyblock ? mode : null);
    }

    private static void tick(Minecraft client) {
        // The dev client runs in singleplayer, where neither signal exists.
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            onSkyblock = client.level != null;
            return;
        }
        if (useModApi) return;
        boolean found = false;
        if (client.level != null) {
            Objective sidebar = client.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
            if (sidebar != null) {
                found = ItemNames.clean(sidebar.getDisplayName().getString()).toUpperCase(Locale.ROOT).contains("SKYBLOCK");
            }
        }
        set(found, null);
    }

    private static void set(boolean skyblock, String mode) {
        island = mode;
        if (skyblock != onSkyblock) {
            onSkyblock = skyblock;
            InventoryReader.debug(skyblock ? "Entered SkyBlock{}" : "Left SkyBlock{}", mode != null ? " (" + mode + ")" : "");
        }
    }
}

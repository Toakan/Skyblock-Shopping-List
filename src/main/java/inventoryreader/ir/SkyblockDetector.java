package inventoryreader.ir;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Tells whether the player is on a Hypixel SkyBlock server. Tracking, the HUD and keybinds are off
 * everywhere else, so lobbies and other servers never change the counts.
 *
 * <p>The official Hypixel Mod API is a required dependency: its location event decides. If it is somehow
 * missing at runtime, nothing counts as SkyBlock: tracking, the HUD and keys stay off rather than running on
 * servers that may not be SkyBlock.
 */
public final class SkyblockDetector {
    private static final String MOD_API_ID = "hypixel-mod-api";

    private static volatile boolean onSkyblock = false;

    private SkyblockDetector() {}

    public static void register() {
        if (FabricLoader.getInstance().isModLoaded(MOD_API_ID)) {
            HypixelLocationListener.register();
        } else {
            InventoryReader.LOGGER.warn("Hypixel Mod API not found; SkyBlock detection unavailable, so tracking, HUD and keys stay off");
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

    /** Called by {@link HypixelLocationListener} on every server change. */
    static void onModApiLocation(boolean skyblock, String mode) {
        set(skyblock, skyblock ? mode : null);
    }

    private static void tick(Minecraft client) {
        // The dev client runs in singleplayer, where the Mod API sends nothing.
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            onSkyblock = client.level != null;
        }
    }

    private static void set(boolean skyblock, String mode) {
        if (skyblock != onSkyblock) {
            onSkyblock = skyblock;
            InventoryReader.debug(skyblock ? "Entered SkyBlock{}" : "Left SkyBlock{}", mode != null ? " (" + mode + ")" : "");
        }
    }
}

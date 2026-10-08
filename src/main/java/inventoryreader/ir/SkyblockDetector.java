package inventoryreader.ir;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;

import java.util.Locale;

/**
 * Tells whether the player is on a Hypixel SkyBlock server, from the sidebar scoreboard title ("SKYBLOCK",
 * "SKYBLOCK CO-OP", ...). Only reads what the server already shows; sends nothing. Tracking, the HUD and
 * keybinds are off everywhere else, so lobbies and other servers never change the counts.
 */
public final class SkyblockDetector {
    private static volatile boolean onSkyblock = false;

    private SkyblockDetector() {}

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(SkyblockDetector::update);
    }

    public static boolean isOnSkyblock() {
        return onSkyblock;
    }

    private static void update(Minecraft client) {
        // The dev client runs in singleplayer, where there is no SkyBlock scoreboard to find.
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            onSkyblock = client.level != null;
            return;
        }
        boolean found = false;
        if (client.level != null) {
            Objective sidebar = client.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR);
            if (sidebar != null) {
                found = ItemNames.clean(sidebar.getDisplayName().getString()).toUpperCase(Locale.ROOT).contains("SKYBLOCK");
            }
        }
        if (found != onSkyblock) {
            onSkyblock = found;
            InventoryReader.LOGGER.info(found ? "Entered SkyBlock" : "Left SkyBlock");
        }
    }
}

package inventoryreader.ir;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Reads a container menu one tick after the player opens it (so the server has filled the slots), then
 * re-reads storage and Forge menus every half second while they stay open, so items taken out (e.g. collected
 * from the Forge) leave the container's count instead of being counted there and in the inventory.
 */
public final class StorageViewerMod {
    private static AbstractContainerMenu lastMenu = null;
    private static int ticksOpen = 0;
    private static boolean handled = false;
    private static final int REREAD_TICKS = 10;

    private StorageViewerMod() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(StorageViewerMod::onEndClientTick);
    }

    private static void onEndClientTick(Minecraft client) {
        Screen screen = client.gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?>) || client.player == null || !SkyblockDetector.isTracking()) {
            lastMenu = null;
            return;
        }
        AbstractContainerMenu menu = client.player.containerMenu;
        if (menu != lastMenu) {
            lastMenu = menu;
            ticksOpen = 0;
            handled = false;
        }
        if (ticksOpen < 1) {
            ticksOpen++;
        } else if (!handled) {
            handled = true;
            String title = screen.getTitle().getString();
            StorageReader.getInstance().saveContainerContents(menu, title);
            if (title.contains("Bank")) {
                CoinTracker.readBank(menu, title);
            }
            if (ForgeTracker.isForgeMenu(title)) {
                ForgeTracker.readForge(menu);
            }
            if (ForgeSpeed.isHotmMenu(title)) {
                ForgeSpeed.readHotm(menu);
            }
            if (title.contains("Sack")) {
                SackReader.getInstance().readSack(menu, title);
            }
        } else if (++ticksOpen % REREAD_TICKS == 0) {
            // Both only write when something changed.
            String title = screen.getTitle().getString();
            StorageReader.getInstance().saveContainerContents(menu, title);
            if (ForgeTracker.isForgeMenu(title)) {
                ForgeTracker.readForge(menu);
            }
            // The perk can be levelled or switched with the menu open.
            if (ForgeSpeed.isHotmMenu(title)) {
                ForgeSpeed.readHotm(menu);
            }
        }
    }
}

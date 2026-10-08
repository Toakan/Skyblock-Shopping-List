package inventoryreader.ir;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Reads a container menu once, one tick after the player opens it (so the server has filled the slots). */
public final class StorageViewerMod {
    private static AbstractContainerMenu lastMenu = null;
    private static int ticksOpen = 0;
    private static boolean handled = false;

    private StorageViewerMod() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(StorageViewerMod::onEndClientTick);
    }

    private static void onEndClientTick(Minecraft client) {
        Screen screen = client.gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?>) || client.player == null) {
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
            if (title.contains("Sack")) {
                SackReader.getInstance().readSack(menu, title);
            }
        }
    }
}

package inventoryreader.ir;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class ReminderManager {
    private static final int REMINDER_INTERVAL = 100; // 5 seconds
    private static int tickCounter = 0;
    private static final int STALE_CHECK_INTERVAL = 400; // 20 seconds
    private static final long STALE_AFTER_MS = 60 * 60 * 1000L;
    /** Ticks on SkyBlock before the first stale check, so it doesn't land on top of the join messages. */
    private static final int STALE_GRACE_TICKS = 1200; // 60 seconds
    private static int skyblockTicks = 0;
    /** Epoch ms of the last stale-sack warning; in memory only, so it shows again after a restart. */
    private static long lastStaleWarning = 0;

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || !SkyblockDetector.isOnSkyblock()) {
                skyblockTicks = 0;
                return;
            }
            skyblockTicks++;
            if (skyblockTicks >= STALE_GRACE_TICKS && skyblockTicks % STALE_CHECK_INTERVAL == 0) {
                checkStaleSacks();
            }

            if (SackReader.getNeedsReminder()) {
                tickCounter++;

                if (tickCounter >= REMINDER_INTERVAL) {
                    tickCounter = 0;

                    Component message = Component.literal("[" + InventoryReader.NAME + "] ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("Remember to open a sack or type ")
                            .withStyle(ChatFormatting.WHITE))
                        .append(Component.literal("/ssl done")
                            .withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(" to stop this reminder.")
                            .withStyle(ChatFormatting.WHITE));

                    Minecraft.getInstance().gui.hud.getChat()
                        .addClientSystemMessage(message);
                }
            } else {
                tickCounter = 0;
            }
        });
    }

    /** Warns, at most once an hour, when sacks haven't been opened for an hour and the list isn't empty. */
    private static void checkStaleSacks() {
        SandboxWidget widget = SandboxWidget.getInstance();
        if (!widget.isStaleSackWarning() || widget.getShoppingList().isEmpty() || SackReader.getNeedsReminder()) return;
        long now = System.currentTimeMillis();
        long lastRead = SackReader.getInstance().getLastRead();
        if (now - lastRead < STALE_AFTER_MS || now - lastStaleWarning < STALE_AFTER_MS) return;
        lastStaleWarning = now;

        String status = lastRead == 0
            ? "Sack counts haven't been read yet. "
            : "Sack counts may be out of date (last checked " + (now - lastRead) / STALE_AFTER_MS + "h ago). ";
        Component message = Component.literal("[" + InventoryReader.NAME + "] ")
            .withStyle(ChatFormatting.GOLD)
            .append(Component.literal(status).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("Open your sacks to refresh them.").withStyle(ChatFormatting.YELLOW));
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(message);
    }
}
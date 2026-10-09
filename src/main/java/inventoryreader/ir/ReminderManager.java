package inventoryreader.ir;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

public class ReminderManager {
    /** Sack names shown in one message before the rest are summed up as "+N more". */
    private static final int MAX_SACKS_NAMED = 3;
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
        });
    }

    /** One chat line naming the sacks to open so the list's counts are right. Sent once, never repeated. */
    public static void promptSacks(List<String> sacks) {
        if (sacks.isEmpty() || !SkyblockDetector.isOnSkyblock()) return;
        Component message = Component.literal("[" + InventoryReader.NAME + "] ")
            .withStyle(ChatFormatting.GOLD)
            .append(Component.literal("Open your ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(sackList(sacks)).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" for accurate counts.").withStyle(ChatFormatting.WHITE));
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.gui.hud.getChat().addClientSystemMessage(message));
    }

    /** "Mining Sack, Combat Sack", or with more than {@link #MAX_SACKS_NAMED} "A, B, C +2 more". */
    private static String sackList(List<String> sacks) {
        String named = String.join(", ", sacks.subList(0, Math.min(MAX_SACKS_NAMED, sacks.size())));
        return sacks.size() > MAX_SACKS_NAMED ? named + " +" + (sacks.size() - MAX_SACKS_NAMED) + " more" : named;
    }

    /** Warns, at most once an hour, when sacks haven't been opened for an hour and the list isn't empty. */
    private static void checkStaleSacks() {
        SandboxWidget widget = SandboxWidget.getInstance();
        if (!widget.isStaleSackWarning() || widget.getShoppingList().isEmpty()) return;
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
            .append(Component.literal(widget.getListSacks().isEmpty()
                ? "Open your sacks to refresh them."
                : "Open your " + sackList(widget.getListSacks()) + " to refresh them.").withStyle(ChatFormatting.YELLOW));
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(message);
    }
}
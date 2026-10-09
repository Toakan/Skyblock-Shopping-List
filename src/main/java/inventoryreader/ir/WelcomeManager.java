package inventoryreader.ir;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import java.io.File;
import java.io.IOException;


public class WelcomeManager {
    private static final File WELCOME_FLAG_FILE = new File(FilePathManager.MOD_DIR, "welcome_shown.txt");
    private static boolean isFirstTimeUser = true;

    public static void initialize() {
        checkFirstTimeUser();
        if (!isFirstTimeUser) {
            return;
        }
        // Shown the first time the player reaches SkyBlock, not on joining any server.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (isFirstTimeUser && client.player != null && SkyblockDetector.isOnSkyblock()) {
                checkFirstTimeUser();
                if (isFirstTimeUser) {
                    showWelcomeMessage(client);
                }
            }
        });
    }

    private static void checkFirstTimeUser() {
        isFirstTimeUser = !WELCOME_FLAG_FILE.exists();
    }

    private static void showWelcomeMessage(Minecraft client) {
        if (client.player == null) return;

        String divider = "§6§l" + "=".repeat(40);

        client.player.sendSystemMessage(Component.literal(divider));
        client.player.sendSystemMessage(Component.literal("§b§l" + InventoryReader.NAME).setStyle(
            Style.EMPTY.withBold(true).withColor(ChatFormatting.AQUA)
        ));

        client.player.sendSystemMessage(Component.literal("§eA shopping list for Hypixel SkyBlock: pick recipes, see what you still need."));
        client.player.sendSystemMessage(Component.literal(""));

        MutableComponent commandsText = Component.literal("§6§lCommands:").append(Component.literal("\n§e- Press "));
        commandsText.append(Component.literal("§b[V]").setStyle(Style.EMPTY.withBold(true).withColor(ChatFormatting.AQUA)));
        commandsText.append(Component.literal("§e to open the menu"));
        commandsText.append(Component.literal("\n§e- Press "));
        commandsText.append(Component.literal("§b[B]").setStyle(Style.EMPTY.withBold(true).withColor(ChatFormatting.AQUA)));
        commandsText.append(Component.literal("§e to move the HUD"));
        client.player.sendSystemMessage(commandsText);

        MutableComponent chatCommandsText = Component.literal("§6§lChat Commands:").setStyle(
            Style.EMPTY.withBold(true).withColor(ChatFormatting.GOLD)
        );
        client.player.sendSystemMessage(chatCommandsText);
        client.player.sendSystemMessage(Component.literal("§e- §b/ssl menu§e: Open the menu"));
        client.player.sendSystemMessage(Component.literal("§e- §b/ssl hud§e: Move the HUD"));
        client.player.sendSystemMessage(Component.literal("§e- §b/ssl reset§e: Reset all mod data"));
        client.player.sendSystemMessage(Component.literal("§e- §b/ssl§e: Show all available commands"));

        client.player.sendSystemMessage(Component.literal(""));
        MutableComponent firstTimeText = Component.literal("§d§lFirst-Time Setup:").setStyle(
            Style.EMPTY.withBold(true).withColor(ChatFormatting.LIGHT_PURPLE)
        );
        client.player.sendSystemMessage(firstTimeText);
        MutableComponent warningText = Component.literal("⚠️ Open your Backpacks and Ender Chest once so they are counted. The HUD tells you which Sacks to open. ⚠️").setStyle(
            Style.EMPTY.withBold(true).withColor(ChatFormatting.RED)
        );
        client.player.sendSystemMessage(warningText);
        client.player.sendSystemMessage(Component.literal("§e1. Press §b[V]§e and click recipes to add them to your shopping list"));
        client.player.sendSystemMessage(Component.literal("§e2. Check or correct your counts in 'Your Resources'"));
        client.player.sendSystemMessage(Component.literal("§e3. Press §b[H]§e to show the shopping list on screen"));

        try {
            WELCOME_FLAG_FILE.createNewFile();
        } catch (IOException e) {
            InventoryReader.LOGGER.error("Failed to create welcome flag file", e);
        }

        isFirstTimeUser = false;
        client.player.sendSystemMessage(Component.literal(divider));
    }
}

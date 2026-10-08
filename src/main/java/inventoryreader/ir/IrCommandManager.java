package inventoryreader.ir;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

public final class IrCommandManager {

    private IrCommandManager() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(commandTree("ssl"));
            // Name from when the mod was called Inventory Reader.
            dispatcher.register(commandTree("ir"));
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> commandTree(String name) {
        return
            literal(name)
                .executes(context -> {
                    context.getSource().sendFeedback(Component.literal(InventoryReader.NAME + " commands (/ssl, or /ir):")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)));
                    context.getSource().sendFeedback(Component.literal("- /ssl reset: Reset all mod data")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
                    context.getSource().sendFeedback(Component.literal("- /ssl done: Acknowledge reminder")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
                    context.getSource().sendFeedback(Component.literal("- /ssl menu: Open the menu (shopping list, resources, recipes, settings)")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
                    context.getSource().sendFeedback(Component.literal("- /ssl hud: Move or resize the HUD")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
                    context.getSource().sendFeedback(Component.literal("- /ssl credits: Show credits")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
                    return 1;
                })
                .then(literal("reset")
                    .executes(context -> {
                        InventoryReader.LOGGER.info("Executing complete mod reset");
                        FilePathManager.resetData();
                        StorageReader.getInstance().clear();
                        SackReader.getInstance().clear();
                        CoinTracker.clear();
                        InventoryReaderClient.clearInventorySnapshot();
                        SandboxWidget.getInstance().resetConfiguration();
                        SackReader.setNeedsReminder(true);
                        context.getSource().sendFeedback(
                            Component.literal(InventoryReader.NAME + " data reset! ")
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN))
                                .append(Component.literal("Open a sack or type /ssl done to stop reminders.")
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)))
                        );
                        return 1;
                    })
                )
                .then(literal("done")
                    .executes(context -> {
                        SackReader.setNeedsReminder(false);
                        context.getSource().sendFeedback(Component.literal("Acknowledged! Reminders stopped.")
                            .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)));
                        return 1;
                    })
                )
                .then(literal("menu")
                    .executes(context -> {
                        // Deferred to the next tick: the chat screen closes after the command runs.
                        InventoryReaderClient.shouldOpenMenu = true;
                        return 1;
                    })
                )
                // Older name for the menu.
                .then(literal("widget")
                    .executes(context -> {
                        InventoryReaderClient.shouldOpenMenu = true;
                        return 1;
                    })
                )
                .then(literal("hud")
                    .executes(context -> {
                        InventoryReaderClient.shouldOpenMoveHud = true;
                        return 1;
                    })
                )
                .then(literal("credits")
                    .executes(context -> {
                        context.getSource().sendFeedback(
                            Component.literal(InventoryReader.NAME + " by Tad, based on Inventory Reader by Scholiboi")
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD))
                        );
                        context.getSource().sendFeedback(
                            Component.literal("Data source: NotEnoughUpdates-REPO")
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE))
                                .append(Component.literal(" • "))
                                .append(Component.literal("https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO")
                                    .setStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)))
                        );
                        return 1;
                    })
                );
    }
}
package inventoryreader.ir;

import com.mojang.brigadier.CommandDispatcher;

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
            registerIrCommands(dispatcher);
        });
    }
    
    public static void registerIrCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("ir")
                .executes(context -> {
                    context.getSource().sendFeedback(Component.literal("Inventory Reader Commands:")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD)));
                    context.getSource().sendFeedback(Component.literal("- /ir reset: Reset all mod data")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
                    context.getSource().sendFeedback(Component.literal("- /ir done: Acknowledge reminder")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
                    context.getSource().sendFeedback(Component.literal("- /ir menu: Open Inventory Reader menu")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
                    context.getSource().sendFeedback(Component.literal("- /ir widget: Open Widget Customization Menu")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
                    context.getSource().sendFeedback(Component.literal("- /ir credits: Show credits")
                        .setStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)));
                    return 1;
                })
                .then(literal("reset")
                    .executes(context -> {
                        InventoryReader.LOGGER.info("Executing complete mod reset");
                        FilePathManager.resetData();
                        StorageReader.getInstance().clear();
                        SackReader.getInstance().clear();
                        InventoryReaderClient.clearInventorySnapshot();
                        SandboxWidget.getInstance().resetConfiguration();
                        SackReader.setNeedsReminder(true);
                        context.getSource().sendFeedback(
                            Component.literal("Inventory Reader data reset! ")
                                .setStyle(Style.EMPTY.withColor(ChatFormatting.GREEN))
                                .append(Component.literal("Open a sack or type /ir done to stop reminders.")
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
                        InventoryReaderClient.shouldOpenSandboxViewer = true;
                        return 1;
                    })
                )
                .then(literal("widget")
                    .executes(context -> {
                        InventoryReaderClient.shouldOpenWidgetCustomization = true;
                        return 1;
                    })
                )
                .then(literal("credits")
                    .executes(context -> {
                        context.getSource().sendFeedback(
                            Component.literal("Inventory Reader by Scholiboi, maintained by Tad")
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
                )
        );
    }
}
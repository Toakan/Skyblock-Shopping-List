package inventoryreader.ir;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class ReminderManager {
    private static final int REMINDER_INTERVAL = 100; // 5 seconds
    private static int tickCounter = 0;
    
    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            
            if (SackReader.getNeedsReminder()) {
                tickCounter++;
                
                if (tickCounter >= REMINDER_INTERVAL) {
                    tickCounter = 0;
                    
                    Component message = Component.literal("[Inventory Reader] ")
                        .withStyle(ChatFormatting.GOLD)
                        .append(Component.literal("Remember to open a sack or type ")
                            .withStyle(ChatFormatting.WHITE))
                        .append(Component.literal("/ir done")
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
}
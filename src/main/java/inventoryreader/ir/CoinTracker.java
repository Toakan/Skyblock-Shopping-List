package inventoryreader.ir;

import com.google.gson.reflect.TypeToken;
import inventoryreader.ir.recipes.RemoteRecipeFetcher;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the "Coins" resource equal to purse + bank, so coin costs (shop purchases) show what is left like any
 * other item. The purse is read from the SkyBlock sidebar; the bank balance from the bank menu when the
 * player opens it. Read-only: nothing is sent or clicked.
 */
public final class CoinTracker {
    private static final Pattern PURSE = Pattern.compile("(?:Purse|Piggy):\\s*([\\d,]+(?:\\.\\d+)?)");
    private static final Pattern BANK = Pattern.compile("(?:Current balance|Bank balance|Balance):\\s*([\\d,]+(?:\\.\\d+)?)");
    private static final Type MAP_TYPE = new TypeToken<Map<String, Long>>() {}.getType();
    private static final int CHECK_INTERVAL_TICKS = 20;

    private static long purse = -1;
    private static Long bank;
    private static int lastWritten = -1;
    private static int ticks = 0;

    private CoinTracker() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++ticks < CHECK_INTERVAL_TICKS) return;
            ticks = 0;
            if (!SkyblockDetector.isOnSkyblock() || client.level == null) return;
            long read = readPurse(client);
            if (read >= 0) {
                purse = read;
                publish();
            }
        });
    }

    /** Called when a menu with "Bank" in its title is opened. */
    public static void readBank(AbstractContainerMenu menu, String title) {
        for (int i = 0; i < menu.slots.size() - 36; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            for (Component line : lore.lines()) {
                Matcher m = BANK.matcher(ItemNames.clean(line.getString()));
                if (m.find()) {
                    bank = parse(m.group(1));
                    JsonFiles.write(FilePathManager.COINS_JSON, Map.of("bank", bank));
                    publish();
                    return;
                }
            }
        }
        // Logged so a changed menu layout can be diagnosed from latest.log.
        InventoryReader.debug("No bank balance found in menu \"{}\"", title);
    }

    /** Forgets the bank balance (after a reset deleted coins.json). */
    public static void clear() {
        bank = 0L;
        lastWritten = -1;
    }

    private static long readPurse(Minecraft client) {
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) return -1;
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
            Component line = entry.display() != null
                ? entry.display()
                : PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName());
            Matcher m = PURSE.matcher(ItemNames.clean(line.getString()));
            if (m.find()) return parse(m.group(1));
        }
        return -1;
    }

    private static void publish() {
        if (purse < 0) return; // wait for the first sidebar read
        if (bank == null) {
            Map<String, Long> saved = JsonFiles.read(FilePathManager.COINS_JSON, MAP_TYPE);
            bank = saved != null && saved.get("bank") != null ? saved.get("bank") : 0L;
        }
        long total = Math.max(0, purse) + bank;
        // Resource counts are ints; balances past ~2.1 billion are shown as that cap.
        int value = (int) Math.min(Integer.MAX_VALUE, total);
        if (value != lastWritten) {
            lastWritten = value;
            ResourcesManager.getInstance().setResourceAmount(RemoteRecipeFetcher.COINS_NAME, value);
        }
    }

    private static long parse(String number) {
        try {
            return (long) Double.parseDouble(number.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

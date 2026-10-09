package inventoryreader.ir;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Hypixel's periodic "[Sacks] +12 items, -3 items. (Last 30s.)" chat summary. Items that go
 * straight into sacks while mining only show up here. The per-item changes are in the hover text of the
 * "+N items" / "-N items" parts, one per line ("+64 Coal (Mining Sack)"). Observes messages only; never
 * cancels or edits them.
 */
public final class SackChatListener {
    /** "+1,234 Enchanted Coal (Mining Sack)": optional sign, count, item name, optional sack name. */
    /**
     * The whole message, "[Sacks] +12 items, -3 items. (Last 30s.)". Player chat always starts with the
     * sender's name or a channel prefix, so a player typing "[Sacks] ..." can't match.
     */
    private static final Pattern SACKS_MESSAGE = Pattern.compile("^\\[Sacks] [+-][\\d,]+ items?.*\\(Last \\d+s\\.\\)$");
    private static final Pattern ITEM_LINE = Pattern.compile("^\\s*([+-]?)([\\d,]+)\\s+(.+?)(?:\\s+\\([^()]*\\))?\\s*$");

    private SackChatListener() {}

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !SkyblockDetector.isOnSkyblock()) return;
            try {
                onGameMessage(message);
            } catch (RuntimeException e) {
                InventoryReader.LOGGER.warn("Could not parse sack message: {}", message.getString(), e);
            }
        });
    }

    private static void onGameMessage(Component message) {
        String text = message.getString().strip();
        if (!SACKS_MESSAGE.matcher(text).matches()) return;

        // A set of texts: several parts of the message can carry their own copy of the same hover list.
        Set<String> hoverTexts = new LinkedHashSet<>();
        collectHoverTexts(message, hoverTexts);

        Map<String, Integer> deltas = new LinkedHashMap<>();
        for (String hover : hoverTexts) {
            // Lines without their own sign take it from the nearest "Added"/"Removed" heading.
            int sectionSign = 1;
            for (String line : hover.split("\n")) {
                if (line.contains("Removed")) sectionSign = -1;
                else if (line.contains("Added")) sectionSign = 1;
                Matcher m = ITEM_LINE.matcher(line);
                if (!m.matches()) continue;
                Integer count = parseCount(m.group(2));
                if (count == null || count == 0) continue;
                int sign = m.group(1).isEmpty() ? sectionSign : (m.group(1).equals("-") ? -1 : 1);
                deltas.merge(ItemNames.clean(m.group(3)), sign * count, Integer::sum);
            }
        }

        if (deltas.isEmpty()) {
            // Logged so a changed message format can be diagnosed from latest.log.
            InventoryReader.debug("Unrecognised [Sacks] message: {} | hover: {}", text, hoverTexts);
            return;
        }
        InventoryReader.debug("[Sacks] read: {}", deltas);
        SackReader.getInstance().applyChatDeltas(deltas);
    }

    /** Collects the text of every show-text hover in the component tree, each distinct text once. */
    private static void collectHoverTexts(Component component, Set<String> out) {
        if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component value) && value != null) {
            out.add(value.getString());
        }
        for (Component sibling : component.getSiblings()) {
            collectHoverTexts(sibling, out);
        }
    }

    private static Integer parseCount(String s) {
        try {
            return Integer.parseInt(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

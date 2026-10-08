package inventoryreader.ir;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the periodic "[Sacks] +12 items, -3 items. (Last 30s.)" chat summary. The per-item changes are in
 * the hover text of the "+N items" / "-N items" parts. Observes messages only; never cancels or edits them.
 */
public final class SackChatListener {
    private static final Pattern SACKS_ADDED_AND_REMOVED = Pattern.compile(
        "\\[Sacks\\] [+-].+ items?, [+-].+ items?\\. \\(Last .+s\\.\\).*"
    );
    private static final Pattern SACKS_SINGLE = Pattern.compile(
        "\\[Sacks\\] [+-].+ items?\\. \\(Last .+s\\.\\).*"
    );

    private SackChatListener() {}

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            try {
                onGameMessage(message);
            } catch (RuntimeException e) {
                InventoryReader.LOGGER.warn("Could not parse sack message: {}", message.getString(), e);
            }
        });
    }

    private static void onGameMessage(Component message) {
        String text = message.getString();
        if (!text.startsWith("[Sacks]")) return;
        List<Component> parts = message.getSiblings();
        Map<String, Integer> deltas = new LinkedHashMap<>();
        if (SACKS_ADDED_AND_REMOVED.matcher(text).find()) {
            // Siblings: "+N items" (hover), ", ", ... "-N items" (hover), ...
            readHover(parts, 0, deltas);
            readHover(parts, 3, deltas);
        } else if (SACKS_SINGLE.matcher(text).find()) {
            readHover(parts, 0, deltas);
        } else {
            return;
        }
        InventoryReader.LOGGER.debug("Sack chat deltas: {}", deltas);
        SackReader.getInstance().applyChatDeltas(deltas);
    }

    private static void readHover(List<Component> parts, int index, Map<String, Integer> out) {
        if (index >= parts.size()) return;
        HoverEvent hover = parts.get(index).getStyle().getHoverEvent();
        if (!(hover instanceof HoverEvent.ShowText(Component value)) || value == null) return;
        parseItemLines(value.getSiblings(), out);
    }

    /**
     * The hover text repeats four siblings per item: count, item name, sack name, line break.
     */
    private static void parseItemLines(List<Component> contents, Map<String, Integer> out) {
        Integer count = null;
        for (int i = 0; i < contents.size(); i++) {
            String s = contents.get(i).getString().trim();
            if (s.isEmpty()) continue;
            if (i % 4 == 0) {
                count = parseCount(s);
            } else if (i % 4 == 1 && count != null) {
                out.merge(ItemNames.clean(s), count, Integer::sum);
                count = null;
            }
        }
    }

    private static Integer parseCount(String s) {
        try {
            return Integer.parseInt(s.replace(",", "").replace("+", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

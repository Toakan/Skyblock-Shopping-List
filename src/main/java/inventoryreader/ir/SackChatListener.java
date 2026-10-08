package inventoryreader.ir;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final Pattern ITEM_LINE = Pattern.compile("^\\s*([+-]?)([\\d,]+)\\s+(.+?)(?:\\s+\\([^()]*\\))?\\s*$");

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
        if (!text.contains("[Sacks]")) return;

        List<String> hoverTexts = new ArrayList<>();
        collectHoverTexts(message, hoverTexts, Collections.newSetFromMap(new IdentityHashMap<>()));

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
            InventoryReader.LOGGER.info("Unrecognised [Sacks] message: {} | hover: {}", text, hoverTexts);
            return;
        }
        InventoryReader.LOGGER.debug("Sack chat deltas: {}", deltas);
        SackReader.getInstance().applyChatDeltas(deltas);
    }

    /** Collects the text of every distinct show-text hover in the component tree. */
    private static void collectHoverTexts(Component component, List<String> out, Set<Component> seen) {
        if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component value)
                && value != null && seen.add(value)) {
            out.add(value.getString());
        }
        for (Component sibling : component.getSiblings()) {
            collectHoverTexts(sibling, out, seen);
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

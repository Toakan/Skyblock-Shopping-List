package inventoryreader.ir;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SandboxWidget {
    private static final Identifier SANDBOX_WIDGET_LAYER = Identifier.fromNamespaceAndPath(InventoryReader.MOD_ID, "sandbox_widget");
    private static final int DARK_PANEL_BG = 0x99271910;
    private static final int HEADER_BG = 0xCC2C4A1B;
    private static final int GOLD = 0xFFFFB728;
    private static final int RECIPE_LEVEL_INDENT = 10;
    private static final SandboxWidget INSTANCE = new SandboxWidget();
    private volatile boolean enabled = false;
    private volatile String selectedRecipe = null;
    /** Written by the update thread, read by the render thread; always replaced, never mutated. */
    private volatile RecipeManager.RecipeNode recipeTree = null;
    private int widgetX = 10;
    private int widgetY = 40;
    private int widgetWidth = 250;
    private int widgetHeight = 300;
    private Map<String, Boolean> expandedNodes = new ConcurrentHashMap<>();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private volatile int craftAmount = 1;
    private final ResourcesManager resourcesManager;
    private final ScheduledExecutorService scheduler;
    /** Resource version the current tree was computed from; -1 forces a recompute. */
    private volatile long computedVersion = -1;
    private int currentNodeLineHeight = 16;
    private float currentTreeScale = 1.0f;

    private SandboxWidget() {
        this.resourcesManager = ResourcesManager.getInstance();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "IR-WidgetUpdate");
            t.setDaemon(true);
            return t;
        });
        HudElementRegistry.addLast(SANDBOX_WIDGET_LAYER, (context, tickCounter) -> {
            if (enabled && selectedRecipe != null && recipeTree != null) {
                render(context);
            }
        });
        loadConfiguration();
        scheduler.scheduleWithFixedDelay(this::refreshIfStale, 0, 1, TimeUnit.SECONDS);
    }

    public static SandboxWidget getInstance() {
        return INSTANCE;
    }
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            this.recipeTree = null;
        }
        requestRefresh();
        saveConfiguration();
    }
    public boolean isEnabled() {
        return this.enabled;
    }
    public void setSelectedRecipe(String recipeName) {
        this.selectedRecipe = recipeName;
        if (recipeName != null) {
            expandedNodes.put(recipeName, true);
        }
        requestRefresh();
        saveConfiguration();
    }
    public String getSelectedRecipe() {
        return this.selectedRecipe;
    }
    public int getWidgetX() {
        return widgetX;
    }
    public int getWidgetY() {
        return widgetY;
    }
    public void setWidgetPosition(int x, int y) {
        this.widgetX = x;
        this.widgetY = y;
        saveConfiguration();
    }
    public int getWidgetWidth() { return widgetWidth; }
    public int getWidgetHeight() { return widgetHeight; }
    public void setWidgetSize(int width, int height) {
        Minecraft client = Minecraft.getInstance();
        int screenW = client.getWindow().getGuiScaledWidth();
        int screenH = client.getWindow().getGuiScaledHeight();
        this.widgetWidth = Math.max(180, Math.min(width, screenW - 20));
        this.widgetHeight = Math.max(120, Math.min(height, screenH - 20));
        saveConfiguration();
    }
    public static String getNodeKey(RecipeManager.RecipeNode node) {
        return node.name + "_" + node.amount;
    }
    public boolean isNodeExpanded(String nodeKey) {
        Boolean result = expandedNodes.getOrDefault(nodeKey, false);
        return result != null ? result : false;
    }
    public void setNodeExpansion(String nodeKey, boolean expanded) {
        expandedNodes.put(nodeKey, expanded);
    }
    public void toggleNodeExpansion(String nodeKey) {
        Boolean currentState = expandedNodes.getOrDefault(nodeKey, false);
        if (currentState == null) {
            currentState = false;
        }

        boolean newState = !currentState;
        expandedNodes.put(nodeKey, newState);
        saveConfiguration();
    }
    public void saveConfiguration() {
        WidgetConfig config = new WidgetConfig(
            enabled,
            selectedRecipe,
            widgetX,
            widgetY,
            widgetWidth,
            widgetHeight,
            new HashMap<>(expandedNodes),
            craftAmount
        );
        JsonFiles.write(FilePathManager.WIDGET_CONFIG_JSON, config);
    }
    private void loadConfiguration() {
        WidgetConfig config = JsonFiles.read(FilePathManager.WIDGET_CONFIG_JSON, WidgetConfig.class);
        if (config == null) return;
        this.enabled = config.enabled;
        this.selectedRecipe = config.selectedRecipe;
        this.widgetX = config.widgetX;
        this.widgetY = config.widgetY;
        if (config.expandedNodes != null) {
            this.expandedNodes = new ConcurrentHashMap<>(config.expandedNodes);
        }
        if (config.craftAmount > 0) {
            this.craftAmount = config.craftAmount;
        }
        if (config.widgetWidth > 0) this.widgetWidth = config.widgetWidth;
        if (config.widgetHeight > 0) this.widgetHeight = config.widgetHeight;
    }
    /** Restores defaults after a reset deleted the config file. */
    public void resetConfiguration() {
        enabled = false;
        selectedRecipe = null;
        recipeTree = null;
        widgetX = 10;
        widgetY = 40;
        widgetWidth = 250;
        widgetHeight = 300;
        expandedNodes = new ConcurrentHashMap<>();
        craftAmount = 1;
        messages.clear();
        saveConfiguration();
    }
    private static class WidgetConfig {
        boolean enabled;
        String selectedRecipe;
        int widgetX;
        int widgetY;
    int widgetWidth;
    int widgetHeight;
        Map<String, Boolean> expandedNodes;
        int craftAmount;
    public WidgetConfig(boolean enabled, String selectedRecipe, int widgetX, int widgetY, int widgetWidth, int widgetHeight,
                Map<String, Boolean> expandedNodes, int craftAmount) {
            this.enabled = enabled;
            this.selectedRecipe = selectedRecipe;
            this.widgetX = widgetX;
            this.widgetY = widgetY;
        this.widgetWidth = widgetWidth;
        this.widgetHeight = widgetHeight;
            this.expandedNodes = expandedNodes;
            this.craftAmount = craftAmount;
        }
    }
    /** Marks the tree stale; the update thread recomputes it within a second. */
    private void requestRefresh() {
        computedVersion = -1;
        scheduler.execute(this::refreshIfStale);
    }

    /** Runs on the update thread. Recomputes only when resources or the selection changed. */
    private void refreshIfStale() {
        try {
            long version = resourcesManager.getVersion();
            if (version == computedVersion) return;
            computedVersion = version;
            updateRecipeData();
        } catch (RuntimeException e) {
            // Never let an exception escape: it would cancel the scheduled refresh for good.
            InventoryReader.LOGGER.error("Failed to update HUD recipe", e);
        }
    }

    private void updateRecipeData() {
        String recipe = selectedRecipe;
        if (!enabled || recipe == null) {
            return;
        }

        ResourcesManager.RemainingResponse response = resourcesManager.getRemainingIngredients(recipe, craftAmount);

        List<String> newMessages = new ArrayList<>();
        newMessages.add("Craftable -");
        if (response.messages != null) {
            List<Map.Entry<String, Integer>> sortedEntries = new ArrayList<>(response.messages.entrySet());
            sortedEntries.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));
            for (Map.Entry<String, Integer> entry : sortedEntries) {
                if (entry.getValue() != null && entry.getValue() > 0) {
                    newMessages.add("   " + entry.getValue() + "× " + entry.getKey());
                }
            }
        }
        RecipeManager.RecipeNode tree = response.full_recipe;
        if (tree != null) {
            expandedNodes.putIfAbsent(makePathKey(recipe, tree.name), true);
        }
        messages.clear();
        messages.addAll(newMessages);
        recipeTree = tree;
    }
    private void render(GuiGraphicsExtractor context) {
        RecipeManager.RecipeNode recipeTree = this.recipeTree;
        String selectedRecipe = this.selectedRecipe;
        if (!enabled || selectedRecipe == null || recipeTree == null) {
            return;
        }
    Minecraft client = Minecraft.getInstance();
    int height = client.getWindow().getGuiScaledHeight();
    int panelWidth = widgetWidth;
    int visibleLines = countVisibleRecipeTreeLines(recipeTree, selectedRecipe);
    int panelMaxHeight = Math.min(height - 40, widgetHeight);

    int recipeTreeHeightMax = Math.max(0, visibleLines * 16);
    int messageLinesRaw = countMessageLines(client, panelWidth);
    int availableForMessagesMax = Math.max(0, Math.min((int)(height * 0.4), panelMaxHeight - 20 - recipeTreeHeightMax - 15));
    int messageSectionHeightEst = messageLinesRaw > 0 ? Math.min(messageLinesRaw * 10 + 20, availableForMessagesMax) : 0;

    int availableTreeHeight1 = Math.max(0, panelMaxHeight - 22 - (messageSectionHeightEst > 0 ? (messageSectionHeightEst + 15) : 0));
    int safeLines = Math.max(1, visibleLines);
    int computedLine1 = safeLines > 0 ? Math.max(6, (int)Math.floor((float)availableTreeHeight1 / (float)safeLines)) : 16;
    computedLine1 = Math.min(16, computedLine1);
    int treeHeightActual = safeLines * computedLine1;

    int availableForMessages2 = Math.max(0, Math.min((int)(height * 0.4), panelMaxHeight - 20 - treeHeightActual - 15));
    int messageSectionHeight = messageLinesRaw > 0 ? Math.min(messageLinesRaw * 10 + 20, availableForMessages2) : 0;
    int desiredPanelHeight = 20 + treeHeightActual + messageSectionHeight + 15;
    int panelHeight = Math.min(panelMaxHeight, desiredPanelHeight);

        int panelX = widgetX;
        int panelY = widgetY;

    context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, DARK_PANEL_BG);

        context.fill(panelX, panelY, panelX + panelWidth, panelY + 20, HEADER_BG);

        int borderColor = 0xFFDAA520;
        int borderThickness = 2;
        for (int i = 0; i < borderThickness; i++) {
            context.outline(panelX - i,
                panelY - i, 
                panelWidth + i * 2, 
                panelHeight + i * 2, 
                borderColor
            );
        }

        Component title = Component.literal("Recipe: " + selectedRecipe)
            .setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true));
        int titleWidth = client.font.width(title);
        int maxTitleWidth = Math.max(20, panelWidth - 10);
        float titleScale = titleWidth > maxTitleWidth ? (float)maxTitleWidth / (float)titleWidth : 1.0f;
        context.pose().pushMatrix();
        context.pose().translate(panelX + (panelWidth - Math.min(titleWidth, maxTitleWidth)) / 2f, panelY + 5);
        context.pose().scale(titleScale, titleScale);
        context.text(
            client.font,
            title,
            0,
            0,
            0xFFFFFFFF,
            false
        );
        context.pose().popMatrix();


        context.fill(panelX, panelY + 19, panelX + panelWidth, panelY + 20, 0x99608C35);

    int y = panelY + 22;
    int availableTreeHeight = Math.max(0, panelHeight - 22 - (messageSectionHeight > 0 ? (messageSectionHeight + 15) : 0));
    int computedLine = safeLines > 0 ? Math.max(6, (int)Math.floor((float)availableTreeHeight / (float)safeLines)) : 16;
    computedLine = Math.min(16, computedLine);
    currentNodeLineHeight = computedLine;
    currentTreeScale = currentNodeLineHeight / 16.0f;
    int treeEndY = renderRecipeTree(context, recipeTree, panelX, y, 0, selectedRecipe);

        if (messageSectionHeight > 0) {
            context.fill(panelX, treeEndY, panelX + panelWidth, treeEndY + 1, 0x99608C35);

            int messageY = treeEndY + 6;

            int maxMessageY = panelY + panelHeight - 5;

            drawMessages(context, panelX, messageY, panelWidth, maxMessageY);
        }
    }
    private int countVisibleRecipeTreeLines(RecipeManager.RecipeNode node, String pathKey) {
        if (node == null) return 0;
        int count = 1;

        // Use path-based key to keep expansion stable regardless of amounts
        String nodeKey = makePathKey(pathKey, node.name);
        boolean isExpanded = expandedNodes.getOrDefault(nodeKey, false);

        if (node.ingredients != null && !node.ingredients.isEmpty() && isExpanded) {
            for (RecipeManager.RecipeNode child : node.ingredients) {
                count += countVisibleRecipeTreeLines(child, makePathKey(pathKey, node.name));
            }
        }
        return count;
    }
    private int renderRecipeTree(GuiGraphicsExtractor context, RecipeManager.RecipeNode node, int x, int y, int level, String pathKey) {
        if (node == null) return y;
        Minecraft client = Minecraft.getInstance();
        int unitIndent = Math.max(4, Math.round(RECIPE_LEVEL_INDENT * currentTreeScale));
        int indent = level * unitIndent;
        boolean hasEnough = (node.amount == 0);
        String nodeKey = makePathKey(pathKey, node.name);
        boolean isExpanded = expandedNodes.getOrDefault(nodeKey, false);
        boolean hasChildren = node.ingredients != null && !node.ingredients.isEmpty();
        int bgColor = 0x99271910;
        int mouseX = (int)(client.mouseHandler.xpos() / client.getWindow().getGuiScale());
        int mouseY = (int)(client.mouseHandler.ypos() / client.getWindow().getGuiScale());
        int nodeBaseWidth = Math.max(100, widgetWidth - 20); // account for panel padding
        int nodeHeight = Math.max(6, currentNodeLineHeight);
        boolean isHovered = mouseX >= x + indent && mouseX <= x + indent + nodeBaseWidth - indent && 
                            mouseY >= y && mouseY <= y + nodeHeight;
        int nodeWidth = nodeBaseWidth - indent;
        context.fill(x + indent, y, x + indent + nodeWidth, y + nodeHeight, bgColor);
        if (isHovered) {
            context.fill(x + indent, y, x + indent + nodeWidth, y + nodeHeight, 0x22FFFFFF);
        }
        int borderColor = hasEnough ? 0x88608C35 : 0x88FF5555;
        context.outline(x + indent, y, nodeWidth, nodeHeight, borderColor);
        if (hasChildren) {
            String expandIcon = isExpanded ? "▼" : "▶";
            context.text(
                client.font,
                expandIcon,
                x + indent + Math.max(3, Math.round(5 * currentTreeScale)),
                y + Math.max(1, Math.round(4 * currentTreeScale)),
                0xFFFFFFFF,
                false
            );
        }
    int nameX = x + indent + (hasChildren ? Math.max(14, Math.round(25 * currentTreeScale)) : Math.max(6, Math.round(10 * currentTreeScale)));
        int textColor;
        boolean isBold = (level == 0);
        if (level == 0) {
            textColor = GOLD; 
        } else {
            textColor = hasEnough ? 0xFFFFFFFF : 0xFFFF6B6B;
        }
        String amountText = node.amount + "× ";
        int amountColor = hasEnough ? 0xFF6EFF6E : 0xFFFF6B6B;
        Component itemName = Component.literal(node.name)
            .setStyle(Style.EMPTY.withColor(textColor).withBold(isBold));
        int amountWidth = client.font.width(amountText);
        int itemWidth = client.font.width(itemName);
        int totalTextWidth = amountWidth + itemWidth;
        int maxTextWidth = Math.max(10, nodeWidth - (hasChildren ? Math.max(14, Math.round(25 * currentTreeScale)) : Math.max(6, Math.round(10 * currentTreeScale))));
        int adjustedMaxTextWidth = (int)Math.floor(maxTextWidth / Math.max(0.01f, currentTreeScale));
        float textScaleLocal = totalTextWidth > adjustedMaxTextWidth ? (float)adjustedMaxTextWidth / (float)totalTextWidth : 1.0f;
        float textScale = Math.min(1.0f, textScaleLocal) * currentTreeScale;
        context.pose().pushMatrix();
        context.pose().translate(nameX, y + Math.max(1, Math.round(4 * currentTreeScale)));
        context.pose().scale(textScale, textScale);
        context.text(
            client.font,
            amountText,
            0,
            0,
            amountColor,
            false
        );
        context.text(
            client.font,
            itemName,
            amountWidth,
            0,
            0xFFFFFFFF,
            false
        );
        context.pose().popMatrix();
        y += nodeHeight;
    if (hasChildren && isExpanded && node.ingredients.size() > 0) {
            int lineColor = 0xFF777777;
            for (int i = 0; i < node.ingredients.size(); i++) {
                RecipeManager.RecipeNode child = node.ingredients.get(i);
                int lineStartX = x + indent + Math.max(4, Math.round(6 * currentTreeScale));
                int vertLineY = y;
                int childIndentX = x + indent + unitIndent;
                int vertLen = Math.max(3, Math.round(8 * currentTreeScale));
                context.fill(lineStartX, vertLineY, lineStartX + 1, vertLineY + vertLen, lineColor);
                context.fill(lineStartX, vertLineY + vertLen, childIndentX, vertLineY + vertLen + 1, lineColor);
        int nextY = renderRecipeTree(context, child, x, y, level + 1, nodeKey);
                y = nextY;
            }
        }
        return y;
    }
    private void drawMessages(GuiGraphicsExtractor context, int x, int y, int width, int maxY) {
        Minecraft client = Minecraft.getInstance();
        int baseHeader = 13;
        int baseLine = 10;
        int availableHeight = Math.max(0, maxY - y);
        int lines = countMessageLines(client, width);
        int desiredHeight = baseHeader + Math.max(0, (lines - 1) * baseLine);
        float scale = desiredHeight > 0 ? Math.min(1.0f, Math.max(0.4f, (float)availableHeight / (float)desiredHeight)) : 1.0f;

        if (y + Math.round(baseLine * scale) <= maxY) {
            Component messagesHeader = Component.literal("Craftable -")
                .setStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(true));
            context.pose().pushMatrix();
            context.pose().translate(x + 5, y);
            context.pose().scale(scale, scale);
            context.text(
                client.font,
                messagesHeader,
                0,
                0,
                0xFFFFFFFF,
                false
            );
            context.pose().popMatrix();
        } else {
            return;
        }

        if (messages.size() == 1 && messages.get(0).equals("Craftable -")) {
            return;
        }
        y += Math.round(baseHeader * scale);

        List<String> messagesCopy = new ArrayList<>(messages);
        messagesCopy.sort((a, b) -> {
            if (a.equals("Craftable -")) return -1;
            if (b.equals("Craftable -")) return 1;
            try {
                int amountA = extractAmount(a);
                int amountB = extractAmount(b);
                return Integer.compare(amountB, amountA);
            } catch (Exception e) {
                return 0;
            }
        });

        int unscaledWrapWidth = Math.max(10, (int)Math.floor((width - 15) / Math.max(0.01f, scale)));
        for (String message : messagesCopy) {
            if (message.equals("Craftable -")) continue;
            int textColor = message.startsWith("   ") ? 0xFFFF9D00 : 0xFFFFFFFF;
            String[] words = message.split(" ");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                if (client.font.width(line.toString() + word) > unscaledWrapWidth) {
                    if (y + Math.round(baseLine * scale) > maxY) return;
                    context.pose().pushMatrix();
                    context.pose().translate(x + 5, y);
                    context.pose().scale(scale, scale);
                    context.text(
                        client.font,
                        line.toString(),
                        0,
                        0,
                        textColor,
                        false
                    );
                    context.pose().popMatrix();
                    y += Math.round(baseLine * scale);
                    line = new StringBuilder(message.startsWith("   ") ? "      " : "   ").append(word).append(" ");
                } else {
                    line.append(word).append(" ");
                }
            }
            if (line.length() > 0) {
                if (y + Math.round((baseLine - 1) * scale) > maxY) return;
                context.pose().pushMatrix();
                context.pose().translate(x + 5, y);
                context.pose().scale(scale, scale);
                context.text(
                    client.font,
                    line.toString(),
                    0,
                    0,
                    textColor,
                    false
                );
                context.pose().popMatrix();
                y += Math.round((baseLine - 1) * scale);
            }
        }
    }

    private int extractAmount(String message) {
        try {
            int xIndex = message.indexOf('×');
            if (xIndex > 0) {
                String amountStr = message.substring(0, xIndex).trim();
                return Integer.parseInt(amountStr);
            }
        } catch (Exception e) {
        }
        return 0;
    }
    public void addMessage(String message) {
        this.messages.add(message);
    }
    public List<String> getMessagesSnapshot() {
        return new ArrayList<>(this.messages);
    }
    public int getCraftAmount() {
        return craftAmount;
    }
    public void setCraftAmount(int craftAmount) {
        if (craftAmount < 1) {
            craftAmount = 1;
        }
        this.craftAmount = craftAmount;
        requestRefresh();
        saveConfiguration();
    }
    private int countMessageLines(Minecraft client, int width) {
        int lineCount = 1;
        if (messages.isEmpty()) return lineCount;
        if (messages.size() == 1 && messages.get(0).equals("Craftable -")) return lineCount;
        for (String message : new ArrayList<>(messages)) {
            if (message.equals("Craftable -")) continue;
            String[] words = message.split(" ");
            StringBuilder line = new StringBuilder();
            int linesInMessage = 1;
            for (String word : words) {
                if (client.font.width(line.toString() + word) > width - 15) {
                    linesInMessage++;
                    line = new StringBuilder(message.startsWith("   ") ? "      " : "   ").append(word).append(" ");
                } else {
                    line.append(word).append(" ");
                }
            }
            lineCount += linesInMessage;
        }
        return lineCount;
    }

    public static String makePathKey(String parent, String name) {
        if (parent == null || parent.isEmpty()) return name == null ? "" : name;
        if (name == null || name.isEmpty()) return parent;
        return parent + ">" + name;
    }
}


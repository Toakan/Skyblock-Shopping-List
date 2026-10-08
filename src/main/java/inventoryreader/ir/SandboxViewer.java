package inventoryreader.ir;

import java.util.*;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

public class SandboxViewer extends Screen {

    private static final int PANEL_BG = 0xFF232323;
    private static final int ITEM_BG = 0xFF303030;
    private static final int ITEM_BG_ALT = 0xFF383838;
    private static final int SELECTED_BG = 0xFF5F7FA0;
    private static final int BORDER_COLOR = 0xFF555555;
    private static final int TITLE_BG = 0xFF2A4153;
    private static final int GOLD = 0xFFFFB728;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xFFDDDDDD;
    private static final int SUCCESS_GREEN = 0xFF6EFF6E;
    private static final int ERROR_RED = 0xFFFF6B6B;

    public enum Mode {
        RESOURCE_VIEWER,
        RECIPE_VIEWER,
        FORGE_MODE,
        MODIFY_RESOURCES
    }

    private Mode mode = Mode.RESOURCE_VIEWER;

    private final RecipeManager recipeManager = RecipeManager.getInstance();
    private final ResourcesManager resourcesManager = ResourcesManager.getInstance();

    private List<ResourcesManager.ResourceEntry> resources = new ArrayList<>();
    private List<ResourcesManager.ResourceEntry> filteredResources = new ArrayList<>();
    private String resourceSearchTerm = "";

    private List<String> recipeNames = new ArrayList<>();
    private List<String> filteredRecipeNames = new ArrayList<>();
    private String recipeSearchTerm = "";
    private String selectedRecipe = null;
    private RecipeManager.RecipeNode expandedRecipeTree = null;
    private Map<String, Integer> simpleRecipe = null;

    private ResourcesManager.RemainingResponse remainingResult = null;
    private int craftAmount = 1;
    private boolean craftable = false;
    private final List<String> messages = new ArrayList<>();

    private EditBox searchBox;
    private EditBox amountField;
    private final Map<String, EditBox> resourceAmountFields = new HashMap<>();
    private String activeTextField = null;
    private int scrollOffset = 0;

    private final Map<String, Boolean> expandedNodes = new HashMap<>();

    private record ClickableElement(int x, int y, int width, int height, Runnable action) {}
    private final List<ClickableElement> clickableElements = new ArrayList<>();

    private int recipeCombinedScrollOffset = 0;
    private int recipeCombinedMaxScroll = 0;
    private int recipeCombinedAreaX = 0, recipeCombinedAreaY = 0, recipeCombinedAreaWidth = 0, recipeCombinedAreaHeight = 0;

    private int forgeCombinedScrollOffset = 0;
    private int forgeCombinedMaxScroll = 0;
    private int forgeCombinedAreaX = 0, forgeCombinedAreaY = 0, forgeCombinedAreaWidth = 0, forgeCombinedAreaHeight = 0;

    private List<ResourcesManager.ResourceEntry> selectedResources = new ArrayList<>();

    public SandboxViewer() {
        super(Component.literal("Hypixel Forge"));
    }

    @Override
    protected void init() {
        this.clearWidgets();

        int tabWidth = 110;
        int tabHeight = 20;
        int startX = 20;
        int tabY = 32;

        this.addRenderableWidget(Button.builder(Component.literal("Resources"), button -> {
            mode = Mode.RESOURCE_VIEWER;
            this.init();
        }).bounds(startX, tabY, tabWidth, tabHeight).build());

        this.addRenderableWidget(Button.builder(Component.literal("Recipes"), button -> {
            mode = Mode.RECIPE_VIEWER;
            this.init();
        }).bounds(startX + tabWidth, tabY, tabWidth, tabHeight).build());

        this.addRenderableWidget(Button.builder(Component.literal("Forge Mode"), button -> {
            mode = Mode.FORGE_MODE;
            this.init();
        }).bounds(startX + 2 * tabWidth, tabY, tabWidth, tabHeight).build());

        this.addRenderableWidget(Button.builder(Component.literal("Modify"), button -> {
            mode = Mode.MODIFY_RESOURCES;
            this.init();
        }).bounds(startX + 3 * tabWidth, tabY, tabWidth, tabHeight).build());

        switch (mode) {
            case RESOURCE_VIEWER -> initResourceViewer();
            case RECIPE_VIEWER -> initRecipeViewer();
            case FORGE_MODE -> initForgeMode();
            case MODIFY_RESOURCES -> initModifyResources();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {}

    private void initResourceViewer() {
        searchBox = new EditBox(this.font, 30, 56, 210, 18, Component.literal("Search Resources"));
        searchBox.setHint(Component.literal("Search resources..."));
        searchBox.setResponder(this::onResourceSearchChanged);
        this.addRenderableWidget(searchBox);

        this.addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> loadResources())
            .bounds(this.width - 100, 56, 80, 18).build());

        loadResources();
    }

    private void initRecipeViewer() {
        searchBox = new EditBox(this.font, 30, 56, 210, 18, Component.literal(""));
        searchBox.setHint(Component.literal("Search recipes..."));
        searchBox.setResponder(this::onRecipeSearchChanged);
        this.addRenderableWidget(searchBox);

        loadRecipes();
    }

    private void initForgeMode() {
        loadResources();
        loadRecipes();

        searchBox = new EditBox(this.font, 30, 56, 180, 18, Component.literal(""));
        searchBox.setHint(Component.literal("Search recipes..."));
        searchBox.setResponder(this::onRecipeSearchChanged);
        this.addRenderableWidget(searchBox);

        amountField = new EditBox(this.font, 215, 56, 35, 18, Component.literal("1"));
        amountField.setValue(String.valueOf(craftAmount));
        amountField.setResponder(this::onAmountChanged);
        this.addRenderableWidget(amountField);

        this.addRenderableWidget(Button.builder(
            Component.literal(SandboxWidget.getInstance().isEnabled() ? "Disable HUD" : "Enable HUD"),
            button -> {
                boolean isCurrentlyEnabled = SandboxWidget.getInstance().isEnabled();
                SandboxWidget.getInstance().setEnabled(!isCurrentlyEnabled);
                if (!isCurrentlyEnabled && selectedRecipe != null) {
                    SandboxWidget.getInstance().setSelectedRecipe(selectedRecipe);
                }
                button.setMessage(Component.literal(SandboxWidget.getInstance().isEnabled() ? "Disable HUD" : "Enable HUD"));
            }
        ).bounds(this.width - 110, 56, 100, 18).build());
    }

    private void initModifyResources() {
        resourceAmountFields.clear();
        activeTextField = null;

        searchBox = new EditBox(this.font, 30, 56, 210, 18, Component.literal("Search Resources"));
        searchBox.setHint(Component.literal("Search resources..."));
        searchBox.setResponder(this::onResourceSearchChanged);
        this.addRenderableWidget(searchBox);

        this.addRenderableWidget(Button.builder(Component.literal("Save Changes"), button -> saveResourceChanges())
            .bounds(this.width - 130, 56, 110, 18).build());

        loadResources();
    }

    private void saveResourceChanges() {
        ResourcesManager rm = ResourcesManager.getInstance();
        for (ResourcesManager.ResourceEntry entry : selectedResources) {
            rm.setResourceAmount(entry.name, entry.amount);
        }
        selectedResources.clear();
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendOverlayMessage(Component.literal("Resources saved successfully!"));
        }
    }

    private void loadResources() {
        if (mode == Mode.MODIFY_RESOURCES) {
            resources = resourcesManager.getAllResourceEntriesIncludingZero();
        } else {
            resources = resourcesManager.getAllResourceEntries();
        }
        filterResources();
    }

    private void loadRecipes() {
        recipeNames = recipeManager.getRecipeNames();
        filterRecipes();

        if (selectedRecipe != null) {
            expandedRecipeTree = recipeManager.expandRecipe(selectedRecipe, craftAmount);
            simpleRecipe = recipeManager.getSimpleRecipe(selectedRecipe, craftAmount);
        }
    }

    private void filterResources() {
        if (mode == Mode.MODIFY_RESOURCES) {
            filteredResources = resources.stream()
                .filter(resource -> resourceSearchTerm.isEmpty() || resource.name.toLowerCase().contains(resourceSearchTerm.toLowerCase()))
                .sorted((a, b) -> a.name.compareToIgnoreCase(b.name))
                .collect(Collectors.toList());
        } else {
            boolean hasSearch = resourceSearchTerm != null && !resourceSearchTerm.isEmpty();
            filteredResources = resources.stream()
                .filter(resource -> hasSearch || resource.amount > 0)
                .filter(resource -> resourceSearchTerm.isEmpty() || resource.name.toLowerCase().contains(resourceSearchTerm.toLowerCase()))
                .sorted((a, b) -> a.name.compareToIgnoreCase(b.name))
                .collect(Collectors.toList());
        }
        int maxVisible = getResourceMaxVisibleItems();
        int maxOffset = Math.max(0, filteredResources.size() - maxVisible);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
    }

    private void filterRecipes() {
        if (recipeSearchTerm.isEmpty()) {
            filteredRecipeNames = new ArrayList<>(recipeNames);
        } else {
            filteredRecipeNames = recipeNames.stream()
                .filter(name -> name.toLowerCase().contains(recipeSearchTerm.toLowerCase()))
                .collect(Collectors.toList());
        }
        int maxVisible = getRecipeMaxVisibleItems();
        int maxOffset = Math.max(0, filteredRecipeNames.size() - maxVisible);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
    }

    private void onResourceSearchChanged(String text) {
        resourceSearchTerm = text;

        scrollOffset = 0;
        if (mode == Mode.RESOURCE_VIEWER) {
            if (resourceSearchTerm != null && !resourceSearchTerm.isEmpty()) {
                resources = resourcesManager.getAllResourceEntriesIncludingZero();
            } else {
                resources = resourcesManager.getAllResourceEntries();
            }
        }
        filterResources();
    }

    private void onRecipeSearchChanged(String text) {
        recipeSearchTerm = text;
    scrollOffset = 0;
    filterRecipes();
    }

    private void onAmountChanged(String text) {
        try {
            craftAmount = Math.max(1, Integer.parseInt(text));
        } catch (NumberFormatException e) {
            craftAmount = 1;
        }

        if (selectedRecipe != null) {
            expandedRecipeTree = recipeManager.expandRecipe(selectedRecipe, craftAmount);
            simpleRecipe = recipeManager.getSimpleRecipe(selectedRecipe, craftAmount);
            if (mode == Mode.FORGE_MODE) {
                checkRecipeRequirements();

            }
        }
    }

    private void checkRecipeRequirements() {
        if (selectedRecipe == null) return;

        remainingResult = resourcesManager.getRemainingIngredients(selectedRecipe, craftAmount);

        messages.clear();
        if (remainingResult.messages != null && !remainingResult.messages.isEmpty()) {
            for (Map.Entry<String, Integer> entry : remainingResult.messages.entrySet()) {
                String message = "You need to craft x" + entry.getValue() + " " + entry.getKey();
                messages.add(message);
            }
        }

        craftable = remainingResult.full_recipe != null
            && remainingResult.full_recipe.ingredients.stream().allMatch(child -> child.amount <= 0);

        if (messages.isEmpty() && !craftable) {
            String message = "Missing resources to craft " + selectedRecipe;
            messages.add(message);
        }
    }

    private void selectRecipe(String name) {
        selectedRecipe = name;
        expandedRecipeTree = recipeManager.expandRecipe(name, craftAmount);
        simpleRecipe = recipeManager.getSimpleRecipe(name, craftAmount);
        messages.clear(); 

        if (SandboxWidget.getInstance().isEnabled()) {
            SandboxWidget.getInstance().setSelectedRecipe(name);
        }

        this.init();
        if (mode == Mode.FORGE_MODE) {
            checkRecipeRequirements();
        }
    }

    private void toggleNodeExpanded(String nodePath) {
        expandedNodes.put(nodePath, !expandedNodes.getOrDefault(nodePath, false));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ctx, boolean doubleClick) {
        double mouseX = ctx.x();
        double mouseY = ctx.y();
        int button = ctx.button();

        if (mode == Mode.MODIFY_RESOURCES) {
            String previousActiveField = activeTextField;
            activeTextField = null;

            boolean textFieldClicked = false;
            for (Map.Entry<String, EditBox> entry : resourceAmountFields.entrySet()) {
                EditBox field = entry.getValue();
                if (field.isMouseOver(mouseX, mouseY)) {
                    field.setFocused(true);
                    textFieldClicked = true;
                    activeTextField = entry.getKey();

                    if (!entry.getKey().equals(previousActiveField)) {
                        field.setCursorPosition(0);
                        field.setHighlightPos(field.getValue().length());
                    }
                } else {
                    field.setFocused(false);
                }
            }

            if (textFieldClicked) {
                return true;
            }
        }

        for (ClickableElement element : clickableElements) {
            if (mouseX >= element.x && mouseX < element.x + element.width &&
                mouseY >= element.y && mouseY < element.y + element.height) {
                element.action.run();
                return true;
            }
        }

        // (Screen's new event system handles child widget clicks automatically)
        return super.mouseClicked(ctx, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        int modifiers = event.modifiers();
        if (mode == Mode.MODIFY_RESOURCES && activeTextField != null) {
            EditBox field = resourceAmountFields.get(activeTextField);
            if (field != null && field.isFocused()) {
                if (keyCode == 258) { // Tab – move between fields
                    boolean shiftPressed = (modifiers & 1) != 0;
                    moveToNextTextField(shiftPressed);
                    return true;
                }
                if (keyCode == 257 || keyCode == 335) { // Enter – confirm and advance
                    field.setFocused(false);
                    moveToNextTextField(false);
                    return true;
                }
                if (field.keyPressed(event)) {
                    return true;
                }
            }
        }
        return super.keyPressed(event);
    }

    private void moveToNextTextField(boolean reverse) {
        if (filteredResources.isEmpty() || activeTextField == null) return;

        int maxVisibleItems = getResourceMaxVisibleItems();
        int startIndex = Math.min(scrollOffset, Math.max(0, filteredResources.size() - maxVisibleItems));
        int endIndex = Math.min(startIndex + maxVisibleItems, filteredResources.size());

        List<String> visibleResources = new ArrayList<>();
        for (int i = startIndex; i < endIndex; i++) {
            visibleResources.add(filteredResources.get(i).name);
        }

        if (visibleResources.isEmpty()) return;

        int currentIndex = visibleResources.indexOf(activeTextField);
        if (currentIndex == -1) {
            currentIndex = 0;
        } else {
            if (reverse) {
                currentIndex = (currentIndex - 1 + visibleResources.size()) % visibleResources.size();
            } else {
                currentIndex = (currentIndex + 1) % visibleResources.size();
            }
        }

        if (resourceAmountFields.containsKey(activeTextField)) {
            resourceAmountFields.get(activeTextField).setFocused(false);
        }

        String newActiveField = visibleResources.get(currentIndex);
        activeTextField = newActiveField;

        if (resourceAmountFields.containsKey(newActiveField)) {
            EditBox field = resourceAmountFields.get(newActiveField);
            field.setFocused(true);
            field.setCursorPosition(0);
            field.setHighlightPos(field.getValue().length());
        }
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (mode == Mode.MODIFY_RESOURCES && activeTextField != null) {
            EditBox field = resourceAmountFields.get(activeTextField);
            if (field != null && field.isFocused() && field.charTyped(event)) {
                return true;
            }
        }
        return super.charTyped(event);
    }

    private int getResourceMaxVisibleItems() {
        int listHeight = Math.max(0, this.height - 10 - 139);
        int lineHeight = 30;
        if (mode == Mode.MODIFY_RESOURCES) {
            return Math.max(1, listHeight / lineHeight);
        } else {
            int columnsCount = Math.max(1, (this.width - 40) / 220);
            return Math.max(1, listHeight / lineHeight) * columnsCount;
        }
    }

    private int getRecipeMaxVisibleItems() {
        int listHeight = Math.max(0, this.height - 10 - 150);
        return Math.max(1, listHeight / 24);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mode == Mode.RECIPE_VIEWER && recipeCombinedAreaWidth > 0 && recipeCombinedAreaHeight > 0) {
            if (mouseX >= recipeCombinedAreaX && mouseX <= recipeCombinedAreaX + recipeCombinedAreaWidth &&
                mouseY >= recipeCombinedAreaY && mouseY <= recipeCombinedAreaY + recipeCombinedAreaHeight) {
                recipeCombinedScrollOffset -= (int)(verticalAmount * 24);
                recipeCombinedScrollOffset = Math.max(0, Math.min(recipeCombinedScrollOffset, recipeCombinedMaxScroll));
                return true;
            }
        }

        if (mode == Mode.FORGE_MODE && forgeCombinedAreaWidth > 0 && forgeCombinedAreaHeight > 0) {
            if (mouseX >= forgeCombinedAreaX && mouseX <= forgeCombinedAreaX + forgeCombinedAreaWidth &&
                mouseY >= forgeCombinedAreaY && mouseY <= forgeCombinedAreaY + forgeCombinedAreaHeight) {
                forgeCombinedScrollOffset -= (int)(verticalAmount * 24);
                forgeCombinedScrollOffset = Math.max(0, Math.min(forgeCombinedScrollOffset, forgeCombinedMaxScroll));
                return true;
            }
        }

        int maxVisibleItems;
        int maxItems;
        if (mode == Mode.RESOURCE_VIEWER || mode == Mode.MODIFY_RESOURCES) {
            maxVisibleItems = getResourceMaxVisibleItems();
            maxItems = filteredResources.size();
        } else {
            maxVisibleItems = getRecipeMaxVisibleItems();
            maxItems = filteredRecipeNames.size();
        }

        if (maxItems > maxVisibleItems) {
            scrollOffset -= (int) verticalAmount;
            scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, maxItems - maxVisibleItems)));
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private static final int CONTENT_Y = 80;
    private int lastMouseX;
    private int lastMouseY;

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        try {
            context.fill(0, 0, this.width, this.height, 0xFF0E0E0E);

            context.fill(0, 0, this.width, 30, TITLE_BG);
            context.outline(0, 0, this.width, 30, BORDER_COLOR);
            String titleStr = "Skyblock Resource Calculator";
            context.text(font,
                Component.literal(titleStr).setStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true)),
                this.width / 2 - font.width(titleStr) / 2, 11, GOLD, false);

            context.fill(0, 30, this.width, 54, 0xFF161616);

            int activeTabX = switch (mode) {
                case RESOURCE_VIEWER  -> 20;
                case RECIPE_VIEWER   -> 130;
                case FORGE_MODE      -> 240;
                case MODIFY_RESOURCES -> 350;
            };
            context.fill(activeTabX, 52, activeTabX + 110, 54, 0xFF5FAF3F);

            context.fill(0, 54, this.width, 78, 0xFF121212);
            context.fill(0, 77, this.width, 78, BORDER_COLOR);

            if (mode == Mode.FORGE_MODE) {
                context.text(font, "x", 204, 62, TEXT_SECONDARY, false);
            }

            int contentX = 20;
            int contentWidth = this.width - 40;
            int contentHeight = this.height - CONTENT_Y - 10;
            context.fill(contentX, CONTENT_Y, contentX + contentWidth, CONTENT_Y + contentHeight, PANEL_BG);
            context.outline(contentX, CONTENT_Y, contentWidth, contentHeight, BORDER_COLOR);

            clickableElements.clear();

            switch (mode) {
                case RESOURCE_VIEWER  -> renderResourceViewer(context, contentX, CONTENT_Y, contentWidth, contentHeight);
                case RECIPE_VIEWER   -> renderRecipeViewer(context, contentX, CONTENT_Y, contentWidth, contentHeight);
                case FORGE_MODE      -> renderForgeMode(context, contentX, CONTENT_Y, contentWidth, contentHeight);
                case MODIFY_RESOURCES -> renderModifyResources(context, contentX, CONTENT_Y, contentWidth, contentHeight);
            }

            super.extractRenderState(context, mouseX, mouseY, delta);
        } catch (Exception e) {
            InventoryReader.LOGGER.error("Error in render method", e);
        }
    }


    private void renderResourceViewer(GuiGraphicsExtractor context, int contentX, int contentY, int contentWidth, int contentHeight) {
        int lineHeight = 30;
        int columnsCount = Math.max(1, contentWidth / 220);
        int columnWidth = contentWidth / columnsCount;
        int gridStartY = contentY + 35;
        int totalItems = filteredResources.size();
        int maxVisibleItems = getResourceMaxVisibleItems();
        int startIndex = Math.min(scrollOffset, Math.max(0, totalItems - maxVisibleItems));

        int headerHeight = 24;
        context.fill(contentX, gridStartY, contentX + contentWidth, gridStartY + headerHeight, ITEM_BG_ALT);
        context.outline(contentX, gridStartY, contentWidth, headerHeight, BORDER_COLOR);

        context.text(font, "Resource Name", contentX + 12, gridStartY + 8, GOLD, false);
        context.text(font, "Amount", contentX + contentWidth - columnWidth/4 - 40, gridStartY + 8, GOLD, false);

        int listStartY = gridStartY + headerHeight;
        int listHeight = contentY + contentHeight - listStartY;
        context.fill(contentX, listStartY, contentX + contentWidth, contentY + contentHeight, PANEL_BG);

        if (totalItems == 0) {
            drawCenteredText(context, "No resources found", contentX + contentWidth / 2, listStartY + 30, TEXT_SECONDARY);
        }

        int index = 0;
        for (int i = startIndex; i < Math.min(startIndex + maxVisibleItems, totalItems); i++) {
            ResourcesManager.ResourceEntry resource = filteredResources.get(i);
            int row = index / columnsCount;
            int col = index % columnsCount;
            int itemX = contentX + col * columnWidth + 5;
            int itemY = listStartY + row * lineHeight + 3;
            int itemWidth = columnWidth - 10;

            boolean isAlternate = row % 2 == 1;
            context.fill(itemX, itemY, itemX + itemWidth, itemY + lineHeight - 4, isAlternate ? ITEM_BG_ALT : ITEM_BG);

            if (isMouseOver(itemX, itemY, itemWidth, lineHeight - 4)) {
                context.fill(itemX, itemY, itemX + itemWidth, itemY + lineHeight - 4, SELECTED_BG);
            }

            context.text(font, resource.name, itemX + 8, itemY + (lineHeight - font.lineHeight) / 2, WHITE, false);
            String amountText = resource.amount + "×";
            int amountWidth = font.width(amountText);
            context.text(font, amountText, itemX + itemWidth - amountWidth - 8, itemY + (lineHeight - font.lineHeight) / 2, GOLD, false);
            index++;
        }

        if (totalItems > maxVisibleItems) {
            int scrollbarWidth = 6;
            int scrollbarX = contentX + contentWidth - scrollbarWidth - 2;
            context.fill(scrollbarX, listStartY, scrollbarX + scrollbarWidth, listStartY + listHeight, ITEM_BG_ALT);

            int thumbHeight = Math.max(10, listHeight * maxVisibleItems / totalItems);
            int thumbY = listStartY;
            if (totalItems > maxVisibleItems) {
                thumbY += (scrollOffset * (listHeight - thumbHeight) / (totalItems - maxVisibleItems));
            }
            context.fill(scrollbarX, thumbY, scrollbarX + scrollbarWidth, thumbY + thumbHeight, BORDER_COLOR);
        }
    }

    private boolean isMouseOver(int x, int y, int width, int height) {
        double mouseX = this.minecraft.mouseHandler.xpos() * (double)this.minecraft.getWindow().getGuiScaledWidth() / (double)this.minecraft.getWindow().getScreenWidth();
        double mouseY = this.minecraft.mouseHandler.ypos() * (double)this.minecraft.getWindow().getGuiScaledHeight() / (double)this.minecraft.getWindow().getScreenHeight();
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private void renderRecipeViewer(GuiGraphicsExtractor context, int contentX, int contentY, int contentWidth, int contentHeight) {
        int leftPanelWidth = 220;
        int leftPanelX = contentX + 10;
        int rightPanelX = contentX + leftPanelWidth + 20;

        context.fill(contentX + leftPanelWidth + 10, contentY, contentX + leftPanelWidth + 11, contentY + contentHeight, BORDER_COLOR);

        renderRecipeList(context, leftPanelX, contentY, leftPanelWidth, contentHeight);

        if (selectedRecipe != null && expandedRecipeTree != null) {
            renderRecipeDetails(context, rightPanelX, contentY, contentWidth - leftPanelWidth - 30, contentHeight);
        } else {
            drawCenteredText(context, "Select a recipe from the list", rightPanelX + (contentWidth - leftPanelWidth - 30) / 2, contentY + contentHeight / 2, TEXT_SECONDARY);
        }
    }

    private void renderForgeMode(GuiGraphicsExtractor context, int contentX, int contentY, int contentWidth, int contentHeight) {
        int leftPanelWidth = 220;
        int leftPanelX = contentX + 10;
        int rightPanelX = contentX + leftPanelWidth + 20;

        context.fill(contentX + leftPanelWidth + 10, contentY, contentX + leftPanelWidth + 11, contentY + contentHeight, BORDER_COLOR);

        renderRecipeList(context, leftPanelX, contentY, leftPanelWidth, contentHeight);

        if (selectedRecipe == null || remainingResult == null) {
            drawCenteredText(context, "Select a recipe to forge", rightPanelX + (contentWidth - leftPanelWidth - 40) / 2, contentY + contentHeight / 2, TEXT_SECONDARY);
            return;
        }

        renderForgeDetails(context, rightPanelX, contentY, contentWidth - leftPanelWidth - 40, contentHeight);
    }

    private void renderRecipeList(GuiGraphicsExtractor context, int x, int y, int width, int height) {
        int recipeListY = y + 40;
        int lineHeight = 24;

        context.fill(x, recipeListY, x + width - 10, recipeListY + 24, TITLE_BG);
        context.outline(x, recipeListY, width - 10, 24, BORDER_COLOR);
        context.text(font, "Available Recipes", x + 10, recipeListY + 8, GOLD, false);

        recipeListY += 30;

        int maxVisibleItems = getRecipeMaxVisibleItems();
        int totalItems = filteredRecipeNames.size();

        int visibleIndex = 0;
        for (String name : filteredRecipeNames) {
            if (visibleIndex >= scrollOffset && visibleIndex < scrollOffset + maxVisibleItems) {
                int itemY = recipeListY + (visibleIndex - scrollOffset) * lineHeight;
                boolean isSelected = name.equals(selectedRecipe);
                int itemBgColor = (visibleIndex % 2 == 0) ? ITEM_BG : ITEM_BG_ALT;
                context.fill(x, itemY, x + width - 16, itemY + lineHeight - 2, itemBgColor);

                if (isSelected) {
                    context.fill(x, itemY, x + width - 16, itemY + lineHeight - 2, SELECTED_BG);
                }
                if (isMouseOver(x, itemY, width - 16, lineHeight - 2) && !isSelected) {
                    context.fill(x, itemY, x + width - 16, itemY + lineHeight - 2, 0x32FFFFFF);
                }

                final String currentName = name;
                clickableElements.add(new ClickableElement(x, itemY, width - 16, lineHeight - 2, () -> selectRecipe(currentName)));

                String displayName = name;
                int maxWidth = width - 30;
                if (font.width(displayName) > maxWidth) {
                    displayName = font.plainSubstrByWidth(displayName, maxWidth - font.width("...")) + "...";
                }
                context.text(font, displayName, x + 8, itemY + (lineHeight - font.lineHeight) / 2, isSelected ? WHITE : TEXT_SECONDARY, false);
            }
            visibleIndex++;
        }

        if (totalItems > maxVisibleItems) {
            int scrollbarWidth = 6;
            int scrollbarX = x + width - 16;
            int listStartY = recipeListY;
            int listHeight = y + height - listStartY;

            context.fill(scrollbarX, listStartY, scrollbarX + scrollbarWidth, listStartY + listHeight, ITEM_BG_ALT);

            int thumbHeight = Math.max(10, listHeight * maxVisibleItems / totalItems);
            int thumbY = listStartY;
            if (totalItems > maxVisibleItems) {
                thumbY += (scrollOffset * (listHeight - thumbHeight) / (totalItems - maxVisibleItems));
            }
            context.fill(scrollbarX, thumbY, scrollbarX + scrollbarWidth, thumbY + thumbHeight, BORDER_COLOR);
        }
    }

    private void renderRecipeDetails(GuiGraphicsExtractor context, int x, int y, int width, int height) {
        int rightColumnY = y + 10;

        context.fill(x, rightColumnY, x + width, rightColumnY + 40, TITLE_BG);
        context.outline(x, rightColumnY, width, 40, BORDER_COLOR);
        drawCenteredText(context, selectedRecipe, x + width / 2, rightColumnY + 15, GOLD);
        rightColumnY += 50;
        recipeCombinedAreaX = x;
        recipeCombinedAreaY = rightColumnY;
        recipeCombinedAreaWidth = width;
        recipeCombinedAreaHeight = y + height - rightColumnY - 10;

        context.fill(recipeCombinedAreaX, recipeCombinedAreaY, recipeCombinedAreaX + recipeCombinedAreaWidth, recipeCombinedAreaY + recipeCombinedAreaHeight, PANEL_BG);
        context.outline(recipeCombinedAreaX, recipeCombinedAreaY, recipeCombinedAreaWidth, recipeCombinedAreaHeight, BORDER_COLOR);

        int contentY = recipeCombinedAreaY + 10;
        int headerH = 20;
        int contentHeightMaterials;
        {
            int tmpY = contentY;
            tmpY += headerH + 6;
            int cardWidth = 180;
            int cardsPerRow = Math.max(1, (width - 20) / cardWidth);
            int materialRows = 0;
            if (simpleRecipe != null && !simpleRecipe.isEmpty()) {
                int materialCount = simpleRecipe.size();
                materialRows = (materialCount + cardsPerRow - 1) / cardsPerRow;
            }
            tmpY += (simpleRecipe == null || simpleRecipe.isEmpty()) ? 24 : materialRows * 34 + 4;
            contentHeightMaterials = tmpY - contentY;
        }
        int contentHeightTree;
        {
            int tmpY = contentY + contentHeightMaterials + 10;
            tmpY += headerH + 6;
            int dryEndY = renderRecipeTree(null, expandedRecipeTree, "", x + 15, tmpY + 4);
            contentHeightTree = (dryEndY - tmpY) + 4;
        }

        int totalContentHeight = contentHeightMaterials + 10 + headerH + 6 + contentHeightTree;
        recipeCombinedMaxScroll = Math.max(0, totalContentHeight - recipeCombinedAreaHeight + 10);
        recipeCombinedScrollOffset = Math.max(0, Math.min(recipeCombinedScrollOffset, recipeCombinedMaxScroll));

        context.enableScissor(recipeCombinedAreaX, recipeCombinedAreaY, recipeCombinedAreaX + recipeCombinedAreaWidth, recipeCombinedAreaY + recipeCombinedAreaHeight);
        int drawY = contentY - recipeCombinedScrollOffset;

        context.fill(x, drawY, x + width, drawY + headerH, ITEM_BG_ALT);
        context.text(font, "Required Materials", x + 10, drawY + 6, GOLD, false);
        drawY += headerH + 6;
        int cardWidth = 180;
        int cardsPerRow = Math.max(1, (width - 20) / cardWidth);
        int cardSpacing = 10;
        if (simpleRecipe != null && !simpleRecipe.isEmpty()) {
            int materialIndex = 0;
            for (Map.Entry<String, Integer> entry : simpleRecipe.entrySet()) {
                int col = materialIndex % cardsPerRow;
                int row = materialIndex / cardsPerRow;
                int itemX = x + col * (cardWidth + cardSpacing);
                int itemY = drawY + row * 34;
                if (itemY + 30 >= recipeCombinedAreaY && itemY <= recipeCombinedAreaY + recipeCombinedAreaHeight) {
                    context.fill(itemX, itemY, itemX + cardWidth, itemY + 28, ITEM_BG);
                    context.outline(itemX, itemY, cardWidth, 28, BORDER_COLOR);
                    context.text(font, entry.getKey(), itemX + 8, itemY + 10, WHITE, false);
                    String qtyText = entry.getValue() + "×";
                    context.text(font, qtyText, itemX + cardWidth - font.width(qtyText) - 8, itemY + 10, GOLD, false);
                }
                materialIndex++;
            }
            int materialRows = (simpleRecipe.size() + cardsPerRow - 1) / cardsPerRow;
            drawY += materialRows * 34 + 4;
        } else {
            context.text(font, "No materials required", x + 10, drawY + 5, TEXT_SECONDARY, false);
            drawY += 24;
        }

    drawY += 10;
        context.fill(x, drawY, x + width, drawY + headerH, ITEM_BG_ALT);
        context.text(font, "Crafting Tree (Click to expand)", x + 10, drawY + 6, GOLD, false);
        drawY += headerH + 6;
        drawY = renderRecipeTree(context, expandedRecipeTree, "", x + 15, drawY);

        context.disableScissor();

        if (recipeCombinedMaxScroll > 0) {
            int sbWidth = 6;
            int sbX = recipeCombinedAreaX + recipeCombinedAreaWidth - sbWidth - 4;
            int sbY = recipeCombinedAreaY + 2;
            int sbHeight = recipeCombinedAreaHeight - 4;
            context.fill(sbX, sbY, sbX + sbWidth, sbY + sbHeight, ITEM_BG_ALT);
            int thumbHeight = Math.max(10, (int)(sbHeight * (recipeCombinedAreaHeight / (float)(totalContentHeight + 1))));
            int thumbY = sbY + (int)((recipeCombinedScrollOffset / (float)recipeCombinedMaxScroll) * (sbHeight - thumbHeight));
            context.fill(sbX, thumbY, sbX + sbWidth, thumbY + thumbHeight, BORDER_COLOR);
        }
    }

    private void renderForgeDetails(GuiGraphicsExtractor context, int x, int y, int width, int height) {
        int rightColumnY = y + 10;

        context.fill(x, rightColumnY, x + width, rightColumnY + 40, TITLE_BG);
        context.outline(x, rightColumnY, width, 40, BORDER_COLOR);
        drawCenteredText(context, selectedRecipe, x + width / 2, rightColumnY + 15, GOLD);
        rightColumnY += 50;

        context.fill(x, rightColumnY, x + width, rightColumnY + 60, ITEM_BG);
        context.outline(x, rightColumnY, width, 60, BORDER_COLOR);
        context.text(font, "Crafting Amount: ", x + 10, rightColumnY + 10, WHITE, false);
        context.text(font, craftAmount + "", x + 120, rightColumnY + 10, GOLD, false);
        String statusIcon = craftable ? "✓" : "✗";
        String craftableText = craftable ? "Can be crafted!" : "Missing ingredients";
        int craftableColor = craftable ? SUCCESS_GREEN : ERROR_RED;
        context.text(font, statusIcon, x + 10, rightColumnY + 30, craftableColor, false);
        context.text(font, craftableText, x + 30, rightColumnY + 30, craftableColor, false);
        rightColumnY += 70;

        if (!messages.isEmpty()) {
            context.fill(x, rightColumnY, x + width, rightColumnY + 30, ITEM_BG_ALT);
            context.text(font, "Crafting Messages", x + 10, rightColumnY + 10, GOLD, false);
            rightColumnY += 35;
            int msgBoxHeight = Math.min(messages.size() * 20 + 10, 100);
            context.fill(x, rightColumnY, x + width, rightColumnY + msgBoxHeight, PANEL_BG);
            context.outline(x, rightColumnY, width, msgBoxHeight, BORDER_COLOR);
            int msgY = rightColumnY + 8;
            for (int i = 0; i < messages.size() && msgY < rightColumnY + msgBoxHeight - 15; i++) {
                String msg = "• " + messages.get(i);
                context.text(font, msg, x + 12, msgY, TEXT_SECONDARY, false);
                msgY += 20;
            }
            rightColumnY += msgBoxHeight + 15;
        }

        forgeCombinedAreaX = x;
        forgeCombinedAreaY = rightColumnY;
        forgeCombinedAreaWidth = width;
        forgeCombinedAreaHeight = y + height - rightColumnY - 10;

        context.fill(forgeCombinedAreaX, forgeCombinedAreaY, forgeCombinedAreaX + forgeCombinedAreaWidth, forgeCombinedAreaY + forgeCombinedAreaHeight, PANEL_BG);
        context.outline(forgeCombinedAreaX, forgeCombinedAreaY, forgeCombinedAreaWidth, forgeCombinedAreaHeight, BORDER_COLOR);

        int contentY = forgeCombinedAreaY + 10;
        int headerH = 20;
        int contentHeightMaterials;
        {
            int tmpY = contentY;
            tmpY += headerH + 6;
            int cardWidth = 180;
            int cardsPerRow = Math.max(1, (width - 20) / cardWidth);
            int materialRows = 0;
            if (simpleRecipe != null && !simpleRecipe.isEmpty()) {
                int materialCount = simpleRecipe.size();
                materialRows = (materialCount + cardsPerRow - 1) / cardsPerRow;
            }
            tmpY += ((simpleRecipe == null || simpleRecipe.isEmpty()) ? 24 : materialRows * 34 + 4);
            contentHeightMaterials = tmpY - contentY;
        }

        int contentHeightTree;
        {
            int tmpY = contentY + contentHeightMaterials + 10;
            tmpY += headerH + 6;
            int dryEndY = renderRecipeTree(null, remainingResult != null ? remainingResult.full_recipe : null, "", x + 15, tmpY + 4);
            contentHeightTree = (dryEndY - tmpY) + 4;
        }

        int totalContentHeight = contentHeightMaterials + 10 + headerH + 6 + contentHeightTree;
        forgeCombinedMaxScroll = Math.max(0, totalContentHeight - forgeCombinedAreaHeight + 10);
        forgeCombinedScrollOffset = Math.max(0, Math.min(forgeCombinedScrollOffset, forgeCombinedMaxScroll));

        context.enableScissor(forgeCombinedAreaX, forgeCombinedAreaY, forgeCombinedAreaX + forgeCombinedAreaWidth, forgeCombinedAreaY + forgeCombinedAreaHeight);
        int drawY = contentY - forgeCombinedScrollOffset;

        context.fill(x, drawY, x + width, drawY + headerH, ITEM_BG_ALT);
        context.text(font, "Required Materials", x + 10, drawY + 6, GOLD, false);
        drawY += headerH + 6;
        int cardWidth = 180;
        int cardsPerRow = Math.max(1, (width - 20) / cardWidth);
        if (simpleRecipe != null && !simpleRecipe.isEmpty()) {
            int materialIndex = 0;
            for (Map.Entry<String, Integer> entry : simpleRecipe.entrySet()) {
                int col = materialIndex % cardsPerRow;
                int row = materialIndex / cardsPerRow;
                int itemX = x + col * (cardWidth + 10);
                int itemY = drawY + row * 34;
                if (itemY + 30 >= forgeCombinedAreaY && itemY <= forgeCombinedAreaY + forgeCombinedAreaHeight) {
                    context.fill(itemX, itemY, itemX + cardWidth, itemY + 28, ITEM_BG);
                    context.outline(itemX, itemY, cardWidth, 28, BORDER_COLOR);
                    context.text(font, entry.getKey(), itemX + 8, itemY + 10, WHITE, false);
                    String qtyText = entry.getValue() + "×";
                    context.text(font, qtyText, itemX + cardWidth - font.width(qtyText) - 8, itemY + 10, GOLD, false);
                }
                materialIndex++;
            }
            int materialRows = (simpleRecipe.size() + cardsPerRow - 1) / cardsPerRow;
            drawY += materialRows * 34 + 4;
        } else {
            context.text(font, "No materials required", x + 10, drawY + 5, TEXT_SECONDARY, false);
            drawY += 24;
        }

    drawY += 10;
        context.fill(x, drawY, x + width, drawY + headerH, ITEM_BG_ALT);
        context.text(font, "Required Recipe Tree (Click to Expand)", x + 10, drawY + 6, GOLD, false);
        drawY += headerH + 6;
        drawY = renderRecipeTree(context, remainingResult != null ? remainingResult.full_recipe : null, "", x + 15, drawY);

        context.disableScissor();

        if (forgeCombinedMaxScroll > 0) {
            int sbWidth = 6;
            int sbX = forgeCombinedAreaX + forgeCombinedAreaWidth - sbWidth - 4;
            int sbY = forgeCombinedAreaY + 2;
            int sbHeight = forgeCombinedAreaHeight - 4;
            context.fill(sbX, sbY, sbX + sbWidth, sbY + sbHeight, ITEM_BG_ALT);
            int thumbHeight = Math.max(10, (int)(sbHeight * (forgeCombinedAreaHeight / (float)(totalContentHeight + 1))));
            int thumbY = sbY + (int)((forgeCombinedScrollOffset / (float)forgeCombinedMaxScroll) * (sbHeight - thumbHeight));
            context.fill(sbX, thumbY, sbX + sbWidth, thumbY + thumbHeight, BORDER_COLOR);
        }
    }

    private int renderRecipeTree(GuiGraphicsExtractor context, Object nodeObj, String path, int x, int y) {
        if (nodeObj == null) return y;

        int lineHeight = 24;
        String name;
        int amount;
        List<?> ingredients;

        if (nodeObj instanceof RecipeManager.RecipeNode node) {
            name = node.name;
            amount = node.amount;
            ingredients = node.ingredients;
        } else {
            return y;
        }

        String fullPath = path + name;
        boolean isExpanded = expandedNodes.getOrDefault(fullPath, false);
        boolean hasIngredients = ingredients != null && !ingredients.isEmpty();

        int textColor = WHITE;
        if (mode == Mode.FORGE_MODE && amount > 0) {
            textColor = resourcesManager.getResourceByName(name) >= amount ? SUCCESS_GREEN : ERROR_RED;
        }

        int nodeWidth = Math.min(300, Math.max(100, font.width(name) + font.width(amount + "×") + 40));
        if (context != null) {
            context.fill(x - 5, y, x + nodeWidth, y + lineHeight, ITEM_BG);
            context.outline(x - 5, y, nodeWidth + 5, lineHeight, BORDER_COLOR);
            if (isMouseOver(x - 5, y, nodeWidth, lineHeight)) {
                context.fill(x - 5, y, x + nodeWidth, y + lineHeight, SELECTED_BG);
            }
            if (hasIngredients) {
                clickableElements.add(new ClickableElement(x - 5, y, nodeWidth, lineHeight, () -> toggleNodeExpanded(fullPath)));
            }
            context.text(font, amount + "×", x + 15, y + (lineHeight - font.lineHeight) / 2, GOLD, false);
            context.text(font, name, x + 15 + font.width(amount + "×") + 5, y + (lineHeight - font.lineHeight) / 2, textColor, false);
        }
        int endY = y + lineHeight;
        if (isExpanded && hasIngredients) {
            int childY = y + lineHeight + 2;
            int indent = 24;
            for (Object child : ingredients) {
                int nextY = renderRecipeTree(context, child, fullPath + ".", x + indent, childY);
                childY = nextY + 4;
            }
            endY = childY - 4;
        }
        return endY;
    }

    private void drawCenteredText(GuiGraphicsExtractor context, String text, int centerX, int y, int color) {
        context.text(font, text, centerX - font.width(text) / 2, y, color, false);
    }


    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private void renderModifyResources(GuiGraphicsExtractor context, int contentX, int contentY, int contentWidth, int contentHeight) {
        int lineHeight = 30;

        int leftPanelWidth = contentWidth - 200;
        int rightPanelX = contentX + leftPanelWidth + 20;

        Set<String> currentResourceKeys = new HashSet<>();
        for (ResourcesManager.ResourceEntry resource : filteredResources) {
            currentResourceKeys.add(resource.name);
        }

        resourceAmountFields.entrySet().removeIf(entry -> !currentResourceKeys.contains(entry.getKey()));

        context.fill(contentX + leftPanelWidth + 10, contentY, contentX + leftPanelWidth + 11, contentY + contentHeight, BORDER_COLOR);

        int gridStartY = contentY + 35;
        int totalItems = filteredResources.size();
        int maxVisibleItems = getResourceMaxVisibleItems();
        int startIndex = Math.min(scrollOffset, Math.max(0, totalItems - maxVisibleItems));

        int headerHeight = 24;
        context.fill(contentX, gridStartY, contentX + leftPanelWidth, gridStartY + headerHeight, ITEM_BG_ALT);
        context.outline(contentX, gridStartY, leftPanelWidth, headerHeight, BORDER_COLOR);

        context.text(font, "Resource Name", contentX + 12, gridStartY + 8, GOLD, false);
        context.text(font, "Amount", contentX + leftPanelWidth - 120, gridStartY + 8, GOLD, false);
        context.text(font, "Actions", contentX + leftPanelWidth - 60, gridStartY + 8, GOLD, false);

        int listStartY = gridStartY + headerHeight;
        context.fill(contentX, listStartY, contentX + leftPanelWidth, contentY + contentHeight, PANEL_BG);

        if (totalItems == 0) {
            drawCenteredText(context, "No resources found", contentX + leftPanelWidth / 2, listStartY + 30, TEXT_SECONDARY);
            return;
        }

        context.fill(rightPanelX, contentY, rightPanelX + 180, contentY + 40, TITLE_BG);
        context.outline(rightPanelX, contentY, 180, 40, BORDER_COLOR);
        drawCenteredText(context, "Modified Resources", rightPanelX + 90, contentY + 15, GOLD);

        context.fill(rightPanelX, contentY + 45, rightPanelX + 180, contentY + contentHeight, PANEL_BG);
        context.outline(rightPanelX, contentY + 45, 180, contentHeight - 45, BORDER_COLOR);

        for (int i = startIndex; i < Math.min(startIndex + maxVisibleItems, totalItems); i++) {
            ResourcesManager.ResourceEntry resource = filteredResources.get(i);
            int row = i - startIndex;
            int rowY = listStartY + row * lineHeight;

            int x = contentX + 5;
            int y = rowY + 5;

            int bgColor = (i % 2 == 0) ? ITEM_BG : ITEM_BG_ALT;
            context.fill(x, y, x + leftPanelWidth - 10, y + lineHeight - 10, bgColor);

            context.text(font, resource.name, x + 5, y + 7, WHITE, false);

            final String resourceKey = resource.name;
            int amountFieldWidth = 60;
            int amountFieldX = x + leftPanelWidth - 140;
            int amountFieldY = y + 2;

            EditBox amountField = resourceAmountFields.get(resourceKey);
            if (amountField == null) {
                final EditBox newField = new EditBox(font, amountFieldX, amountFieldY, amountFieldWidth, 16, Component.literal(""));
                newField.setValue(String.valueOf(resource.amount));
                newField.setMaxLength(10);

                newField.setResponder(text -> {
                    try {
                        int newAmount = text.isEmpty() ? 0 : Integer.parseInt(text);
                        if (newAmount >= 0) {
                            ResourcesManager.ResourceEntry resourceEntry = filteredResources.stream()
                                .filter(entry -> entry.name.equals(resourceKey))
                                .findFirst()
                                .orElse(null);

                            if (resourceEntry != null) {
                                resourceEntry.amount = newAmount;
                                updateSelectedResource(resourceEntry);
                            }
                        }
                    } catch (NumberFormatException e) {
                        ResourcesManager.ResourceEntry resourceEntry = filteredResources.stream()
                            .filter(entry -> entry.name.equals(resourceKey))
                            .findFirst()
                            .orElse(null);

                        if (resourceEntry != null && resourceAmountFields.containsKey(resourceKey)) {
                            EditBox fieldToUpdate = resourceAmountFields.get(resourceKey);
                            fieldToUpdate.setValue(String.valueOf(resourceEntry.amount));
                        }
                    }
                });

                resourceAmountFields.put(resourceKey, newField);
                amountField = newField;


                amountField.setBordered(true);
            } else {
                amountField.setX(amountFieldX);
                amountField.setY(amountFieldY);

                String currentText = amountField.getValue();
                int currentAmount;
                try {
                    currentAmount = currentText.isEmpty() ? 0 : Integer.parseInt(currentText);
                } catch (NumberFormatException e) {
                    currentAmount = -1;
                }

                if (currentAmount != resource.amount && !amountField.isFocused()) {
                    amountField.setValue(String.valueOf(resource.amount));
                }
            }

            int fieldBgColor = amountField.isFocused() ? 0xFF404040 : 0xFF333333;
            int fieldBorderColor = amountField.isFocused() ? WHITE : BORDER_COLOR;

            context.fill(amountFieldX - 1, amountFieldY - 1, amountFieldX + amountFieldWidth + 1, amountFieldY + 18, fieldBorderColor);
            context.fill(amountFieldX, amountFieldY, amountFieldX + amountFieldWidth, amountFieldY + 17, fieldBgColor);

            amountField.extractRenderState(context, lastMouseX, lastMouseY, 0);

            int buttonSize = 16;
            int buttonY = y + (lineHeight - 10 - buttonSize) / 2;

            int minusX = x + leftPanelWidth - 80;
            context.fill(minusX, buttonY, minusX + buttonSize, buttonY + buttonSize, 0xFF444444);
            context.outline(minusX, buttonY, buttonSize, buttonSize, BORDER_COLOR);
            context.text(font, "-", minusX + (buttonSize - font.width("-")) / 2, 
                             buttonY + (buttonSize - font.lineHeight) / 2, WHITE, false);

            int plusX = x + leftPanelWidth - 40;
            context.fill(plusX, buttonY, plusX + buttonSize, buttonY + buttonSize, 0xFF444444);
            context.outline(plusX, buttonY, buttonSize, buttonSize, BORDER_COLOR);
            context.text(font, "+", plusX + (buttonSize - font.width("+")) / 2, 
                             buttonY + (buttonSize - font.lineHeight) / 2, WHITE, false);

            final int resourceIndex = i;
            clickableElements.add(new ClickableElement(minusX, buttonY, buttonSize, buttonSize, 
                                                      () -> decrementResource(resourceIndex)));
            clickableElements.add(new ClickableElement(plusX, buttonY, buttonSize, buttonSize, 
                                                      () -> incrementResource(resourceIndex)));
        }

        int modifiedY = contentY + 55;
        int modifiedCount = 0;
        for (ResourcesManager.ResourceEntry entry : selectedResources) {
            context.text(font, entry.name, rightPanelX + 10, modifiedY, WHITE, false);
            context.text(font, String.valueOf(entry.amount), 
                            rightPanelX + 180 - 10 - font.width(String.valueOf(entry.amount)), 
                            modifiedY, GOLD, false);
            modifiedY += 20;
            modifiedCount++;

            if (modifiedCount >= 15) {
                context.text(font, "...", rightPanelX + 90, modifiedY, TEXT_SECONDARY, false);
                break;
            }
        }

        if (selectedResources.isEmpty()) {
            drawCenteredText(context, "No modifications yet", rightPanelX + 90, contentY + 70, TEXT_SECONDARY);
        }

        if (totalItems > maxVisibleItems) {
            int scrollHeight = contentHeight - (listStartY - contentY);
            int scrollThumbHeight = Math.max(32, scrollHeight * maxVisibleItems / totalItems);
            int scrollThumbY = listStartY;

            if (totalItems > maxVisibleItems) {
                scrollThumbY += (scrollOffset * (scrollHeight - scrollThumbHeight)) / (totalItems - maxVisibleItems);
            }

            context.fill(contentX + leftPanelWidth - 8, listStartY, contentX + leftPanelWidth - 4, contentY + contentHeight, 0xFF333333);
            context.fill(contentX + leftPanelWidth - 8, scrollThumbY, contentX + leftPanelWidth - 4, scrollThumbY + scrollThumbHeight, 0xFF666666);
        }
    }

    private void incrementResource(int index) {
        if (index >= 0 && index < filteredResources.size()) {
            ResourcesManager.ResourceEntry resource = filteredResources.get(index);
            resource.amount++;
            updateSelectedResource(resource);
        }
    }

    private void decrementResource(int index) {
        if (index >= 0 && index < filteredResources.size()) {
            ResourcesManager.ResourceEntry resource = filteredResources.get(index);
            if (resource.amount > 0) {
                resource.amount--;
                updateSelectedResource(resource);
            }
        }
    }

    private void updateSelectedResource(ResourcesManager.ResourceEntry resource) {
        boolean found = false;
        for (int i = 0; i < selectedResources.size(); i++) {
            if (selectedResources.get(i).name.equals(resource.name)) {
                selectedResources.set(i, resource);
                found = true;
                break;
            }
        }

        if (!found) {
            selectedResources.add(new ResourcesManager.ResourceEntry(resource.name, resource.amount));
        }

    }

}

// !!IMPORTANT!! 1.20.1 version of the file !!IMPORTANT!!

package com.gudu0.simplexray.client;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The in-game config screen: opened via keybind, lets the player manage which blocks
 * get an x-ray outline and what color each one uses.
 * <p>
 * Layout is three side-by-side panels plus a manually-drawn hotbar at the bottom:
 * <p>  - Settings panel (left):   enable/disable toggle; per-block RGB color picker + hex field.
 *                                  Click a block in the Enabled panel to load its settings here.
 * <p>  - Enabled panel (center):  the player's current tracked-block list — icon, name, color swatch, remove button.
 * <p>  - Add Blocks panel (right): a searchable, scrollable list of every block in the game, click to add.
 * <p>  - Hotbar:                   lets the player drag a block straight out of their hotbar to add it.
 * <p>
 * The Enabled and Add Blocks panels share the same scroll/clip approach: a fixed-size visible window, plus one
 * extra row that's drawn and then clipped half off, to signal "there's more below, scroll for it."
 * <p>
 * The hex field inside the Settings panel is managed manually (not via addDrawableChild) so its visibility
 * can be fully controlled — it only appears when a block is selected.
 */

public class XrayConfigScreen extends Screen {

    // ---- Layout constants — tune these to resize/reposition the whole screen ----
    private static final int ROW_HEIGHT = 20;
    private static final int PANEL_TOP = 24;
    private static final int LIST_TOP = 70;
    private static final int VISIBLE_ROWS = 8;       // how many full rows each panel shows before scrolling
    private static final int PANEL_WIDTH = 150;
    private static final int PANEL_GAP = 20;         // horizontal gap between the panels
    private static final int PEEK_HEIGHT = ROW_HEIGHT / 2; // height of the half-cut "there's more" row at the bottom
    private static final int HOTBAR_SLOT_SIZE = 20;
    private static final long TOOLTIP_DELAY_MS = 500; // how long the mouse must hover a truncated name before the tooltip shows
    private static final int PANEL_FILL_COLOR = 0xC0101010;
    private static final int PANEL_BORDER_COLOR = 0xFF555555;

    // ---- Settings panel: color picker layout (all y values are absolute screen coordinates) ----
    private static final int COLOR_SECTION_TOP = PANEL_TOP + 54;  // y=78, first content row below toggle + separator
    private static final int SLIDER_R_Y        = COLOR_SECTION_TOP + 31; // y=109
    private static final int SLIDER_G_Y        = SLIDER_R_Y + 20;        // y=129
    private static final int SLIDER_B_Y        = SLIDER_G_Y + 20;        // y=149
    private static final int HEX_FIELD_Y       = SLIDER_B_Y + 24;        // y=173
    private static final int SLIDER_TRACK_X_OFFSET = 20; // from settingsPanelLeft(), leaves room for "R:"/etc. label
    private static final int SLIDER_TRACK_WIDTH    = 100;
    // Color writes are deferred: we update the in-memory color immediately (for live preview)
    // but only flush to disk after this many ms of inactivity, so rapid drags don't hammer the disk.
    private static final long COLOR_SAVE_DELAY_MS = 500;

    // Cached once across the mod's whole runtime — the block registry doesn't change while the game is running,
    // so there's no reason to rebuild/re-sort this list every time the screen opens or the player types a letter.
    private static List<Block> allBlocksSorted;

    private final Screen parent; // screen to return to when this one closes

    // ---- Right panel state: search + add ----
    @SuppressWarnings("FieldCanBeLocal")
    private TextFieldWidget searchField;
    private List<Block> filteredBlocks = new ArrayList<>(); // current search results, already sorted/filtered
    private int scrollOffset = 0; // rows of filteredBlocks scrolled past, for the right panel

    // ---- Center panel state: enabled list ----
    private int leftScrollOffset = 0; // same idea as scrollOffset, but for the center "Enabled" panel

    // ---- Settings panel state: selected block + color picker ----
    private Block selectedBlock = null; // null = nothing selected; settings panel shows placeholder text
    private int draggingSlider = -1;       // 0=R, 1=G, 2=B; -1 when not dragging
    private boolean pendingColorSave = false;
    private long lastColorChangeMs = 0L;
    // Hex field is NOT registered via addDrawableChild — we call render/mouseClicked on it manually
    // so it only appears and accepts input when a block is selected.
    private TextFieldWidget hexField;
    private boolean suppressHexListener = false; // prevents a feedback loop when we set the field text programmatically
    private boolean hexFieldFocused = false;    // authoritative focus flag — hexField.isFocused() isn't reliable outside addDrawableChild

    // ---- Hover/tooltip state (for truncated block names) ----
    private Block hoveredBlock = null;
    private long hoverStartTime = 0L;

    // ---- Hotbar drag-and-drop state ----
    private ItemStack draggingStack = ItemStack.EMPTY; // non-empty while the player is mid-drag from the hotbar

    public XrayConfigScreen(Screen parent) {
        super(Text.literal("Xray Config"));
        this.parent = parent;
    }

    // ===================================================================================
    // SETUP
    // ===================================================================================

    @Override
    protected void init() {
        int rightLeft = rightPanelLeft();
        int settingsLeft = settingsPanelLeft();

        // The search box lives inside the right panel's header area.
        this.searchField = new TextFieldWidget(this.textRenderer, rightLeft + 8, PANEL_TOP + 22, PANEL_WIDTH - 16, 16, Text.literal("Search"));
        this.searchField.setChangedListener(text -> {
            scrollOffset = 0; // jump back to the top whenever the result set changes
            updateFilteredBlocks(text);
        });
        this.addDrawableChild(this.searchField);
        this.setInitialFocus(this.searchField); // lets the player start typing immediately, no click needed

        updateFilteredBlocks(""); // populate the right panel with the full list before anything's typed

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), btn -> this.close())
                .dimensions(this.width / 2 - 100, this.height - 28, 200, 20)
                .build());

        // Hex field is managed outside the normal widget lifecycle (see field comment above).
        this.hexField = new TextFieldWidget(this.textRenderer, settingsLeft + 22, HEX_FIELD_Y, PANEL_WIDTH - 32, 14, Text.literal("Hex"));
        this.hexField.setMaxLength(6); // exactly 6 hex digits — "#" is drawn separately as a label
        this.hexField.setTextPredicate(text -> text.matches("[0-9A-Fa-f]{0,6}"));
        this.hexField.setChangedListener(this::onHexFieldChanged);
        // Restore text after a window resize (init() is re-called by the framework on resize).
        if (selectedBlock != null) syncHexField();
    }

    // ===================================================================================
    // LAYOUT HELPERS
    // ===================================================================================

    private int settingsPanelLeft() {
        return this.width / 2 - (3 * PANEL_WIDTH + 2 * PANEL_GAP) / 2;
    }

    private int leftPanelLeft() {
        return settingsPanelLeft() + PANEL_WIDTH + PANEL_GAP;
    }

    private int rightPanelLeft() {
        return leftPanelLeft() + PANEL_WIDTH + PANEL_GAP;
    }

    // ===================================================================================
    // BLOCK LIST / SEARCH LOGIC
    // ===================================================================================

    /** Builds (once) and returns the full, alphabetically-sorted list of every registered block. */
    private List<Block> getAllBlocksSorted() {
        if (allBlocksSorted == null) {
            allBlocksSorted = Registries.BLOCK.stream()
                    .sorted(Comparator.comparing(b -> b.getName().getString()))
                    .collect(Collectors.toList());
            allBlocksSorted.remove(Blocks.AIR); // causes immense lag if trying to render outlines for air, it's the most common block in the world.
        }
        return allBlocksSorted;
    }

    /**
     * Re-filters the right panel's search results from the full block list, based on the current search text. <p>
     * Called once on screen init (empty query = show everything), and again every time the search text changes.
     */
    private void updateFilteredBlocks(String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();

        // Sorts an exact name match (e.g. typing "stone" matching the block literally named "Stone") to the very
        // top of the results, ahead of plain alphabetical order — everything else falls back to alphabetical.
        Comparator<Block> exactMatchFirst = Comparator
                .comparing((Block b) -> !b.getName().getString().toLowerCase(Locale.ROOT).equals(q))
                .thenComparing(b -> b.getName().getString());

        this.filteredBlocks = getAllBlocksSorted().stream()
                .filter(block -> q.isEmpty() || block.getName().getString().toLowerCase(Locale.ROOT).contains(q))
                .sorted(exactMatchFirst)
                .collect(Collectors.toList());
    }

    // ===================================================================================
    // COLOR PICKER HELPERS
    // ===================================================================================

    /** Pushes the selected block's current color into the hex field without triggering the changed listener. */
    private void syncHexField() {
        if (selectedBlock == null || hexField == null) return;
        int color = XrayConfig.getColor(selectedBlock);
        suppressHexListener = true;
        hexField.setText(String.format("%06X", color & 0xFFFFFF));
        suppressHexListener = false;
    }

    /**
     * Reads the dragged mouse position against the slider track and applies the resulting
     * channel value to the selected block's color (in-memory only; save is deferred).
     */
    private void applySliderValue(int mouseXInt, int trackX) {
        if (selectedBlock == null || draggingSlider < 0) return;
        int value = MathHelper.clamp((mouseXInt - trackX) * 255 / SLIDER_TRACK_WIDTH, 0, 255);
        int color = XrayConfig.getColor(selectedBlock);
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        switch (draggingSlider) {
            case 0 -> r = value;
            case 1 -> g = value;
            case 2 -> b = value;
        }
        XrayConfig.setColorNoSave(selectedBlock, 0xFF000000 | (r << 16) | (g << 8) | b);
        syncHexField();
        pendingColorSave = true;
        lastColorChangeMs = System.currentTimeMillis();
    }

    /** Called by the hex field's changed listener. Only acts when input is a full valid 6-digit hex color. */
    private void onHexFieldChanged(String text) {
        if (suppressHexListener || selectedBlock == null) return;
        if (text.length() != 6) return; // wait for a complete value; ignore partial input
        try {
            int rgb = Integer.parseUnsignedInt(text, 16);
            XrayConfig.setColorNoSave(selectedBlock, 0xFF000000 | (rgb & 0xFFFFFF));
            pendingColorSave = true;
            lastColorChangeMs = System.currentTimeMillis();
        } catch (NumberFormatException ignored) {}
    }

    // ===================================================================================
    // HIT-TESTING (shared between mouse clicks, scrolling, and render-time hover checks)
    // ===================================================================================

    private boolean isInEnabledList(double mouseX, double mouseY) {
        int left = leftPanelLeft();
        return mouseX >= left && mouseX < left + PANEL_WIDTH
                && mouseY >= LIST_TOP && mouseY < LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT + PEEK_HEIGHT;
    }

    private boolean isInSearchResults(double mouseX, double mouseY) {
        int left = rightPanelLeft();
        return mouseX >= left && mouseX < left + PANEL_WIDTH
                && mouseY >= LIST_TOP && mouseY < LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT + PEEK_HEIGHT;
    }

    /** Shrinks `full` to fit within `maxWidth` pixels, appending "..." if it had to cut anything. */
    private String truncateName(String full, int maxWidth) {
        if (this.textRenderer.getWidth(full) <= maxWidth) return full;
        String ellipsis = "...";
        int targetWidth = Math.max(maxWidth - this.textRenderer.getWidth(ellipsis), 0);
        return this.textRenderer.trimToWidth(full, targetWidth) + ellipsis;
    }

    // ===================================================================================
    // MOUSE INPUT
    // ===================================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Hex field focus management — run before any early-return handler.
        // We track focus ourselves (hexFieldFocused) rather than relying on hexField.isFocused(),
        // because the Screen routes keyboard events through its own child-list focus tracking and
        // won't know about a widget that was never added via addDrawableChild.
        if (selectedBlock != null) {
            if (hexField.mouseClicked(mouseX, mouseY, button)) {
                hexFieldFocused = true;
                return true;
            }
            hexFieldFocused = false; // any click not on the hex field unfocuses it
        }

        if (button == 0) {
            int settingsLeft = settingsPanelLeft();

            // --- Settings panel: "Enable Mod" toggle ---
            int toggleY = PANEL_TOP + 26;
            int checkboxX = settingsLeft + 8;
            if (mouseX >= checkboxX && mouseX < checkboxX + 14 + this.textRenderer.getWidth("Enable Mod")
                    && mouseY >= toggleY && mouseY < toggleY + ROW_HEIGHT) {
                XrayConfig.setModEnabled(!XrayConfig.isModEnabled());
                return true;
            }

            // --- Settings panel: RGB sliders ---
            if (selectedBlock != null) {
                int trackX = settingsLeft + SLIDER_TRACK_X_OFFSET;
                if (mouseX >= trackX && mouseX < trackX + SLIDER_TRACK_WIDTH) {
                    if (mouseY >= SLIDER_R_Y && mouseY < SLIDER_R_Y + 12) {
                        draggingSlider = 0; applySliderValue((int) mouseX, trackX); return true;
                    }
                    if (mouseY >= SLIDER_G_Y && mouseY < SLIDER_G_Y + 12) {
                        draggingSlider = 1; applySliderValue((int) mouseX, trackX); return true;
                    }
                    if (mouseY >= SLIDER_B_Y && mouseY < SLIDER_B_Y + 12) {
                        draggingSlider = 2; applySliderValue((int) mouseX, trackX); return true;
                    }
                }
            }
        }

        // --- Hotbar: start a drag if the player clicked a non-empty slot ---
        if (button == 0) {
            int hotbarY = this.height - 56;
            int hotbarLeft = this.width / 2 - (9 * HOTBAR_SLOT_SIZE) / 2;
            for (int i = 0; i < 9; i++) {
                int x = hotbarLeft + i * HOTBAR_SLOT_SIZE;
                if (mouseX >= x && mouseX < x + HOTBAR_SLOT_SIZE - 2 && mouseY >= hotbarY && mouseY < hotbarY + HOTBAR_SLOT_SIZE - 2) {
                    ItemStack stack = null;
                    if (this.client != null) {
                        if (this.client.player != null) {
                            stack = this.client.player.getInventory().getStack(i);
                        }
                    }
                    if (stack != null && !stack.isEmpty()) {
                        draggingStack = stack; // just a reference for rendering/logic — never mutates the real inventory
                        return true;
                    }
                }
            }
        }

        // --- Enabled panel: remove button and row selection ---
        if (button == 0) {
            List<Block> enabled = XrayConfig.getEnabledBlocks();
            int leftLeft = leftPanelLeft();
            for (int row = 0; row < VISIBLE_ROWS; row++) {
                int index = leftScrollOffset + row; // map the visible row back to its real index in the full list
                if (index >= enabled.size()) break;
                Block block = enabled.get(index);
                int y = LIST_TOP + row * ROW_HEIGHT;

                // X button — checked first so clicking it doesn't also trigger row selection
                int xLeft = leftLeft + PANEL_WIDTH - 24;
                if (mouseX >= xLeft && mouseX < xLeft + 16 && mouseY >= y && mouseY < y + ROW_HEIGHT) {
                    if (selectedBlock == block) selectedBlock = null; // clear selection if the selected block is removed
                    XrayConfig.removeBlock(block);
                    return true;
                }

                // Row click: toggle selection (clicking the already-selected block deselects it)
                if (mouseX >= leftLeft && mouseX < leftLeft + PANEL_WIDTH - 20 && mouseY >= y && mouseY < y + ROW_HEIGHT) {
                    selectedBlock = (selectedBlock == block) ? null : block;
                    if (selectedBlock != null) syncHexField();
                    return true;
                }
            }

            // --- Right panel: click a search result to add it ---
            if (isInSearchResults(mouseX, mouseY)) {
                int index = scrollOffset + (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
                if (index >= 0 && index < filteredBlocks.size()) {
                    XrayConfig.addBlock(filteredBlocks.get(index));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button); // let widgets (search box, Done button) handle anything we didn't
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (button == 0 && draggingSlider >= 0 && selectedBlock != null) {
            int trackX = settingsPanelLeft() + SLIDER_TRACK_X_OFFSET;
            applySliderValue((int) mouseX, trackX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // End a slider drag
        if (button == 0 && draggingSlider >= 0) {
            draggingSlider = -1;
            return true;
        }
        // Finish a hotbar drag: if released over the Enabled panel, add the dragged block (if it is one).
        if (button == 0 && !draggingStack.isEmpty()) {
            int leftLeft = leftPanelLeft();
            int leftBottom = LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT + 10;

            if (mouseX >= leftLeft && mouseX < leftLeft + PANEL_WIDTH && mouseY >= PANEL_TOP && mouseY < leftBottom) {
                if (draggingStack.getItem() instanceof BlockItem blockItem) {
                    XrayConfig.addBlock(blockItem.getBlock());
                }
                // silently ignored if it's not a BlockItem (e.g. a sword) — nothing to add
            }

            draggingStack = ItemStack.EMPTY; // drag ends here regardless of where it was dropped
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
        // Each panel scrolls independently — only the one the mouse is actually over responds.
        if (isInSearchResults(mouseX, mouseY)) {
            int maxOffset = Math.max(0, filteredBlocks.size() - VISIBLE_ROWS);
            scrollOffset = MathHelper.clamp(scrollOffset - (int) Math.signum(verticalAmount), 0, maxOffset);
            return true;
        }
        if (isInEnabledList(mouseX, mouseY)) {
            int maxOffset = Math.max(0, XrayConfig.getEnabledBlocks().size() - VISIBLE_ROWS);
            leftScrollOffset = MathHelper.clamp(leftScrollOffset - (int) Math.signum(verticalAmount), 0, maxOffset);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, verticalAmount);
    }

    // Forward keyboard events to the hex field when it has focus, before the screen routes them to
    // the search field (which is always a registered child and would otherwise steal them).
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (selectedBlock != null && hexFieldFocused) {
            // TextFieldWidget.keyPressed() gates on isFocused() internally, which can return false
            // when the widget isn't part of the screen's child hierarchy. Force it true first.
            hexField.setFocused(true);
            return hexField.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (selectedBlock != null && hexFieldFocused) {
            hexField.setFocused(true); // same reason as keyPressed above
            return hexField.charTyped(chr, modifiers);
        }
        return super.charTyped(chr, modifiers);
    }

    // ===================================================================================
    // RENDER
    // ===================================================================================

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Flush a pending color change to disk once the delay has elapsed after the last input.
        if (pendingColorSave && System.currentTimeMillis() - lastColorChangeMs >= COLOR_SAVE_DELAY_MS) {
            XrayConfig.save();
            pendingColorSave = false;
        }

        super.render(context, mouseX, mouseY, delta); // draws registered widgets: search box, Done button

        // -----------------------------------------------------------------------------
        // SETTINGS PANEL
        // -----------------------------------------------------------------------------
        //region settingspanel
        int settingsLeft = settingsPanelLeft();
        int settingsBottom = LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT + PEEK_HEIGHT + 10;

        context.fill(settingsLeft, PANEL_TOP, settingsLeft + PANEL_WIDTH, settingsBottom, PANEL_FILL_COLOR);
        context.drawBorder(settingsLeft, PANEL_TOP, PANEL_WIDTH, settingsBottom - PANEL_TOP, PANEL_BORDER_COLOR);

        // Title: the selected block's name when something is selected, otherwise a generic heading
        String panelTitle = selectedBlock != null
                ? truncateName(selectedBlock.getName().getString(), PANEL_WIDTH - 16)
                : "Block Settings";
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(panelTitle), settingsLeft + PANEL_WIDTH / 2, PANEL_TOP + 8, 0xFFFFFF);

        // "Enable Mod" toggle
        int toggleY = PANEL_TOP + 26;
        int checkboxX = settingsLeft + 8;
        int checkboxY = toggleY + 5;
        boolean modEnabled = XrayConfig.isModEnabled();
        context.fill(checkboxX, checkboxY, checkboxX + 10, checkboxY + 10, modEnabled ? 0xFF55FF55 : 0xFF333333);
        context.drawBorder(checkboxX, checkboxY, 10, 10, 0xFFFFFFFF);
        if (modEnabled) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("✓"), checkboxX + 1, checkboxY, 0xFF004400);
        }
        context.drawTextWithShadow(this.textRenderer, Text.literal("Enable Mod"), checkboxX + 14, toggleY + 6, 0xFFFFFF);
        boolean hoveredToggle = mouseX >= checkboxX
                && mouseX < checkboxX + 14 + this.textRenderer.getWidth("Enable Mod")
                && mouseY >= toggleY && mouseY < toggleY + ROW_HEIGHT;

        // Separator between the toggle and the color-picker area
        int separatorY = PANEL_TOP + 48;
        context.fill(settingsLeft + 4, separatorY, settingsLeft + PANEL_WIDTH - 4, separatorY + 1, 0xFF444444);

        if (selectedBlock == null) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Choose a block to edit"),
                    settingsLeft + PANEL_WIDTH / 2, COLOR_SECTION_TOP + 10, 0xFF888888);
        } else {
            int color = XrayConfig.getColor(selectedBlock);
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;

            // Color preview swatch — shows the live result of slider/hex changes before the save is flushed
            int previewX = settingsLeft + 8;
            context.fill(previewX, COLOR_SECTION_TOP, previewX + PANEL_WIDTH - 16, COLOR_SECTION_TOP + 14, color);
            context.drawBorder(previewX, COLOR_SECTION_TOP, PANEL_WIDTH - 16, 14, 0xFFFFFFFF);

            // RGB sliders
            drawSlider(context, settingsLeft, SLIDER_R_Y, "R", r, 0xFFFF0000);
            drawSlider(context, settingsLeft, SLIDER_G_Y, "G", g, 0xFF00FF00);
            drawSlider(context, settingsLeft, SLIDER_B_Y, "B", b, 0xFF0000FF);

            // "#" prefix drawn manually; hex field rendered manually (not a registered widget)
            context.drawTextWithShadow(this.textRenderer, Text.literal("#"), settingsLeft + 8, HEX_FIELD_Y + 2, 0xAAAAAA);
            hexField.setX(settingsLeft + 22); // re-anchor on every frame in case of resize between frames
            hexField.render(context, mouseX, mouseY, delta);
        }
        //endregion

        // -----------------------------------------------------------------------------
        // ENABLED (CENTER) PANEL
        // -----------------------------------------------------------------------------
        //region leftpanel
        List<Block> enabled = XrayConfig.getEnabledBlocks();
        // Keep the scroll offset valid if the list shrank since the last scroll (e.g. a block was just removed).
        leftScrollOffset = MathHelper.clamp(leftScrollOffset, 0, Math.max(0, enabled.size() - VISIBLE_ROWS));

        int leftLeft = leftPanelLeft();
        int leftBottom = LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT + PEEK_HEIGHT + 10;

        context.fill(leftLeft, PANEL_TOP, leftLeft + PANEL_WIDTH, leftBottom, PANEL_FILL_COLOR); // panel background
        context.drawBorder(leftLeft, PANEL_TOP, PANEL_WIDTH, leftBottom - PANEL_TOP, PANEL_BORDER_COLOR); // panel outline
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Enabled"), leftLeft + PANEL_WIDTH / 2, PANEL_TOP + 8, 0xFFFFFF);

        Block newHoveredBlock = null; // tracked this frame, compared against `hoveredBlock` after both panels are drawn

        if (enabled.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No blocks yet"), leftLeft + PANEL_WIDTH / 2, LIST_TOP, 0xAAAAAA);
        } else {
            int clipBottom = LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT + PEEK_HEIGHT;
            context.enableScissor(leftLeft, LIST_TOP, leftLeft + PANEL_WIDTH, clipBottom); // hard-clip anything below this rect

            // Draws one extra row beyond VISIBLE_ROWS on purpose — the scissor above cuts it off halfway,
            // which is what gives the "there's more below, scroll for it" visual hint.
            for (int row = 0; row <= VISIBLE_ROWS; row++) {
                int index = leftScrollOffset + row;
                if (index >= enabled.size()) break;
                Block block = enabled.get(index);
                int y = LIST_TOP + row * ROW_HEIGHT;

                context.drawItem(new ItemStack(block.asItem()), leftLeft + 8, y); // throwaway stack, purely visual

                int nameLeft = leftLeft + 28;
                int swatchLeft = leftLeft + PANEL_WIDTH - 44;
                int nameMaxWidth = swatchLeft - 4 - nameLeft; // available space before the name would overlap the swatch

                String fullName = block.getName().getString();
                String displayName = truncateName(fullName, nameMaxWidth);
                context.drawTextWithShadow(this.textRenderer, Text.literal(displayName), nameLeft, y + 4, 0xFFFFFF);

                // Only fully-visible rows (not the half-cut peek row) are eligible for the tooltip.
                boolean wasTruncated = !displayName.equals(fullName);
                if (row < VISIBLE_ROWS && wasTruncated && mouseX >= nameLeft && mouseX < swatchLeft - 4 && mouseY >= y && mouseY < y + ROW_HEIGHT) {
                    newHoveredBlock = block;
                }

                // Color swatch — read-only visual showing the block's current color.
                // Click the row body to select the block and edit its color in the Settings panel.
                context.fill(swatchLeft, y + 3, swatchLeft + 14, y + 17, XrayConfig.getColor(block));
                context.drawBorder(swatchLeft, y + 3, 14, 14, 0xFFFFFFFF);

                context.drawTextWithShadow(this.textRenderer, Text.literal("X"), leftLeft + PANEL_WIDTH - 20, y + 4, 0xFF5555); // remove button

                // Yellow highlight around the row of the currently selected block
                if (row < VISIBLE_ROWS && block == selectedBlock) {
                    context.drawBorder(leftLeft + 2, y + 1, PANEL_WIDTH - 4, ROW_HEIGHT - 2, 0xFFFFFF55);
                }
            }
            context.disableScissor();
        }
        //endregion

        // -----------------------------------------------------------------------------
        // ADD BLOCKS (RIGHT) PANEL — searchable list of every block in the game
        // -----------------------------------------------------------------------------
        //region rightpanel
        int rightLeft = rightPanelLeft();
        int rightBottom = LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT + PEEK_HEIGHT + 10;

        context.fill(rightLeft, PANEL_TOP, rightLeft + PANEL_WIDTH, rightBottom, PANEL_FILL_COLOR);
        context.drawBorder(rightLeft, PANEL_TOP, PANEL_WIDTH, rightBottom - PANEL_TOP, PANEL_BORDER_COLOR);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Add Blocks"), rightLeft + PANEL_WIDTH / 2, PANEL_TOP + 8, 0xFFFFFF);

        if (filteredBlocks.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No matches"), rightLeft + PANEL_WIDTH / 2, LIST_TOP, 0xAAAAAA);
        } else {
            int clipBottomRight = LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT + PEEK_HEIGHT;
            context.enableScissor(rightLeft, LIST_TOP, rightLeft + PANEL_WIDTH, clipBottomRight);

            for (int i = 0; i <= VISIBLE_ROWS; i++) { // same "one extra row, clipped" trick as the Enabled panel
                int index = scrollOffset + i;
                if (index >= filteredBlocks.size()) break;
                Block block = filteredBlocks.get(index);
                int y = LIST_TOP + i * ROW_HEIGHT;

                // Hover highlight — restricted to fully-visible rows, same reasoning as the Enabled panel's tooltip gate.
                if (i < VISIBLE_ROWS && mouseX >= rightLeft && mouseX < rightLeft + PANEL_WIDTH && mouseY >= y && mouseY < y + ROW_HEIGHT) {
                    context.fill(rightLeft + 2, y, rightLeft + PANEL_WIDTH - 2, y + ROW_HEIGHT, 0x40FFFFFF);
                }

                int nameLeft = rightLeft + 8;
                int nameMaxWidth = PANEL_WIDTH - 16;
                String fullName = block.getName().getString();
                String displayName = truncateName(fullName, nameMaxWidth);
                context.drawTextWithShadow(this.textRenderer, Text.literal(displayName), nameLeft, y + 6, 0xFFFFFF);

                boolean wasTruncated = !displayName.equals(fullName);
                if (i < VISIBLE_ROWS && wasTruncated && mouseX >= nameLeft && mouseX < nameLeft + nameMaxWidth && mouseY >= y && mouseY < y + ROW_HEIGHT) {
                    newHoveredBlock = block;
                }
            }
            context.disableScissor();
        }

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 6, 0xFFFFFF); // screen title
        //endregion

        // -----------------------------------------------------------------------------
        // HOTBAR — drawn manually, since the real HUD hotbar doesn't render while a Screen is open
        // -----------------------------------------------------------------------------
        //region hotbar
        int hotbarY = this.height - 56;
        int hotbarLeft = this.width / 2 - (9 * HOTBAR_SLOT_SIZE) / 2;

        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Drag from hotbar to add"), this.width / 2, hotbarY - 12, 0xAAAAAA);

        PlayerInventory inv = null;
        if (this.client != null) {
            if (this.client.player != null) {
                inv = this.client.player.getInventory();
            }
        }
        for (int i = 0; i < 9; i++) {
            int x = hotbarLeft + i * HOTBAR_SLOT_SIZE;
            context.fill(x, hotbarY, x + HOTBAR_SLOT_SIZE - 2, hotbarY + HOTBAR_SLOT_SIZE - 2, 0x80000000); // slot background
            ItemStack stack = null;
            if (inv != null) {
                stack = inv.getStack(i);
            }
            if (stack != null && !stack.isEmpty()) {
                context.drawItem(stack, x + 2, hotbarY + 2);
            }
        }

        // The dragged item follows the cursor — drawn after the hotbar so it sits on top of it.
        if (!draggingStack.isEmpty()) {
            context.drawItem(draggingStack, mouseX - 8, mouseY - 8);
        }
        //endregion

        // -----------------------------------------------------------------------------
        // TOOLTIP — shows the full name of a truncated, hovered block after a short delay
        // -----------------------------------------------------------------------------
        //region tooltip
        if (newHoveredBlock != hoveredBlock) {
            hoveredBlock = newHoveredBlock;
            hoverStartTime = System.currentTimeMillis(); // reset the timer whenever the hovered block changes (including to/from "none")
        }
        if (hoveredBlock != null && System.currentTimeMillis() - hoverStartTime >= TOOLTIP_DELAY_MS) {
            context.drawTooltip(this.textRenderer, hoveredBlock.getName(), mouseX, mouseY);
        }
        if (hoveredToggle) {
            context.drawTooltip(this.textRenderer, List.of(
                    Text.literal("Enable the mod's rendering of outlines,"),
                    Text.literal("and scanning of chunks for them")
            ), mouseX, mouseY);
        }
        //endregion
    }

    /**
     * Draws a single RGB slider row inside the Settings panel.
     * @param label     single-char channel label ("R", "G", or "B")
     * @param value     current channel value, 0–255
     * @param fillColor ARGB color used for the filled portion of the track
     */
    private void drawSlider(DrawContext context, int settingsLeft, int y, String label, int value, int fillColor) {
        int trackX = settingsLeft + SLIDER_TRACK_X_OFFSET;
        int fillWidth = value * SLIDER_TRACK_WIDTH / 255;

        context.drawTextWithShadow(this.textRenderer, Text.literal(label + ":"), settingsLeft + 6, y + 1, 0xAAAAAA);

        context.fill(trackX, y + 3, trackX + SLIDER_TRACK_WIDTH, y + 7, 0xFF333333); // track background
        if (fillWidth > 0) context.fill(trackX, y + 3, trackX + fillWidth, y + 7, fillColor); // filled portion
        // Handle: a 2px-wide white vertical bar at the value position
        int handleX = trackX + fillWidth;
        context.fill(handleX - 1, y + 1, handleX + 1, y + 9, 0xFFFFFFFF);

        context.drawTextWithShadow(this.textRenderer, Text.literal(String.valueOf(value)),
                trackX + SLIDER_TRACK_WIDTH + 4, y + 1, 0xFFFFFF);
    }

    // ===================================================================================
    // SCREEN LIFECYCLE
    // ===================================================================================

    @Override
    public boolean shouldPause() {
        return false; // plain Screen subclasses pause the world by default — this keeps mobs/particles/time running while open
    }

    @Override
    public void close() {
        // Flush any unsaved color change immediately on close rather than waiting for the delay.
        if (pendingColorSave) {
            XrayConfig.save();
            pendingColorSave = false;
        }
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}

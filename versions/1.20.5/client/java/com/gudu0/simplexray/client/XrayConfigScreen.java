// !!IMPORTANT!! 1.20.5 version of the file !!IMPORTANT!!
// !!IMPORTANT!! NOT FIXED !!IMPORTANT!!

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
 * Layout is two side-by-side panels plus a manually-drawn hotbar at the bottom:
 * <p>  - Left panel  ("Enabled"):    the player's current tracked-block list — icon, name, color swatch, remove button.
 * <p>  - Right panel ("Add Blocks"): a searchable, scrollable list of every block in the game, click to add.
 * <p>  - Hotbar:                     lets the player drag a block straight out of their hotbar to add it.
 * <p>
 * Both list panels share the same scroll/clip approach: a fixed-size visible window, plus one
 * extra row that's drawn and then clipped half off, to signal "there's more below, scroll for it."
 */
    
public class XrayConfigScreen extends Screen {

    // ---- Layout constants — tune these to resize/reposition the whole screen ----
    private static final int ROW_HEIGHT = 20;
    private static final int PANEL_TOP = 24;
    private static final int LIST_TOP = 70;
    private static final int VISIBLE_ROWS = 8;       // how many full rows each panel shows before scrolling
    private static final int PANEL_WIDTH = 150;
    private static final int PANEL_GAP = 20;         // horizontal gap between the two panels
    private static final int PEEK_HEIGHT = ROW_HEIGHT / 2; // height of the half-cut "there's more" row at the bottom
    private static final int HOTBAR_SLOT_SIZE = 20;
    private static final long TOOLTIP_DELAY_MS = 500; // how long the mouse must hover a truncated name before the tooltip shows

    // Cached once across the mod's whole runtime — the block registry doesn't change while the game is running,
    // so there's no reason to rebuild/re-sort this list every time the screen opens or the player types a letter.
    private static List<Block> allBlocksSorted;

    private final Screen parent; // screen to return to when this one closes

    // ---- Right panel state: search + add ----
    @SuppressWarnings("FieldCanBeLocal")
    private TextFieldWidget searchField;
    private List<Block> filteredBlocks = new ArrayList<>(); // current search results, already sorted/filtered
    private int scrollOffset = 0; // rows of filteredBlocks scrolled past, for the right panel

    // ---- Left panel state: enabled list ----
    private int leftScrollOffset = 0; // same idea as scrollOffset, but for the left "Enabled" panel

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
    }

    // ===================================================================================
    // LAYOUT HELPERS
    // ===================================================================================

    private int leftPanelLeft() {
        return this.width / 2 - PANEL_WIDTH - PANEL_GAP / 2;
    }

    private int rightPanelLeft() {
        return this.width / 2 + PANEL_GAP / 2;
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
     * Re-filters the right panel's search results from the full block list, based on the current search text.
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

        // --- Left / Right Panel ---
        if (button == 0) {

            // --- Left panel: per-row color swatch / remove button ---
            List<Block> enabled = XrayConfig.getEnabledBlocks();
            int leftLeft = leftPanelLeft();
            for (int row = 0; row < VISIBLE_ROWS; row++) {
                int index = leftScrollOffset + row; // map the visible row back to its real index in the full list
                if (index >= enabled.size()) break;
                Block block = enabled.get(index);
                int y = LIST_TOP + row * ROW_HEIGHT;

                int swatchLeft = leftLeft + PANEL_WIDTH - 44;
                if (mouseX >= swatchLeft && mouseX < swatchLeft + 14 && mouseY >= y + 3 && mouseY < y + 17) {
                    XrayConfig.cycleColor(block);
                    return true;
                }

                int xLeft = leftLeft + PANEL_WIDTH - 24;
                if (mouseX >= xLeft && mouseX < xLeft + 16 && mouseY >= y && mouseY < y + ROW_HEIGHT) {
                    XrayConfig.removeBlock(block);
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
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // Finish a hotbar drag: if released over the left panel, add the dragged block (if it is one).
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
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
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
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ===================================================================================
    // RENDER
    // ===================================================================================

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta); // draws registered widgets: search box, Done button

        // -----------------------------------------------------------------------------
        // LEFT PANEL — "Enabled": the player's tracked-block list
        // -----------------------------------------------------------------------------
        List<Block> enabled = XrayConfig.getEnabledBlocks();
        // Keep the scroll offset valid if the list shrank since the last scroll (e.g. a block was just removed).
        leftScrollOffset = MathHelper.clamp(leftScrollOffset, 0, Math.max(0, enabled.size() - VISIBLE_ROWS));

        int leftLeft = leftPanelLeft();
        int leftBottom = LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT + PEEK_HEIGHT + 10;

        context.fill(leftLeft, PANEL_TOP, leftLeft + PANEL_WIDTH, leftBottom, 0xC0101010); // panel background
        context.drawBorder(leftLeft, PANEL_TOP, PANEL_WIDTH, leftBottom - PANEL_TOP, 0xFF555555); // panel outline
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

                context.fill(swatchLeft, y + 3, swatchLeft + 14, y + 17, XrayConfig.getColor(block)); // color swatch
                context.drawBorder(swatchLeft, y + 3, 14, 14, 0xFFFFFFFF);

                context.drawTextWithShadow(this.textRenderer, Text.literal("X"), leftLeft + PANEL_WIDTH - 20, y + 4, 0xFF5555); // remove button
            }
            context.disableScissor();
        }

        // -----------------------------------------------------------------------------
        // RIGHT PANEL — "Add Blocks": searchable list of every block in the game
        // -----------------------------------------------------------------------------
        int rightLeft = rightPanelLeft();
        int rightBottom = LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT + PEEK_HEIGHT + 10;

        context.fill(rightLeft, PANEL_TOP, rightLeft + PANEL_WIDTH, rightBottom, 0xC0101010);
        context.drawBorder(rightLeft, PANEL_TOP, PANEL_WIDTH, rightBottom - PANEL_TOP, 0xFF555555);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Add Blocks"), rightLeft + PANEL_WIDTH / 2, PANEL_TOP + 8, 0xFFFFFF);

        if (filteredBlocks.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No matches"), rightLeft + PANEL_WIDTH / 2, LIST_TOP, 0xAAAAAA);
        } else {
            int clipBottomRight = LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT + PEEK_HEIGHT;
            context.enableScissor(rightLeft, LIST_TOP, rightLeft + PANEL_WIDTH, clipBottomRight);

            for (int i = 0; i <= VISIBLE_ROWS; i++) { // same "one extra row, clipped" trick as the left panel
                int index = scrollOffset + i;
                if (index >= filteredBlocks.size()) break;
                Block block = filteredBlocks.get(index);
                int y = LIST_TOP + i * ROW_HEIGHT;

                // Hover highlight — restricted to fully-visible rows, same reasoning as the left panel's tooltip gate.
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

        // -----------------------------------------------------------------------------
        // HOTBAR — drawn manually, since the real HUD hotbar doesn't render while a Screen is open
        // -----------------------------------------------------------------------------
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

        // -----------------------------------------------------------------------------
        // TOOLTIP — shows the full name of a truncated, hovered block after a short delay
        // -----------------------------------------------------------------------------
        if (newHoveredBlock != hoveredBlock) {
            hoveredBlock = newHoveredBlock;
            hoverStartTime = System.currentTimeMillis(); // reset the timer whenever the hovered block changes (including to/from "none")
        }
        if (hoveredBlock != null && System.currentTimeMillis() - hoverStartTime >= TOOLTIP_DELAY_MS) {
            context.drawTooltip(this.textRenderer, hoveredBlock.getName(), mouseX, mouseY);
        }
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
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }
}
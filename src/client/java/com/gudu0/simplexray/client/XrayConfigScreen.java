package com.gudu0.simplexray.client;

import net.minecraft.block.Block;
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

public class XrayConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 20;
    private static final int PANEL_TOP = 24;
    private static final int LIST_TOP = 70;
    private static final int VISIBLE_ROWS = 8;
    private static final int PANEL_WIDTH = 150;
    private static final int PANEL_GAP = 20;

    private static List<Block> allBlocksSorted; // cached — the block registry doesn't change at runtime

    private final Screen parent;
    private TextFieldWidget searchField;
    private List<Block> filteredBlocks = new ArrayList<>();
    private int scrollOffset = 0;

    private static final int HOTBAR_SLOT_SIZE = 20;

    private ItemStack draggingStack = ItemStack.EMPTY;

    public XrayConfigScreen(Screen parent) {
        super(Text.literal("Xray Config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int rightLeft = rightPanelLeft();

        this.searchField = new TextFieldWidget(this.textRenderer, rightLeft + 8, PANEL_TOP + 22, PANEL_WIDTH - 16, 16, Text.literal("Search"));
        this.searchField.setChangedListener(text -> {
            scrollOffset = 0;
            updateFilteredBlocks(text);
        });
        this.addDrawableChild(this.searchField);
        this.setInitialFocus(this.searchField);

        updateFilteredBlocks("");

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), btn -> this.close())
                .dimensions(this.width / 2 - 100, this.height - 28, 200, 20)
                .build());
    }

    private int leftPanelLeft() {
        return this.width / 2 - PANEL_WIDTH - PANEL_GAP / 2;
    }

    private int rightPanelLeft() {
        return this.width / 2 + PANEL_GAP / 2;
    }

    private List<Block> getAllBlocksSorted() {
        if (allBlocksSorted == null) {
            allBlocksSorted = Registries.BLOCK.stream()
                    .sorted(Comparator.comparing(b -> b.getName().getString()))
                    .collect(Collectors.toList());
        }
        return allBlocksSorted;
    }

    private void updateFilteredBlocks(String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();

        Comparator<Block> exactMatchFirst = Comparator
                .comparing((Block b) -> !b.getName().getString().toLowerCase(Locale.ROOT).equals(q))
                .thenComparing(b -> b.getName().getString());

        this.filteredBlocks = getAllBlocksSorted().stream()
                .filter(block -> q.isEmpty() || block.getName().getString().toLowerCase(Locale.ROOT).contains(q))
                .sorted(exactMatchFirst)
                .collect(Collectors.toList());
    }

    private boolean isInSearchResults(double mouseX, double mouseY) {
        int left = rightPanelLeft();
        return mouseX >= left && mouseX < left + PANEL_WIDTH
                && mouseY >= LIST_TOP && mouseY < LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int hotbarY = this.height - 56;
            int hotbarLeft = this.width / 2 - (9 * HOTBAR_SLOT_SIZE) / 2;
            for (int i = 0; i < 9; i++) {
                int x = hotbarLeft + i * HOTBAR_SLOT_SIZE;
                if (mouseX >= x && mouseX < x + HOTBAR_SLOT_SIZE - 2 && mouseY >= hotbarY && mouseY < hotbarY + HOTBAR_SLOT_SIZE - 2) {
                    ItemStack stack = this.client.player.getInventory().getStack(i);
                    if (!stack.isEmpty()) {
                        draggingStack = stack;
                        return true;
                    }
                }
            }
        }

        if (button == 0) {
            List<Block> enabled = XrayConfig.getEnabledBlocks();
            int leftLeft = leftPanelLeft();
            for (int i = 0; i < enabled.size(); i++) {
                Block block = enabled.get(i);
                int y = LIST_TOP + i * ROW_HEIGHT;

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

            if (isInSearchResults(mouseX, mouseY)) {
                int index = scrollOffset + (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
                if (index >= 0 && index < filteredBlocks.size()) {
                    XrayConfig.addBlock(filteredBlocks.get(index));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && !draggingStack.isEmpty()) {
            int leftLeft = leftPanelLeft();
            int leftBottom = LIST_TOP + Math.max(XrayConfig.getEnabledBlocks().size(), 1) * ROW_HEIGHT + 10;

            if (mouseX >= leftLeft && mouseX < leftLeft + PANEL_WIDTH && mouseY >= PANEL_TOP && mouseY < leftBottom) {
                if (draggingStack.getItem() instanceof BlockItem blockItem) {
                    XrayConfig.addBlock(blockItem.getBlock());
                }
                // silently ignored if it's not a BlockItem (e.g. a sword) — nothing to add
            }

            draggingStack = ItemStack.EMPTY;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
        if (isInSearchResults(mouseX, mouseY)) {
            int maxOffset = Math.max(0, filteredBlocks.size() - VISIBLE_ROWS);
            scrollOffset = MathHelper.clamp(scrollOffset - (int) Math.signum(verticalAmount), 0, maxOffset);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, verticalAmount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        // Left panel: enabled blocks
        List<Block> enabled = XrayConfig.getEnabledBlocks();
        int leftLeft = leftPanelLeft();
        int leftBottom = LIST_TOP + Math.max(enabled.size(), 1) * ROW_HEIGHT + 10;

        context.fill(leftLeft, PANEL_TOP, leftLeft + PANEL_WIDTH, leftBottom, 0xC0101010);
        context.drawBorder(leftLeft, PANEL_TOP, PANEL_WIDTH, leftBottom - PANEL_TOP, 0xFF555555);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Enabled"), leftLeft + PANEL_WIDTH / 2, PANEL_TOP + 8, 0xFFFFFF);

        if (enabled.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No blocks yet"), leftLeft + PANEL_WIDTH / 2, LIST_TOP, 0xAAAAAA);
        } else {
            for (int i = 0; i < enabled.size(); i++) {
                Block block = enabled.get(i);
                int y = LIST_TOP + i * ROW_HEIGHT;
                context.drawItem(new ItemStack(block.asItem()), leftLeft + 8, y);
                context.drawTextWithShadow(this.textRenderer, block.getName(), leftLeft + 28, y + 4, 0xFFFFFF);

                int swatchLeft = leftLeft + PANEL_WIDTH - 44;
                context.fill(swatchLeft, y + 3, swatchLeft + 14, y + 17, XrayConfig.getColor(block));
                context.drawBorder(swatchLeft, y + 3, 14, 14, 0xFFFFFFFF);

                context.drawTextWithShadow(this.textRenderer, Text.literal("X"), leftLeft + PANEL_WIDTH - 20, y + 4, 0xFF5555);
            }
        }

        // Right panel: search + add
        int rightLeft = rightPanelLeft();
        int rightBottom = LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT + 10;

        context.fill(rightLeft, PANEL_TOP, rightLeft + PANEL_WIDTH, rightBottom, 0xC0101010);
        context.drawBorder(rightLeft, PANEL_TOP, PANEL_WIDTH, rightBottom - PANEL_TOP, 0xFF555555);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Add Blocks"), rightLeft + PANEL_WIDTH / 2, PANEL_TOP + 8, 0xFFFFFF);

        if (filteredBlocks.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No matches"), rightLeft + PANEL_WIDTH / 2, LIST_TOP, 0xAAAAAA);
        } else {
            for (int i = 0; i < VISIBLE_ROWS; i++) {
                int index = scrollOffset + i;
                if (index >= filteredBlocks.size()) break;
                Block block = filteredBlocks.get(index);
                int y = LIST_TOP + i * ROW_HEIGHT;
                if (mouseX >= rightLeft && mouseX < rightLeft + PANEL_WIDTH && mouseY >= y && mouseY < y + ROW_HEIGHT) {
                    context.fill(rightLeft + 2, y, rightLeft + PANEL_WIDTH - 2, y + ROW_HEIGHT, 0x40FFFFFF);
                }
                context.drawTextWithShadow(this.textRenderer, block.getName(), rightLeft + 8, y + 6, 0xFFFFFF);
            }
        }

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 6, 0xFFFFFF);
        // Hotbar row — drawn manually since the HUD hotbar doesn't render while a screen is open
        int hotbarY = this.height - 56;
        int hotbarLeft = this.width / 2 - (9 * HOTBAR_SLOT_SIZE) / 2;

        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Drag from hotbar to add"), this.width / 2, hotbarY - 12, 0xAAAAAA);

        PlayerInventory inv = this.client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            int x = hotbarLeft + i * HOTBAR_SLOT_SIZE;
            context.fill(x, hotbarY, x + HOTBAR_SLOT_SIZE - 2, hotbarY + HOTBAR_SLOT_SIZE - 2, 0x80000000);
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                context.drawItem(stack, x + 2, hotbarY + 2);
            }
        }

        if (!draggingStack.isEmpty()) {
            context.drawItem(draggingStack, mouseX - 8, mouseY - 8);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
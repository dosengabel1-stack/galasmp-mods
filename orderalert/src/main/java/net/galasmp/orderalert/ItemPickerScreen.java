package net.galasmp.orderalert;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Zweiter Schritt: Item aus allen Items (wie im Kreativ-Inventar) waehlen, mit Suche. */
public final class ItemPickerScreen extends Screen {

    private static final int COLS = 9, ROWS = 5, CELL = 22;
    private final Screen parent;
    private final double price;
    private final List<ItemStack> all = new ArrayList<>();
    private final List<ItemStack> shown = new ArrayList<>();
    private final List<Button> cells = new ArrayList<>();
    private String query = "";
    private int page;

    public ItemPickerScreen(Screen parent, double price) {
        super(Component.literal("Item wählen"));
        this.parent = parent;
        this.price = price;
    }

    private void loadItems() {
        if (!all.isEmpty() || this.minecraft.player == null || this.minecraft.level == null) return;
        CreativeModeTabs.tryRebuildTabContents(this.minecraft.player.connection.enabledFeatures(), true,
                this.minecraft.level.registryAccess());
        for (ItemStack st : CreativeModeTabs.searchTab().getDisplayItems()) all.add(st);
    }

    private void filter() {
        shown.clear();
        String q = query.toLowerCase(Locale.ROOT).trim();
        for (ItemStack st : all) {
            if (q.isEmpty() || st.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)) shown.add(st);
        }
        int pages = Math.max(1, (shown.size() + COLS * ROWS - 1) / (COLS * ROWS));
        page = Math.max(0, Math.min(page, pages - 1));
    }

    private int left() {
        return this.width / 2 - COLS * CELL / 2;
    }

    @Override
    protected void init() {
        loadItems();
        filter();
        int top = 58;
        EditBox search = new EditBox(this.font, left(), 34, COLS * CELL, 18, Component.literal("Suche"));
        search.setHint(Component.literal("§7Item suchen, z.B. Wurftrank"));
        search.setValue(query);
        search.setResponder(t -> { query = t; page = 0; filter(); });
        this.addRenderableWidget(search);
        this.setInitialFocus(search);

        cells.clear();
        for (int i = 0; i < COLS * ROWS; i++) {
            final int slot = i;
            Button b = Button.builder(Component.empty(), btn -> pick(slot))
                    .bounds(left() + (i % COLS) * CELL, top + (i / COLS) * CELL, CELL - 2, CELL - 2).build();
            cells.add(this.addRenderableWidget(b));
        }
        int by = top + ROWS * CELL + 6;
        this.addRenderableWidget(Button.builder(Component.literal("<"), b -> { page = Math.max(0, page - 1); })
                .bounds(left(), by, 30, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(">"), b -> { page++; filter(); })
                .bounds(left() + COLS * CELL - 30, by, 30, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Zurück"), b -> this.onClose())
                .bounds(this.width / 2 - 50, by + 26, 100, 20).build());
    }

    private ItemStack at(int slot) {
        int idx = page * COLS * ROWS + slot;
        return idx < shown.size() ? shown.get(idx) : ItemStack.EMPTY;
    }

    private void pick(int slot) {
        ItemStack st = at(slot);
        if (st.isEmpty()) return;
        OrderAlertClient.addRule(st, price);
        this.minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        for (int i = 0; i < cells.size(); i++) cells.get(i).visible = !at(i).isEmpty();
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(this.font, Component.literal("§6Item wählen §7(ab §a$" + OrderAlertClient.fmt(price) + "§7 pro Stück)"),
                this.width / 2, 14, 0xFFFFFFFF);
        int top = 58;
        String hovered = null;
        for (int i = 0; i < cells.size(); i++) {
            ItemStack st = at(i);
            if (st.isEmpty()) continue;
            int x = left() + (i % COLS) * CELL + 2, y = top + (i / COLS) * CELL + 2;
            g.renderItem(st, x, y);
            if (cells.get(i).isHoveredOrFocused()) hovered = st.getHoverName().getString();
        }
        int pages = Math.max(1, (shown.size() + COLS * ROWS - 1) / (COLS * ROWS));
        g.drawCenteredString(this.font, Component.literal((page + 1) + " / " + pages), this.width / 2, top + ROWS * CELL + 12, 0xFFAAAAAA);
        if (hovered != null) {
            g.drawCenteredString(this.font, Component.literal("§e" + hovered), this.width / 2, top + ROWS * CELL + 56, 0xFFFFFFFF);
        }
    }
}

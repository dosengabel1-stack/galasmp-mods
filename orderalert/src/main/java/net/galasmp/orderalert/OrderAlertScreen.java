package net.galasmp.orderalert;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Hauptmenue (Taste J): Liste der Alarme, Preis eingeben, dann Item waehlen. */
public final class OrderAlertScreen extends Screen {

    private static final int ROWS = 5;
    private final Screen parent;
    private EditBox priceBox;
    private String priceText = "";
    private int page;
    private String error = "";

    public OrderAlertScreen(Screen parent) {
        super(Component.literal("OrderAlert"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = 40;
        var rules = OrderAlertClient.config.rules;
        int pages = Math.max(1, (rules.size() + ROWS - 1) / ROWS);
        page = Math.min(page, pages - 1);

        // Liste der Alarme mit Entfernen-Knopf
        for (int i = 0; i < ROWS; i++) {
            int idx = page * ROWS + i;
            if (idx >= rules.size()) break;
            int y = top + i * 22;
            final int remove = idx;
            this.addRenderableWidget(Button.builder(Component.literal("§cX"), b -> {
                OrderAlertClient.removeRule(remove);
                this.rebuildWidgets();
            }).bounds(cx + 130, y, 20, 20).build());
        }
        if (pages > 1) {
            this.addRenderableWidget(Button.builder(Component.literal("<"), b -> { page = Math.max(0, page - 1); this.rebuildWidgets(); })
                    .bounds(cx - 150, top + ROWS * 22, 20, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal(">"), b -> { page = Math.min(pages - 1, page + 1); this.rebuildWidgets(); })
                    .bounds(cx + 130, top + ROWS * 22, 20, 20).build());
        }

        // Neuer Alarm: Preis + Item waehlen
        int ny = top + ROWS * 22 + 24;
        priceBox = new EditBox(this.font, cx - 150, ny, 140, 20, Component.literal("Mindestpreis"));
        priceBox.setMaxLength(20);
        priceBox.setHint(Component.literal("§7Mindestpreis, z.B. 1300"));
        priceBox.setValue(priceText);
        priceBox.setResponder(t -> { priceText = t; error = ""; });
        this.addRenderableWidget(priceBox);
        this.setInitialFocus(priceBox);
        this.addRenderableWidget(Button.builder(Component.literal("Item wählen →"), b -> {
            double price = OrderAlertClient.parseNumber(priceText);
            if (price < 0) {
                error = "Bitte einen Preis eingeben, z.B. 1300 oder 1.5k";
                return;
            }
            this.minecraft.setScreen(new ItemPickerScreen(this, price));
        }).bounds(cx - 6, ny, 156, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Jetzt pr\u00fcfen"), b -> {
            this.minecraft.setScreen(null);   // Menue zu, sonst wartet die Pruefung
            OrderAlertClient.testNow(this.minecraft);
        }).bounds(cx - 150, ny + 48, 145, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Ton testen"), b -> OrderAlertClient.playAlarm(this.minecraft))
                .bounds(cx + 5, ny + 48, 145, 20).build());
        this.addRenderableWidget(Button.builder(onOffLabel(), b -> {
            OrderAlertClient.config.enabled = !OrderAlertClient.config.enabled;
            OrderAlertClient.save();
            b.setMessage(onOffLabel());
        }).bounds(cx - 150, ny + 24, 145, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> this.onClose())
                .bounds(cx + 5, ny + 24, 145, 20).build());
    }

    private Component onOffLabel() {
        return Component.literal("Alarme: " + (OrderAlertClient.config.enabled ? "§aAN" : "§cAUS"));
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int top = 40;
        g.drawCenteredString(this.font, Component.literal("§6§lOrderAlert"), cx, 12, 0xFFFFFFFF);
        g.drawCenteredString(this.font, Component.literal(error.isEmpty() ? "Meldet sich bei Orders ab deinem Preis pro Stück" : "§c" + error), cx, 24, 0xFFAAAAAA);
        var rules = OrderAlertClient.config.rules;
        if (rules.isEmpty()) {
            g.drawCenteredString(this.font, Component.literal("Noch keine Alarme. Unten Preis eingeben und Item wählen."), cx, top + 6, 0xFF888888);
        }
        for (int i = 0; i < ROWS; i++) {
            int idx = page * ROWS + i;
            if (idx >= rules.size()) break;
            var r = rules.get(idx);
            int y = top + i * 22;
            g.fill(cx - 150, y, cx + 126, y + 20, 0x66000000);
            String name = r.item.length() > 30 ? r.item.substring(0, 29) + "…" : r.item;
            g.drawString(this.font, Component.literal("§e" + name), cx - 144, y + 6, 0xFFFFFFFF);
            String p = "ab §a$" + OrderAlertClient.fmt(r.minPrice);
            g.drawString(this.font, Component.literal(p), cx + 120 - this.font.width(Component.literal(p)), y + 6, 0xFFFFFFFF);
        }
    }
}

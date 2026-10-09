package net.galasmp.oreglow;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Menue: ein Knopf pro Erz, Klick schaltet die Leucht-Textur an oder aus. */
public final class OreGlowScreen extends Screen {

    private final Screen parent;
    private final Set<String> selected = new LinkedHashSet<>();
    private final List<String> original = new ArrayList<>();

    public OreGlowScreen(Screen parent) {
        super(Component.literal("OreGlow"));
        this.parent = parent;
    }

    private PackRepository repo() {
        return this.minecraft.getResourcePackRepository();
    }

    /** Die echte Pack-ID im Spiel zu unserem Pack-Namen (Fabric haengt einen Praefix an). */
    private String packId(String name) {
        for (Pack p : repo().getAvailablePacks()) {
            String id = p.getId();
            if (id.contains(OreGlowClient.MOD_ID) && (id.endsWith("/" + name) || id.endsWith(":" + name))) return id;
        }
        return null;
    }

    @Override
    protected void init() {
        if (original.isEmpty()) {
            original.addAll(repo().getSelectedIds());
            selected.addAll(original);
        }
        int cols = 2, w = 150, h = 20, gap = 4;
        int left = this.width / 2 - w - gap / 2;
        int top = 34;
        for (int i = 0; i < OreGlowClient.ORES.length; i++) {
            String[] ore = OreGlowClient.ORES[i];
            int x = left + (i % cols) * (w + gap);
            int y = top + (i / cols) * (h + gap);
            Button b = Button.builder(label(ore), btn -> {
                String id = packId(ore[0]);
                if (id == null) return;
                if (!selected.remove(id)) selected.add(id);
                btn.setMessage(label(ore));
            }).bounds(x, y, w, h).build();
            this.addRenderableWidget(b);
        }
        int rows = (OreGlowClient.ORES.length + cols - 1) / cols;
        int by = top + rows * (h + gap) + 6;
        this.addRenderableWidget(Button.builder(Component.literal("Alle an"), btn -> setAll(true))
                .bounds(left, by, w, h).build());
        this.addRenderableWidget(Button.builder(Component.literal("Alle aus"), btn -> setAll(false))
                .bounds(left + w + gap, by, w, h).build());
        this.addRenderableWidget(Button.builder(Component.literal("Fertig"), btn -> this.onClose())
                .bounds(this.width / 2 - 100, by + h + gap + 4, 200, h).build());
    }

    private void setAll(boolean on) {
        for (String[] ore : OreGlowClient.ORES) {
            String id = packId(ore[0]);
            if (id == null) continue;
            if (on) selected.add(id);
            else selected.remove(id);
        }
        this.rebuildWidgets();
    }

    private Component label(String[] ore) {
        String id = packId(ore[0]);
        boolean on = id != null && selected.contains(id);
        return Component.literal(ore[1] + ": " + (on ? "\u00a7aAN" : "\u00a7cAUS"));
    }

    @Override
    public void onClose() {
        List<String> now = new ArrayList<>(selected);
        if (!now.equals(original)) {
            // Neue Auswahl uebernehmen, speichern und Texturen neu laden
            repo().setSelected(now);
            this.minecraft.options.updateResourcePacks(repo());
        }
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFF6FF00);
        g.drawCenteredString(this.font, Component.literal("Klick schaltet ein Erz an oder aus. Beim Schliessen wird neu geladen."),
                this.width / 2, 22, 0xFFAAAAAA);
    }
}

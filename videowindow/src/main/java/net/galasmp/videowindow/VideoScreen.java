package net.galasmp.videowindow;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Taste B: Link eingeben, Lautstaerke, Fenster zeigen/verstecken. */
public final class VideoScreen extends Screen {

    private final Screen parent;
    private EditBox linkBox;
    private String linkText;
    private String message = "";

    public VideoScreen(Screen parent) {
        super(Component.literal("Video-Fenster"));
        this.parent = parent;
        this.linkText = !VideoPlayer.link.isEmpty() ? VideoPlayer.link : VideoWindowClient.config.lastLink;
    }

    @Override
    protected void init() {
        var c = VideoWindowClient.config;
        int cx = this.width / 2;
        int y = 46;

        linkBox = new EditBox(this.font, cx - 150, y, 238, 20, Component.literal("Link"));
        linkBox.setMaxLength(2048);
        linkBox.setHint(Component.literal("§7YouTube-, Twitch- oder Kick-Link einfügen"));
        linkBox.setValue(linkText == null ? "" : linkText);
        linkBox.setResponder(t -> { linkText = t; message = ""; });
        this.addRenderableWidget(linkBox);
        this.setInitialFocus(linkBox);
        this.addRenderableWidget(Button.builder(Component.literal("Einfügen"), b -> {
            String clip = this.minecraft.keyboardHandler.getClipboard();
            if (clip != null) linkBox.setValue(clip.trim());
        }).bounds(cx + 92, y, 58, 20).build());

        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("§a▶ Abspielen"), b -> start())
                .bounds(cx - 150, y, 145, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("§c■ Stoppen"), b -> {
            VideoPlayer.stop();
            message = "Video gestoppt.";
        }).bounds(cx + 5, y, 145, 20).build());

        y += 24;
        this.addRenderableWidget(new AbstractSliderButton(cx - 150, y, 300, 20, Component.empty(), c.volume / 100.0) {
            { updateMessage(); }

            @Override
            protected void updateMessage() {
                setMessage(Component.literal("Lautstärke: " + (int) Math.round(this.value * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                VideoPlayer.setVolume((int) Math.round(this.value * 100));
            }
        });

        y += 24;
        this.addRenderableWidget(Button.builder(visibleLabel(), b -> {
            c.visible = !c.visible;
            VideoWindowClient.save();
            b.setMessage(visibleLabel());
        }).bounds(cx - 150, y, 145, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Größe zurücksetzen"), b -> {
            VideoWindow.resetSize();
            message = "Fenster wieder oben rechts in Standardgröße.";
        }).bounds(cx + 5, y, 145, 20).build());

        y += 24;
        this.addRenderableWidget(Button.builder(Component.literal("❚❚ Pause / ▶ Weiter"), b -> {
            if (VideoPlayer.backend != null) VideoPlayer.backend.togglePause();
        }).bounds(cx - 150, y, 145, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("An Video anpassen"), b -> {
            VideoWindow.fitToVideo();
            message = "Fensterhöhe passt jetzt zum Video.";
        }).bounds(cx + 5, y, 145, 20).build());

        y += 30;
        this.addRenderableWidget(Button.builder(Component.literal("Fertig"), b -> this.onClose())
                .bounds(cx - 75, y, 150, 20).build());
    }

    private void start() {
        String err = VideoPlayer.play(linkText);
        if (err != null) {
            message = "§c" + err;
            return;
        }
        this.minecraft.setScreen(null);
    }

    private static Component visibleLabel() {
        return Component.literal("Fenster: " + (VideoWindowClient.config.visible ? "§asichtbar" : "§cversteckt"));
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if ((event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) && linkBox.isFocused()) {
            start();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent instanceof net.minecraft.client.gui.screens.ChatScreen ? null : parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        g.drawCenteredString(this.font, Component.literal("§b§lVideo-Fenster"), cx, 12, 0xFFFFFFFF);
        String status = VideoPlayer.status();
        String line = !message.isEmpty() ? message : (!status.isEmpty() ? status : "YouTube, Twitch, Kick oder direkte Video-Links");
        g.drawCenteredString(this.font, Component.literal(line), cx, 28, 0xFFAAAAAA);

        int y = 198;
        g.drawCenteredString(this.font, Component.literal("§7Chat öffnen (§fT§7), dann mit der Maus:"), cx, y, 0xFFFFFFFF);
        g.drawCenteredString(this.font, Component.literal("§7Titelleiste ziehen = §fverschieben§7,  Ecken = §fgrößer/kleiner§7,"), cx, y + 11, 0xFFFFFFFF);
        g.drawCenteredString(this.font, Component.literal("§7Seiten = §fnur breiter/höher§7,  Shift + Ecke = frei"), cx, y + 22, 0xFFFFFFFF);
        g.drawCenteredString(this.font, Component.literal("§7Klick ins Video = §fPause§7,  Leiste unten = §fspulen§7,  Mausrad = §fLautstärke"), cx, y + 33, 0xFFFFFFFF);
    }
}

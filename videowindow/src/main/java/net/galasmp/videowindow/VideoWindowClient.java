package net.galasmp.videowindow;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.dimaskama.mcef.api.MCEFApi;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * VideoWindow: ein Video-Fenster (YouTube, Twitch, beliebige Webseite) mit Ton im Spiel.
 *   Taste B          -> Link eingeben, Lautstaerke, Fenster zeigen/verstecken
 *   /video <Link>    -> Video starten
 *   /video stopp | zeigen | verstecken | lautstaerke <0-100>
 * Bei offenem Chat (T) kann das Fenster mit der Maus verschoben und an Ecken/Seiten
 * in der Groesse veraendert werden.
 */
public final class VideoWindowClient implements ClientModInitializer {

    public static final String MOD_ID = "videowindow";

    public static final class Config {
        /** Position und Groesse als Anteil vom Bildschirm (0..1), -1 = Standard */
        public double fx = -1, fy = -1, fw = -1, fh = -1;
        public boolean visible = true;
        public int volume = 70;
        /** true = bei YouTube die ganze Seite laden statt nur den Player */
        public boolean youtubeFullPage = false;
        public String lastLink = "";
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static Config config = new Config();
    private static KeyMapping openKey;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("videowindow.json");
    }

    private static void load() {
        try {
            if (Files.exists(file())) {
                Config c = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), Config.class);
                if (c != null) config = c;
            }
        } catch (Exception ignored) {
            // kaputte Datei: Standard nehmen
        }
        if (config.lastLink == null) config.lastLink = "";
        config.volume = Math.max(0, Math.min(100, config.volume));
    }

    public static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // nicht schlimm
        }
    }

    public static Component msg(String text) {
        return Component.literal("§8[§bVideo§8] §r" + text);
    }

    @Override
    public void onInitializeClient() {
        load();

        // Chromium schon beim Start vorbereiten (beim allerersten Start wird es heruntergeladen)
        try {
            MCEFApi.initialize();
        } catch (Throwable t) {
            VideoPlayer.error = "Browser konnte nicht starten: " + t.getMessage();
        }

        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.videowindow.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.consumeClick()) mc.setScreen(new VideoScreen(mc.screen));
            VideoPlayer.tick();
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "window"), (g, delta) -> VideoWindow.render(g));

        // Maus im Chat: Fenster verschieben / Groesse aendern / im Video klicken
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ChatScreen)) return;
            ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !VideoWindow.mouseClicked(e));
            ScreenMouseEvents.allowMouseDrag(screen).register((s, e, dx, dy) -> !VideoWindow.mouseDragged(e));
            ScreenMouseEvents.allowMouseRelease(screen).register((s, e) -> !VideoWindow.mouseReleased(e));
            ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, sx, sy) -> !VideoWindow.mouseScrolled(mx, my, sy));
            ScreenEvents.afterRender(screen).register((s, g, mx, my, delta) -> VideoWindow.afterChatRender(g, mx, my));
            ScreenEvents.remove(screen).register(s -> VideoWindow.chatClosed());
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(VideoPlayer::stop));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("video")
                        .executes(ctx -> {
                            Minecraft mc = Minecraft.getInstance();
                            mc.execute(() -> mc.setScreen(new VideoScreen(null)));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("stopp").executes(ctx -> {
                            VideoPlayer.stop();
                            ctx.getSource().sendFeedback(msg("§fVideo gestoppt."));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("zeigen").executes(ctx -> {
                            config.visible = true; save();
                            ctx.getSource().sendFeedback(msg("§fFenster wird angezeigt."));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("verstecken").executes(ctx -> {
                            config.visible = false; save();
                            ctx.getSource().sendFeedback(msg("§fFenster versteckt (Ton läuft weiter). §7/video zeigen"));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("lautstaerke")
                                .then(ClientCommandManager.argument("prozent", IntegerArgumentType.integer(0, 100)).executes(ctx -> {
                                    VideoPlayer.setVolume(IntegerArgumentType.getInteger(ctx, "prozent"));
                                    ctx.getSource().sendFeedback(msg("§fLautstärke: §e" + config.volume + "%"));
                                    return 1;
                                })))
                        .then(ClientCommandManager.argument("link", StringArgumentType.greedyString()).executes(ctx -> {
                            String link = StringArgumentType.getString(ctx, "link");
                            String err = VideoPlayer.play(link);
                            ctx.getSource().sendFeedback(msg(err == null ? "§fLade §e" + link : "§c" + err));
                            return 1;
                        }))));
    }
}

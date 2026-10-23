package net.galasmp.videowindow;

import net.fabricmc.loader.api.FabricLoader;

import java.util.Locale;

/** Steuert, was im Fenster laeuft: Video-Player (WaterMedia) oder notfalls Browser (MCEF). */
public final class VideoPlayer {

    private VideoPlayer() {}

    public static final boolean HAS_MEDIA = FabricLoader.getInstance().isModLoaded("watermedia");
    public static final boolean HAS_BROWSER = FabricLoader.getInstance().isModLoaded("mcef-modern");

    static Backend backend;
    /** Was der Nutzer eingegeben hat */
    public static String link = "";
    private static String url = "";
    private static boolean usingBrowser;
    public static String error = "";

    public static boolean active() {
        return backend != null || !error.isEmpty();
    }

    public static String status() {
        if (!error.isEmpty()) return "§c" + error;
        return backend != null ? backend.status() : "";
    }

    /** Startet einen Link. Gibt eine Fehlermeldung zurueck oder null. */
    public static String play(String input) {
        if (input == null || input.trim().isEmpty()) return "Bitte einen Link eingeben";
        String u = normalize(input.trim());
        if (u == null) return "Das sieht nicht nach einem Link aus";
        if (!HAS_MEDIA && !HAS_BROWSER) return "Mod \"WaterMedia\" fehlt";

        stop();
        link = input.trim();
        url = u;
        VideoWindowClient.config.lastLink = link;
        VideoWindowClient.config.visible = true;
        VideoWindowClient.save();

        if (HAS_MEDIA) {
            backend = MediaBackend.create(u);
            usingBrowser = false;
        } else {
            backend = BrowserBackend.create(u);
            usingBrowser = true;
        }
        return null;
    }

    public static void stop() {
        if (backend != null) {
            try {
                backend.close();
            } catch (Throwable ignored) {
            }
            backend = null;
        }
        error = "";
        link = "";
        url = "";
        VideoWindow.reset();
    }

    public static void setVolume(int percent) {
        VideoWindowClient.config.volume = Math.max(0, Math.min(100, percent));
        VideoWindowClient.save();
        if (backend != null) backend.volume(VideoWindowClient.config.volume);
    }

    public static void tick() {
        if (backend == null) return;
        backend.tick();
        // Keine Video-Datei? Dann als normale Webseite im Browser oeffnen (falls installiert)
        if (!usingBrowser && HAS_BROWSER && backend.failed() && !isVideoPlatform(url)) {
            backend.close();
            backend = BrowserBackend.create(url);
            usingBrowser = true;
        }
    }

    private static boolean isVideoPlatform(String u) {
        String l = u.toLowerCase(Locale.ROOT);
        return l.contains("youtube.com") || l.contains("youtu.be") || l.contains("twitch.tv") || l.contains("kick.com");
    }

    private static String normalize(String s) {
        if (s.contains(" ")) return null;
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return s;
        if (!s.contains(".")) return null;
        return "https://" + s;
    }
}

package net.galasmp.videowindow;

import net.dimaskama.mcef.api.MCEFApi;
import net.dimaskama.mcef.api.MCEFBrowser;
import net.minecraft.client.Minecraft;
import org.cef.browser.CefBrowser;
import org.cef.network.CefCookie;
import org.cef.network.CefCookieManager;

import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verwaltet den eingebetteten Browser: Link umwandeln, laden, Lautstaerke, Groesse. */
public final class VideoPlayer {

    private VideoPlayer() {}

    private static final Pattern YT = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?(?:[^#]*&)?v=|shorts/|live/|embed/|v/)|youtu\\.be/|youtube-nocookie\\.com/embed/)([A-Za-z0-9_-]{11})");
    private static final Pattern YT_TIME = Pattern.compile("[?&#](?:t|start)=([0-9hms]+)");
    private static final Pattern YT_LIST = Pattern.compile("[?&]list=([A-Za-z0-9_-]+)");
    private static final Pattern TW_VIDEO = Pattern.compile("twitch\\.tv/videos/(\\d+)");
    private static final Pattern TW_CLIP = Pattern.compile("(?:clips\\.twitch\\.tv/(?:embed\\?clip=)?|twitch\\.tv/[A-Za-z0-9_]+/clip/)([A-Za-z0-9_-]+)");
    private static final Pattern TW_CHANNEL = Pattern.compile("twitch\\.tv/(?:popout/)?([A-Za-z0-9_]{2,25})(?:[/?#]|$)");
    private static final Pattern TW_PLAYER = Pattern.compile("player\\.twitch\\.tv/");
    private static final Set<String> TW_RESERVED = Set.of("directory", "videos", "settings", "search", "downloads", "p", "jobs", "turbo", "subscriptions", "inventory", "wallet", "friends", "messages");

    /** Twitch prueft, auf welcher Seite der Player eingebettet ist */
    private static final String TWITCH_PARENT = "www.twitch.tv";
    /** YouTube-Player werden ueber eine YouTube-Seite gestartet, damit ein Referer da ist (sonst Fehler 153) */
    private static final String YT_BOUNCE = "https://www.youtube.com/robots.txt";

    public static MCEFBrowser browser;
    /** Was der Nutzer eingegeben hat */
    public static String link = "";
    /** Was der Browser am Ende anzeigen soll */
    private static String target = "";
    /** != null: erst ueber die YouTube-Seite springen, dann hierhin */
    private static String bounceTarget;
    private static int bounceTries;
    private static boolean fullPageCss;
    private static boolean pendingCreate;
    private static int ticks;
    private static int readyTick = -1;
    private static boolean volumeDirty;

    /** Aktuelle Aufloesung des Browsers in echten Pixeln */
    public static int pixelW, pixelH;
    private static int wantW, wantH;
    private static long lastResize;

    public static String error = "";

    // ------------------------------------------------------------------ Status

    public static boolean active() {
        return browser != null || pendingCreate;
    }

    /** true solange noch nichts Sinnvolles zu sehen ist */
    public static boolean loading() {
        return pendingCreate || bounceTarget != null || browser == null || browser.getTextureView() == null;
    }

    public static String status() {
        if (!error.isEmpty()) return "§c" + error;
        MCEFApi.Initialization init;
        try {
            init = MCEFApi.initialize();
        } catch (Throwable t) {
            return "§cBrowser fehlt: Mod \"MCEF Modern\" installieren";
        }
        if (init.getFuture().isCompletedExceptionally()) return "§cBrowser konnte nicht starten (siehe Log)";
        if (!init.isDone()) {
            float p = init.getPercentage();
            String pct = p >= 0 ? " " + Math.round(p) + "%" : "";
            return switch (init.getStage()) {
                case DOWNLOADING -> "Browser wird heruntergeladen" + pct + " (nur beim 1. Mal)";
                case EXTRACTING -> "Browser wird entpackt" + pct;
                case INSTALL -> "Browser wird installiert" + pct;
                default -> "Browser startet ...";
            };
        }
        if (loading()) return "Lade Video ...";
        return "";
    }

    // ------------------------------------------------------------------ Steuerung

    /** Startet einen Link. Gibt eine Fehlermeldung zurueck oder null. */
    public static String play(String input) {
        if (input == null) return "Kein Link";
        input = input.trim();
        if (input.isEmpty()) return "Bitte einen Link eingeben";
        String url = convert(input);
        if (url == null) return "Das sieht nicht nach einem Link aus";
        error = "";
        link = input;
        VideoWindowClient.config.lastLink = input;
        VideoWindowClient.config.visible = true;
        VideoWindowClient.save();

        boolean ytEmbed = url.startsWith("https://www.youtube.com/embed/");
        target = url;
        bounceTarget = ytEmbed ? url : null;
        bounceTries = 0;
        fullPageCss = url.startsWith("https://www.youtube.com/watch");
        readyTick = -1;
        String first = ytEmbed ? YT_BOUNCE : url;

        if (browser != null) {
            if (fullPageCss) skipYoutubeConsent();
            try {
                browser.getCefBrowser().loadURL(first);
            } catch (Throwable t) {
                error = "Laden fehlgeschlagen: " + t.getMessage();
            }
            ticks = 0;
            return null;
        }

        pendingCreate = true;
        final String startUrl = first;
        final boolean full = fullPageCss;
        MCEFApi.getInstanceFuture().whenComplete((api, ex) -> Minecraft.getInstance().execute(() -> {
            if (!pendingCreate) return;          // inzwischen gestoppt
            pendingCreate = false;
            if (ex != null || api == null) {
                error = "Browser konnte nicht starten" + (ex != null ? ": " + ex.getMessage() : "");
                return;
            }
            try {
                if (full) skipYoutubeConsent();
                browser = api.createBrowser(startUrl, false);
                int w = Math.max(16, wantW > 0 ? wantW : 640);
                int h = Math.max(16, wantH > 0 ? wantH : 360);
                browser.resize(w, h);
                pixelW = w;
                pixelH = h;
                lastResize = System.currentTimeMillis();
                ticks = 0;
            } catch (Throwable t) {
                browser = null;
                error = "Browser-Fehler: " + t.getMessage();
            }
        }));
        return null;
    }

    public static void stop() {
        pendingCreate = false;
        bounceTarget = null;
        if (browser != null) {
            try {
                browser.close();
            } catch (Throwable ignored) {
            }
            browser = null;
        }
        link = "";
        target = "";
        pixelW = pixelH = 0;
        VideoWindow.reset();
    }

    public static void setVolume(int percent) {
        VideoWindowClient.config.volume = Math.max(0, Math.min(100, percent));
        VideoWindowClient.save();
        volumeDirty = true;
    }

    /** Wird jeden Frame vom Fenster aufgerufen: gewuenschte Aufloesung in echten Pixeln. */
    public static void wantSize(int w, int h, boolean force) {
        wantW = w;
        wantH = h;
        if (browser == null) return;
        if (w == pixelW && h == pixelH) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastResize < 120) return;
        browser.resize(w, h);
        pixelW = w;
        pixelH = h;
        lastResize = now;
    }

    // ------------------------------------------------------------------ Maus ins Video

    public static void mouseMoved(int x, int y) {
        if (browser != null) browser.onMouseMoved(x, y);
    }

    public static void mouseClicked(net.minecraft.client.input.MouseButtonEvent e) {
        if (browser == null) return;
        browser.setFocus(true);
        browser.onMouseClicked(e, false);
    }

    public static void mouseReleased(net.minecraft.client.input.MouseButtonEvent e) {
        if (browser != null) browser.onMouseReleased(e);
    }

    public static void mouseScrolled(int x, int y, double amount) {
        if (browser != null) browser.onMouseScrolled(x, y, amount);
    }

    // ------------------------------------------------------------------ jeden Tick

    public static void tick() {
        if (browser == null) return;
        ticks++;
        // Tick-Zaehler laeuft auch ohne Welt weiter, Browser bleibt bis Stopp/Trennen offen
        CefBrowser cef;
        try {
            cef = browser.getCefBrowser();
        } catch (Throwable t) {
            return;
        }

        if (bounceTarget != null && ticks % 10 == 0) {
            String u = safeUrl(cef);
            if (u != null && u.contains("/embed/")) {
                bounceTarget = null;
            } else if (u != null && u.contains("robots.txt")) {
                if (bounceTries % 6 == 0) {
                    cef.executeJavaScript("location.replace(" + js(bounceTarget) + ");", u, 0);
                }
                bounceTries++;
            } else if (ticks > 400) {
                // Umweg klappt nicht: direkt laden
                cef.loadURL(bounceTarget);
                bounceTarget = null;
            }
            if (bounceTarget == null) readyTick = ticks;
        } else if (bounceTarget == null && readyTick < 0 && ticks > 20) {
            readyTick = ticks;
        }

        if (readyTick >= 0) {
            int since = ticks - readyTick;
            // Lautstaerke ein paar Mal setzen, bis der Player bereit ist
            if (since == 40 || since == 100 || since == 200) volumeDirty = true;
            if (fullPageCss && since % 40 == 0) cef.executeJavaScript(FULLPAGE_JS, safeUrlOr(cef), 0);
        }
        if (volumeDirty && bounceTarget == null) {
            volumeDirty = false;
            cef.executeJavaScript(volumeJs(VideoWindowClient.config.volume), safeUrlOr(cef), 0);
        }
    }

    private static String safeUrl(CefBrowser cef) {
        try {
            return cef.getURL();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String safeUrlOr(CefBrowser cef) {
        String u = safeUrl(cef);
        return u == null ? "" : u;
    }

    private static String volumeJs(int v) {
        return "(function(){try{var v=" + v + ";"
                + "var p=document.getElementById('movie_player');"
                + "if(p&&p.setVolume){p.setVolume(v);if(v>0&&p.unMute)p.unMute();}"
                + "document.querySelectorAll('video,audio').forEach(function(e){e.volume=v/100;if(v>0)e.muted=false;});"
                + "}catch(e){}})();";
    }

    /** Ganze YouTube-Seite: nur den Player bildschirmfuellend zeigen */
    private static final String FULLPAGE_JS = "(function(){try{if(document.getElementById('vw-style'))return;"
            + "var s=document.createElement('style');s.id='vw-style';"
            + "s.textContent='#movie_player{position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;background:#000!important}"
            + "#movie_player video{left:0!important;top:0!important;width:100vw!important;height:100vh!important;object-fit:contain!important}"
            + "html,body{overflow:hidden!important}';"
            + "document.head.appendChild(s);window.dispatchEvent(new Event('resize'));}catch(e){}})();";

    private static String js(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** YouTube-Cookie-Abfrage ueberspringen (nur fuer die ganze Seite noetig) */
    private static void skipYoutubeConsent() {
        try {
            Date now = new Date();
            Date later = new Date(now.getTime() + 365L * 24 * 3600 * 1000);
            CefCookieManager.getGlobalManager().setCookie("https://www.youtube.com/",
                    new CefCookie("SOCS", "CAI", ".youtube.com", "/", true, false, now, now, true, later));
        } catch (Throwable ignored) {
            // dann eben per Klick bestaetigen
        }
    }

    // ------------------------------------------------------------------ Link umwandeln

    public static String convert(String input) {
        String s = input.trim();
        if (s.contains(" ")) return null;
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            if (!s.contains(".")) return null;
            s = "https://" + s;
        }

        Matcher m = YT.matcher(s);
        if (m.find()) {
            String id = m.group(1);
            int start = parseTime(s);
            Matcher l = YT_LIST.matcher(s);
            String list = l.find() ? l.group(1) : null;
            if (VideoWindowClient.config.youtubeFullPage) {
                return "https://www.youtube.com/watch?v=" + id + (start > 0 ? "&t=" + start + "s" : "") + (list != null ? "&list=" + list : "");
            }
            return "https://www.youtube.com/embed/" + id + "?autoplay=1&playsinline=1&rel=0&modestbranding=1"
                    + (start > 0 ? "&start=" + start : "") + (list != null ? "&list=" + list : "");
        }

        if (!TW_PLAYER.matcher(s).find()) {
            m = TW_VIDEO.matcher(s);
            if (m.find()) {
                return "https://player.twitch.tv/?video=v" + m.group(1) + "&parent=" + TWITCH_PARENT + "&autoplay=true&muted=false";
            }
            m = TW_CLIP.matcher(s);
            if (m.find()) {
                return "https://clips.twitch.tv/embed?clip=" + m.group(1) + "&parent=" + TWITCH_PARENT + "&autoplay=true&muted=false";
            }
            m = TW_CHANNEL.matcher(s);
            if (m.find() && !TW_RESERVED.contains(m.group(1).toLowerCase(Locale.ROOT))) {
                return "https://player.twitch.tv/?channel=" + m.group(1).toLowerCase(Locale.ROOT) + "&parent=" + TWITCH_PARENT + "&autoplay=true&muted=false";
            }
        }
        return s;
    }

    /** "90", "1m30s", "1h2m3s" -> Sekunden */
    private static int parseTime(String url) {
        Matcher m = YT_TIME.matcher(url);
        if (!m.find()) return 0;
        String t = m.group(1);
        try {
            if (t.matches("\\d+")) return Integer.parseInt(t);
            int total = 0;
            Matcher p = Pattern.compile("(\\d+)([hms])").matcher(t);
            while (p.find()) {
                int n = Integer.parseInt(p.group(1));
                total += switch (p.group(2)) {
                    case "h" -> n * 3600;
                    case "m" -> n * 60;
                    default -> n;
                };
            }
            return total;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

package net.galasmp.videowindow;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.dimaskama.mcef.api.MCEFApi;
import net.dimaskama.mcef.api.MCEFBrowser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Notloesung fuer normale Webseiten: eingebetteter Chromium-Browser (Mod "MCEF Modern", optional).
 * Fuer YouTube/Twitch wird der Video-Player benutzt.
 */
final class BrowserBackend implements Backend {

    private final String url;
    private MCEFBrowser browser;
    private boolean creating;
    private String error = "";
    private int pixelW, pixelH, wantW = 640, wantH = 360;
    private long lastResize;
    private int ticks;
    private boolean closed;

    private BrowserBackend(String url) {
        this.url = url;
    }

    static Backend create(String url) {
        return new BrowserBackend(url);
    }

    /** Chromium schon beim Spielstart vorbereiten (wird beim 1. Mal heruntergeladen) */
    static void init() {
        try {
            MCEFApi.initialize();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void tick() {
        if (closed || !error.isEmpty()) return;
        if (browser == null && !creating) {
            creating = true;
            MCEFApi.getInstanceFuture().whenComplete((api, ex) -> Minecraft.getInstance().execute(() -> {
                creating = false;
                if (closed) return;
                if (ex != null || api == null) {
                    error = "Browser konnte nicht starten";
                    return;
                }
                try {
                    browser = api.createBrowser(url, false);
                    browser.resize(wantW, wantH);
                    pixelW = wantW;
                    pixelH = wantH;
                    lastResize = System.currentTimeMillis();
                } catch (Throwable t) {
                    error = "Browser-Fehler: " + t.getMessage();
                }
            }));
        }
        if (browser != null) {
            ticks++;
            if (ticks == 60 || ticks == 140) volume(VideoWindowClient.config.volume);
        }
    }

    @Override
    public GpuTextureView texture() {
        return browser != null ? browser.getTextureView() : null;
    }

    @Override
    public String status() {
        if (!error.isEmpty()) return "§c" + error;
        MCEFApi.Initialization init = MCEFApi.initialize();
        if (init.getFuture().isCompletedExceptionally()) return "§cBrowser konnte nicht starten";
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
        return browser == null || browser.getTextureView() == null ? "Lade Seite ..." : "";
    }

    @Override
    public boolean failed() {
        return !error.isEmpty();
    }

    @Override
    public void volume(int v) {
        if (browser == null) return;
        String js = "(function(){try{var v=" + v + ";"
                + "var p=document.getElementById('movie_player');if(p&&p.setVolume){p.setVolume(v);}"
                + "document.querySelectorAll('video,audio').forEach(function(e){e.volume=v/100;});}catch(e){}})();";
        try {
            browser.getCefBrowser().executeJavaScript(js, "", 0);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void close() {
        closed = true;
        if (browser != null) {
            try {
                browser.close();
            } catch (Throwable ignored) {
            }
            browser = null;
        }
    }

    @Override
    public void resize(int w, int h, boolean force) {
        wantW = w;
        wantH = h;
        if (browser == null || (w == pixelW && h == pixelH)) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastResize < 120) return;
        browser.resize(w, h);
        pixelW = w;
        pixelH = h;
        lastResize = now;
    }

    @Override
    public int pixelW() {
        return pixelW;
    }

    @Override
    public int pixelH() {
        return pixelH;
    }

    @Override
    public boolean interactive() {
        return true;
    }

    @Override
    public void mouseMoved(int x, int y) {
        if (browser != null) browser.onMouseMoved(x, y);
    }

    @Override
    public void mouseClicked(MouseButtonEvent e) {
        if (browser == null) return;
        browser.setFocus(true);
        browser.onMouseClicked(e, false);
    }

    @Override
    public void mouseReleased(MouseButtonEvent e) {
        if (browser != null) browser.onMouseReleased(e);
    }

    @Override
    public void mouseScrolled(int x, int y, double amount) {
        if (browser != null) browser.onMouseScrolled(x, y, amount);
    }

    @Override
    public com.mojang.blaze3d.platform.cursor.CursorType cursor() {
        return browser != null ? browser.getCursorType() : null;
    }
}

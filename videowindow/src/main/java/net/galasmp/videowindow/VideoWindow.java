package net.galasmp.videowindow;

import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.BlitRenderState;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;
import org.lwjgl.glfw.GLFW;

/** Das Fenster selbst: zeichnen, verschieben, Groesse aendern, Player bedienen. Alles in GUI-Koordinaten. */
public final class VideoWindow {

    private VideoWindow() {}

    /** Hoehe der Titelleiste (nur bei offenem Chat sichtbar) */
    static final int TITLE = 14;
    /** Hoehe der Steuerleiste unten im Video (nur bei offenem Chat) */
    static final int CTRL = 13;
    private static final int MIN_W = 80, MIN_H = 45;
    private static final int EDGE = 4;
    private static final int CORNER = 10;
    private static final int BTN = 11;

    private static final CursorType RESIZE_NWSE = CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NWSE_CURSOR, "videowindow_nwse", CursorType.DEFAULT);
    private static final CursorType RESIZE_NESW = CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NESW_CURSOR, "videowindow_nesw", CursorType.DEFAULT);
    private static final CursorType MOVE = CursorType.createStandardCursor(GLFW.GLFW_RESIZE_ALL_CURSOR, "videowindow_move", CursorType.DEFAULT);

    enum Zone { NONE, VIDEO, TITLE, BTN_LINK, BTN_HIDE, BTN_CLOSE, BTN_PLAY, SEEK, CONTROLS, N, S, E, W, NE, NW, SE, SW }

    private static int x, y, w, h;
    private static int screenW, screenH;

    private static Zone drag = Zone.NONE;
    private static double startMx, startMy;
    private static int sx, sy, sw, sh;
    private static boolean videoPressed;
    /** Beim Spulen: Vorschau-Position 0..1, -1 = nicht am Spulen */
    private static double seekPreview = -1;
    private static int lastHoverX = Integer.MIN_VALUE, lastHoverY = Integer.MIN_VALUE;
    private static boolean hoverInside;
    private static long volumeShownUntil;

    // ------------------------------------------------------------------ Lage

    private static void layout() {
        Minecraft mc = Minecraft.getInstance();
        screenW = mc.getWindow().getGuiScaledWidth();
        screenH = mc.getWindow().getGuiScaledHeight();
        var c = VideoWindowClient.config;
        if (c.fw <= 0 || c.fh <= 0) {
            int dw = Math.max(MIN_W, Math.round(screenW * 0.36f));
            int dh = Math.max(MIN_H, dw * 9 / 16);
            setRect(screenW - dw - 6, TITLE + 6, dw, dh);
        }
        if (drag == Zone.NONE) {
            w = (int) Math.round(c.fw * screenW);
            h = (int) Math.round(c.fh * screenH);
            x = (int) Math.round(c.fx * screenW);
            y = (int) Math.round(c.fy * screenH);
        }
        clamp();
    }

    private static void clamp() {
        w = Math.max(MIN_W, Math.min(w, screenW));
        h = Math.max(MIN_H, Math.min(h, screenH - TITLE));
        x = Math.max(0, Math.min(x, screenW - w));
        y = Math.max(TITLE, Math.min(y, screenH - h));
    }

    private static void setRect(int nx, int ny, int nw, int nh) {
        x = nx; y = ny; w = nw; h = nh;
        clamp();
        var c = VideoWindowClient.config;
        if (screenW > 0 && screenH > 0) {
            c.fx = x / (double) screenW;
            c.fy = y / (double) screenH;
            c.fw = w / (double) screenW;
            c.fh = h / (double) screenH;
        }
    }

    public static void resetSize() {
        var c = VideoWindowClient.config;
        c.fx = c.fy = c.fw = c.fh = -1;
        VideoWindowClient.save();
    }

    /** Fenster auf das Seitenverhaeltnis des Videos bringen (Breite bleibt) */
    public static void fitToVideo() {
        Backend b = VideoPlayer.backend;
        if (b == null || b.videoWidth() <= 0 || b.videoHeight() <= 0) return;
        layout();
        int nh = (int) Math.round(w * b.videoHeight() / (double) b.videoWidth());
        setRect(x, y, w, nh);
        VideoWindowClient.save();
    }

    private static boolean shown() {
        return VideoWindowClient.config.visible && VideoPlayer.active();
    }

    private static boolean chatOpen() {
        return Minecraft.getInstance().screen instanceof ChatScreen;
    }

    private static Backend backend() {
        return VideoPlayer.backend;
    }

    // ------------------------------------------------------------------ Zeichnen

    public static void render(GuiGraphics g) {
        if (!shown()) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        layout();
        boolean chat = chatOpen();
        Backend b = backend();

        if (b != null) {
            double scale = mc.getWindow().getGuiScale();
            b.resize(Math.max(16, (int) Math.round(w * scale)), Math.max(16, (int) Math.round(h * scale)), drag == Zone.NONE);
        }

        int border = chat ? (drag != Zone.NONE ? 0xFF55AAFF : 0xFF3A3A3A) : 0xAA000000;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, border);
        g.fill(x, y, x + w, y + h, 0xFF000000);

        GpuTextureView tex = b != null ? b.texture() : null;
        if (tex != null) {
            int[] r = videoRect(b);
            g.guiRenderState.submitGuiElement(new BlitRenderState(
                    RenderPipelines.GUI_TEXTURED,
                    TextureSetup.singleTexture(tex, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)),
                    new Matrix3x2f(g.pose()),
                    r[0], r[1], r[2], r[3],
                    0.0F, 1.0F, 0.0F, 1.0F,
                    0xFFFFFFFF,
                    g.scissorStack.peek()
            ));
        }

        String status = VideoPlayer.status();
        if (!status.isEmpty()) {
            int ty = y + h / 2 - 4;
            g.fill(x, ty - 4, x + w, ty + 12, 0xCC000000);
            g.drawCenteredString(font, cut(font, status, w - 6), x + w / 2, ty, 0xFFFFFFFF);
        } else if (b != null && b.paused() && !chat) {
            g.drawString(font, "❚❚", x + 4, y + 4, 0xDDFFFFFF);
        }

        if (System.currentTimeMillis() < volumeShownUntil) {
            String v = "Lautstärke " + VideoWindowClient.config.volume + "%";
            int vw = font.width(v) + 8;
            g.fill(x + w - vw - 3, y + 3, x + w - 3, y + 15, 0xCC000000);
            g.drawString(font, v, x + w - vw + 1, y + 5, 0xFFFFFFFF);
        }

        if (chat) {
            int[] mouse = mouseGui();
            Zone hover = drag != Zone.NONE ? drag : zoneAt(mouse[0], mouse[1]);
            if (b != null && b.hasControls()) renderControls(g, font, b, hover, mouse[0]);
            renderFrame(g, font, hover);
        }
    }

    /** Bildbereich: Video mit schwarzen Balken im richtigen Seitenverhaeltnis, Browser fuellt alles */
    private static int[] videoRect(Backend b) {
        int vw = b.videoWidth(), vh = b.videoHeight();
        if (vw <= 0 || vh <= 0) return new int[]{x, y, x + w, y + h};
        double s = Math.min(w / (double) vw, h / (double) vh);
        int dw = (int) Math.round(vw * s), dh = (int) Math.round(vh * s);
        int dx = x + (w - dw) / 2, dy = y + (h - dh) / 2;
        return new int[]{dx, dy, dx + dw, dy + dh};
    }

    private static void renderFrame(GuiGraphics g, Font font, Zone hover) {
        int top = y - TITLE;
        g.fill(x - 1, top, x + w + 1, y, 0xF0202020);
        g.fill(x - 1, top, x + w + 1, top + 1, 0xFF55AAFF);

        int bx = x + w - 3 - BTN * 3 - 4;
        String title = "▶ " + (VideoPlayer.link.isEmpty() ? "Video" : VideoPlayer.link);
        g.drawString(font, cut(font, title, bx - x - 6), x + 4, top + 3, 0xFFDDDDDD);

        drawButton(g, font, bx, top + 2, "✎", hover == Zone.BTN_LINK, 0xFFFFFFFF);
        drawButton(g, font, bx + BTN + 2, top + 2, "–", hover == Zone.BTN_HIDE, 0xFFFFFFFF);
        drawButton(g, font, bx + (BTN + 2) * 2, top + 2, "×", hover == Zone.BTN_CLOSE, 0xFFFF6666);

        int c = 0xFFAAAAAA;
        g.fill(x - 1, top, x + 4, top + 1, c);
        g.fill(x + w - 4, y + h, x + w + 1, y + h + 1, c);
        g.fill(x + w, y + h - 4, x + w + 1, y + h + 1, c);
        g.fill(x - 1, y + h, x + 4, y + h + 1, c);
        g.fill(x - 1, y + h - 4, x, y + h + 1, c);
    }

    // ---- Steuerleiste (Pause, Spulen, Zeit) ----

    private static String timeText(Backend b) {
        if (b.live()) return "§c● LIVE";
        long d = b.duration();
        long t = seekPreview >= 0 && d > 0 ? (long) (seekPreview * d) : b.time();
        return fmt(t) + (d > 0 ? " / " + fmt(d) : "");
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        long hh = s / 3600, mm = (s / 60) % 60, ss = s % 60;
        return hh > 0 ? String.format("%d:%02d:%02d", hh, mm, ss) : String.format("%d:%02d", mm, ss);
    }

    /** {playX0, playX1, seekX0, seekX1, barTop} */
    private static int[] controlsLayout(Font font, Backend b) {
        int barTop = y + h - CTRL;
        int timeW = font.width(timeText(b)) + 6;
        int seekX0 = x + 16, seekX1 = x + w - timeW - 4;
        return new int[]{x + 2, x + 13, seekX0, Math.max(seekX0, seekX1), barTop};
    }

    private static void renderControls(GuiGraphics g, Font font, Backend b, Zone hover, int mouseX) {
        int[] l = controlsLayout(font, b);
        int top = l[4];
        g.fill(x, top, x + w, y + h, 0xC0000000);

        // Play/Pause
        g.fill(l[0], top + 1, l[1], top + 12, hover == Zone.BTN_PLAY ? 0xFF505050 : 0x00000000);
        g.drawCenteredString(font, b.paused() ? "▶" : "❚❚", (l[0] + l[1]) / 2 + 1, top + 3, 0xFFFFFFFF);

        // Spulleiste
        int barY = top + 5;
        if (b.canSeek() && l[3] > l[2]) {
            g.fill(l[2], barY, l[3], barY + 3, 0xFF555555);
            double p = seekPreview >= 0 ? seekPreview : (b.duration() > 0 ? b.time() / (double) b.duration() : 0);
            p = Math.max(0, Math.min(1, p));
            int px = l[2] + (int) Math.round((l[3] - l[2]) * p);
            g.fill(l[2], barY, px, barY + 3, 0xFFFF3B3B);
            if (hover == Zone.SEEK || seekPreview >= 0) g.fill(px - 1, barY - 2, px + 2, barY + 5, 0xFFFFFFFF);
        }

        String t = timeText(b);
        g.drawString(font, t, x + w - font.width(t) - 4, top + 3, 0xFFDDDDDD);
    }

    private static void drawButton(GuiGraphics g, Font font, int bx, int by, String label, boolean hover, int color) {
        g.fill(bx, by, bx + BTN, by + BTN, hover ? 0xFF505050 : 0xFF303030);
        g.drawCenteredString(font, label, bx + BTN / 2 + 1, by + 2, color);
    }

    private static String cut(Font font, String s, int max) {
        if (max <= 6) return "";
        if (font.width(s) <= max) return s;
        while (s.length() > 1 && font.width(s + "…") > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static int[] mouseGui() {
        Minecraft mc = Minecraft.getInstance();
        double mx = mc.mouseHandler.xpos() * screenW / Math.max(1, mc.getWindow().getScreenWidth());
        double my = mc.mouseHandler.ypos() * screenH / Math.max(1, mc.getWindow().getScreenHeight());
        return new int[]{(int) mx, (int) my};
    }

    // ------------------------------------------------------------------ Trefferpruefung

    static Zone zoneAt(double mx, double my) {
        if (!shown()) return Zone.NONE;
        int top = y - TITLE, left = x - 1, right = x + w + 1, bottom = y + h + 1;
        if (mx < left - EDGE || mx >= right + EDGE || my < top - EDGE || my >= bottom + EDGE) return Zone.NONE;

        boolean l = mx < left + EDGE - 1, r = mx >= right - EDGE + 1;
        boolean t = my < top + EDGE - 2, b = my >= bottom - EDGE + 1;
        boolean lc = mx < left + CORNER, rc = mx >= right - CORNER;
        boolean tc = my < top + CORNER, bc = my >= bottom - CORNER;
        if ((l || b) && lc && bc) return Zone.SW;
        if ((r || b) && rc && bc) return Zone.SE;
        if ((l || t) && lc && tc) return Zone.NW;
        if ((r || t) && rc && tc) return Zone.NE;
        if (l) return Zone.W;
        if (r) return Zone.E;
        if (t) return Zone.N;
        if (b) return Zone.S;

        if (my < y) {
            int bx = x + w - 3 - BTN * 3 - 4;
            int by = top + 2;
            if (my >= by && my < by + BTN) {
                if (mx >= bx && mx < bx + BTN) return Zone.BTN_LINK;
                if (mx >= bx + BTN + 2 && mx < bx + BTN * 2 + 2) return Zone.BTN_HIDE;
                if (mx >= bx + (BTN + 2) * 2 && mx < bx + BTN * 3 + 4) return Zone.BTN_CLOSE;
            }
            return Zone.TITLE;
        }
        if (mx >= x && mx < x + w && my >= y && my < y + h) {
            Backend be = backend();
            if (be != null && be.hasControls() && my >= y + h - CTRL) {
                int[] c = controlsLayout(Minecraft.getInstance().font, be);
                if (mx >= c[0] && mx < c[1]) return Zone.BTN_PLAY;
                if (be.canSeek() && mx >= c[2] - 2 && mx < c[3] + 2) return Zone.SEEK;
                return Zone.CONTROLS;
            }
            return Zone.VIDEO;
        }
        return Zone.TITLE;   // 1-px-Rahmen: wie Titelleiste (verschieben)
    }

    // ------------------------------------------------------------------ Maus (nur bei offenem Chat)

    /** @return true = Klick gehoert dem Fenster */
    public static boolean mouseClicked(MouseButtonEvent e) {
        if (!shown()) return false;
        layout();
        Zone z = zoneAt(e.x(), e.y());
        if (z == Zone.NONE) return false;
        Minecraft mc = Minecraft.getInstance();
        Backend b = backend();
        switch (z) {
            case VIDEO -> {
                if (b == null) return true;
                if (b.interactive()) {
                    videoPressed = true;
                    b.mouseClicked(toBrowser(b, e));
                } else if (e.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    b.togglePause();
                }
            }
            case BTN_PLAY -> { if (b != null) b.togglePause(); }
            case SEEK -> {
                if (b != null && b.canSeek()) seekPreview = seekFraction(b, e.x());
            }
            case CONTROLS -> { }
            case BTN_LINK -> mc.execute(() -> mc.setScreen(new VideoScreen(mc.screen)));
            case BTN_HIDE -> {
                VideoWindowClient.config.visible = false;
                VideoWindowClient.save();
                if (mc.player != null) mc.player.displayClientMessage(VideoWindowClient.msg("§fFenster versteckt, Ton läuft weiter. §7Taste B oder /video zeigen"), false);
            }
            case BTN_CLOSE -> VideoPlayer.stop();
            default -> {
                if (e.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
                drag = z;
                startMx = e.x();
                startMy = e.y();
                sx = x; sy = y; sw = w; sh = h;
            }
        }
        return true;
    }

    private static double seekFraction(Backend b, double mx) {
        int[] c = controlsLayout(Minecraft.getInstance().font, b);
        if (c[3] <= c[2]) return 0;
        return Math.max(0, Math.min(1, (mx - c[2]) / (double) (c[3] - c[2])));
    }

    public static boolean mouseDragged(MouseButtonEvent e) {
        Backend b = backend();
        if (seekPreview >= 0) {
            if (b != null) seekPreview = seekFraction(b, e.x());
            return true;
        }
        if (videoPressed) {
            if (b != null) {
                MouseButtonEvent be = toBrowser(b, e);
                b.mouseMoved((int) be.x(), (int) be.y());
            }
            return true;
        }
        if (drag == Zone.NONE) return false;
        double dx = e.x() - startMx, dy = e.y() - startMy;
        if (drag == Zone.TITLE) {
            setRect((int) Math.round(sx + dx), (int) Math.round(sy + dy), sw, sh);
            return true;
        }

        boolean west = drag == Zone.W || drag == Zone.NW || drag == Zone.SW;
        boolean east = drag == Zone.E || drag == Zone.NE || drag == Zone.SE;
        boolean north = drag == Zone.N || drag == Zone.NW || drag == Zone.NE;
        boolean south = drag == Zone.S || drag == Zone.SW || drag == Zone.SE;
        boolean corner = (west || east) && (north || south);

        double nw = sw, nh = sh;
        if (east) nw = sw + dx;
        if (west) nw = sw - dx;
        if (south) nh = sh + dy;
        if (north) nh = sh - dy;

        double maxW = west ? sx + sw : screenW - sx;
        double maxH = north ? sy + sh - TITLE : screenH - sy;

        if (corner && (e.modifiers() & GLFW.GLFW_MOD_SHIFT) == 0) {
            // Ecke: Seitenverhaeltnis bleibt gleich
            double s = (nw / sw + nh / sh) / 2.0;
            s = Math.max(s, Math.max(MIN_W / (double) sw, MIN_H / (double) sh));
            s = Math.min(s, Math.min(maxW / sw, maxH / sh));
            nw = sw * s;
            nh = sh * s;
        } else {
            nw = Math.max(MIN_W, Math.min(nw, maxW));
            nh = Math.max(MIN_H, Math.min(nh, maxH));
        }
        int iw = (int) Math.round(nw), ih = (int) Math.round(nh);
        int nx = west ? sx + sw - iw : sx;
        int ny = north ? sy + sh - ih : sy;
        setRect(nx, ny, iw, ih);
        return true;
    }

    public static boolean mouseReleased(MouseButtonEvent e) {
        Backend b = backend();
        if (seekPreview >= 0) {
            if (b != null) b.seek((long) (seekFraction(b, e.x()) * b.duration()));
            seekPreview = -1;
            return true;
        }
        if (videoPressed) {
            videoPressed = false;
            if (b != null) b.mouseReleased(toBrowser(b, e));
            return true;
        }
        if (drag != Zone.NONE) {
            drag = Zone.NONE;
            VideoWindowClient.save();
            return true;
        }
        return false;
    }

    public static boolean mouseScrolled(double mx, double my, double amount) {
        if (!shown()) return false;
        Zone z = zoneAt(mx, my);
        Backend b = backend();
        if ((z == Zone.VIDEO || z == Zone.CONTROLS || z == Zone.SEEK || z == Zone.BTN_PLAY) && b != null) {
            if (b.interactive() && z == Zone.VIDEO) {
                int[] p = toBrowser(b, mx, my);
                b.mouseScrolled(p[0], p[1], amount);
            } else {
                // Mausrad = Lautstaerke
                VideoPlayer.setVolume(VideoWindowClient.config.volume + (amount > 0 ? 5 : -5));
                volumeShownUntil = System.currentTimeMillis() + 1200;
            }
            return true;
        }
        return z != Zone.NONE;
    }

    /** Nach dem Zeichnen des Chats: Mauszeiger setzen und Hover ans Video geben */
    public static void afterChatRender(GuiGraphics g, int mx, int my) {
        if (!shown()) return;
        Backend b = backend();
        Zone z = drag != Zone.NONE ? drag : zoneAt(mx, my);
        CursorType cursor = switch (z) {
            case N, S -> CursorTypes.RESIZE_NS;
            case E, W -> CursorTypes.RESIZE_EW;
            case NW, SE -> RESIZE_NWSE;
            case NE, SW -> RESIZE_NESW;
            case TITLE -> MOVE;
            case BTN_LINK, BTN_HIDE, BTN_CLOSE, BTN_PLAY, SEEK -> CursorTypes.POINTING_HAND;
            case VIDEO -> b != null && b.cursor() != null ? b.cursor() : (b != null && !b.interactive() ? CursorTypes.POINTING_HAND : CursorTypes.ARROW);
            default -> null;
        };
        if (cursor != null) g.requestCursor(cursor);

        if (b == null || !b.interactive() || videoPressed) return;
        boolean inside = z == Zone.VIDEO;
        if (inside && (mx != lastHoverX || my != lastHoverY)) {
            int[] p = toBrowser(b, mx, my);
            b.mouseMoved(p[0], p[1]);
        } else if (!inside && hoverInside) {
            b.mouseMoved(-10, -10);
        }
        hoverInside = inside;
        lastHoverX = mx;
        lastHoverY = my;
    }

    public static void chatClosed() {
        Backend b = backend();
        if (b != null) {
            if (videoPressed) b.mouseReleased(new MouseButtonEvent(-10, -10, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)));
            if (hoverInside) b.mouseMoved(-10, -10);
        }
        if (drag != Zone.NONE) VideoWindowClient.save();
        reset();
    }

    public static void reset() {
        drag = Zone.NONE;
        videoPressed = false;
        seekPreview = -1;
        hoverInside = false;
        lastHoverX = lastHoverY = Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------ GUI -> Browser-Pixel

    private static int[] toBrowser(Backend b, double mx, double my) {
        int pw = b.pixelW() > 0 ? b.pixelW() : w;
        int ph = b.pixelH() > 0 ? b.pixelH() : h;
        int bx = (int) Math.floor((mx - x) * pw / Math.max(1, w));
        int by = (int) Math.floor((my - y) * ph / Math.max(1, h));
        return new int[]{bx, by};
    }

    private static MouseButtonEvent toBrowser(Backend b, MouseButtonEvent e) {
        int[] p = toBrowser(b, e.x(), e.y());
        return new MouseButtonEvent(p[0], p[1], e.buttonInfo());
    }
}

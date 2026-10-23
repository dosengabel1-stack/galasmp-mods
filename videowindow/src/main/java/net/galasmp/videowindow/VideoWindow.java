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

/** Das Fenster selbst: zeichnen, verschieben, Groesse aendern. Alle Werte in GUI-Koordinaten. */
public final class VideoWindow {

    private VideoWindow() {}

    /** Hoehe der Titelleiste (nur bei offenem Chat sichtbar) */
    static final int TITLE = 14;
    private static final int MIN_W = 64, MIN_H = 36;
    /** Breite der Greifzone an den Raendern */
    private static final int EDGE = 4;
    /** Laenge der Eck-Greifzone */
    private static final int CORNER = 10;
    private static final int BTN = 11;

    private static final CursorType RESIZE_NWSE = CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NWSE_CURSOR, "videowindow_nwse", CursorType.DEFAULT);
    private static final CursorType RESIZE_NESW = CursorType.createStandardCursor(GLFW.GLFW_RESIZE_NESW_CURSOR, "videowindow_nesw", CursorType.DEFAULT);
    private static final CursorType MOVE = CursorType.createStandardCursor(GLFW.GLFW_RESIZE_ALL_CURSOR, "videowindow_move", CursorType.DEFAULT);

    enum Zone { NONE, VIDEO, TITLE, BTN_LINK, BTN_HIDE, BTN_CLOSE, N, S, E, W, NE, NW, SE, SW }

    // aktuelle Fensterlage (Videobereich, ohne Titelleiste)
    private static int x, y, w, h;
    private static int screenW, screenH;

    // Ziehen
    private static Zone drag = Zone.NONE;
    private static double startMx, startMy;
    private static int sx, sy, sw, sh;
    private static boolean videoPressed;
    private static int lastHoverX = Integer.MIN_VALUE, lastHoverY = Integer.MIN_VALUE;
    private static boolean hoverInside;

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

    private static boolean shown() {
        return VideoWindowClient.config.visible && VideoPlayer.active();
    }

    private static boolean chatOpen() {
        return Minecraft.getInstance().screen instanceof ChatScreen;
    }

    // ------------------------------------------------------------------ Zeichnen

    public static void render(GuiGraphics g) {
        if (!shown()) return;
        Minecraft mc = Minecraft.getInstance();
        layout();
        boolean chat = chatOpen();

        // Browser-Aufloesung an echte Pixel anpassen (scharfes Bild)
        double scale = mc.getWindow().getGuiScale();
        VideoPlayer.wantSize(Math.max(16, (int) Math.round(w * scale)), Math.max(16, (int) Math.round(h * scale)), drag == Zone.NONE);

        int border = chat ? (drag != Zone.NONE ? 0xFF55AAFF : 0xFF3A3A3A) : 0xAA000000;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, border);
        g.fill(x, y, x + w, y + h, 0xFF000000);

        GpuTextureView tex = VideoPlayer.browser != null ? VideoPlayer.browser.getTextureView() : null;
        boolean bounce = tex != null && isBouncePage();
        if (tex != null && !bounce) {
            g.guiRenderState.submitGuiElement(new BlitRenderState(
                    RenderPipelines.GUI_TEXTURED,
                    TextureSetup.singleTexture(tex, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)),
                    new Matrix3x2f(g.pose()),
                    x, y, x + w, y + h,
                    0.0F, 1.0F, 0.0F, 1.0F,
                    0xFFFFFFFF,
                    g.scissorStack.peek()
            ));
        }

        String status = VideoPlayer.status();
        if (!status.isEmpty() && (tex == null || bounce || !VideoPlayer.error.isEmpty())) {
            Font font = mc.font;
            int ty = y + h / 2 - 4;
            g.fill(x, ty - 4, x + w, ty + 12, 0xCC000000);
            g.drawCenteredString(font, cut(font, status, w - 6), x + w / 2, ty, 0xFFFFFFFF);
        }

        if (chat) renderFrame(g, mc);
    }

    private static boolean isBouncePage() {
        try {
            String u = VideoPlayer.browser.getCefBrowser().getURL();
            return u != null && u.contains("robots.txt");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void renderFrame(GuiGraphics g, Minecraft mc) {
        Font font = mc.font;
        int top = y - TITLE;
        g.fill(x - 1, top, x + w + 1, y, 0xF0202020);
        g.fill(x - 1, top, x + w + 1, top + 1, 0xFF55AAFF);

        int bx = x + w - 3 - BTN * 3 - 4;
        String title = "▶ " + (VideoPlayer.link.isEmpty() ? "Video" : VideoPlayer.link);
        g.drawString(font, cut(font, title, bx - x - 6), x + 4, top + 3, 0xFFDDDDDD);

        int[] mouse = mouseGui();
        Zone hover = zoneAt(mouse[0], mouse[1]);
        drawButton(g, font, bx, top + 2, "✎", hover == Zone.BTN_LINK, 0xFFFFFFFF);
        drawButton(g, font, bx + BTN + 2, top + 2, "–", hover == Zone.BTN_HIDE, 0xFFFFFFFF);
        drawButton(g, font, bx + (BTN + 2) * 2, top + 2, "×", hover == Zone.BTN_CLOSE, 0xFFFF6666);

        // Ecken markieren
        int c = 0xFFAAAAAA;
        g.fill(x - 1, top, x + 4, top + 1, c);
        g.fill(x + w - 4, y + h, x + w + 1, y + h + 1, c);
        g.fill(x + w, y + h - 4, x + w + 1, y + h + 1, c);
        g.fill(x - 1, y + h, x + 4, y + h + 1, c);
        g.fill(x - 1, y + h - 4, x, y + h + 1, c);
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
        if (mx >= x && mx < x + w && my >= y && my < y + h) return Zone.VIDEO;
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
        switch (z) {
            case VIDEO -> {
                videoPressed = true;
                VideoPlayer.mouseClicked(toBrowser(e));
            }
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

    public static boolean mouseDragged(MouseButtonEvent e) {
        if (videoPressed) {
            MouseButtonEvent b = toBrowser(e);
            VideoPlayer.mouseMoved((int) b.x(), (int) b.y());
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

        // Platz bis zum Bildschirmrand (Gegenseite bleibt stehen)
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
        if (videoPressed) {
            videoPressed = false;
            VideoPlayer.mouseReleased(toBrowser(e));
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
        if (z == Zone.VIDEO) {
            int[] p = toBrowser(mx, my);
            VideoPlayer.mouseScrolled(p[0], p[1], amount);
            return true;
        }
        return z != Zone.NONE;
    }

    /** Nach dem Zeichnen des Chats: Mauszeiger setzen und Hover ans Video geben */
    public static void afterChatRender(GuiGraphics g, int mx, int my) {
        if (!shown()) return;
        Zone z = drag != Zone.NONE ? drag : zoneAt(mx, my);
        CursorType cursor = switch (z) {
            case N, S -> CursorTypes.RESIZE_NS;
            case E, W -> CursorTypes.RESIZE_EW;
            case NW, SE -> RESIZE_NWSE;
            case NE, SW -> RESIZE_NESW;
            case TITLE -> MOVE;
            case BTN_LINK, BTN_HIDE, BTN_CLOSE -> CursorTypes.POINTING_HAND;
            case VIDEO -> VideoPlayer.browser != null ? VideoPlayer.browser.getCursorType() : CursorTypes.ARROW;
            default -> null;
        };
        if (cursor != null) g.requestCursor(cursor);

        if (videoPressed) return;   // laeuft ueber mouseDragged
        boolean inside = z == Zone.VIDEO;
        if (inside && (mx != lastHoverX || my != lastHoverY)) {
            int[] p = toBrowser(mx, my);
            VideoPlayer.mouseMoved(p[0], p[1]);
        } else if (!inside && hoverInside) {
            VideoPlayer.mouseMoved(-10, -10);
        }
        hoverInside = inside;
        lastHoverX = mx;
        lastHoverY = my;
    }

    public static void chatClosed() {
        if (videoPressed) VideoPlayer.mouseReleased(new MouseButtonEvent(-10, -10, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)));
        if (hoverInside) VideoPlayer.mouseMoved(-10, -10);
        if (drag != Zone.NONE) VideoWindowClient.save();
        reset();
    }

    public static void reset() {
        drag = Zone.NONE;
        videoPressed = false;
        hoverInside = false;
        lastHoverX = lastHoverY = Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------ GUI -> Browser-Pixel

    private static int[] toBrowser(double mx, double my) {
        int pw = VideoPlayer.pixelW > 0 ? VideoPlayer.pixelW : w;
        int ph = VideoPlayer.pixelH > 0 ? VideoPlayer.pixelH : h;
        int bx = (int) Math.floor((mx - x) * pw / Math.max(1, w));
        int by = (int) Math.floor((my - y) * ph / Math.max(1, h));
        return new int[]{bx, by};
    }

    private static MouseButtonEvent toBrowser(MouseButtonEvent e) {
        int[] p = toBrowser(e.x(), e.y());
        return new MouseButtonEvent(p[0], p[1], e.buttonInfo());
    }
}

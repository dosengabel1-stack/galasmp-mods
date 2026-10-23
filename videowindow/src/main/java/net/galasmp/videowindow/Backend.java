package net.galasmp.videowindow;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.input.MouseButtonEvent;

/** Etwas, das ein Bild (und Ton) fuer das Fenster liefert: Video-Player oder Browser. */
interface Backend {

    /** Aktuelles Bild, oder null wenn noch nichts da ist. */
    GpuTextureView texture();

    /** Seitenverhaeltnis des Videos (0 = Fenster ganz ausfuellen) */
    default int videoWidth() { return 0; }
    default int videoHeight() { return 0; }

    /** Statuszeile ("" = alles gut, Bild anzeigen) */
    String status();

    /** Endgueltig fehlgeschlagen */
    boolean failed();

    void tick();

    void volume(int percent);

    void close();

    // ---- nur Browser ----
    default void resize(int pixelW, int pixelH, boolean force) {}
    default boolean interactive() { return false; }
    default void mouseMoved(int x, int y) {}
    default void mouseClicked(MouseButtonEvent e) {}
    default void mouseReleased(MouseButtonEvent e) {}
    default void mouseScrolled(int x, int y, double amount) {}
    default int pixelW() { return 0; }
    default int pixelH() { return 0; }
    default com.mojang.blaze3d.platform.cursor.CursorType cursor() { return null; }

    // ---- nur Video-Player ----
    default boolean hasControls() { return false; }
    default boolean paused() { return false; }
    default void togglePause() {}
    default long time() { return 0; }
    default long duration() { return 0; }
    default boolean canSeek() { return false; }
    default void seek(long ms) {}
    default boolean live() { return false; }
}

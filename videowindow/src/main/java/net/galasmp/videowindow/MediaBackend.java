package net.galasmp.videowindow;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.media.players.MediaPlayer;
import org.watermedia.api.platform.MatureContentException;

/**
 * Video-Player ueber WaterMedia (FFmpeg): YouTube (ueber yt-dlp), Twitch, Kick,
 * direkte Video-Links usw. FFmpeg kann H.264, deshalb klappt hier auch Twitch.
 */
final class MediaBackend implements Backend {

    private final MRL mrl;
    private MediaPlayer player;
    private String error = "";
    private int waitTicks;

    private ExternalTexture texture;
    private ExternalTexture.View view;
    private long texId;
    private int texW, texH;

    private MediaBackend(String url) {
        this.mrl = MediaAPI.mrl(url);
    }

    static Backend create(String url) {
        return new MediaBackend(url);
    }

    @Override
    public void tick() {
        if (player != null || !error.isEmpty()) return;
        MRL.Status s = mrl.status();
        if (s == MRL.Status.FETCHING) return;
        if (!s.loaded()) {
            error = describe(mrl.exception(), s);
            return;
        }
        if (!MediaAPI.ffmpegLoaded()) {
            if (MediaAPI.ffmpegError()) error = "FFmpeg fehlt - Mod \"WaterMedia Binaries\" installieren";
            else if (++waitTicks > 20 * 60) error = "Video-Engine startet nicht (siehe Log)";
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Thread renderThread = Thread.currentThread();
        try {
            player = MediaAPI.createPlayer(mrl, () -> MediaAPI.glEngine(renderThread, mc), MediaAPI::alEngine);
        } catch (Exception e) {
            error = "Player-Fehler: " + e.getMessage();
            return;
        }
        if (player == null) {
            if (mrl.status() != MRL.Status.FETCHING) error = "Video konnte nicht geöffnet werden";
            return;
        }
        player.volume(VideoWindowClient.config.volume);
        player.start();
    }

    private static String describe(Throwable t, MRL.Status s) {
        if (t instanceof MatureContentException) {
            return "18+-Inhalt gesperrt (config/watermedia: allowMatureContent)";
        }
        if (t != null && t.getMessage() != null) {
            String m = t.getMessage();
            if (m.toLowerCase().contains("offline")) return "Stream ist gerade offline";
            return m;
        }
        return s == MRL.Status.BLOCKED ? "Video ist gesperrt" : "Link konnte nicht geladen werden";
    }

    @Override
    public GpuTextureView texture() {
        if (player == null) return null;
        long id = player.texture();
        int w = player.width(), h = player.height();
        if (id <= 0 || w <= 0 || h <= 0) return null;
        if (view == null || id != texId || w != texW || h != texH) {
            if (view != null) view.close();
            texture = new ExternalTexture((int) id, w, h);
            view = new ExternalTexture.View(texture);
            texId = id;
            texW = w;
            texH = h;
        }
        return view;
    }

    @Override
    public int videoWidth() {
        return player != null ? Math.max(0, player.width()) : 0;
    }

    @Override
    public int videoHeight() {
        return player != null ? Math.max(0, player.height()) : 0;
    }

    @Override
    public String status() {
        if (!error.isEmpty()) return "§c" + error;
        if (mrl.status() == MRL.Status.FETCHING) return "Video wird gesucht ...";
        if (player == null) return "Video-Engine startet ...";
        return switch (player.status()) {
            case WAITING, LOADING -> "Lade Video ...";
            case BUFFERING -> texture() == null ? "Lade Video ..." : "Puffert ...";
            case ERROR -> "§cFehler beim Abspielen";
            case ENDED -> "Video zu Ende";
            case STOPPED -> "Gestoppt";
            default -> "";
        };
    }

    @Override
    public boolean failed() {
        return !error.isEmpty() || (player != null && player.error());
    }

    @Override
    public void volume(int percent) {
        if (player != null) player.volume(percent);
    }

    @Override
    public void close() {
        if (view != null) {
            view.close();
            view = null;
        }
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    // ---- Steuerung ----

    @Override
    public boolean hasControls() {
        return player != null;
    }

    @Override
    public boolean paused() {
        return player != null && player.paused();
    }

    @Override
    public void togglePause() {
        if (player == null) return;
        if (player.ended() || player.stopped()) player.start();
        else player.togglePlay();
    }

    @Override
    public long time() {
        return player != null ? Math.max(0, player.time()) : 0;
    }

    @Override
    public long duration() {
        return player != null ? Math.max(0, player.duration()) : 0;
    }

    @Override
    public boolean canSeek() {
        return player != null && player.canSeek() && player.duration() > 0;
    }

    @Override
    public void seek(long ms) {
        if (canSeek()) player.seek(Math.max(0, Math.min(ms, player.duration())));
    }

    @Override
    public boolean live() {
        return player != null && player.liveSource();
    }
}

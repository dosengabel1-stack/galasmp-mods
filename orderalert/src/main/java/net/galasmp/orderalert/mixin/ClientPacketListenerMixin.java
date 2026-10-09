package net.galasmp.orderalert.mixin;

import net.galasmp.orderalert.OrderAlertClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hintergrund-Pruefung: das Menue, das auf unser eigenes /orders antwortet, wird nicht
 * angezeigt. Wir lesen seinen Inhalt und schliessen es sofort wieder.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleOpenScreen", at = @At("HEAD"), cancellable = true)
    private void orderalert$openScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return;   // Vanilla gibt das Paket an den Haupt-Thread weiter
        if (System.currentTimeMillis() > OrderAlertClient.ownRequestUntil || mc.screen != null) return;
        OrderAlertClient.ownRequestUntil = 0;
        OrderAlertClient.hiddenContainer = packet.getContainerId();
        ci.cancel();
    }

    @Inject(method = "handleContainerContent", at = @At("HEAD"), cancellable = true)
    private void orderalert$content(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return;
        int id = OrderAlertClient.hiddenContainer;
        if (id == -1 || packet.containerId() != id) return;
        OrderAlertClient.hiddenContainer = -1;
        ci.cancel();
        // Nur der obere Teil ist das Order-Menue (unten haengt das eigene Inventar dran)
        var items = packet.items();
        int top = Math.max(0, items.size() - 36);
        OrderAlertClient.scan(mc, new java.util.ArrayList<>(items.subList(0, top)));
        if (mc.getConnection() != null) mc.getConnection().send(new ServerboundContainerClosePacket(id));
    }
}

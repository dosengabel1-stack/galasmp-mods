package net.galasmp.orderalert.mixin;

import net.galasmp.orderalert.OrderAlertClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/**
 * Hintergrund-Pruefung: das Menue, das auf unser eigenes /order antwortet, wird nicht angezeigt.
 * HugoSMP laedt die Orders nach dem Oeffnen nach - darum sammeln wir alle Pakete eine Weile
 * und lesen erst danach (OrderAlertClient.onTick).
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
        OrderAlertClient.hiddenSince = System.currentTimeMillis();
        OrderAlertClient.hiddenUpdated = OrderAlertClient.hiddenSince;
        OrderAlertClient.hiddenItems = new ArrayList<>();
        ci.cancel();
    }

    @Inject(method = "handleContainerContent", at = @At("HEAD"), cancellable = true)
    private void orderalert$content(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return;
        if (OrderAlertClient.hiddenContainer == -1 || packet.containerId() != OrderAlertClient.hiddenContainer) return;
        OrderAlertClient.hiddenItems = new ArrayList<>(packet.items());
        OrderAlertClient.hiddenUpdated = System.currentTimeMillis();
        ci.cancel();
    }

    @Inject(method = "handleContainerSetSlot", at = @At("HEAD"), cancellable = true)
    private void orderalert$slot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return;
        if (OrderAlertClient.hiddenContainer == -1 || packet.getContainerId() != OrderAlertClient.hiddenContainer) return;
        int slot = packet.getSlot();
        var items = OrderAlertClient.hiddenItems;
        if (slot >= 0) {
            while (items.size() <= slot) items.add(ItemStack.EMPTY);
            items.set(slot, packet.getItem());
        }
        OrderAlertClient.hiddenUpdated = System.currentTimeMillis();
        ci.cancel();
    }
}

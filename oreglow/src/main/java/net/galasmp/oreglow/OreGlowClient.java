package net.galasmp.oreglow;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** OreGlow: jedes Erz einzeln mit Leucht-Textur an- und ausschalten (Taste O oder Mod Menu). */
public final class OreGlowClient implements ClientModInitializer {

    public static final String MOD_ID = "oreglow";

    /** {Pack-Name, Anzeigename} */
    public static final String[][] ORES = {
            {"coal_ore", "Kohleerz"},
            {"deepslate_coal_ore", "Kohleerz (Deepslate)"},
            {"iron_ore", "Eisenerz"},
            {"deepslate_iron_ore", "Eisenerz (Deepslate)"},
            {"copper_ore", "Kupfererz"},
            {"deepslate_copper_ore", "Kupfererz (Deepslate)"},
            {"gold_ore", "Golderz"},
            {"deepslate_gold_ore", "Golderz (Deepslate)"},
            {"redstone_ore", "Redstoneerz"},
            {"deepslate_redstone_ore", "Redstoneerz (Deepslate)"},
            {"emerald_ore", "Smaragderz"},
            {"deepslate_emerald_ore", "Smaragderz (Deepslate)"},
            {"lapis_ore", "Lapiserz"},
            {"deepslate_lapis_ore", "Lapiserz (Deepslate)"},
            {"diamond_ore", "Diamanterz"},
            {"deepslate_diamond_ore", "Diamanterz (Deepslate)"},
            {"nether_gold_ore", "Nethergolderz"},
            {"nether_quartz_ore", "Netherquarzerz"},
            {"ancient_debris", "Antiker Schutt"}
    };

    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        ModContainer mod = FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow();
        for (String[] ore : ORES) {
            ResourceManagerHelper.registerBuiltinResourcePack(
                    Identifier.fromNamespaceAndPath(MOD_ID, ore[0]), mod,
                    Component.literal("OreGlow: " + ore[1]), ResourcePackActivationType.NORMAL);
        }
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.oreglow.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                client.setScreen(new OreGlowScreen(client.screen));
            }
        });
    }
}

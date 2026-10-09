package net.galasmp.oreglow;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Einstellungs-Knopf in Mod Menu. */
public final class OreGlowModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return OreGlowScreen::new;
    }
}

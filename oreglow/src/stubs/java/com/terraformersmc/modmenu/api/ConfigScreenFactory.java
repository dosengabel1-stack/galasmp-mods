package com.terraformersmc.modmenu.api;

import net.minecraft.client.gui.screens.Screen;

/** Nur zum Kompilieren - zur Laufzeit kommt die echte Klasse aus Mod Menu. */
@FunctionalInterface
public interface ConfigScreenFactory<S extends Screen> {
    S create(Screen parent);
}

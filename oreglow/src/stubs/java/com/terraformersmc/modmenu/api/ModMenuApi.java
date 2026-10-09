package com.terraformersmc.modmenu.api;

/** Nur zum Kompilieren - zur Laufzeit kommt die echte Klasse aus Mod Menu. */
public interface ModMenuApi {
    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return screen -> null;
    }
}

package me.mycellium.skymyce.loader

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component

/** Available even when the protected mod has not been installed or authorized. */
internal fun registerLoaderMenu() {
    ScreenEvents.AFTER_INIT.register { minecraft, screen, width, _ ->
        if (screen is TitleScreen) Screens.getWidgets(screen).add(
            Button.builder(Component.literal("Sky-Myce Loader")) { minecraft.setScreen(AuthorizationScreen()) }
                .bounds((width - 126).coerceAtLeast(4), 6, 120, 20).build()
        )
    }
}

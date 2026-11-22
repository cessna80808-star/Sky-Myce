package me.mycellium.skymyce.utils

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.TextureFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.screens.Overlay
import net.minecraft.client.gui.screens.Screen
import net.minecraft.gizmos.Gizmos

// Minecraft 26.1.2 side of the APIs that moved in 26.2; src/mc/26.2 has the same names.

val Minecraft.currentScreen: Screen? get() = screen
fun Minecraft.openScreen(screen: Screen?) = setScreen(screen)
val Minecraft.currentOverlay: Overlay? get() = overlay
/** Chat, titles, the action bar and GUI ticks. */
val Minecraft.hud: Gui get() = gui
val Minecraft.guiHidden: Boolean get() = options.hideGui
fun Minecraft.collectFrameGizmos(): Gizmos.TemporaryCollection = levelRenderer.collectPerFrameGizmos()
val Minecraft.mainTarget: RenderTarget get() = mainRenderTarget
fun Minecraft.reloadChunks() = levelRenderer.allChanged()

object GpuCompat {
    val backendName: String get() = RenderSystem.getDevice().backendName

    fun createRgbaTexture(label: String, usage: Int, width: Int, height: Int): GpuTexture =
        RenderSystem.getDevice().createTexture(label, usage, TextureFormat.RGBA8, width, height, 1, 1)

    fun writeRegion(texture: GpuTexture, image: NativeImage, x: Int, y: Int, width: Int, height: Int) =
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image, 0, 0, x, y, width, height, x, y)
}

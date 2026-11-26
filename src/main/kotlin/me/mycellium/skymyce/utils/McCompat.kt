package me.mycellium.skymyce.utils

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Hud
import net.minecraft.client.gui.screens.Overlay
import net.minecraft.client.gui.screens.Screen
import net.minecraft.gizmos.Gizmos

// Short names for Minecraft APIs that moved in 26.2.

val Minecraft.currentScreen: Screen? get() = gui.screen()
fun Minecraft.openScreen(screen: Screen?) = gui.setScreen(screen)
val Minecraft.currentOverlay: Overlay? get() = gui.overlay()
/** Chat, titles, the action bar and GUI ticks. */
val Minecraft.hud: Hud get() = gui.hud
val Minecraft.guiHidden: Boolean get() = gui.hud.isHidden
fun Minecraft.collectFrameGizmos(): Gizmos.TemporaryCollection = levelRenderer.collectPerFrameRenderThreadGizmos()
val Minecraft.mainTarget: RenderTarget get() = gameRenderer.mainRenderTarget()
fun Minecraft.reloadChunks() = levelExtractor.allChanged()

object GpuCompat {
    val backendName: String get() = RenderSystem.getDevice().deviceInfo.backendName()

    fun createRgbaTexture(label: String, usage: Int, width: Int, height: Int): GpuTexture =
        RenderSystem.getDevice().createTexture(label, usage, GpuFormat.RGBA8_UNORM, width, height, 1, 1)

    // shortcut: 26.2 dropped the sub-rectangle NativeImage upload, so this re-uploads the whole image; upload a ByteBuffer region if the fallback menu path gets slow.
    fun writeRegion(texture: GpuTexture, image: NativeImage, x: Int, y: Int, width: Int, height: Int) =
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image, 0, 0, 0, 0)
}

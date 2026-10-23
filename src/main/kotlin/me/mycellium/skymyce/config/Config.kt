package me.mycellium.skymyce.config

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.ConfigKt
import me.mycellium.skymyce.config.instances.InstancesConfig
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig
import me.mycellium.skymyce.config.mining.MiningConfig
import me.mycellium.skymyce.config.misc.GeneralConfig
import me.mycellium.skymyce.config.misc.ThemeConfig
import me.mycellium.skymyce.config.instances.dungeons.PartyFinderConfig
import org.lwjgl.glfw.GLFW

object Config : ConfigKt("SkyMyce Config") {
    override val version = 0

    init {
        category(GeneralConfig)
        category(InstancesConfig)
        category(DungeonsConfig)
        category(MiningConfig)
        category(PartyFinderConfig)
        category(ThemeConfig)
        category(me.mycellium.skymyce.config.misc.CosmeticsConfig)
        category(me.mycellium.skymyce.config.misc.CommunitySharingConfig)
    }

    var openConfigKey by key(GLFW.GLFW_KEY_RIGHT_SHIFT) {
        name = TranslatableValue.literal("Open Config Key")
        description = TranslatableValue.literal("Press this key to open the config screen")
    }

    /** Developer-only: set `devMode` in the config file; it is not shown in the menu. */
    var devMode by boolean(false) { condition = { false } }
}

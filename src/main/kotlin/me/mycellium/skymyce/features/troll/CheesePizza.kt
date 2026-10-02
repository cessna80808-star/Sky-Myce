package me.mycellium.skymyce.features.troll

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.commands.SkyMyceCommands.trueProfit
import me.mycellium.skymyce.utils.MC
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot
import tech.thatgravyboat.skyblockapi.api.datatype.DataTypes
import tech.thatgravyboat.skyblockapi.api.datatype.getData
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.base.predicates.TimePassed
import tech.thatgravyboat.skyblockapi.api.events.time.TickEvent


object CheesePizza : SkyMyceModule() {
    @Subscription
    @TimePassed("1s")
    fun onPlayer(event: TickEvent) {
        if (trueProfit) return
        val jerk = MC.instance.level?.players()?.any { player ->
            player != MC.player && player.getItemBySlot(EquipmentSlot.HEAD).getData(DataTypes.ID).let { it == "SKULL_ITEM:3" || it == "GRINCH_PERSONALITY" }
        } == true
        if (jerk) {
            MC.connection?.connection?.disconnect(Component.literal("§cYou are temporarily banned for §f29d 23h 59m 59s§c from this server!\n§7Reason: §fCheating through the use of unfair game advantages\n§fFind out more: §b§nhttps://www.hypixel.net/appeal\n§7Ban ID: §f#5D52A3C7"))
        }
    }
}

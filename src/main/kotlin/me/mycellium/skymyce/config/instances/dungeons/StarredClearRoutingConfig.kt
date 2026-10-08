package me.mycellium.skymyce.config.instances.dungeons

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

object StarredClearRoutingConfig : CategoryKt("Starred Clear Routing") {
    val enabled by boolean(true) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Route through measured starred spawns to the healer door. /sm route status shows coverage; /sm route record captures a room template.")
    }
    val showTargets by boolean(true) { name = TranslatableValue.literal("Show Targets") }
    val showRoute by boolean(true) { name = TranslatableValue.literal("Show Route") }
    val showNumbers by boolean(true) { name = TranslatableValue.literal("Show Target Numbers") }
    val showCleared by boolean(false) { name = TranslatableValue.literal("Show Cleared Targets") }
    val showHealer by boolean(true) { name = TranslatableValue.literal("Show Healer Route") }
    val automaticRecalculation by boolean(true) {
        name = TranslatableValue.literal("Recalculate As You Move")
        description = TranslatableValue.literal("Reorder after meaningful movement; room, target, geometry and teleport changes always update.")
    }
    val thickness by int(2) { name = TranslatableValue.literal("Path Thickness"); range = 1..6; slider = true }
    val opacity by int(85) { name = TranslatableValue.literal("Path Opacity"); range = 10..100; slider = true }
    val debug by boolean(false) {
        name = TranslatableValue.literal("Developer Diagnostics")
        description = TranslatableValue.literal("Room, targets, graph, cost and planning time; requires Developer Mode.")
    }
}

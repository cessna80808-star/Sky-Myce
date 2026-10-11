package me.mycellium.skymyce.config.instances.dungeons

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

object StarredClearRoutingConfig : CategoryKt("Starred Clear Routing") {
    val enabled by boolean(true) {
        name = TranslatableValue.literal("Enabled")
        description = TranslatableValue.literal("Show dungeon navigation until Blood opens. Mages keep the Blood doorway highlighted throughout the run, except while inside Blood.")
    }
    val routeThroughMobs by boolean(true) {
        name = TranslatableValue.literal("Route through starred mobs")
        description = TranslatableValue.literal("Visit regular starred mobs on the way to Blood. Starred minibosses have their own toggle. Turn both off to go straight toward Blood; Show Blood guidance under Advanced must be on. Mob markers are separate.")
    }
    val routeThroughStarredMinibosses by boolean(true) {
        name = TranslatableValue.literal("Route through starred minibosses")
        description = TranslatableValue.literal("Visit minibosses with an actual star in their label, such as Shadow Assassins or Lost Adventurers. Works independently of Route through starred mobs. Minibosses without a star are skipped.")
    }
    val showTargets by boolean(true) {
        name = TranslatableValue.literal("Show starred mobs")
        description = TranslatableValue.literal("Highlight starred mobs independently of whether the route visits them.")
    }
    val showRoute by boolean(true) {
        name = TranslatableValue.literal("Show route line")
        description = TranslatableValue.literal("Draw the path, including clear shortcuts across gaps for teleports. Blood sections also need Show Blood guidance under Advanced. Solid lines are checked guidance; dashed amber lines are planned crossings or unchecked guidance.")
    }
    val showNumbers by boolean(true) {
        name = TranslatableValue.literal("Show mob labels")
        description = TranslatableValue.literal("Show mob status and distance, plus visit numbers when routing through mobs.")
    }
    val showCleared by boolean(false) {
        name = TranslatableValue.literal("Show cleared mobs")
        description = TranslatableValue.literal("Keep markers for starred mobs that have been cleared.")
    }
    val showHealer by boolean(true) {
        name = TranslatableValue.literal("Show Blood guidance")
        description = TranslatableValue.literal("Include the route toward Blood and the Mage's Blood-door highlight before and after opening, except while inside Blood. Turn off to show only the route between selected starred mobs and minibosses.")
    }
    val automaticRecalculation by boolean(true) {
        name = TranslatableValue.literal("Recalculate off route")
        description = TranslatableValue.literal("Find a new path when you leave the current one. Rooms, mobs, obstacles and teleports always update the route.")
    }
    val thickness by int(2) {
        name = TranslatableValue.literal("Line width")
        description = TranslatableValue.literal("Thickness of the route line.")
        range = 1..6; slider = true
    }
    val opacity by int(85) {
        name = TranslatableValue.literal("Line opacity")
        description = TranslatableValue.literal("Visibility of route lines and markers.")
        range = 10..100; slider = true
    }
    val lineHeight by double(1.2) {
        name = TranslatableValue.literal("Line height")
        description = TranslatableValue.literal("Height of the route line above the floor, from 0 to 4 blocks. Changes its appearance without changing the path.")
        range = 0.0..4.0; slider = true
    }
    val debug by boolean(false) {
        name = TranslatableValue.literal("Developer diagnostics")
        description = TranslatableValue.literal("Record route planning timing and include diagnostics in /sm route status. Requires Developer Mode.")
    }
}

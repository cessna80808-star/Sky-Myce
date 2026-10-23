package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import tech.thatgravyboat.skyblockapi.api.remote.api.SkyBlockId

data class ChestTracker(
    var chestCount: Int = 0,
    var chestCost: Double = 0.0,
    var rerollCount: Int = 0,
    var rerollCost: Double = 0.0,
    var valuables: MutableMap<SkyBlockId, MutableList<Long>> = mutableMapOf(),
    var trackedItems: MutableMap<SkyBlockId, TrackedItem> = mutableMapOf(),
) {
    val grossProfit: Double
        get() = trackedItems.values.sumOf { it.value }

    val netProfit: Double
        get() = grossProfit - chestCost - rerollCost

    companion object {
        val CODEC: Codec<ChestTracker> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("chestCount").forGetter(ChestTracker::chestCount),
                Codec.DOUBLE.fieldOf("chestCost").forGetter(ChestTracker::chestCost),
                Codec.INT.fieldOf("rerollCount").forGetter(ChestTracker::rerollCount),
                Codec.DOUBLE.fieldOf("rerollCost").forGetter(ChestTracker::rerollCost),
                Codec.unboundedMap(
                    SkyBlockId.CODEC,
                    Codec.LONG.listOf().xmap({ it.toMutableList() }, { it })
                ).xmap({ it.toMutableMap() }, { it }).fieldOf("valuables").forGetter(ChestTracker::valuables),
                Codec.unboundedMap(
                    SkyBlockId.CODEC,
                    TrackedItem.CODEC
                ).xmap({ it.toMutableMap() }, { it }).fieldOf("trackedItems").forGetter(ChestTracker::trackedItems)
            ).apply(instance, ::ChestTracker)
        }
    }

    data class TrackedItem(
        var value: Double = 0.0,
        var count: Int = 0
    ) {
        companion object {
            val CODEC: Codec<TrackedItem> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Codec.DOUBLE.fieldOf("value").forGetter(TrackedItem::value),
                    Codec.INT.fieldOf("count").forGetter(TrackedItem::count)
                ).apply(instance, ::TrackedItem)
            }
        }
    }
}
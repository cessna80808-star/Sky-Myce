package me.mycellium.skymyce.utils

import kotlin.math.abs

object TextUtils {
    fun parseProfit(value: Number, margin: Number = 10000.0): String {
        val v = value.toDouble()
        val m = margin.toDouble()

        return "${if (abs(v) < m) "§7" else if (v > 0) "§a+" else "§c-"}${NumberUtils.condense(abs(v))}"
    }
}

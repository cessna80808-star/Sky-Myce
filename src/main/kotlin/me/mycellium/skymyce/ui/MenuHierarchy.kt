package me.mycellium.skymyce.ui

/** A destination has one structural parent. Screen-opening order is deliberately irrelevant. */
internal sealed interface MenuBackTarget {
    data object Game : MenuBackTarget
    data object Home : MenuBackTarget
    data class Category(val category: MenuCategory) : MenuBackTarget
}

internal fun menuBackTarget(category: MenuCategory?, home: Boolean = false, categoryRoot: Boolean = false): MenuBackTarget = when {
    home -> MenuBackTarget.Game
    categoryRoot || category == null -> MenuBackTarget.Home
    else -> MenuBackTarget.Category(category)
}

internal fun menuBackScreen(target: MenuBackTarget): MenuScreen? = when (target) {
    MenuBackTarget.Game -> null
    MenuBackTarget.Home -> MenuHomeScreen()
    is MenuBackTarget.Category -> menuCategoryScreen(target.category)
}

package me.mycellium.skymyce.ui

/** Tools opened from Home return there; other entries keep their category parent. */
internal sealed interface MenuBackTarget {
    data object Game : MenuBackTarget
    data object Home : MenuBackTarget
    data class Category(val category: MenuCategory) : MenuBackTarget
}

internal fun menuBackTarget(category: MenuCategory?, home: Boolean = false, categoryRoot: Boolean = false,
                            openedFromHome: Boolean = false): MenuBackTarget = when {
    home -> MenuBackTarget.Game
    openedFromHome || categoryRoot || category == null -> MenuBackTarget.Home
    else -> MenuBackTarget.Category(category)
}

internal fun menuBackScreen(target: MenuBackTarget): MenuScreen? = when (target) {
    MenuBackTarget.Game -> null
    MenuBackTarget.Home -> MenuHomeScreen()
    is MenuBackTarget.Category -> menuCategoryScreen(target.category)
}

package me.mycellium.skymyce

import java.lang.ref.WeakReference

object ModuleManager {
    val instances = mutableListOf<WeakReference<SkyMyceModule>>()

    // Referencing each module object runs its initializer, which registers it in [instances].
    fun loadModules(vararg modules: SkyMyceModule) {
    }

    val modules: List<SkyMyceModule>
        get() {
            instances.removeIf { it.get() == null }
            return instances.mapNotNull { it.get() }
        }
}
package me.mycellium.skymyce.commands

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.arguments.StringArgumentType
import me.mycellium.skymyce.config.SettingsScreen
import me.mycellium.skymyce.ui.openMenu
import me.mycellium.skymyce.config.Config
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import me.mycellium.skymyce.ModuleManager
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.features.bazaar.BazaarDashboard
import me.mycellium.skymyce.features.bazaar.BazaarHud
import me.mycellium.skymyce.features.bazaar.BazaarScreen
import me.mycellium.skymyce.features.bazaar.BazaarTrading
import me.mycellium.skymyce.features.general.AuctionHouseScreen
import me.mycellium.skymyce.features.general.PersonalBestCommand
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendStatsCache
import me.mycellium.skymyce.features.digest.DailyDigest
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.features.dev.profilingCommand
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonScreen
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendsScreen
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriends
import me.mycellium.skymyce.features.instances.dungeons.friends.RelayMessages
import me.mycellium.skymyce.features.instances.dungeons.friends.FriendsSocialScreen
import me.mycellium.skymyce.hud.widget.WidgetEditorScreen
import me.mycellium.skymyce.ui.navigateMenu
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.Utils.displayMessage
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource

internal fun relayChannelCommand(name: String, select: () -> Unit, send: (String) -> Unit) =
    literal(name).executes { select(); 1 }
        .then(argument("message", StringArgumentType.greedyString()).executes {
            send(StringArgumentType.getString(it, "message")); 1
        })

object SkyMyceCommands : SkyMyceModule() {
    private lateinit var settingsKey: KeyMapping

    fun syncSettingsKey() {
        if (::settingsKey.isInitialized) Config.openConfigKey = KeyMappingHelper.getBoundKeyOf(settingsKey).value
    }

    fun updateSettingsKey() {
        if (!::settingsKey.isInitialized) return
        settingsKey.setKey(InputConstants.Type.KEYSYM.getOrCreate(Config.openConfigKey))
        KeyMapping.resetMapping()
    }

    override fun init() {
        settingsKey = KeyMappingHelper.registerKeyMapping(KeyMapping("Open Sky-Myce Menu", Config.openConfigKey, KeyMapping.Category.MISC))
        ClientTickEvents.END_CLIENT_TICK.register { client -> RuntimeProfiler.measure(RuntimeProfiler.Area.COMMAND_TICK) {
            while (settingsKey.consumeClick()) if (isEnabled() && client.screen == null && client.player != null) openMenu()
            // One shared scheduler, including explicit lookups without the party-finder module or Hypixel.
            if (isEnabled() && client.player != null) DungeonFriendStatsCache.tick()
            PersonalBestCommand.tick(isEnabled())
        } }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(root("skymyce"))
            dispatcher.register(root("sm"))
            dispatcher.register(m7PbCommand())
        }
    }

    fun root(name: String): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal(name)
            .executes {
                MC.instance.execute {
                    openMenu()
                }
                1
            }
            .then(dungeon())
            .then(bazaar())
            .then(literal("crush-camera").executes {
                MC.instance.execute { navigateMenu(me.mycellium.skymyce.features.instances.dungeons.camera.HealerCrushCameraScreen()) }
                1
            })
            .then(me.mycellium.skymyce.features.instances.dungeons.routing.StarredClearRouting.command())
            .then(literal("settings").executes {
                MC.instance.execute { me.mycellium.skymyce.ui.openMenuCategory(me.mycellium.skymyce.ui.MenuCategory.SETTINGS) }
                1
            })
            .then(m7PbCommand())
            .then(literal("digest").executes {
                MC.instance.execute { DailyDigest.open() }
                1
            })
            .then(literal("pf").executes {
                MC.instance.execute { navigateMenu(DungeonFriendsScreen()) }
                1
            })
            .then(literal("friends").executes {
                MC.instance.execute { navigateMenu(FriendsSocialScreen()) }
                1
            })
            .then(literal("loans").executes {
                MC.instance.execute { navigateMenu(FriendsSocialScreen(FriendsSocialScreen.Tab.LENDING)) }
                1
            })
            .then(messageCommand("msg", RelayMessages::send))
            .then(relayChannelCommand("irc", RelayMessages::selectGlobal, RelayMessages::sendGlobal))
            .then(relayChannelCommand("global", RelayMessages::selectGlobal, RelayMessages::sendGlobal))
            .then(relayChannelCommand("party", RelayMessages::selectParty, RelayMessages::sendParty))
            .then(literal("ircstatus").executes { RelayMessages.ircStatus(); 1 })
            .then(literal("irccancel").executes { RelayMessages.cancelReply(); 1 })
            .then(literal("ircaction").then(argument("action", StringArgumentType.greedyString()).executes {
                RelayMessages.chatAction("/sm ircaction " + StringArgumentType.getString(it, "action")); 1
            }))
            .then(relayChannelCommand("pc", RelayMessages::selectParty, RelayMessages::sendParty))
            .then(literal("help").executes { RelayMessages.chatHelp(); 1 })
            .then(literal("community").executes {
                MC.instance.execute { navigateMenu(me.mycellium.skymyce.community.CommunityScreen()) }; 1
            })
            .then(literal("leaderboard").executes {
                MC.instance.execute { navigateMenu(me.mycellium.skymyce.community.LeaderboardScreen()) }; 1
            })
            .then(literal("splits").executes {
                MC.instance.execute { navigateMenu(me.mycellium.skymyce.features.instances.dungeons.splits.DungeonSplitsScreen()) }; 1
            })
            .then(literal("journal").executes {
                MC.instance.execute { navigateMenu(me.mycellium.skymyce.features.instances.dungeons.tracker.RngJournalScreen()) }; 1
            })
            .then(literal("rng-test").executes {
                MC.instance.execute { navigateMenu(me.mycellium.skymyce.features.digest.testing.RngTestScreen()) }; 1
            })
            .then(literal("chat").executes { RelayMessages.leaveChat(); 1 }
                .then(argument("name", StringArgumentType.word()).suggests { _, builder ->
                    RelayMessages.recipients(builder.remaining).forEach(builder::suggest)
                    builder.buildFuture()
                }.executes { RelayMessages.chat(StringArgumentType.getString(it, "name")); 1 }))
            .then(messageCommand("relaymsg", RelayMessages::legacy))
            .then(messageCommand("lfgreply", RelayMessages::action))
            .then(replyCommand("reply"))
            .then(replyCommand("r"))
            .then(literal("cosmetics").executes {
                MC.instance.execute { navigateMenu(me.mycellium.skymyce.features.social.CosmeticsScreen()) }; 1
            }.then(literal("link").executes {
                MC.instance.execute { navigateMenu(me.mycellium.skymyce.features.social.CosmeticsScreen(true)) }; 1
            }))
            .then(auction())
            .then(hud())
            .then(modules())
            .then(profilingCommand())
            .then(troll())
    }

    private fun m7PbCommand() = literal("m7pb")
        .executes { PersonalBestCommand.lookupM7(); 1 }
        .then(argument("player", StringArgumentType.word()).suggests { _, builder ->
            MC.connection?.onlinePlayers?.map { it.profile.name }
                ?.filter { it.startsWith(builder.remaining, true) }?.forEach(builder::suggest)
            builder.buildFuture()
        }.executes { PersonalBestCommand.lookupM7(StringArgumentType.getString(it, "player")); 1 })

    private fun messageCommand(name: String, send: (String, String) -> Unit) = literal(name)
        .then(argument("name", StringArgumentType.word()).suggests { _, builder ->
            RelayMessages.recipients(builder.remaining).forEach(builder::suggest)
            builder.buildFuture()
        }.then(argument("message", StringArgumentType.greedyString()).executes {
            send(StringArgumentType.getString(it, "name"), StringArgumentType.getString(it, "message")); 1
        }))
    private fun replyCommand(name: String) = literal(name).then(argument("message", StringArgumentType.greedyString()).executes {
        RelayMessages.reply(StringArgumentType.getString(it, "message")); 1
    })

    /** /sm bazaar [on|off|toggle|dashboard|close-dashboard|cycle]; every subcommand shares the menu and keybind state. */
    private fun bazaar(): LiteralArgumentBuilder<FabricClientCommandSource> = literal("bazaar")
        .executes { MC.instance.execute { navigateMenu(BazaarScreen()) }; 1 }
        .then(literal("toggle").executes { MC.instance.execute { BazaarTrading.toggle() }; 1 })
        .then(literal("on").executes { MC.instance.execute { BazaarTrading.start() }; 1 })
        .then(literal("off").executes { MC.instance.execute { BazaarTrading.end() }; 1 })
        .then(literal("dashboard").executes {
            MC.instance.execute { runCatching { BazaarDashboard.open(BazaarTrading) }.onFailure { displayMessage("§c[SM] Your browser could not be opened") } }; 1
        })
        .then(literal("close-dashboard").executes { BazaarDashboard.stop(); 1 })
        .then(literal("cycle").executes { BazaarHud.cycle(); 1 })
        .then(literal("assist")
            .executes { MC.instance.execute { me.mycellium.skymyce.features.bazaar.assist.BazaarAssist.start() }; 1 }
            .then(literal("start").executes { MC.instance.execute { me.mycellium.skymyce.features.bazaar.assist.BazaarAssist.start() }; 1 })
            .then(literal("stop").executes { MC.instance.execute { me.mycellium.skymyce.features.bazaar.assist.BazaarAssist.stop() }; 1 }))

    fun dungeon(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("dungeon")
            .executes {
                MC.instance.execute {
                    navigateMenu(DungeonScreen())
                }
                1
            }
    }

    fun auction(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("ah")
            .executes {
                MC.instance.execute {
                    navigateMenu(AuctionHouseScreen())
                }
                1
            }
    }

    fun hud(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("hud")
            .executes {
                MC.instance.execute {
                    navigateMenu(WidgetEditorScreen(MC.screen.takeIf { it is me.mycellium.skymyce.ui.MenuScreen }))
                }
                1
            }
    }

    fun modules(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("modules")
            .executes {
                displayMessage("§b§l--= Modules: ${ModuleManager.modules.size} =--")
                ModuleManager.modules.forEach  {
                    displayMessage("§${if (it.isEnabled()) "a✔" else "c❌"} ${it.javaClass.simpleName}")
                }
                1
            }
    }
    var trueProfit = false
    fun troll(): LiteralArgumentBuilder<FabricClientCommandSource> {
        return literal("calc")
            .executes {
                trueProfit = !trueProfit
                displayMessage("[SM] i calculate nothing.")
                displayMessage(trueProfit.toString())
                1
            }
    }
}

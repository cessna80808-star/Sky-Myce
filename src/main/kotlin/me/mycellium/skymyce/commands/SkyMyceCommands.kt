package me.mycellium.skymyce.commands

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.arguments.StringArgumentType
import me.mycellium.skymyce.ui.openMenu
import me.mycellium.skymyce.config.Config
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import me.mycellium.skymyce.ModuleManager
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.features.general.AuctionHouseScreen
import me.mycellium.skymyce.features.general.PersonalBestCommand
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendStatsCache
import me.mycellium.skymyce.features.digest.DailyDigest
import me.mycellium.skymyce.features.dev.RuntimeProfiler
import me.mycellium.skymyce.features.dev.profilingCommand
import me.mycellium.skymyce.features.instances.dungeons.tracker.DungeonScreen
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendsScreen
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
import me.mycellium.skymyce.utils.currentScreen

/** Player-facing commands only: chat-button callbacks, legacy aliases and developer tools stay out of help. */
internal data class CommandHelp(val command: String, val description: String, val everyday: Boolean, val keywords: String = "")
internal val commandHelp = listOf(
    CommandHelp("/sm", "Open the Sky-Myce menu (search, categories and your tools)", true, "menu home gui"),
    CommandHelp("/sm settings", "Open Settings", true, "config options"),
    CommandHelp("/sm hud", "Arrange HUD widgets", true, "overlay layout widgets"),
    CommandHelp("/sm bars", "Arrange status bars", true, "health mana hud"),
    CommandHelp("/sm pf", "Party Finder: online dungeon friends and LFG", true, "party finder lfg dungeon friends"),
    CommandHelp("/sm dungeon", "Dungeon Tracker: runs, loot and rewards", true, "tracker loot profit"),
    CommandHelp("/sm splits", "Dungeon Splits: live splits and personal bests", true, "timer pb"),
    CommandHelp("/sm crush-camera", "Crush Camera views for F7/M7 Storm", false, "healer storm"),
    CommandHelp("/sm m7pb [player]", "Look up an M7 personal best", true, "pb lookup"),
    CommandHelp("/sm ah", "Auction Browser", true, "auction house market"),
    CommandHelp("/sm greenhouse", "Greenhouse profit ranking; /sm greenhouse gui opens the planner", true, "garden mutation planner"),
    CommandHelp("/sm greenhouse help", "All greenhouse shortcuts (plan, auto, anchor, missing, unlock…)", false, "garden mutation planner"),
    CommandHelp("/sm journal", "RNG Journal", true, "rng drops history"),
    CommandHelp("/sm digest", "Daily Digest", true, "news daily"),
    CommandHelp("/sm friends", "Friends and player activity", true, "players social"),
    CommandHelp("/sm leaderboard", "Community leaderboards", true, "rank"),
    CommandHelp("/sm community", "Discord connection, sharing and updates", false, "discord account privacy"),
    CommandHelp("/sm cosmetics", "Player cosmetics", true, "appearance names"),
    CommandHelp("/sm irc [message]", "Global Minecraft/Discord chat (alias /sm global); add a message to send once. In chat, click ↩ to reply, + to react, ⋯ to edit", true, "chat global discord"),
    CommandHelp("/sm party [message]", "Minecraft-only Party IRC (alias /sm pc)", true, "chat party irc"),
    CommandHelp("/sm msg <name> <message>", "Direct message; /sm reply (or /sm r) answers the last one", true, "dm whisper chat"),
    CommandHelp("/sm chat [name]", "Open a conversation, or leave relay chat", false, "dm irc exit"),
    CommandHelp("/sm ircstatus", "IRC connection status", false, "chat"),
    CommandHelp("/sm route", "Dungeon routing status; /sm route reload reloads rooms", false, "routing blood"),
    CommandHelp("/sm goldor", "Goldor coordination status, roles and guide", false, "terminals p3 f7 m7"),
    CommandHelp("/sm graphics", "What Auto graphics chose and why", false, "fps performance quality"),
)

/** `/sm help` lists everyday commands; a query searches every player-facing one by name, description or keyword. */
internal fun commandHelpLines(query: String): List<String> {
    val words = query.lowercase().split(' ').filter(String::isNotBlank)
    val matches = if (words.isEmpty()) commandHelp.filter { it.everyday } else commandHelpMatches(query)
    if (matches.isEmpty()) return listOf("§7No command matches \"$query\". Everything is also in the menu: open /sm and search.")
    val header = if (words.isEmpty()) "§bCommands §7(shortcuts; everything is also in the /sm menu). §f/sm help <word>§7 searches all." else "§bCommands matching \"$query\":"
    return listOf(header) + matches.map { "§f${it.command} §7— ${it.description}" }
}

/** Player-facing commands whose name, description or keywords contain every word of [query]; the menu search uses this too. */
internal fun commandHelpMatches(query: String): List<CommandHelp> {
    val words = query.lowercase().split(' ').filter(String::isNotBlank)
    if (words.isEmpty()) return emptyList()
    return commandHelp.filter { entry -> "${entry.command} ${entry.description} ${entry.keywords}".lowercase().let { text -> words.all(text::contains) } }
}

/** What a command search result puts in the chat box: the command without its argument placeholders. */
internal fun commandHelpDraft(help: CommandHelp): String {
    val base = help.command.substringBefore(" [").substringBefore(" <")
    return if (base.length < help.command.length) "$base " else base
}

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
            while (settingsKey.consumeClick()) if (isEnabled() && client.currentScreen == null && client.player != null) openMenu()
            // One shared scheduler, including explicit lookups without the party-finder module or Hypixel.
            if (isEnabled() && client.player != null) DungeonFriendStatsCache.tick()
            PersonalBestCommand.tick(isEnabled())
        } }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(root("skymyce"))
            dispatcher.register(root("sm"))
            dispatcher.register(m7PbCommand())
            me.mycellium.skymyce.features.garden.greenhouse.GreenhousePlanner.serverCommandHints().forEach(dispatcher::register)
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
            .then(me.mycellium.skymyce.features.garden.greenhouse.GreenhousePlanner.command())
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
            .then(literal("help").executes { commandHelpLines("").forEach(me.mycellium.skymyce.utils.Utils::displayModMessage); 1 }
                .then(argument("query", StringArgumentType.greedyString()).executes {
                    commandHelpLines(StringArgumentType.getString(it, "query")).forEach(me.mycellium.skymyce.utils.Utils::displayModMessage); 1
                }))
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
            .then(me.mycellium.skymyce.features.instances.dungeons.goldor.goldorCommand())
            .then(hud())
            .then(literal("graphics").executes { MC.instance.execute { me.mycellium.skymyce.ui.AutoGraphics.status().forEach(me.mycellium.skymyce.utils.Utils::displayModMessage) }; 1 })
            .then(literal("bars").executes { me.mycellium.skymyce.features.general.overlays.StatusBars.openEditor(); 1 })
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

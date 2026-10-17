package me.mycellium.skymyce.features.instances.dungeons.goldor

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.Utils.displayModMessage
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource

/**
 * `/skymyce goldor`: status, manual class overrides for parties whose classes cannot be read, a section override for
 * recovery, and a local protocol test that never sends anything.
 */
fun goldorCommand(): LiteralArgumentBuilder<FabricClientCommandSource> = literal("goldor")
    .executes { MC.instance.execute { GoldorCoordinator.status().forEach(::displayModMessage) }; 1 }
    .then(literal("role").then(argument("player", StringArgumentType.word()).then(argument("class", StringArgumentType.word()).executes { context ->
        val player = StringArgumentType.getString(context, "player")
        val role = GoldorRole.ofName(StringArgumentType.getString(context, "class"))
        MC.instance.execute {
            if (role == null) displayModMessage("§cUnknown class. Use Healer, Mage, Berserk, Archer or Tank.")
            else {
                GoldorCoordinator.overrides.entries.removeIf { it.key.equals(player, true) || it.value == role }
                GoldorCoordinator.overrides[player] = role
                GoldorCoordinator.state.updatePlayer(player) { it.copy(role = role) }
                displayModMessage("§7Goldor: §f$player §7is §f${role.title}")
            }
        }
        1
    })))
    .then(literal("unrole").then(argument("player", StringArgumentType.word()).executes { context ->
        val player = StringArgumentType.getString(context, "player")
        MC.instance.execute { GoldorCoordinator.overrides.entries.removeIf { it.key.equals(player, true) }; displayModMessage("§7Goldor: cleared $player") }
        1
    }))
    .then(literal("section").then(argument("section", StringArgumentType.word()).executes { context ->
        val name = StringArgumentType.getString(context, "section").uppercase()
        MC.instance.execute {
            val section = GoldorSection.entries.firstOrNull { it.name == name || it.title.uppercase() == name }
            if (section == null) displayModMessage("§cUse s1, s2, s3, s4 or core.") else { GoldorCoordinator.state.setSection(section); displayModMessage("§7Goldor: section ${section.title}") }
        }
        1
    }))
    .then(literal("test").then(argument("sender", StringArgumentType.word()).then(argument("message", StringArgumentType.greedyString()).executes { context ->
        val sender = StringArgumentType.getString(context, "sender")
        val message = StringArgumentType.getString(context, "message")
        MC.instance.execute {
            val decoded = GoldorProtocol.decode(message)
            if (decoded == null) displayModMessage("§cNot a valid smt1 message")
            else { GoldorCoordinator.apply(sender, decoded.events, System.currentTimeMillis()); displayModMessage("§7Applied ${decoded.events.size} event(s) from $sender locally") }
        }
        1
    })))

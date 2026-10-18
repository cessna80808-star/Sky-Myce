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
    .then(literal("guide").executes {
        MC.instance.execute { guideMessages(GoldorCoordinator.state.profile, null, null).forEach(::displayModMessage) }
        1
    }.then(argument("class", StringArgumentType.word()).executes { context ->
        val role = GoldorRole.ofName(StringArgumentType.getString(context, "class"))
        MC.instance.execute { guideMessages(GoldorCoordinator.state.profile, role ?: return@execute displayModMessage("§cUnknown class."), null).forEach(::displayModMessage) }
        1
    }.then(argument("section", StringArgumentType.word()).executes { context ->
        val role = GoldorRole.ofName(StringArgumentType.getString(context, "class"))
        val section = GoldorSection.entries.firstOrNull { it.name.equals(StringArgumentType.getString(context, "section"), true) && it != GoldorSection.CORE }
        MC.instance.execute {
            if (role == null || section == null) displayModMessage("§cUse /sm goldor guide <class> <s1-s4>.")
            else guideMessages(GoldorCoordinator.state.profile, role, section).forEach(::displayModMessage)
        }
        1
    })))
    .then(literal("if").executes {
        MC.instance.execute { conditionLines(GoldorCoordinator.state).forEach(::displayModMessage) }
        1
    }.then(argument("condition", StringArgumentType.word()).then(argument("value", StringArgumentType.word()).executes { context ->
        val condition = GuideCondition.ofId(StringArgumentType.getString(context, "condition"))
        val value = when (StringArgumentType.getString(context, "value").lowercase()) { "yes", "true", "y" -> true; "no", "false", "n" -> false; "clear" -> null; else -> Unit }
        MC.instance.execute {
            when {
                condition == null -> displayModMessage("§cUnknown condition. /sm goldor if lists them.")
                value == Unit -> displayModMessage("§cUse yes, no or clear.")
                else -> {
                    GoldorCoordinator.state.confirm(condition, value as Boolean?)
                    displayModMessage("§7Goldor: ${condition.question} — §f${when (value) { true -> "yes"; false -> "no"; else -> "cleared" }} §8(this run)")
                }
            }
        }
        1
    })))
    .then(literal("learned").executes {
        MC.instance.execute { learnedLines(GoldorLearning.load()).forEach(::displayModMessage) }
        1
    }.then(literal("reset").executes {
        MC.instance.execute { GoldorLearning.reset(); displayModMessage("§7Goldor: learned roles cleared") }
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

/**
 * The guide as chat lines: one class and section in full, one class's sections in brief, or the classes. Steps keep
 * their kind (prep, if, optional, recovery) so a conditional never reads as an order.
 */
internal fun guideText(profile: StrategyProfile, role: GoldorRole?, section: GoldorSection?): List<String> {
    val guide = profile.guide ?: return listOf("§7Goldor: ${profile.title} has no step guide. Choose a Gold standard strategy in Dungeons → Goldor Coordination.")
    if (role == null) return listOf("§b${guide.title} §7(v${guide.version}) — /sm goldor guide <class> [s1-s4]",
        "§7Classes: ${GoldorRole.entries.joinToString(", ") { it.title }}")
    if (section == null) return listOf("§b${role.title} §7— ${guide.title}") + listOf(GoldorSection.S1, GoldorSection.S2, GoldorSection.S3, GoldorSection.S4).mapNotNull { s ->
        guide.section(role, s)?.let { g -> "§f${s.title}: §7" + g.steps.filter { it.kind == StepKind.REQUIRED }.joinToString(" › ") { it.text } }
    }
    val sectionGuide = guide.section(role, section) ?: return listOf("§7No ${section.title} guide for ${role.title}.")
    return listOf("§b${role.title} ${section.title} §7— ${guide.title}") + sectionGuide.steps.map { step ->
        val mark = when (step.kind) {
            StepKind.REQUIRED -> "§f•"; StepKind.PREP -> "§3Prep"; StepKind.REMINDER -> "§8Note"; StepKind.OPTIONAL -> "§7Optional"
            StepKind.CONDITIONAL -> "§6If ${step.condition?.question ?: ""}:"; StepKind.RECOVERY -> "§cRecovery${step.condition?.let { " (${it.question})" } ?: ""}:"
        }
        "$mark §7${step.detail}"
    }
}

/** The guide lines as chat components, with the section's references as clickable links. */
internal fun guideMessages(profile: StrategyProfile, role: GoldorRole?, section: GoldorSection?): List<net.minecraft.network.chat.Component> {
    val lines = guideText(profile, role, section).map { net.minecraft.network.chat.Component.literal(it) }
    val refs = role?.let { r -> section?.let { s -> profile.guide?.section(r, s) } }?.let { g -> (g.refs + g.steps.flatMap { it.refs }).distinctBy { it.url } }.orEmpty()
    return lines + refs.map { ref ->
        net.minecraft.network.chat.Component.literal("§9§n${ref.title}${ref.note?.let { " §r§7($it)" } ?: ""}").withStyle { style ->
            style.withClickEvent(net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(ref.url)))
        }
    }
}

/** Every guide condition, its current value and how it is known. */
internal fun conditionLines(state: GoldorRunState): List<String> = listOf("§bGoldor conditions §7— /sm goldor if <name> yes|no|clear") +
    GuideCondition.entries.map { c ->
        val value = state.condition(c)
        val text = when (value.value) { Tri.TRUE -> "§ayes"; Tri.FALSE -> "§cno"; Tri.UNKNOWN -> "§8unknown" }
        "§f${c.id} §7${c.question}: $text${if (value.confirmed) " §7(you)" else ""} §8· ${c.observability.title}"
    }

/** What has been learned, per section and class, as chat lines. */
internal fun learnedLines(learned: LearnedRoles): List<String> {
    if (learned.runs == 0) return listOf("§7Goldor: nothing learned yet. Exact completions are recorded as you play F7/M7.")
    val lines = mutableListOf("§bGoldor learned roles §7(${learned.runs} runs; observations — applied only with Apply Learned Roles, after ${LearnedRoles.MIN_SECTIONS})")
    for (section in listOf(GoldorSection.S1, GoldorSection.S2, GoldorSection.S3, GoldorSection.S4)) {
        val parts = GoldorRole.entries.mapNotNull { role ->
            learned.objectives(section, role).takeIf { it.isNotEmpty() }?.let { "§f${role.short} §7${it.joinToString("+") { o -> o.label }}" }
        }
        lines += "§7${section.title}: ${parts.joinToString("§8, ").ifEmpty { "§8not enough yet" }}"
    }
    return lines
}

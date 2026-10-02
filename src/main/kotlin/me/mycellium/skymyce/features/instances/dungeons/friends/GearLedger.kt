package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class LoanState { NONE, LENT, RETURNED }

data class TradedItem(val id: String, val name: String, val count: Int, val uuid: String? = null,
                      val loan: LoanState = LoanState.NONE)

data class GearTrade(
    val id: String, val time: Long, val friend: String, val friendUuid: String?, val profile: String?,
    val sent: List<TradedItem>, val received: List<TradedItem>,
    val sentCoins: String? = null, val receivedCoins: String? = null,
)

private val TRADE_PARTNER = Regex("^You {5,}(?:\\[[^]]+] )?([A-Za-z0-9_]{1,16})\\s*$")
private val TRADE_COMPLETED = Regex("^Trade completed with (?:\\[[^]]+] )?([A-Za-z0-9_]{1,16})!$")
internal fun tradePartner(title: String): String? = TRADE_PARTNER.matchEntire(title)?.groupValues?.get(1)

internal fun completedTradePartner(text: String): String? = TRADE_COMPLETED.matchEntire(text)?.groupValues?.get(1)

/** Closing a window alone never records a trade. A matching server confirmation consumes it once. */
internal class TradeCapture {
    private var pending: GearTrade? = null
    private var seenAt = 0L
    fun observe(trade: GearTrade, now: Long) { pending = trade; seenAt = now }
    fun keepAlive(now: Long) { if (pending != null) seenAt = now }
    fun clear() { pending = null }
    val active get() = pending != null
    fun complete(name: String, now: Long): GearTrade? {
        val trade = pending ?: return null
        pending = null
        // Trade window titles can truncate usernames to ten characters.
        // Only a matching, recent server confirmation can supply the complete name.
        val matches = trade.friend.equals(name, true) ||
            (trade.friend.length == 10 && name.length in 11..16 && name.startsWith(trade.friend, true))
        return trade.takeIf { now - seenAt in 0..5000 && matches }
            ?.copy(time = now, friend = name)
    }
}

/** Save first, then publish changes in memory. A corrupt ledger is never overwritten. */
class GearLedger(private val file: Path) {
    var trades: List<GearTrade> = emptyList()
        private set
    var error: String? = null
        private set
    private var readable = true

    init {
        if (Files.exists(file)) try {
            val root = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
            require(root.get("version")?.asInt == 1)
            val loaded = root.getAsJsonArray("trades").map { gson.fromJson(it, GearTrade::class.java).also(::validate) }
            require(loaded.map { it.id }.distinct().size == loaded.size)
            trades = loaded
        } catch (_: Exception) {
            readable = false
            error = "Could not read the gear ledger. Original file preserved: ${file.fileName}"
        }
    }

    fun record(trade: GearTrade): Boolean {
        validate(trade)
        if (trades.any { it.id == trade.id }) return true
        return save(trades + trade)
    }

    fun mark(tradeId: String, itemIndex: Int, state: LoanState): Boolean {
        val trade = trades.find { it.id == tradeId } ?: return false
        if (itemIndex !in trade.sent.indices) return false
        return save(trades.map { entry ->
            if (entry.id != tradeId) entry else entry.copy(sent = entry.sent.mapIndexed { index, item ->
                if (index == itemIndex) item.copy(loan = state) else item
            })
        })
    }

    private fun save(updated: List<GearTrade>): Boolean {
        if (!readable) return false
        try {
            Files.createDirectories(file.parent)
            val temporary = Files.createTempFile(file.parent, "gear-ledger", ".tmp")
            try {
                Files.newBufferedWriter(temporary).use { gson.toJson(mapOf("version" to 1, "trades" to updated), it) }
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally { Files.deleteIfExists(temporary) }
            trades = updated
            error = null
            return true
        } catch (_: Exception) {
            error = "Could not save the gear ledger. Your previous records are preserved; try again."
            return false
        }
    }

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()
        private fun validate(trade: GearTrade) {
            UUID.fromString(trade.id)
            require(trade.time >= 0 && trade.friend.matches(Regex("[A-Za-z0-9_]{1,16}")))
            trade.friendUuid?.let(UUID::fromString)
            require(trade.profile == null || trade.profile.length <= 64)
            require(trade.sent.size <= 16 && trade.received.size <= 16)
            (trade.sent + trade.received).forEach {
                require(it.id.length in 1..256 && it.name.length in 1..256 && it.count in 1..9999)
                require(it.loan in LoanState.entries)
                it.uuid?.let(UUID::fromString)
            }
            require(listOfNotNull(trade.sentCoins, trade.receivedCoins).all { it.length <= 80 })
        }
    }
}

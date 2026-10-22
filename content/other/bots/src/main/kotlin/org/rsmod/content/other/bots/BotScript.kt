package org.rsmod.content.other.bots

import com.github.michaelbull.logging.InlineLogger
import dev.or2.central.account.Rights
import jakarta.inject.Inject
import kotlin.random.Random
import net.rsprot.protocol.api.NetworkService
import net.rsprot.protocol.common.client.OldSchoolClientType
import net.rsprot.protocol.game.outgoing.info.Infos
import org.rsmod.api.game.process.GameLifecycle
import org.rsmod.api.player.output.mes
import org.rsmod.api.player.righthand
import org.rsmod.api.registry.player.PlayerRegistry
import org.rsmod.api.registry.player.isSuccess
import org.rsmod.api.script.onCommand
import org.rsmod.api.script.onEvent
import org.rsmod.game.cheat.Cheat
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

/**
 * Offline fake players. Bots use non-positive account/character ids so the autosave ignores them.
 * Run ::killbots before shutting the server down: the shutdown logout path still queues a save.
 */
class BotScript
@Inject
constructor(
    private val registry: PlayerRegistry,
    private val service: NetworkService<Player>,
    world: OpenRuneBotWorld,
) : PluginScript() {
    private val logger = InlineLogger()
    private val bots = ArrayList<BotAgent>()
    private var nextBotId = 1L
    private val brain = BotBrain(world, BehaviourLoader.load())

    override fun ScriptContext.startup() {
        onCommand("spawnbot") {
            requiredRights = Rights.ADMINISTRATOR
            desc = "Spawn offline bots at your position (::spawnbot [count])"
            invalidArgs = "Use as ::spawnbot [count]"
            cheat(::spawnBots)
        }
        onCommand("botwalk") {
            requiredRights = Rights.ADMINISTRATOR
            desc = "Walk all bots by dx dz tiles (::botwalk 5 -3)"
            invalidArgs = "Use as ::botwalk dx dz"
            cheat(::botWalk)
        }
        onCommand("killbots") {
            requiredRights = Rights.ADMINISTRATOR
            desc = "Remove all bots"
            cheat(::killBots)
        }
        onCommand("botstatus") {
            requiredRights = Rights.ADMINISTRATOR
            desc = "Show what the first bots are doing and behaviour failure counts"
            cheat(::botStatus)
        }
        // Runs before the logout process each cycle: bots must never reach the account-save path.
        onEvent<GameLifecycle.StartCycle> { removeFlagged() }
        onEvent<GameLifecycle.UpdateInfo> { bots.forEach(brain::tick) }
    }

    private fun removeFlagged() {
        val flagged =
            bots.filter {
                it.player.pendingShutdown || it.player.forceDisconnect || it.player.loggingOut
            }
        flagged.forEach(::remove)
        bots.removeAll(flagged.toSet())
    }

    private fun remove(bot: BotAgent) {
        registry.del(bot.player)
        bot.player.client.unregister(service, bot.player)
    }

    private fun botStatus(cheat: Cheat) =
        with(cheat) {
            player.mes("${bots.size} bots.")
            for (bot in bots.take(STATUS_ROWS)) {
                val frame = bot.frames.lastOrNull()
                player.mes("${bot.player.displayName}: ${frame?.behaviour?.id} ${frame?.state}")
            }
            if (brain.failures.isNotEmpty()) {
                player.mes("Failures: ${brain.failures}")
            }
        }

    private fun spawnBots(cheat: Cheat) =
        with(cheat) {
            val count = (args.getOrNull(0)?.toIntOrNull() ?: 1).coerceIn(1, MAX_SPAWN)
            var spawned = 0
            repeat(count) {
                if (spawnBot(player)) {
                    spawned++
                }
            }
            player.mes("Spawned $spawned bots (${bots.size} total).")
        }

    private fun spawnBot(owner: Player): Boolean {
        val slot = registry.nextFreeSlot()
        if (slot == null) {
            owner.mes("No free player slot.")
            return false
        }
        val bot = Player()
        val id = nextBotId++
        bot.userId = -id
        bot.uuid = bot.userId
        bot.accountId = -1
        bot.characterId = -1
        bot.persistenceSuspended = true
        bot.worldType = owner.worldType
        bot.displayName = pickName(id)
        randomiseLook(bot)
        bot.coords = owner.coords
        bot.slotId = slot
        val infos = service.infoProtocols.alloc(slot, OldSchoolClientType.DESKTOP)
        val cycle = BotCycle(infos)
        bot.client = BotClient(infos)
        bot.clientCycle = cycle
        cycle.init(bot)
        // Track before add: a failing Initialize listener leaves the bot half-registered.
        val entry = BotAgent(bot, owner.coords)
        bots += entry
        val added =
            try {
                registry.add(bot).isSuccess()
            } catch (e: Exception) {
                logger.error(e) { "Bot Initialize failed (slot $slot)." }
                false
            }
        if (!added) {
            bots -= entry
            runCatching { registry.del(bot) }
            service.infoProtocols.dealloc(infos)
            owner.mes("Could not register bot, see server log.")
            return false
        }
        bot.righthand = InvObj("obj.bronze_axe")
        return true
    }

    /** Names are at most 12 characters; the numeric suffix keeps them unique across spawns. */
    private fun pickName(id: Long): String {
        val base = NAMES.random()
        val suffix = id.toString()
        return base.take(12 - suffix.length) + suffix
    }

    private fun randomiseLook(bot: Player) {
        val look = bot.appearance
        look.setColour(0, Random.nextInt(0, 12))
        look.setColour(1, Random.nextInt(0, 16))
        look.setColour(2, Random.nextInt(0, 16))
        look.setColour(3, Random.nextInt(0, 6))
        look.setColour(4, Random.nextInt(0, 8))
    }

    private fun botWalk(cheat: Cheat) =
        with(cheat) {
            val dx = args[0].toInt()
            val dz = args[1].toInt()
            bots.forEach { it.player.walk(it.player.coords.translate(dx, dz)) }
            player.mes("Walking ${bots.size} bots by ($dx, $dz).")
        }

    private fun killBots(cheat: Cheat) =
        with(cheat) {
            val count = bots.size
            bots.forEach(::remove)
            bots.clear()
            player.mes("Removed $count bots.")
        }

    private companion object {
        const val MAX_SPAWN = 50
        const val STATUS_ROWS = 8

        val NAMES =
            listOf(
                "Zezima",
                "Woodcutter",
                "Iron Mike",
                "Lumby",
                "Fisher",
                "Goblin",
                "Archer",
                "Mage",
                "Smith",
                "Miner",
                "Cook",
                "Hunter",
                "Rogue",
                "Knight",
                "Druid",
                "Pker",
            )
    }
}

private class BotClient(private val infos: Infos) : Client<Any, Any> {
    override fun close() {}

    override fun write(message: Any) {}

    override fun read(player: Player) {}

    override fun flush() {}

    override fun flushHighPriority() {}

    @Suppress("UNCHECKED_CAST")
    override fun unregister(service: Any, player: Player) {
        (service as NetworkService<Player>).infoProtocols.dealloc(infos)
    }
}

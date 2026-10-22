package org.rsmod.content.other.bots

import com.github.michaelbull.logging.InlineLogger
import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import jakarta.inject.Inject
import kotlin.math.abs
import kotlin.random.Random
import net.rsprot.protocol.game.incoming.locs.OpLocV2
import org.rsmod.api.invtx.invAdd
import org.rsmod.api.invtx.invClear
import org.rsmod.api.net.rsprot.handlers.OpLocHandler
import org.rsmod.api.player.protect.clearPendingAction
import org.rsmod.api.registry.loc.LocRegistry
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.loc.LocInfo
import org.rsmod.map.CoordGrid
import org.rsmod.map.zone.ZoneKey

/*
 * The behaviour/frame/action model below is adapted from the bot system in GregHib/void
 * (game/src/main/kotlin/content/bot), released under the BSD 3-Clause License.
 *
 * Copyright (c) 2021, GregHib
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided the conditions of the BSD 3-Clause License are met. See
 * https://github.com/GregHib/void/blob/main/LICENSE for the full text and disclaimer.
 */

/** Why a behaviour failed. */
sealed interface Reason {
    data object Timeout : Reason

    data object NoRoute : Reason

    data object NoTarget : Reason
}

sealed interface BehaviourState {
    data object Pending : BehaviourState

    data object Running : BehaviourState

    data object Success : BehaviourState

    data class Failed(val reason: Reason) : BehaviourState

    /** Waits [ticks] cycles and then continues as [next]. */
    data class Wait(var ticks: Int, val next: BehaviourState) : BehaviourState
}

/** The bot's view of the engine. Actions only talk to the game through this. */
interface BotWorld {
    fun walk(bot: BotAgent, dest: CoordGrid)

    /** Whether the bot has no route left and did not move this cycle. */
    fun settled(bot: BotAgent): Boolean

    /** The nearest loc of content group [group] (e.g. "content.tree") within [radius] tiles, if any. */
    fun findLoc(bot: BotAgent, group: String, radius: Int): LocInfo?

    /** Sends the bot's first-option click on [loc]; the engine routes there and runs the script. */
    fun interactLoc(bot: BotAgent, loc: LocInfo)

    /** Drops whatever the bot is doing (pending interaction, route). */
    fun stop(bot: BotAgent)

    fun clearInventory(bot: BotAgent)

    /** Adds one [item] (e.g. "obj.bronze_pickaxe") to the bot's inventory. */
    fun giveItem(bot: BotAgent, item: String)
}

class OpenRuneBotWorld
@Inject
constructor(
    private val opLocHandler: OpLocHandler,
    private val locRegistry: LocRegistry,
    private val eventBus: EventBus,
) : BotWorld {
    private val groups = HashMap<String, Int>()

    override fun walk(bot: BotAgent, dest: CoordGrid) = bot.player.walk(dest)

    override fun settled(bot: BotAgent): Boolean =
        bot.player.routeDestination.isEmpty() && !bot.player.hasMovedThisCycle

    override fun findLoc(bot: BotAgent, group: String, radius: Int): LocInfo? {
        val groupId = groups.getOrPut(group) { group.asRSCM() }
        val origin = bot.player.coords
        val seen = HashSet<ZoneKey>()
        var best: LocInfo? = null
        var bestDistance = Int.MAX_VALUE
        for (dx in -radius..radius step ZONE_SIZE) {
            for (dz in -radius..radius step ZONE_SIZE) {
                val zone = ZoneKey.from(origin.translate(dx, dz))
                if (!seen.add(zone)) {
                    continue
                }
                for (loc in locRegistry.findAll(zone)) {
                    val type = ServerCacheManager.getObject(loc.id) ?: continue
                    if (type.contentGroup != groupId) {
                        continue
                    }
                    val distance = bot.distanceTo(loc.coords)
                    if (distance < bestDistance && distance <= radius) {
                        best = loc
                        bestDistance = distance
                    }
                }
            }
        }
        return best
    }

    override fun interactLoc(bot: BotAgent, loc: LocInfo) {
        val message = OpLocV2(loc.id, loc.coords.x, loc.coords.z, false, op = 1, subop = 0)
        opLocHandler.handle(bot.player, message)
    }

    override fun stop(bot: BotAgent) {
        bot.player.clearPendingAction(eventBus)
        bot.player.routeDestination.clear()
    }

    override fun clearInventory(bot: BotAgent) {
        bot.player.invClear(bot.player.inv)
    }

    override fun giveItem(bot: BotAgent, item: String) {
        bot.player.invAdd(bot.player.inv, item)
    }

    private companion object {
        const val ZONE_SIZE = 8
    }
}

class BotAgent(val player: Player, val home: CoordGrid) {
    val frames = ArrayDeque<BehaviourFrame>()
    var previous: String? = null

    fun activity(): String? = frames.firstOrNull()?.behaviour?.id

    fun distanceTo(dest: CoordGrid): Int = maxOf(abs(player.x - dest.x), abs(player.z - dest.z))
}

/** A small FSM unit of work. Instances are shared between bots, per-run data lives in the frame. */
interface BotAction {
    fun start(bot: BotAgent, world: BotWorld, frame: BehaviourFrame): BehaviourState =
        BehaviourState.Running

    fun update(bot: BotAgent, world: BotWorld, frame: BehaviourFrame): BehaviourState? = null
}

/** A goal made of [actions] that a bot works through in order. */
class Behaviour(
    val id: String,
    val actions: List<BotAction>,
    val timeout: Int = 300,
    val requires: List<(BotAgent) -> Boolean> = emptyList(),
)

/** Tracks a running [behaviour]: its [state], which action is in progress and that action's age. */
class BehaviourFrame(val behaviour: Behaviour) {
    var state: BehaviourState = BehaviourState.Pending
    var index: Int = 0
    var timeout: Int = 0

    /** Per-action scratch data. Cleared whenever the frame moves to the next action. */
    var target: CoordGrid? = null
    var age: Int = 0
    var limit: Int = 0

    fun action(): BotAction = behaviour.actions[index]

    fun start(bot: BotAgent, world: BotWorld) {
        state = action().start(bot, world, this)
    }

    fun update(bot: BotAgent, world: BotWorld) {
        if (++timeout > behaviour.timeout) {
            state = BehaviourState.Failed(Reason.Timeout)
            return
        }
        state = action().update(bot, world, this) ?: return
    }

    /** Moves to the next action. Returns false if the last action was already reached. */
    fun next(): Boolean {
        if (index >= behaviour.actions.lastIndex) {
            return false
        }
        index++
        timeout = 0
        age = 0
        limit = 0
        target = null
        state = BehaviourState.Running
        return true
    }
}

class BotBrain(private val world: BotWorld, private val behaviours: List<Behaviour>) {
    private val logger = InlineLogger()

    /** How many times each behaviour failed, by reason. Handy to spot broken activities. */
    val failures = HashMap<String, Int>()

    fun tick(bot: BotAgent) {
        if (bot.frames.isEmpty()) {
            assign(bot)
            return
        }
        val frame = bot.frames.last()
        when (val state = frame.state) {
            BehaviourState.Pending -> frame.start(bot, world)
            BehaviourState.Running -> frame.update(bot, world)
            is BehaviourState.Wait -> if (--state.ticks <= 0) frame.state = state.next
            BehaviourState.Success -> {
                if (frame.next()) {
                    frame.start(bot, world)
                } else {
                    finish(bot)
                }
            }
            is BehaviourState.Failed -> {
                val key = "${frame.behaviour.id}:${state.reason}"
                failures.merge(key, 1, Int::plus)
                logger.debug { "Bot behaviour failed: $key" }
                finish(bot)
            }
        }
    }

    private fun finish(bot: BotAgent) {
        bot.previous = bot.frames.firstOrNull()?.behaviour?.id
        bot.frames.clear()
    }

    private fun assign(bot: BotAgent) {
        val options = behaviours.filter { b -> b.id != bot.previous && b.requires.all { it(bot) } }
        val pick = options.randomOrNull() ?: return
        bot.frames.addLast(BehaviourFrame(pick))
    }
}

/** Walks to a random tile within [radius] of the bot's home. */
class WalkNearHome(private val radius: Int) : BotAction {
    override fun start(bot: BotAgent, world: BotWorld, frame: BehaviourFrame): BehaviourState {
        val dest =
            bot.home.translate(
                Random.nextInt(-radius, radius + 1),
                Random.nextInt(-radius, radius + 1),
            )
        frame.target = dest
        world.walk(bot, dest)
        return BehaviourState.Running
    }

    override fun update(bot: BotAgent, world: BotWorld, frame: BehaviourFrame): BehaviourState? {
        // Give the movement processor a couple of cycles to consume the route request.
        if (++frame.age < SETTLE_TICKS || !world.settled(bot)) {
            return null
        }
        val dest = frame.target ?: return BehaviourState.Failed(Reason.NoRoute)
        return if (bot.distanceTo(dest) <= ARRIVED_DISTANCE) {
            BehaviourState.Success
        } else {
            BehaviourState.Failed(Reason.NoRoute)
        }
    }

    private companion object {
        const val SETTLE_TICKS = 2
        const val ARRIVED_DISTANCE = 1
    }
}

class WaitTicks(private val min: Int, private val max: Int) : BotAction {
    override fun start(bot: BotAgent, world: BotWorld, frame: BehaviourFrame): BehaviourState =
        BehaviourState.Wait(Random.nextInt(min, max + 1), BehaviourState.Success)
}

/** Clicks the nearest loc of [group] and lets its skill script run for a random while. */
class InteractNearestLoc(
    private val group: String,
    private val radius: Int,
    private val minTicks: Int,
    private val maxTicks: Int,
) : BotAction {
    override fun start(bot: BotAgent, world: BotWorld, frame: BehaviourFrame): BehaviourState {
        val loc = world.findLoc(bot, group, radius) ?: return BehaviourState.Failed(Reason.NoTarget)
        world.interactLoc(bot, loc)
        frame.limit = Random.nextInt(minTicks, maxTicks + 1)
        return BehaviourState.Running
    }

    override fun update(bot: BotAgent, world: BotWorld, frame: BehaviourFrame): BehaviourState? {
        if (++frame.age < frame.limit) {
            return null
        }
        world.stop(bot)
        return BehaviourState.Success
    }
}

/** Stand-in for banking: bots just empty their inventory. */
object ClearInventory : BotAction {
    override fun start(bot: BotAgent, world: BotWorld, frame: BehaviourFrame): BehaviourState {
        world.clearInventory(bot)
        return BehaviourState.Success
    }
}

/** Hands the bot one [item] so the skill script finds the tool it needs. */
class GiveItem(private val item: String) : BotAction {
    override fun start(bot: BotAgent, world: BotWorld, frame: BehaviourFrame): BehaviourState {
        world.giveItem(bot, item)
        return BehaviourState.Success
    }
}

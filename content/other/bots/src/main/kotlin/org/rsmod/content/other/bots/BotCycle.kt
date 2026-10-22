package org.rsmod.content.other.bots

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.util.Wearpos
import net.rsprot.protocol.game.outgoing.info.Infos
import net.rsprot.protocol.game.outgoing.info.util.getOrThrow
import net.rsprot.protocol.game.outgoing.info.util.isEmpty
import net.rsprot.protocol.game.outgoing.info.util.safeReleaseOrThrow
import org.rsmod.api.net.rsprot.ext.setFaceTarget
import org.rsmod.game.client.ClientCycle
import org.rsmod.game.entity.Player
import org.rsmod.game.entity.util.EntityFaceAngle
import org.rsmod.game.movement.MoveSpeed
import org.rsmod.game.seq.EntitySeq
import org.rsmod.game.spot.EntitySpotanim
import org.rsmod.map.CoordGrid

/**
 * Session-less counterpart of the network `RspCycle`: keeps the bot's rsprot avatar in sync so other
 * players can see it, and consumes the info packets rsprot builds for it every cycle.
 */
class BotCycle(private val infos: Infos) : ClientCycle {
    private var knownCoords: CoordGrid = CoordGrid.ZERO
    private var knownCachedSpeed: MoveSpeed = MoveSpeed.Stationary
    private var knownFaceEntity: Int? = -1

    private val extendedInfo
        get() = infos.playerInfo.avatar.extendedInfo

    fun init(player: Player) {
        player.updateCoords()
    }

    override fun update(player: Player) {
        player.updateMoveSpeed()
        player.updateCoords()
        player.applyPublicMessage()
        player.applyFacePathingEntity()
        player.applyFaceAngle()
        player.applyAnim()
        player.applySpotanims()
        player.applySay()
        player.syncAppearance()
    }

    override fun flush(player: Player) = consumePackets()

    override fun release() = consumePackets()

    override fun forceSceneRebuild() {}

    private fun consumePackets() {
        val root = infos.getPackets().rootWorldInfoPackets
        root.worldEntityInfo.getOrThrow().safeRelease()
        root.playerInfo.getOrThrow().safeRelease()
        if (root.npcInfo.isEmpty()) {
            root.npcInfo.safeReleaseOrThrow()
        } else {
            root.npcInfo.getOrThrow().safeRelease()
        }
    }

    private fun Player.updateCoords() {
        infos.updateRootCoord(level, x, z)
        knownCoords = coords
    }

    private fun Player.updateMoveSpeed() {
        if (knownCachedSpeed != cachedMoveSpeed) {
            extendedInfo.setMoveSpeed(cachedMoveSpeed.steps)
            knownCachedSpeed = cachedMoveSpeed
        }
        val moveSpeed = resolvePendingMoveSpeed()
        if ((moveSpeed != cachedMoveSpeed || pendingTelejump) && coords != knownCoords) {
            extendedInfo.setTempMoveSpeed(moveSpeed.steps)
        }
    }

    private fun Player.resolvePendingMoveSpeed(): MoveSpeed =
        when {
            pendingTelejump -> MoveSpeed.Stationary
            pendingTeleport -> MoveSpeed.Walk
            pendingStepCount == 1 -> MoveSpeed.Walk
            pendingStepCount == 2 -> MoveSpeed.Run
            else -> moveSpeed
        }

    private fun Player.applyPublicMessage() {
        val message = publicMessage ?: return
        extendedInfo.setChat(
            colour = message.colour,
            effects = message.effect,
            modicon = message.modIcon,
            autotyper = message.autoTyper,
            text = message.text,
            pattern = message.pattern,
        )
        publicMessage = null
    }

    private fun Player.applyFacePathingEntity() {
        if (knownFaceEntity != faceEntity.entitySlot) {
            extendedInfo.setFaceTarget(faceEntity)
            knownFaceEntity = faceEntity.entitySlot
        }
    }

    private fun Player.applyFaceAngle() {
        if (pendingFaceAngle != EntityFaceAngle.NULL) {
            extendedInfo.setFaceAngle(pendingFaceAngle.intValue)
        }
    }

    private fun Player.applyAnim() {
        when (pendingSequence) {
            EntitySeq.NULL -> return
            EntitySeq.ZERO -> extendedInfo.setSequence(-1, 0)
            else -> extendedInfo.setSequence(pendingSequence.id, pendingSequence.delay)
        }
    }

    private fun Player.applySpotanims() {
        if (pendingSpotanims.isEmpty) {
            return
        }
        for (packed in pendingSpotanims.longIterator()) {
            val (id, delay, height, slot) = EntitySpotanim(packed)
            extendedInfo.setSpotAnim(slot, id, delay, height)
        }
    }

    private fun Player.applySay() {
        val text = pendingSay ?: return
        extendedInfo.setSay(text)
    }

    private fun Player.syncAppearance() {
        if (!appearance.rebuild) {
            return
        }
        val info = extendedInfo
        val colours = appearance.coloursSnapshot()
        for (i in colours.indices) {
            info.setColour(i, colours[i].toInt())
        }
        val identKit = appearance.identKitSnapshot()
        for (i in identKit.indices) {
            info.setIdentKit(i, identKit[i].toInt())
        }
        info.setName(displayName)
        info.setOverheadIcon(-1)
        info.setSkullIcon(-1)
        info.setCombatLevel(combatLevel)
        info.setBodyType(appearance.bodyType)
        info.setPronoun(appearance.pronoun)
        info.setHidden(false)
        info.setNameExtras("", "", "")
        val bas =
            ServerCacheManager.getBas("bas.human_default".asRSCM())
                ?: error("Unable to find Bas Human")
        info.setTransmogrification(-1)
        info.setBaseAnimationSet(
            readyAnim = bas.readyAnim,
            turnAnim = bas.turnOnSpot,
            walkAnim = bas.walkForward,
            walkAnimBack = bas.walkBack,
            walkAnimLeft = bas.walkLeft,
            walkAnimRight = bas.walkRight,
            runAnim = bas.running,
        )
        for (wearpos in Wearpos.visibleWearpos) {
            info.setWornObj(wearpos.slot, -1, -1, -1)
        }
    }
}

package org.rsmod.content.other.bots

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.toml.TomlMapper

/**
 * Builds [Behaviour]s from `behaviours.toml`. Each top-level table is one behaviour:
 * `actions` is an ordered list of `{ type = "...", ... }` entries, see [parseAction].
 */
object BehaviourLoader {
    private const val RESOURCE = "/behaviours.toml"

    fun load(): List<Behaviour> {
        val stream =
            BehaviourLoader::class.java.getResourceAsStream(RESOURCE)
                ?: error("Missing bot resource $RESOURCE")
        val root = stream.use { TomlMapper().readTree(it) }
        return root.fields().asSequence().map { (id, node) -> parseBehaviour(id, node) }.toList()
    }

    private fun parseBehaviour(id: String, node: JsonNode): Behaviour {
        val actions = node["actions"]?.map { parseAction(id, it) }
        require(!actions.isNullOrEmpty()) { "Bot behaviour '$id' has no actions" }
        return Behaviour(id, actions, timeout = node["timeout"]?.asInt() ?: 300)
    }

    private fun parseAction(id: String, node: JsonNode): BotAction =
        when (val type = node.text("type", id)) {
            "walk_near_home" -> WalkNearHome(node.int("radius", id))
            "wait" -> WaitTicks(node.int("min", id), node.int("max", id))
            "clear_inventory" -> ClearInventory
            "interact_loc" ->
                InteractNearestLoc(
                    group = node.text("group", id),
                    radius = node.int("radius", id),
                    minTicks = node.int("min", id),
                    maxTicks = node.int("max", id),
                )
            else -> error("Bot behaviour '$id' has unknown action type '$type'")
        }

    private fun JsonNode.text(field: String, id: String): String =
        this[field]?.asText() ?: error("Bot behaviour '$id': action is missing '$field'")

    private fun JsonNode.int(field: String, id: String): Int =
        this[field]?.takeIf { it.isInt }?.asInt()
            ?: error("Bot behaviour '$id': action is missing integer '$field'")
}

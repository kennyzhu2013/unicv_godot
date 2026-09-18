package com.unciv.godot

import com.unciv.logic.GameInfo
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques
import com.unciv.view.CityView
import com.unciv.view.GameView
import com.unciv.view.MapUnitView
import kotlinx.serialization.json.*

/** 只接受显式选出的基础值，绝不反射序列化 GameInfo 或 View。 */
internal fun value(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Iterable<*> -> JsonArray(value.map(::value))
    else -> error("不支持的 DTO 类型：${value.javaClass.name}")
}
internal fun dto(vararg entries: Pair<String, Any?>) = JsonObject(entries.associate { it.first to value(it.second) })
internal fun HexCoord.dto() = dto("x" to x, "y" to y)
internal fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""
internal fun JsonObject.integer(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: throw GatewayError("INVALID_ARGUMENT", "缺少整数参数：$key")

internal class PlayerSnapshot(private val game: GameInfo) {
    val view = GameView(game, game.currentPlayerCiv)
    private val civ = game.currentPlayerCiv

    fun build(): JsonObject = dto(
        "gameId" to game.gameId,
        "turn" to game.turns,
        "player" to civ.civID,
        "nation" to civ.civName,
        "gold" to civ.gold,
        "research" to view.civView.currentTechnologyName(),
        "worldWrap" to game.tileMap.mapParameters.worldWrap,
        "tiles" to game.tileMap.tileList.map { tile ->
            val t = view.getTile(tile)
            if (!t.isExplored()) dto("x" to tile.position.x, "y" to tile.position.y, "visibility" to "unknown")
            else dto(
                "x" to tile.position.x, "y" to tile.position.y,
                "visibility" to if (t.isVisible()) "visible" else "explored",
                "terrain" to t.baseTerrain, "features" to t.terrainFeatures,
                "resource" to t.getViewableResource(view.civView)?.name,
                "improvement" to t.getShownImprovement(),
                // 领土、道路的当前变化只在可见区域发送。
                "owner" to if (t.isVisible()) tile.getOwner()?.civID else null,
                "road" to if (t.isVisible()) t.roadStatus.name else null,
                "riverBottom" to t.hasBottomRiver,
                "riverLeft" to t.hasBottomLeftRiver,
                "riverRight" to t.hasBottomRightRiver
            )
        },
        "units" to game.tileMap.tileList.asSequence().map(view::getTile)
            .filter { it.isVisible() }.flatMap { it.getVisibleUnits().asSequence() }.map { unit ->
                val own = view.civView.isOwnerOf(unit)
                val pos = unit.getTile().position()
                dto("id" to unit.id, "name" to unit.name, "x" to pos.x, "y" to pos.y,
                    "own" to own, "civilian" to unit.isCivilian(), "health" to unit.unitHealth,
                    "movement" to if (own) unit.currentMovement else null,
                    "nation" to unit.civName,
                    "innerColor" to ("#" + unit.civ().getInnerColor().toString()),
                    "outerColor" to ("#" + unit.civ().getOuterColor().toString()))
            }.toList(),
        "cities" to game.civilizations.flatMap { it.cities }.filter {
            it.civ == civ || view.getTile(it.getCenterTile()).isVisible()
        }.map { city ->
            val own = city.civ == civ
            val cv = view.getCityView(city)
            dto("id" to city.id, "name" to cv.name, "x" to cv.location.x, "y" to cv.location.y,
                "own" to own,
                "population" to if (own) cv.getPopulationCount() else null,
                "production" to if (own) cv.currentConstructionName() else null,
                "queue" to if (own) cv.constructions.constructionQueue else null)
        },
        "technologies" to game.ruleset.technologies.keys.filter { civ.tech.canBeResearched(it) },
        "policies" to if (civ.policies.canAdoptPolicy())
            game.ruleset.policies.values.filter { civ.policies.isAdoptable(it) }.map { it.name } else emptyList<String>(),
        "pending" to pending(),
        "notifications" to civ.notifications.map { it.text }
    )

    fun pending(): List<JsonObject> = buildList {
        fun add(kind: String, message: String, supported: Boolean = true, target: String = "") {
            add(dto("kind" to kind, "message" to message, "supported" to supported, "target" to target))
        }
        for (city in view.civView.cities())
            if (!city.isPuppet() && city.currentConstructionName().isEmpty())
                add("production", "${city.name} 需要选择生产")
        if (view.civView.shouldOpenTechPicker()) add("research", "请选择科技")
        if (view.civView.shouldShowPolicyPicker()) add("policy", "请选择政策，或明确暂缓")
        if (civ.greatPeople.freeGreatPeople > 0) add("greatPerson", "免费伟人选择尚未接入，请保存后用原客户端处理", false)
        if (view.civView.canFoundPantheon() || view.civView.canExpandPantheon() || view.civView.isFoundingReligion()
            || view.civView.isEnhancingReligion() || view.civView.hasFreeBeliefs())
            add("religion", "宗教选择尚未接入，请保存后用原客户端处理", false)
        if (view.civView.mayVoteForDiplomaticVictory()) add("vote", "外交投票尚未接入", false)
        if (civ.tradeRequests.isNotEmpty()) add("trade", "有待处理交易，请保存后用原客户端处理", false)
        if (civ.popupAlerts.isNotEmpty()) {
            val alert = civ.popupAlerts.first()
            add("alert", "${alert.type}：${alert.value}", alert.type in GameSession.informationalAlerts)
        }
    }

    fun unitOptions(unit: MapUnitView): JsonObject {
        val raw = civ.units.getCivUnits().first { it.id == unit.id }
        val founding = UnitActionsFromUniques.getFoundCityAction(raw, raw.currentTile) { _, _ -> }
        return dto("unitId" to unit.id,
            "canFound" to (founding?.action != null),
            "foundReason" to if (founding?.action != null) "" else "此单位不能在当前地块建城，或行动力不足",
            "reachable" to if (!unit.hasMovement() || unit.cannotMove() || unit.isAirUnit() || unit.isPreparingParadrop()) emptyList<JsonObject>()
                else unit.getReachableTilesInCurrentTurn().filter { unit.canMoveTo(it) }.map { it.position().dto() })
    }

    fun cityOptions(city: CityView): JsonObject = dto(
        "name" to city.name,
        "queue" to city.constructions.constructionQueue,
        "constructions" to constructions().filter { city.constructions.shouldBeDisplayed(it) }.map { construction ->
            val needsTile = construction is Building && city.getImprovementToCreate(construction) != null
            val available = !city.isPuppet() && !needsTile && city.constructions.canAddToQueue(construction)
            dto("name" to construction.name, "enabled" to available,
                "reason" to when { needsTile -> "需要指定改良地块，首版尚未接入"; available -> ""; else -> "当前不可生产、已在队列中或队列已满" })
        }
    )

    fun constructions(): List<IConstruction> = game.ruleset.units.values + game.ruleset.buildings.values
}

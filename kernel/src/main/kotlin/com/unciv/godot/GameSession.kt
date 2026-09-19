package com.unciv.godot

import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.Building
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques
import com.unciv.view.CityView
import com.unciv.view.MapUnitView
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class GatewayError(val code: String, override val message: String) : RuntimeException(message)

/** 所有入口（包括查询）串行化；失败的动作丢弃工作副本，不暴露半完成的状态。 */
internal class GameSession(private val root: File) {
    val sessionId: String = UUID.randomUUID().toString()
    var revision = 0
        private set
    var game: GameInfo? = null
        private set
    private val responses = LinkedHashMap<String, Pair<JsonObject, JsonObject>>()
    private val saveDirectory = File(root, ".local/saves").apply { mkdirs() }

    @Synchronized
    fun handle(request: JsonObject): JsonObject {
        try {
            ensure(request.integer("protocol") == 1, "PROTOCOL", "不支持的协议版本")
            val action = request.text("action")
            if (action == "hello") return reply("data" to dto("saveDirectory" to saveDirectory.absolutePath))
            ensure(request.text("session") == sessionId, "SESSION", "会话已失效，请重新连接")
            if (action == "snapshot") return reply("snapshot" to game?.let { PlayerSnapshot(it).build() })
            val requestId = request.text("requestId")
            ensure(requestId.length in 1..100, "REQUEST_ID", "无效请求 ID")
            responses[requestId]?.let { (original, response) ->
                ensure(original == request, "REQUEST_REUSED", "同一请求 ID 不能使用不同参数")
                return response
            }
            ensure(request.integer("revision") == revision, "STALE_STATE", "状态已变化，请刷新后重试")
            if (action == "unitOptions" || action == "cityOptions") {
                val snapshot = PlayerSnapshot(requireGame())
                val data = if (action == "unitOptions") snapshot.unitOptions(unit(snapshot, request.integer("unitId")))
                    else snapshot.cityOptions(city(snapshot, request.text("cityId")))
                return reply("data" to data)
            }
            if (action == "path") {
                val snapshot = PlayerSnapshot(requireGame())
                val unit = unit(snapshot, request.integer("unitId"))
                val target = destination(snapshot, request)
                validateMove(unit, target)
                // 未探索路线仅包含坐标，不包含地形或隐藏对象。
                return reply("data" to dto("path" to unit.getPathToTile(target).map { it.position().dto() }))
            }

            val previous = game
            val candidate = when (action) {
                "load" -> load(File(request.text("path")))
                "demo" -> {
                    val demo = File(saveDirectory, "demo.json")
                    if (!demo.exists()) atomicSave(KernelRuntime.createDemo(), demo)
                    load(demo)
                }
                else -> requireGame().clone().also { it.setTransients() }
            }
            UncivGame.Current.gameInfo = candidate
            val result: JsonObject
            try {
                validateGame(candidate)
                val snapshot = PlayerSnapshot(candidate)
                val civ = candidate.currentPlayerCiv
                var savedPath: String? = null
                when (action) {
                    "load", "demo" -> Unit
                    "move" -> {
                        val unit = unit(snapshot, request.integer("unitId"))
                        val tile = destination(snapshot, request)
                        validateMove(unit, tile)
                        unit.tryResetAction()
                        ensure(unit.tryMoveToTile(tile), "MOVE_FAILED", "移动没有完成")
                    }
                    "foundCity" -> {
                        val unit = civ.units.getCivUnits().firstOrNull { it.id == request.integer("unitId") }
                            ?: throw GatewayError("NOT_OWNED", "找不到己方单位")
                        val actionToRun = UnitActionsFromUniques.getFoundCityAction(unit, unit.currentTile) { leaders, commit ->
                            ensure(request["confirmPromise"]?.jsonPrimitive?.booleanOrNull == true,
                                "CONFIRM_PROMISE", "这会违背对 $leaders 的承诺，是否继续？")
                            commit()
                        }?.action ?: throw GatewayError("CANNOT_FOUND", "此单位当前不能建城")
                        actionToRun()
                    }
                    "production" -> {
                        val city = city(snapshot, request.text("cityId"))
                        ensure(!city.isPuppet(), "PUPPET", "不能手动指定傀儡城市生产")
                        val construction = snapshot.constructions().firstOrNull { it.name == request.text("name") }
                            ?: throw GatewayError("CONSTRUCTION", "未知生产项目")
                        ensure(construction !is Building || city.getImprovementToCreate(construction) == null,
                            "UNSUPPORTED", "需要指定改良地块的项目尚未接入")
                        val existing = city.constructions.constructionQueue.indexOf(construction.name)
                        if (existing >= 0) city.tryMoveEntryToTop(existing)
                        else {
                            ensure(city.constructions.canAddToQueue(construction), "CANNOT_BUILD", "当前不能生产该项目")
                            city.tryAddToQueueConstruction(construction, addToTop = true)
                        }
                        city.updateCityStats()
                    }
                    "research" -> {
                        val name = request.text("name")
                        ensure(candidate.ruleset.technologies.containsKey(name) && civ.tech.canBeResearched(name),
                            "CANNOT_RESEARCH", "当前不能研究该科技")
                        if (civ.tech.freeTechs > 0) civ.tech.getFreeTechnology(name)
                        else civ.tech.techsToResearch = arrayListOf(name)
                    }
                    "policy" -> {
                        val policy = candidate.ruleset.policies[request.text("name")]
                            ?: throw GatewayError("POLICY", "未知政策")
                        ensure(civ.policies.canAdoptPolicy() && civ.policies.isAdoptable(policy), "CANNOT_ADOPT", "当前不能采用此政策")
                        civ.policies.adopt(policy)
                    }
                    "deferPolicy" -> snapshot.view.civView.tryDismissPolicyPicker()
                    "acknowledge" -> {
                        val alert = civ.popupAlerts.firstOrNull() ?: throw GatewayError("NO_ALERT", "没有待确认消息")
                        ensure(alert.type in informationalAlerts, "UNSUPPORTED", "此事件需要尚未接入的选择界面")
                        civ.popupAlerts.removeAt(0)
                    }
                    "nextTurn" -> {
                        val pending = snapshot.pending()
                        ensure(pending.isEmpty(), "PENDING_DECISION", pending.joinToString("；") { it.text("message") })
                        candidate.nextTurn()
                    }
                    "save" -> {
                        val name = request.text("name")
                        ensure(name.matches(Regex("[A-Za-z0-9_-]{1,80}")), "SAVE_NAME", "存档名只能包含字母、数字、下划线和连字符")
                        savedPath = File(saveDirectory, "$name.json").absolutePath
                    }
                    else -> throw GatewayError("UNKNOWN_COMMAND", "未实现命令：$action")
                }
                // 先验证快照可构造，再写盘和提交状态；不会破坏用户导入的原始存档。
                val playerSnapshot = PlayerSnapshot(candidate).build()
                if (savedPath != null) atomicSave(candidate, File(savedPath))
                game = candidate
                revision++
                result = reply("snapshot" to playerSnapshot, "savedPath" to savedPath)
            } catch (error: Exception) {
                UncivGame.Current.gameInfo = previous
                throw error
            }
            responses[requestId] = request to result
            if (responses.size > 32) responses.remove(responses.keys.first())
            return result
        } catch (error: GatewayError) {
            return error(error.code, error.message)
        } catch (error: Exception) {
            error.printStackTrace()
            return error("KERNEL_ERROR", "内核未完成操作：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun requireGame(): GameInfo = game ?: throw GatewayError("NO_GAME", "请先读取存档")
    private fun validateGame(game: GameInfo) {
        ensure(!game.gameParameters.isOnlineMultiplayer, "UNSUPPORTED_GAME", "首版只接入离线存档，不修改联机对局")
        ensure(game.civilizations.count { it.isHuman() } == 1 && game.currentPlayerCiv.isHuman()
            && !game.currentPlayerCiv.isSpectator() && !game.isSimulation(), "UNSUPPORTED_GAME", "首版需要单个人类玩家且轮到该玩家的存档")
        ensure(game.gameParameters.mods.isEmpty(), "UNSUPPORTED_MOD", "首版尚未验证 Mod，请使用基础规则存档")
    }
    private fun load(file: File): GameInfo {
        ensure(file.isFile && file.length() in 1..32L * 1024 * 1024, "SAVE_FILE", "存档不存在、为空或超过 32 MiB")
        return UncivFiles.gameInfoFromString(file.readText(Charsets.UTF_8))
    }
    private fun unit(snapshot: PlayerSnapshot, id: Int): MapUnitView = snapshot.view.civView.getUnits().firstOrNull { it.id == id }
        ?: throw GatewayError("NOT_OWNED", "找不到己方单位")
    private fun city(snapshot: PlayerSnapshot, id: String): CityView {
        return snapshot.view.civView.cities().firstOrNull { it.getCity().id == id }
            ?: throw GatewayError("NOT_OWNED", "找不到己方城市")
    }
    private fun destination(snapshot: PlayerSnapshot, request: JsonObject) =
        snapshot.view.tileMapView.getTile(HexCoord(request.integer("x"), request.integer("y")))
            ?: throw GatewayError("TARGET", "目标不在地图中")
    private fun validateMove(unit: MapUnitView, target: com.unciv.view.TileView) {
        ensure(!unit.isAirUnit() && !unit.isPreparingParadrop(), "UNSUPPORTED", "空军和空降移动尚未接入")
        ensure(unit.hasMovement() && !unit.cannotMove() && unit.canMoveTo(target)
            && unit.getReachableTilesInCurrentTurn().any { it.position() == target.position() },
            "CANNOT_MOVE", "当前回合不能移动到该地块")
    }
    private fun atomicSave(game: GameInfo, target: File) {
        val temporary = Files.createTempFile(saveDirectory.toPath(), "save-", ".tmp")
        try {
            Files.writeString(temporary, UncivFiles.gameInfoToString(game, true))
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
    private fun reply(vararg fields: Pair<String, Any?>) = dto("ok" to true, "protocol" to 1, "session" to sessionId, "revision" to revision, *fields)
    private fun error(code: String, message: String) = dto("ok" to false, "protocol" to 1, "session" to sessionId,
        "revision" to revision, "error" to dto("code" to code, "message" to message))
    private fun ensure(condition: Boolean, code: String, message: String) {
        if (!condition) throw GatewayError(code, message)
    }

    companion object {
        // 仅对白类提示允许“已阅”；有玩法选择的事件必须留给完整界面处理。
        val informationalAlerts = setOf(AlertType.StartIntro, AlertType.FirstContact, AlertType.TechResearched,
            AlertType.WonderBuilt, AlertType.GoldenAge)
    }
}

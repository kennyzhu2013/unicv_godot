package com.unciv.godot

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsFromUniques
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.UUID

class GameSessionTest {
    companion object {
        private lateinit var root: File
        private lateinit var fixture: File

        @BeforeClass @JvmStatic fun setup() {
            root = File(System.getProperty("unciv.root"))
            KernelRuntime.initialize(root)
            fixture = File(root, "godot/.local/tests/start.json")
            fixture.parentFile.mkdirs()
            fixture.writeText(UncivFiles.gameInfoToString(KernelRuntime.createDemo(), false))
        }
    }
    private fun request(session: GameSession, action: String, vararg params: Pair<String, Any?>) = dto(
        "protocol" to 1, "session" to session.sessionId, "revision" to session.revision,
        "requestId" to UUID.randomUUID().toString(), "action" to action, *params)
    private fun run(session: GameSession, action: String, vararg params: Pair<String, Any?>): JsonObject {
        val response = session.handle(request(session, action, *params))
        assertEquals(response.toString(), true, response["ok"]!!.jsonPrimitive.boolean)
        return response
    }
    private fun loadedSession(file: File = fixture) = GameSession(root).also {
        run(it, "load", "path" to file.absolutePath)
    }

    private fun <T> withNativeGame(game: GameInfo, operation: () -> T): T {
        val previous = UncivGame.Current.gameInfo
        UncivGame.Current.gameInfo = game
        return try { operation() } finally { UncivGame.Current.gameInfo = previous }
    }

    private fun promiseFixture(): File {
        val game = UncivFiles.gameInfoFromString(fixture.readText())
        withNativeGame(game) {
            val player = game.currentPlayerCiv
            val other = game.civilizations.single { it.isMajorCiv() && it.isAI() }
            val settlerTile = player.units.getCivUnits().first { it.name == "Settler" }.currentTile
            val otherCityTile = game.tileMap.tileList.first {
                it.aerialDistanceTo(settlerTile) == 5 && it.canBeSettled(other) && it.getUnits().none()
            }
            other.addCity(otherCityTile.position)
            if (!player.knows(other)) player.diplomacyFunctions.makeCivilizationsMeet(other)
            otherCityTile.setExplored(other, true)
            settlerTile.setExplored(other, true)
            // 承诺标记属于被承诺方；先建立外交关系，避免随后被相遇初始化覆盖。
            other.getDiplomacyManager(player)!!.setFlag(DiplomacyFlags.AgreedToNotSettleNearUs, 100)
            assertTrue("承诺地点仍符合普通建城规则", settlerTile.canBeSettled(player))
        }
        return File(root, "godot/.local/tests/settlement-promise.json").apply {
            writeText(UncivFiles.gameInfoToString(game, true))
        }
    }

    @Test fun settlementPromiseRequiresConfirmationWithoutMutation() {
        val session = loadedSession(promiseFixture())
        val original = session.game!!
        val settler = original.currentPlayerCiv.units.getCivUnits().first { it.name == "Settler" }
        val before = UncivFiles.gameInfoToString(original, false)
        val revision = session.revision
        val options = run(session, "unitOptions", "unitId" to settler.id)["data"]!!.jsonObject
        assertTrue(options["canFound"]!!.jsonPrimitive.boolean)
        val unconfirmed = request(session, "foundCity", "unitId" to settler.id)
        val rejected = session.handle(unconfirmed)
        assertFalse(rejected["ok"]!!.jsonPrimitive.boolean)
        assertEquals("CONFIRM_PROMISE", rejected["error"]!!.jsonObject.text("code"))
        assertEquals(rejected, session.handle(unconfirmed))
        val cancelled = session.handle(request(session, "foundCity", "unitId" to settler.id, "confirmPromise" to false))
        assertEquals("CONFIRM_PROMISE", cancelled["error"]!!.jsonObject.text("code"))
        // 前端取消时不再发命令；后续查询也不能提交之前被拒绝的建城。
        run(session, "snapshot")
        assertEquals(revision, session.revision)
        assertSame(original, session.game)
        assertSame(original, UncivGame.Current.gameInfo)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
        assertNull(Gdx.app)
        assertNull(UncivGame.Current.worldScreen)
    }

    @Test fun confirmedSettlementMatchesNativeActionAndIsIdempotent() {
        val file = promiseFixture()
        val native = UncivFiles.gameInfoFromString(file.readText())
        val session = loadedSession(file)
        val settler = native.currentPlayerCiv.units.getCivUnits().first { it.name == "Settler" }
        val position = settler.currentTile.position
        val other = native.civilizations.single { it.isMajorCiv() && it.isAI() }
        val before = UncivFiles.gameInfoToString(native, false)
        var confirmations = 0
        withNativeGame(native) {
            // 原动作取消回调不执行提交；确认回调才执行原建城闭包。
            UnitActionsFromUniques.getFoundCityAction(settler, settler.currentTile) { leaders, _ ->
                confirmations++
                assertTrue(leaders.contains(other.getLeaderDisplayName()))
            }!!.action!!()
            assertEquals(before, UncivFiles.gameInfoToString(native, false))
            UnitActionsFromUniques.getFoundCityAction(settler, settler.currentTile) { leaders, commit ->
                confirmations++
                assertTrue(leaders.contains(other.getLeaderDisplayName()))
                commit()
            }!!.action!!()
        }
        assertEquals(2, confirmations)
        val revision = session.revision
        val confirmed = request(session, "foundCity", "unitId" to settler.id, "confirmPromise" to true)
        val response = session.handle(confirmed)
        assertTrue(response.toString(), response["ok"]!!.jsonPrimitive.boolean)
        val committed = session.game!!
        assertGameplayEquals("承诺确认建城", native, committed)
        val city = committed.currentPlayerCiv.cities.single()
        assertEquals(position, city.location)
        assertTrue(city.isOriginalCapital)
        assertTrue(city.isCapital())
        assertFalse(committed.currentPlayerCiv.units.getCivUnits().any { it.id == settler.id })
        val diplomacy = committed.getCivilization(other.civID).getDiplomacyManager(committed.currentPlayerCiv)!!
        assertTrue("保留承诺，待原外交流程结算", diplomacy.hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs))
        assertTrue("记录附近建城事件", diplomacy.hasFlag(DiplomacyFlags.SettledCitiesNearUs))
        assertEquals(response, session.handle(confirmed))
        assertSame(committed, session.game)
        assertEquals(revision + 1, session.revision)
        val saved = run(session, "save", "name" to "test-promise-roundtrip")
        run(session, "load", "path" to saved.text("savedPath"))
        assertGameplayEquals("承诺建城后保存重载", native, session.game!!)
        assertNull(Gdx.app)
        assertNull(UncivGame.Current.worldScreen)
    }

    @Test fun sameSaveAndCommandsMatchNativeCoreForFifteenTurns() {
        var native = UncivFiles.gameInfoFromString(fixture.readText())
        val session = loadedSession()
        assertNotSame(native, session.game)
        assertGameplayEquals("同一初始存档", native, session.game!!)
        val warrior = native.currentPlayerCiv.units.getCivUnits().first { it.isMilitary() }
        val target = warrior.currentTile.neighbors.first { warrior.movement.canMoveTo(it) }.position
        pairedCommand(session, native, "move", "unitId" to warrior.id, "x" to target.x, "y" to target.y)
        val settler = native.currentPlayerCiv.units.getCivUnits().first { it.name == "Settler" }
        pairedCommand(session, native, "foundCity", "unitId" to settler.id)
        val cityId = native.currentPlayerCiv.cities.single().id
        pairedCommand(session, native, "production", "cityId" to cityId, "name" to "Warrior")
        pairedCommand(session, native, "production", "cityId" to cityId, "name" to "Monument")
        pairedCommand(session, native, "production", "cityId" to cityId, "name" to "Warrior")
        assertEquals(listOf("Warrior", "Monument"), native.currentPlayerCiv.cities.single().cityConstructions.constructionQueue)
        pairedCommand(session, native, "research", "name" to "Pottery")
        for (turn in 1..15) {
            resolveSupportedDecisions(session, native)
            pairedCommand(session, native, "nextTurn")
            assertEquals(turn, session.game!!.turns)
            if (turn % 5 == 0) {
                val saved = run(session, "save", "name" to "test-native-differential")
                assertGameplayEquals("第 $turn 回合保存", native, session.game!!)
                // 两端各自序列化、各自重载，不能将网关结果当作原生端的新基准。
                native = UncivFiles.gameInfoFromString(UncivFiles.gameInfoToString(native, true))
                run(session, "load", "path" to saved.text("savedPath"))
                assertGameplayEquals("第 $turn 回合独立重载", native, session.game!!)
            }
        }
        assertTrue(native.civilizations.any { it.isAI() && it.cities.isNotEmpty() })
        assertTrue(native.currentPlayerCiv.tech.techsResearched.contains("Pottery"))
        assertTrue(native.currentPlayerCiv.units.getCivUnits().count() > 1)
        assertNull(Gdx.app)
        assertNull(Gdx.gl)
        assertNull(Gdx.audio)
        println("原生／网关差分通过：移动、建城、生产队列、科研、决策、15 回合及独立保存重载")
    }

    private fun pairedCommand(session: GameSession, native: GameInfo, action: String, vararg params: Pair<String, Any?>) {
        val args = dto(*params)
        withNativeGame(native) {
            val civ = native.currentPlayerCiv
            when (action) {
                "move" -> {
                    val unit = civ.units.getCivUnits().first { it.id == args.integer("unitId") }
                    unit.action = null
                    unit.movement.moveToTile(native.tileMap[HexCoord(args.integer("x"), args.integer("y"))])
                }
                "foundCity" -> {
                    val unit = civ.units.getCivUnits().first { it.id == args.integer("unitId") }
                    UnitActionsFromUniques.getFoundCityAction(unit, unit.currentTile) { _, _ ->
                        fail("普通差分开局不应有建城承诺")
                    }!!.action!!()
                }
                "production" -> {
                    val city = civ.cities.first { it.id == args.text("cityId") }
                    val construction: com.unciv.models.ruleset.IConstruction =
                        native.ruleset.units[args.text("name")] ?: native.ruleset.buildings[args.text("name")]!!
                    val existing = city.cityConstructions.constructionQueue.indexOf(construction.name)
                    if (existing >= 0) city.cityConstructions.moveEntryToTop(existing)
                    else city.cityConstructions.addToQueue(construction, addToTop = true)
                    city.cityStats.update()
                }
                "research" -> {
                    if (civ.tech.freeTechs > 0) civ.tech.getFreeTechnology(args.text("name"))
                    else civ.tech.techsToResearch = arrayListOf(args.text("name"))
                }
                "policy" -> civ.policies.adopt(native.ruleset.policies[args.text("name")]!!)
                "acknowledge" -> civ.popupAlerts.removeAt(0)
                "nextTurn" -> native.nextTurn()
                else -> error("原生对照尚未定义操作：$action")
            }
        }
        run(session, action, *params)
        assertGameplayEquals("第 ${native.turns} 回合 $action $args", native, session.game!!)
    }

    @Test fun loadMoveFoundProduceTurnSaveReload() {
        val session = loadedSession()
        assertNull("无 LibGDX Application", Gdx.app)
        assertNull("无 GL 上下文", Gdx.gl)
        val initial = session.game!!
        val warrior = initial.currentPlayerCiv.units.getCivUnits().first { it.isMilitary() }
        val reachable = run(session, "unitOptions", "unitId" to warrior.id)["data"]!!.jsonObject["reachable"]!!.jsonArray
        val tile = reachable.map { it.jsonObject }.first { it.integer("x") != warrior.currentTile.position.x || it.integer("y") != warrior.currentTile.position.y }
        run(session, "move", "unitId" to warrior.id, "x" to tile.integer("x"), "y" to tile.integer("y"))
        val moved = session.game!!.currentPlayerCiv.units.getCivUnits().first { it.id == warrior.id }
        assertEquals(tile.integer("x"), moved.currentTile.position.x)
        assertTrue(moved.currentMovement < warrior.currentMovement)
        val settler = session.game!!.currentPlayerCiv.units.getCivUnits().first { it.name == "Settler" }
        run(session, "foundCity", "unitId" to settler.id)
        val city = session.game!!.currentPlayerCiv.cities.single()
        assertFalse(session.game!!.currentPlayerCiv.units.getCivUnits().any { it.id == settler.id })
        run(session, "production", "cityId" to city.id, "name" to "Warrior")
        run(session, "research", "name" to "Pottery")
        while (session.game!!.currentPlayerCiv.popupAlerts.isNotEmpty()) run(session, "acknowledge")
        val beforeTurn = session.game!!.turns
        run(session, "nextTurn")
        assertEquals(beforeTurn + 1, session.game!!.turns)
        assertTrue("AI 应按原规则建立城市", session.game!!.civilizations.any { it.isAI() && it.cities.isNotEmpty() })
        assertTrue("生产应累计", session.game!!.currentPlayerCiv.cities.single().cityConstructions.getWorkDone("Warrior") > 0)
        val saved = run(session, "save", "name" to "test-roundtrip")
        val path = File(saved.text("savedPath"))
        assertFalse("使用原压缩格式", path.readText().startsWith('{'))
        val nativeReload = UncivFiles.gameInfoFromString(path.readText())
        val before = gameplay(session.game!!)
        assertEquals(before, gameplay(nativeReload))
        run(session, "load", "path" to path.absolutePath)
        assertEquals(before, gameplay(session.game!!))
        println("闭环通过：原存档 → 移动 → 建城 → 生产/科研 → AI 回合 → 原格式保存重载")
    }

    @Test fun fifteenTurnsWithExplicitDecisionsAndPeriodicReload() {
        val session = loadedSession()
        val settler = session.game!!.currentPlayerCiv.units.getCivUnits().first { it.name == "Settler" }
        run(session, "foundCity", "unitId" to settler.id)
        for (expectedTurn in 1..15) {
            resolveSupportedDecisions(session)
            run(session, "nextTurn")
            assertEquals(expectedTurn, session.game!!.turns)
            assertNull("连续回合不创建 LibGDX Application", Gdx.app)
            assertNull("连续回合不创建音频上下文", Gdx.audio)
            if (expectedTurn % 5 == 0) {
                val before = gameplay(session.game!!)
                val saved = run(session, "save", "name" to "test-multiturn")
                run(session, "load", "path" to saved.text("savedPath"))
                assertEquals("第 $expectedTurn 回合重载保持状态", before, gameplay(session.game!!))
            }
        }
        assertTrue("AI 持续经营城市", session.game!!.civilizations.any { it.isAI() && it.cities.isNotEmpty() })
        assertTrue("生产已完成并生成单位", session.game!!.currentPlayerCiv.units.getCivUnits().count() > 1)
        println("连续 15 回合通过：显式处理决策，每 5 回合保存重载，无图形与音频上下文")
    }

    private fun resolveSupportedDecisions(session: GameSession, native: GameInfo? = null) {
        fun decide(action: String, vararg params: Pair<String, Any?>) {
            if (native == null) run(session, action, *params)
            else pairedCommand(session, native, action, *params)
        }
        repeat(40) {
            val snapshot = run(session, "snapshot")["snapshot"]!!.jsonObject
            val pending = snapshot["pending"]!!.jsonArray.map { it.jsonObject }
            if (pending.isEmpty()) return
            val decision = pending.first()
            assertTrue("测试遇到尚未接入的决策：$decision", decision["supported"]!!.jsonPrimitive.boolean)
            when (decision.text("kind")) {
                "production" -> {
                    val city = snapshot["cities"]!!.jsonArray.map { it.jsonObject }.first {
                        it["own"]!!.jsonPrimitive.boolean && it.text("production").isEmpty()
                    }
                    decide("production", "cityId" to city.text("id"), "name" to "Warrior")
                }
                "research" -> decide("research", "name" to snapshot["technologies"]!!.jsonArray.first().jsonPrimitive.content)
                "policy" -> decide("policy", "name" to snapshot["policies"]!!.jsonArray.first().jsonPrimitive.content)
                "alert" -> decide("acknowledge")
                else -> fail("未处理的决策：$decision")
            }
        }
        fail("决策未在有限次数内处理完成")
    }

    @Test fun duplicateStaleAndInvalidCommandsDoNotMutateGame() {
        val session = loadedSession()
        val save = request(session, "save", "name" to "test-dedup")
        val first = session.handle(save)
        assertTrue(first["ok"]!!.jsonPrimitive.boolean)
        assertEquals(first, session.handle(save))
        assertEquals("REQUEST_REUSED", session.handle(JsonObject(save + ("name" to JsonPrimitive("other"))))["error"]!!.jsonObject.text("code"))
        val stale = JsonObject(request(session, "nextTurn") + ("revision" to JsonPrimitive(0)))
        assertEquals("STALE_STATE", session.handle(stale)["error"]!!.jsonObject.text("code"))
        val before = UncivFiles.gameInfoToString(session.game!!, false)
        val version = session.revision
        val enemy = session.game!!.civilizations.first { it.isAI() && it.units.getCivUnits().any() }.units.getCivUnits().first()
        val response = session.handle(request(session, "move", "unitId" to enemy.id, "x" to 0, "y" to 0))
        assertEquals("NOT_OWNED", response["error"]!!.jsonObject.text("code"))
        assertFalse(session.handle(request(session, "save", "name" to "../outside"))["ok"]!!.jsonPrimitive.boolean)
        assertFalse(session.handle(request(session, "load", "path" to "missing-file"))["ok"]!!.jsonPrimitive.boolean)
        assertEquals(version, session.revision)
        assertEquals(before, UncivFiles.gameInfoToString(session.game!!, false))
    }

    @Test fun snapshotDoesNotExposeUnexploredTilesOrHiddenEnemies() {
        val session = loadedSession()
        val response = run(session, "snapshot")["snapshot"]!!.jsonObject
        val unknown = response["tiles"]!!.jsonArray.map { it.jsonObject }.filter { it.text("visibility") == "unknown" }
        assertTrue(unknown.isNotEmpty())
        assertTrue(unknown.all { it.keys == setOf("x", "y", "visibility") })
        val allowedUnits = session.game!!.tileMap.tileList.flatMap { it.getUnits().toList() }
            .filter { it.isVisibleTo(session.game!!.currentPlayerCiv) }.map { it.id }.toSet()
        assertTrue(response["units"]!!.jsonArray.all { it.jsonObject.integer("id") in allowedUnits })
        assertTrue(response["cities"]!!.jsonArray.isEmpty())
    }

    @Test fun turnIsBlockedUntilDecisionsAreResolved() {
        val session = loadedSession()
        val result = session.handle(request(session, "nextTurn"))
        assertEquals("PENDING_DECISION", result["error"]!!.jsonObject.text("code"))
        assertEquals(0, session.game!!.turns)
    }

    /** 比较原存档持久化状态（含 AI、科技、政策、外交、建筑、地块），仅排除实际计时。 */
    private fun gameplay(game: GameInfo): JsonObject {
        val state = Json.parseToJsonElement(UncivFiles.gameInfoToString(game, false)).jsonObject
        val civilizations = state["civilizations"]!!.jsonArray.map {
            JsonObject(it.jsonObject - "totalTurnTimeSeconds")
        }
        return JsonObject(state - "currentTurnStartTime" + ("civilizations" to JsonArray(civilizations)))
    }

    private fun assertGameplayEquals(step: String, expected: GameInfo, actual: GameInfo) {
        assertJsonEquals(step, gameplay(expected), gameplay(actual))
    }

    /** 首个差异给出具体字段路径，避免整份地图掩盖失败原因；保留队列和历史的顺序。 */
    private fun assertJsonEquals(path: String, expected: JsonElement, actual: JsonElement) {
        if (expected == actual) return
        when {
            expected is JsonObject && actual is JsonObject -> {
                assertEquals("$path 字段", expected.keys, actual.keys)
                for (key in expected.keys) assertJsonEquals("$path.$key", expected[key]!!, actual[key]!!)
            }
            expected is JsonArray && actual is JsonArray -> {
                assertEquals("$path 长度", expected.size, actual.size)
                for (index in expected.indices) assertJsonEquals("$path[$index]", expected[index], actual[index])
            }
            else -> assertEquals(path, expected, actual)
        }
    }
}

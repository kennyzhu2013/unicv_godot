package com.unciv.godot

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import java.io.File

/** 单进程仅初始化一个内核；不启动 LibGDX Application、音频或 GL 线程。 */
object KernelRuntime {
    fun initialize(root: File) {
        check(File(root, "project.godot").isFile) { "--root 必须指向包含 project.godot 的 Godot 工程目录" }
        check(File("jsons").isDirectory) { "工作目录必须为 Unciv-master/android/assets" }
        Gdx.files = HeadlessFiles()
        UncivGame.Current = UncivGame(true).apply {
            settings = GameSettings().apply {
                showTutorials = false
                autoAssignCityProduction = false
                automatedUnitsMoveOnTurnStart = true
            }
            files = UncivFiles(Gdx.files, File(root, ".local/data").absolutePath)
        }
        RulesetCache.loadRulesets(noMods = false)
    }

    /** 没有用户存档时提供原内核生成的开局，随后用原存档格式落盘再读取。 */
    fun createDemo(): GameInfo {
        val parameters = GameParameters().apply {
            players = arrayListOf(Player("Rome", PlayerType.Human), Player("Greece"))
            numberOfCityStates = 0
            noBarbarians = true
        }
        val map = MapParameters().apply {
            mapSize = MapSize.Tiny
            seed = 4602L
            noRuins = true
        }
        return GameStarter.startNewGame(GameSetupInfo(parameters, map))
    }
}

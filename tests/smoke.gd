extends Node

var app
var steps: Array[String] = []

func run(frontend) -> void:
	app = frontend
	get_tree().create_timer(150.0).timeout.connect(func(): _fail("端到端验证超时"))
	var expected_saves := ProjectSettings.globalize_path("res://.local/saves").simplify_path()
	var actual_saves: String = app.client.save_directory.replace("\\", "/").simplify_path()
	if OS.get_name() == "Windows":
		expected_saves = expected_saves.to_lower()
		actual_saves = actual_saves.to_lower()
	if not check(actual_saves == expected_saves, "内核与 Godot 使用同一工程存档目录"):
		return
	for asset in ["Images.Tilesets/TileSets/FantasyHex/Tiles/Grassland",
			"Images.AbsoluteUnits/TileSets/AbsoluteUnits/Units/Warrior",
			"Images.AbsoluteUnits/TileSets/AbsoluteUnits/Units/Warrior-1",
			"Images.AbsoluteUnits/TileSets/AbsoluteUnits/Units/Warrior-2"]:
		if not check(app.map.texture(asset) != null, "加载真实素材：" + asset):
			return
	steps.append("工程存档目录一致，地形与单位分层纹理加载成功")
	for x in range(-12, 13):
		for y in range(-12, 13):
			var coordinate := Vector2i(x, y)
			if not check(app.MapScript.pixel_to_hex(app.MapScript.hex_to_pixel(coordinate)) == coordinate, "六边形坐标往返"):
				return
	if not await perform("demo"):
		return
	await get_tree().process_frame
	if not check(app.map.tiles.size() > 0 and app.map.unit_nodes.size() > 0, "Godot 地图与单位节点"):
		return
	for node in app.map.unit_nodes.values():
		if not check(node.get_children().any(func(child): return child is Sprite2D and child.texture != null), "单位使用真实纹理而非文字占位"):
			return
	steps.append("读取原内核生成的存档并显示地图")
	var warrior: Dictionary = {}
	var settler: Dictionary = {}
	for unit in app.client.snapshot.units:
		if unit.own and unit.name == "Warrior":
			warrior = unit
		if unit.own and unit.name == "Settler":
			settler = unit
	if not check(not warrior.is_empty() and not settler.is_empty(), "验证开局单位"):
		return
	await app._select_unit(int(warrior.id))
	var destination: Dictionary = {}
	for coordinate: Vector2i in app.map.reachable:
		if coordinate != Vector2i(int(warrior.x), int(warrior.y)):
			destination = {"x": coordinate.x, "y": coordinate.y}
			break
	if not check(not destination.is_empty(), "可达地块"):
		return
	await app._preview_move(destination)
	if not check(not app.target.is_empty(), "路线预览"):
		return
	await app._commit_move()
	var moved := false
	for unit in app.client.snapshot.units:
		if int(unit.id) == int(warrior.id):
			moved = unit.x == destination.x and unit.y == destination.y and unit.movement < warrior.movement
	if not check(moved, "单位移动及行动力消耗"):
		return
	steps.append("通过前端选择、路线预览及确认移动")
	if not await perform("foundCity", {"unitId": int(settler.id)}):
		return
	var cities: Array = app.client.snapshot.cities.filter(func(city): return city.own)
	if not check(cities.size() == 1, "建城结果"):
		return
	steps.append("建城并消耗开拓者")
	await app._select_city(cities[0].id)
	if not check(app.production_picker.item_count > 0, "生产界面候选项"):
		return
	if not await perform("production", {"cityId": cities[0].id, "name": "Warrior"}):
		return
	if not await perform("research", {"name": "Pottery"}):
		return
	steps.append("选择城市生产与科研")
	for choice in app.client.snapshot.pending.duplicate():
		if choice.kind == "alert" and choice.supported:
			if not await perform("acknowledge"):
				return
	var turn := int(app.client.snapshot.turn)
	if not await perform("nextTurn"):
		return
	if not check(int(app.client.snapshot.turn) == turn + 1, "AI 回合推进"):
		return
	steps.append("原 Kotlin AI 与回合结算")
	if not await perform("save", {"name": "smoke-roundtrip"}):
		return
	var saved_directory: String = app.last_saved_path.replace("\\", "/").get_base_dir().simplify_path()
	if OS.get_name() == "Windows":
		saved_directory = saved_directory.to_lower()
	if not check(saved_directory == expected_saves, "保存副本落在工程 .local/saves 中"):
		return
	var before: Dictionary = app.client.snapshot.duplicate(true)
	if not await perform("load", {"path": app.last_saved_path}):
		return
	var after: Dictionary = app.client.snapshot
	for key in ["gameId", "turn", "player", "gold", "research", "cities", "units"]:
		if not check(before[key] == after[key], "重载一致性：" + key):
			return
	steps.append("原格式保存与重载，关键状态一致")
	if not await verify_promise_dialog():
		return
	if not await perform("load", {"path": app.last_saved_path}):
		return
	await get_tree().process_frame
	await get_tree().process_frame
	var directory := ProjectSettings.globalize_path("res://.local")
	DirAccess.make_dir_recursive_absolute(directory)
	if DisplayServer.get_name() != "headless":
		await RenderingServer.frame_post_draw
		get_viewport().get_texture().get_image().save_png(directory.path_join("smoke-map.png"))
	var report := FileAccess.open(directory.path_join("smoke-result.json"), FileAccess.WRITE)
	report.store_string(JSON.stringify({"ok": true, "steps": steps,
		"tiles": app.map.tiles.size(), "units": app.map.unit_nodes.size(),
		"turn": after.turn, "savedPath": app.last_saved_path,
		"renderBackend": DisplayServer.get_name()}, "\t"))
	report.close()
	print("闭环验证通过：", " → ".join(steps))
	get_tree().quit(0)

func verify_promise_dialog() -> bool:
	var fixture := ProjectSettings.globalize_path("res://.local/tests/settlement-promise.json")
	if not check(FileAccess.file_exists(fixture), "缺少承诺场景存档，请先运行 :godot-kernel:test --rerun"):
		return false
	if not await perform("load", {"path": fixture}):
		return false
	var settlers: Array = app.client.snapshot.units.filter(func(unit): return unit.own and unit.name == "Settler")
	if not check(settlers.size() == 1, "承诺场景开拓者"):
		return false
	var settler: Dictionary = settlers[0]
	var before: Dictionary = app.client.snapshot.duplicate(true)
	var revision: int = app.client.revision
	# 连续取消后再确认，验证旧信号连接不会重复提交建城。
	for attempt in range(3):
		await app._select_unit(int(settler.id))
		var result: Dictionary = await app.execute("foundCity", {"unitId": int(settler.id)})
		if not check(result.get("error", {}).get("code") == "CONFIRM_PROMISE", "建城需要承诺确认"):
			return false
		if not check(app.confirmation.visible, "Godot 显示承诺确认对话框"):
			return false
		if not check(app.client.revision == revision and app.client.snapshot == before, "确认前状态保持不变"):
			return false
		if attempt < 2:
			app.confirmation.get_cancel_button().pressed.emit()
			await get_tree().process_frame
			if not check(not app.confirmation.visible and not app.client.busy, "取消关闭对话框且不提交命令"):
				return false
			if not await perform("snapshot"):
				return false
			if not check(app.client.revision == revision and app.client.snapshot == before, "取消后内核状态保持不变"):
				return false
		else:
			app.confirmation.get_ok_button().pressed.emit()
			while app.client.busy:
				await get_tree().process_frame
		if not check(not app.confirmation.visible, "承诺对话框已关闭"):
			return false
	var cities: Array = app.client.snapshot.cities.filter(func(city): return city.own)
	if not check(cities.size() == 1 and cities[0].x == settler.x and cities[0].y == settler.y, "确认后原地点建立一座城市"):
		return false
	if not check(app.client.snapshot.units.all(func(unit): return int(unit.id) != int(settler.id)), "确认后消耗开拓者"):
		return false
	if not check(app.client.revision == revision + 1, "多次取消后确认只提交一次"):
		return false
	steps.append("承诺对话框连续取消不改状态，确认后只建城一次")
	return true

func perform(action: String, params: Dictionary = {}) -> bool:
	var result: Dictionary = await app.execute(action, params)
	if not result.get("ok", false):
		_fail(action + "：" + str(result.get("error")))
		return false
	return true

func check(condition: bool, message: String) -> bool:
	if not condition:
		_fail(message)
	return condition

func _fail(message: String) -> void:
	push_error("闭环验证失败：" + message)
	get_tree().quit(1)

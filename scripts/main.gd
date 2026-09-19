extends Control

const ClientScript = preload("res://scripts/kernel_client.gd")
const MapScript = preload("res://scripts/hex_map.gd")
var client = ClientScript.new()
var map = MapScript.new()
var top_status := Label.new()
var message := Label.new()
var details := Label.new()
var pending := Label.new()
var map_area := Control.new()
var unit_picker := OptionButton.new()
var city_picker := OptionButton.new()
var production_picker := OptionButton.new()
var research_picker := OptionButton.new()
var policy_picker := OptionButton.new()
var file_dialog := FileDialog.new()
var save_name := LineEdit.new()
var unit_id := -1
var city_id := ""
var target: Dictionary = {}
var last_saved_path := ""
var current_game := ""
var buttons: Array[Button] = []
var founding_button: Button
var move_button: Button
var turn_button: Button
var production_button: Button
var confirmation := ConfirmationDialog.new()

func _ready() -> void:
	var system_font := SystemFont.new()
	system_font.font_names = PackedStringArray(["Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC"])
	theme = Theme.new()
	theme.default_font = system_font
	theme.default_font_size = 16
	map.font = system_font
	_build_ui()
	add_child(client)
	client.snapshot_changed.connect(_apply_snapshot)
	client.busy_changed.connect(_on_busy)
	map.tile_selected.connect(_select_tile)
	map.move_requested.connect(_preview_move)
	var hello: Dictionary = await execute("hello")
	if not hello.get("ok", false):
		if "--smoke" in OS.get_cmdline_user_args():
			get_tree().quit(1)
		return
	message.text = "内核已连接。读取现有单人存档，或打开验证开局。"
	if "--smoke" in OS.get_cmdline_user_args():
		var smoke = load("res://tests/smoke.gd").new()
		add_child(smoke)
		await smoke.run(self)
	elif not OS.get_environment("UNCIV_INITIAL_SAVE").is_empty():
		await execute("load", {"path": OS.get_environment("UNCIV_INITIAL_SAVE")})

func _build_ui() -> void:
	var margin := MarginContainer.new()
	margin.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	for side in ["left", "top", "right", "bottom"]:
		margin.add_theme_constant_override("margin_" + side, 14)
	add_child(margin)
	var layout := VBoxContainer.new()
	layout.add_theme_constant_override("separation", 12)
	margin.add_child(layout)
	var header := HBoxContainer.new()
	layout.add_child(header)
	var title := Label.new()
	title.text = "UNCIV / GODOT"
	title.add_theme_font_size_override("font_size", 24)
	header.add_child(title)
	top_status.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	top_status.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	top_status.text = "第一阶段 · Kotlin 规则内核"
	header.add_child(top_status)
	var toolbar := HBoxContainer.new()
	layout.add_child(toolbar)
	button(toolbar, "读取存档", func(): file_dialog.popup_centered_ratio(0.75))
	button(toolbar, "验证开局", func(): await execute("demo"))
	save_name.text = "godot-save"
	save_name.custom_minimum_size.x = 160
	toolbar.add_child(save_name)
	button(toolbar, "保存副本", _save)
	button(toolbar, "重载副本", _reload)
	button(toolbar, "刷新", func(): await execute("snapshot"))
	button(toolbar, "定位", func(): map.center_on_player())
	var body := HBoxContainer.new()
	body.size_flags_vertical = Control.SIZE_EXPAND_FILL
	body.add_theme_constant_override("separation", 14)
	layout.add_child(body)
	map_area.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	map_area.size_flags_vertical = Control.SIZE_EXPAND_FILL
	map_area.clip_contents = true
	map_area.gui_input.connect(func(event): map.handle_input(event))
	map_area.resized.connect(func(): map.viewport_size = map_area.size)
	body.add_child(map_area)
	map_area.add_child(map)
	var scroll := ScrollContainer.new()
	scroll.custom_minimum_size.x = 310
	body.add_child(scroll)
	var panel := VBoxContainer.new()
	panel.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	panel.add_theme_constant_override("separation", 9)
	scroll.add_child(panel)
	label(panel, "单位 / 地块")
	details.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	details.text = "左键选格；右键预览移动\n中键拖动；滚轮缩放"
	panel.add_child(details)
	panel.add_child(unit_picker)
	unit_picker.item_selected.connect(func(index): await _select_unit(int(unit_picker.get_item_metadata(index))))
	move_button = button(panel, "确认移动", _commit_move)
	founding_button = button(panel, "在此建城", func(): await execute("foundCity", {"unitId": unit_id}))
	label(panel, "城市生产")
	panel.add_child(city_picker)
	city_picker.item_selected.connect(func(index): await _select_city(str(city_picker.get_item_metadata(index))))
	panel.add_child(production_picker)
	production_button = button(panel, "设为当前生产", func():
		if production_picker.selected >= 0:
			await execute("production", {"cityId": city_id, "name": production_picker.get_item_metadata(production_picker.selected)}))
	label(panel, "科研")
	panel.add_child(research_picker)
	button(panel, "选择科技", func():
		if research_picker.selected >= 0:
			await execute("research", {"name": research_picker.get_item_text(research_picker.selected)}))
	label(panel, "政策")
	panel.add_child(policy_picker)
	button(panel, "采用政策", func():
		if policy_picker.selected >= 0:
			await execute("policy", {"name": policy_picker.get_item_text(policy_picker.selected)}))
	button(panel, "暂缓政策", func(): await execute("deferPolicy"))
	label(panel, "待处理事项")
	pending.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	panel.add_child(pending)
	button(panel, "消息已阅", func(): await execute("acknowledge"))
	turn_button = button(panel, "结束回合 · AI 行动", func(): await execute("nextTurn"))
	message.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	message.custom_minimum_size.y = 44
	message.text = "连接本地内核……"
	layout.add_child(message)
	var note := Label.new()
	note.text = "验证版：基础规则、离线单人；战斗与完整外交界面尚未接入。原始存档不会被覆盖。"
	note.add_theme_color_override("font_color", Color("879dad"))
	note.add_theme_font_size_override("font_size", 13)
	layout.add_child(note)
	file_dialog.file_mode = FileDialog.FILE_MODE_OPEN_FILE
	file_dialog.access = FileDialog.ACCESS_FILESYSTEM
	file_dialog.title = "选择 Unciv 存档（JSON 或压缩存档，无扩展名也可）"
	file_dialog.file_selected.connect(func(path): await execute("load", {"path": path}))
	add_child(file_dialog)
	add_child(confirmation)
	_on_busy(false)

func button(parent: Node, text: String, action: Callable) -> Button:
	var result := Button.new()
	result.text = text
	result.custom_minimum_size.y = 34
	result.pressed.connect(action)
	parent.add_child(result)
	buttons.append(result)
	return result

func label(parent: Node, text: String) -> void:
	var result := Label.new()
	result.text = text
	result.add_theme_color_override("font_color", Color("d4b779"))
	parent.add_child(result)

func _on_busy(is_busy: bool) -> void:
	for control in buttons:
		control.disabled = is_busy
	for picker in [unit_picker, city_picker, production_picker, research_picker, policy_picker]:
		picker.disabled = is_busy or picker.item_count == 0
	if not is_busy:
		move_button.disabled = target.is_empty() or unit_id < 0
		founding_button.disabled = unit_id < 0 or not founding_button.has_meta("allowed") or not founding_button.get_meta("allowed")
		production_button.disabled = city_id.is_empty()
		turn_button.disabled = client.snapshot.is_empty() or not client.snapshot.get("pending", []).is_empty()
	else:
		message.text = "内核处理中……界面仍可拖动和缩放"

func execute(action: String, params: Dictionary = {}) -> Dictionary:
	var result: Dictionary = await client.command(action, params)
	if not result.get("ok", false):
		var failure: Dictionary = result.get("error", {})
		message.text = failure.get("message", "请求失败")
		if failure.get("code") == "CONFIRM_PROMISE":
			confirmation.dialog_text = message.text
			confirmation.popup_centered()
			if confirmation.confirmed.is_connected(_confirm_founding):
				confirmation.confirmed.disconnect(_confirm_founding)
			confirmation.confirmed.connect(_confirm_founding, CONNECT_ONE_SHOT)
		if failure.get("code") == "STALE_STATE":
			await client.command("snapshot")
	else:
		message.text = "操作完成 · 状态版本 %s" % client.revision
		if result.get("savedPath"):
			last_saved_path = result.savedPath
			message.text = "已保存副本：" + last_saved_path
	return result

func _confirm_founding() -> void:
	await execute("foundCity", {"unitId": unit_id, "confirmPromise": true})

func _apply_snapshot(data: Dictionary) -> void:
	var changed: bool = current_game != data.gameId
	current_game = data.gameId
	map.set_snapshot(data, changed)
	top_status.text = "%s  /  第 %s 回合  /  金币 %s  /  科研 %s" % [data.nation, data.turn, data.gold, data.get("research", "—")]
	unit_id = -1
	city_id = ""
	target.clear()
	unit_picker.clear()
	city_picker.clear()
	production_picker.clear()
	for unit in data.units:
		if unit.own:
			unit_picker.add_item("%s #%s · 行动力 %.1f" % [unit.name, unit.id, unit.movement])
			unit_picker.set_item_metadata(unit_picker.item_count - 1, int(unit.id))
	unit_picker.select(-1)
	for city in data.cities:
		if city.own:
			city_picker.add_item("%s · %s" % [city.name, city.production])
			city_picker.set_item_metadata(city_picker.item_count - 1, city.id)
	city_picker.select(-1)
	research_picker.clear()
	for tech in data.technologies:
		research_picker.add_item(tech)
	policy_picker.clear()
	for policy in data.policies:
		policy_picker.add_item(policy)
	var lines := PackedStringArray()
	for choice in data.pending:
		lines.append(("" if choice.supported else "[未接入] ") + str(choice.message))
	pending.text = "\n".join(lines) if not lines.is_empty() else "无强制选择；可以结束回合。"
	_on_busy(client.busy)

func _select_tile(tile: Dictionary) -> void:
	if client.busy:
		return
	details.text = "(%s, %s) · %s\n资源：%s\n改良：%s" % [tile.x, tile.y, tile.get("terrain", "未探索"), tile.get("resource", "—"), tile.get("improvement", "—")]
	for unit in client.snapshot.get("units", []):
		if unit.own and unit.x == tile.x and unit.y == tile.y:
			await _select_unit(int(unit.id))
			break
	for city in client.snapshot.get("cities", []):
		if city.own and city.x == tile.x and city.y == tile.y:
			await _select_city(city.id)
			break

func _select_unit(id: int) -> void:
	if client.busy:
		return
	unit_id = id
	target.clear()
	var result: Dictionary = await execute("unitOptions", {"unitId": id})
	if result.get("ok", false):
		map.set_reachable(result.data)
		founding_button.set_meta("allowed", result.data.canFound)
		founding_button.tooltip_text = result.data.foundReason
		for unit in client.snapshot.units:
			if int(unit.id) == id:
				map.selected = Vector2i(int(unit.x), int(unit.y))
				map.queue_redraw()
				break
	_on_busy(false)

func _select_city(id: String) -> void:
	if client.busy:
		return
	city_id = id
	var result: Dictionary = await execute("cityOptions", {"cityId": id})
	production_picker.clear()
	if result.get("ok", false):
		for construction in result.data.constructions:
			production_picker.add_item(construction.name)
			var index := production_picker.item_count - 1
			production_picker.set_item_metadata(index, construction.name)
			production_picker.set_item_disabled(index, not construction.enabled)
			production_picker.set_item_tooltip(index, construction.reason)
		for index in range(production_picker.item_count):
			if not production_picker.is_item_disabled(index):
				production_picker.select(index)
				break
	_on_busy(false)

func _preview_move(tile: Dictionary) -> void:
	if client.busy or unit_id < 0:
		return
	var result: Dictionary = await execute("path", {"unitId": unit_id, "x": int(tile.x), "y": int(tile.y)})
	if result.get("ok", false):
		target = {"x": int(tile.x), "y": int(tile.y)}
		map.route = result.data.path
		map.queue_redraw()
		message.text = "路线由 Kotlin 内核计算；点击「确认移动」执行。"
	_on_busy(false)

func _commit_move() -> void:
	if target.is_empty() or unit_id < 0:
		return
	var parameters := target.duplicate()
	parameters.unitId = unit_id
	await execute("move", parameters)

func _save() -> void:
	await execute("save", {"name": save_name.text})

func _reload() -> void:
	if last_saved_path.is_empty():
		message.text = "请先保存一个副本。"
		return
	await execute("load", {"path": last_saved_path})

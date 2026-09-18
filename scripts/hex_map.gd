extends Node2D

signal tile_selected(tile: Dictionary)
signal move_requested(tile: Dictionary)

const RADIUS := 38.0
const TERRAIN_COLORS := {"Grassland": Color("6e9b48"), "Plains": Color("b6a369"),
	"Desert": Color("d6bd7c"), "Coast": Color("38798c"), "Ocean": Color("234d70"),
	"Tundra": Color("929882"), "Snow": Color("d4e0de"), "Lakes": Color("427d95"),
	"Mountain": Color("777b78")}
var tiles: Dictionary = {}
var snapshot: Dictionary = {}
var textures: Dictionary = {}
var unit_nodes: Dictionary = {}
var reachable: Dictionary = {}
var route: Array = []
var selected := Vector2i(99999, 99999)
var viewport_size := Vector2(900, 670)
var font: Font = ThemeDB.fallback_font
var asset_root := ProjectSettings.globalize_path("res://../android/")

static func hex_to_pixel(hex: Vector2i) -> Vector2:
	return Vector2(1.5 * RADIUS * (hex.y - hex.x), -sqrt(3.0) * 0.5 * RADIUS * (hex.x + hex.y))

static func pixel_to_hex(point: Vector2) -> Vector2i:
	var difference := point.x / (1.5 * RADIUS)
	var total := -point.y / (sqrt(3.0) * 0.5 * RADIUS)
	var approx := Vector2((total - difference) * 0.5, (total + difference) * 0.5)
	var best := Vector2i(roundi(approx.x), roundi(approx.y))
	var distance := INF
	# 检查附近的中心，覆盖负坐标和六边形边缘，不依赖物理碰撞体。
	for x in range(floori(approx.x) - 1, ceili(approx.x) + 2):
		for y in range(floori(approx.y) - 1, ceili(approx.y) + 2):
			var candidate := Vector2i(x, y)
			var d := hex_to_pixel(candidate).distance_squared_to(point)
			if d < distance:
				distance = d
				best = candidate
	return best

func set_snapshot(data: Dictionary, focus := false) -> void:
	snapshot = data
	tiles.clear()
	for tile in data.get("tiles", []):
		tiles[Vector2i(int(tile.x), int(tile.y))] = tile
	reachable.clear()
	route.clear()
	_update_units()
	if focus:
		center_on_player()
	queue_redraw()

func center_on_player() -> void:
	for unit in snapshot.get("units", []):
		if unit.own:
			position = viewport_size * 0.5 - hex_to_pixel(Vector2i(int(unit.x), int(unit.y))) * scale.x
			return
	for city in snapshot.get("cities", []):
		if city.own:
			position = viewport_size * 0.5 - hex_to_pixel(Vector2i(int(city.x), int(city.y))) * scale.x
			return

func handle_input(event: InputEvent) -> void:
	if event is InputEventMouseMotion and event.button_mask & MOUSE_BUTTON_MASK_MIDDLE:
		position += event.relative
	if not event is InputEventMouseButton or not event.pressed:
		return
	if event.button_index in [MOUSE_BUTTON_WHEEL_UP, MOUSE_BUTTON_WHEEL_DOWN]:
		var local_point: Vector2 = (event.position - position) / scale.x
		var factor := 1.12 if event.button_index == MOUSE_BUTTON_WHEEL_UP else 1.0 / 1.12
		scale = Vector2.ONE * clampf(scale.x * factor, 0.35, 2.8)
		position = event.position - local_point * scale.x
		return
	var cell := pixel_to_hex((event.position - position) / scale.x)
	if not tiles.has(cell):
		return
	if event.button_index == MOUSE_BUTTON_LEFT:
		selected = cell
		tile_selected.emit(tiles[cell])
		queue_redraw()
	elif event.button_index == MOUSE_BUTTON_RIGHT:
		move_requested.emit(tiles[cell])

func set_reachable(options: Dictionary) -> void:
	reachable.clear()
	for tile in options.get("reachable", []):
		reachable[Vector2i(int(tile.x), int(tile.y))] = true
	queue_redraw()

func texture(relative: String) -> Texture2D:
	if textures.has(relative):
		return textures[relative]
	var path := asset_root.path_join(relative + ".png")
	var result: Texture2D = null
	if FileAccess.file_exists(path):
		var image := Image.load_from_file(path)
		if image != null:
			result = ImageTexture.create_from_image(image)
	textures[relative] = result
	return result

func _polygon(center: Vector2, radius := RADIUS) -> PackedVector2Array:
	var points := PackedVector2Array()
	for i in range(6):
		points.append(center + Vector2.from_angle(i * PI / 3.0) * radius)
	return points

func _draw() -> void:
	# 全地图先绘地形，再绘地貌和标记；只有快照或选择变化才重新绘制。
	for coord: Vector2i in tiles:
		var tile: Dictionary = tiles[coord]
		var center := hex_to_pixel(coord)
		if tile.visibility == "unknown":
			draw_colored_polygon(_polygon(center, RADIUS - 0.5), Color("111e2b"))
			continue
		var color: Color = TERRAIN_COLORS.get(tile.terrain, Color("858577"))
		draw_colored_polygon(_polygon(center), color)
		var art := texture("Images.Tilesets/TileSets/FantasyHex/Tiles/" + tile.terrain)
		if art:
			draw_texture_rect(art, Rect2(center - Vector2(RADIUS, sqrt(3.0) * RADIUS / 2.0), Vector2(RADIUS * 2.0, sqrt(3.0) * RADIUS)), false)
	for coord: Vector2i in tiles:
		var tile: Dictionary = tiles[coord]
		if tile.visibility == "unknown":
			continue
		var center := hex_to_pixel(coord)
		for feature in tile.get("features", []):
			var art := texture("Images.Tilesets/TileSets/FantasyHex/Tiles/" + str(feature))
			if art:
				draw_texture_rect(art, Rect2(center - Vector2(RADIUS, RADIUS), Vector2.ONE * RADIUS * 2.0), false)
			else:
				draw_string(font, center + Vector2(-22, 0), str(feature).substr(0, 3), HORIZONTAL_ALIGNMENT_LEFT, -1, 11, Color.WHITE)
		var polygon := _polygon(center)
		if tile.get("riverBottom", false):
			draw_line(polygon[1], polygon[2], Color("66bfd1"), 3)
		if tile.get("riverLeft", false):
			draw_line(polygon[2], polygon[3], Color("66bfd1"), 3)
		if tile.get("riverRight", false):
			draw_line(polygon[0], polygon[1], Color("66bfd1"), 3)
		if tile.get("resource"):
			draw_circle(center + Vector2(18, 10), 5, Color("e0c272"))
		if tile.get("improvement"):
			draw_rect(Rect2(center + Vector2(-22, 6), Vector2(8, 8)), Color("d5d7bb"))
		if tile.visibility == "explored":
			draw_colored_polygon(polygon, Color(0.02, 0.05, 0.09, 0.6))
		if tile.get("owner") == snapshot.get("player"):
			polygon.append(polygon[0])
			draw_polyline(polygon, Color(0.85, 0.6, 0.25, 0.65), 1.5)
	for coord: Vector2i in reachable:
		draw_arc(hex_to_pixel(coord), RADIUS * 0.65, 0, TAU, 20, Color(0.4, 0.85, 0.85, 0.8), 1.5)
	if route.size() > 1:
		var points := PackedVector2Array()
		for point in route:
			points.append(hex_to_pixel(Vector2i(int(point.x), int(point.y))))
		draw_polyline(points, Color("fff0ad"), 4)
	if tiles.has(selected):
		var points := _polygon(hex_to_pixel(selected), RADIUS - 2)
		points.append(points[0])
		draw_polyline(points, Color("fff0ad"), 3)
	for city in snapshot.get("cities", []):
		var center := hex_to_pixel(Vector2i(int(city.x), int(city.y)))
		draw_rect(Rect2(center + Vector2(-8, -6), Vector2(16, 16)), Color("e5d7b2"))
		draw_string(font, center + Vector2(-28, 29), city.name, HORIZONTAL_ALIGNMENT_LEFT, -1, 14, Color("fff1c4"))

func _update_units() -> void:
	var active: Dictionary = {}
	for unit in snapshot.get("units", []):
		var id := int(unit.id)
		active[id] = true
		if not unit_nodes.has(id):
			var node := Node2D.new()
			node.z_index = 3
			var base := "Images.AbsoluteUnits/TileSets/AbsoluteUnits/Units/" + str(unit.name)
			if texture(base + "-" + str(unit.nation)):
				base += "-" + str(unit.nation)
			for layer in range(3):
				var path := base if layer == 0 else base + "-" + str(layer)
				var art := texture(path)
				if not art:
					continue
				var sprite := Sprite2D.new()
				sprite.texture = art
				sprite.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
				sprite.scale = Vector2.ONE * (RADIUS * 1.8 / art.get_width())
				if layer > 0:
					sprite.modulate = Color(unit.innerColor if layer == 1 else unit.outerColor)
				node.add_child(sprite)
			if node.get_child_count() == 0:
				var label := Label.new()
				label.text = str(unit.name).substr(0, 3)
				node.add_child(label)
			add_child(node)
			unit_nodes[id] = node
		unit_nodes[id].position = hex_to_pixel(Vector2i(int(unit.x), int(unit.y))) + Vector2(-9 if unit.civilian else 9, -17)
	for id in unit_nodes.keys():
		if not active.has(id):
			unit_nodes[id].queue_free()
			unit_nodes.erase(id)

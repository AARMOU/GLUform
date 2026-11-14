@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.gluform.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gluform.app.*
import com.gluform.app.gl.CadView
import com.gluform.app.gl.Camera
import com.gluform.app.gl.Renderer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

interface FileOps { fun open(); fun insert(); fun save(); fun saveAs(); fun export(format: String, quality: String = "standard") }

val Accent = Color(0xFFF2A33A)

/** What the UI is asking the user for in a dialog. */
sealed class Ask {
    class Prompt(val title: String, val labels: List<String>, val initial: List<String>, val note: String = "", val onOk: (List<String>) -> Unit) : Ask()
    class FeatureInputs(val id: Long, val name: String, val info: JSONObject) : Ask()
    class Info(val title: String, val text: String) : Ask()
    object Palette : Ask()
    object ExportStl : Ask()
}

object Ui {
    var ask by mutableStateOf<Ask?>(null)
    var leftOpen by mutableStateOf(true)
    var leftTab by mutableStateOf(0)
    var toolGroup by mutableStateOf("Create")
    var inspectorExpanded by mutableStateOf(false)
}

fun openTool(id: String) {
    val t = Tools.byId(id); val f = FormState(t)
    val sel = Cad.selection.toList()
    for (fd in t.fields) when (fd.kind) {
        Kind.EDGES -> sel.filter { it.kind == "edge" }.let { if (it.isNotEmpty()) f.picks[fd.key] = it }
        Kind.FACES -> sel.filter { it.kind == "face" }.let { if (it.isNotEmpty()) f.picks[fd.key] = it }
        Kind.FACE, Kind.POINT -> sel.firstOrNull { it.kind == "face" }?.let { if (!fd.optional || id == "hole") f.picks[fd.key] = listOf(it) }
        Kind.PLANE -> sel.firstOrNull { it.kind == "face" }?.let { if (id == "sketch" || id == "plane_offset") { f.picks[fd.key] = listOf(it); f.values[fd.key] = "Picked" } }
        Kind.BODIES -> sel.map { it.body }.distinct().let { if (it.isNotEmpty()) f.values[fd.key] = it.joinToString("\n") }
        Kind.BODY -> sel.firstOrNull()?.let { f.values[fd.key] = it.body }
        Kind.SKETCH -> Cad.doc.optJSONArray("sketches")?.let { a -> if (a.length() > 0) f.values[fd.key] = a.getJSONObject(a.length() - 1).optLong("id").toString() }
        else -> {}
    }
    Cad.selection.clear(); Cad.form = f; Cad.push()
}

fun submitForm() {
    val f = Cad.form ?: return
    val (j, err) = f.build()
    if (j == null) { Cad.message = err; return }
    if (j.optString("name").isEmpty()) j.remove("name")
    Cad.exec(f.tool.command, j) { r -> if (r != null) { Cad.form = null; Cad.push() } }
}

@Composable
fun App(files: FileOps) {
    val scheme = if (Cad.light) lightColorScheme(primary = Color(0xFFB36A00), secondaryContainer = Color(0xFFFFE2B8)) else darkColorScheme(
        primary = Accent, onPrimary = Color(0xFF2A1A00), surface = Color(0xFF1B1E24), background = Color(0xFF14171C),
        surfaceVariant = Color(0xFF262A32), secondaryContainer = Color(0xFF3A3220), onSecondaryContainer = Color(0xFFFFDDA8), primaryContainer = Color(0xFF4A3510),
    )
    MaterialTheme(colorScheme = scheme) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            BoxWithConstraints {
                val compact = ViewportLayout.useCompactLayout(maxWidth.value)
                LaunchedEffect(Cad.form, Cad.sketch) {
                    if (Cad.form != null || Cad.sketch != null) Ui.inspectorExpanded = true
                }
                Column(Modifier.fillMaxSize()) {
                    TopBar(files, compact)
                    Ribbon()
                    Row(Modifier.weight(1f)) {
                        if (Ui.leftOpen && !compact) LeftPanel(Modifier.width(ViewportLayout.LEFT_PANEL_WIDTH_DP.dp).fillMaxHeight())
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            Viewport()
                            if (Ui.leftOpen && compact) LeftPanel(
                                Modifier.width(ViewportLayout.LEFT_PANEL_WIDTH_DP.dp).fillMaxHeight().align(Alignment.CenterStart),
                                onDismiss = { Ui.leftOpen = false }
                            )
                            if (compact) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { CompactInspector() }
                        }
                        if (!compact) RightPanel(Modifier.width(ViewportLayout.RIGHT_PANEL_WIDTH_DP.dp).fillMaxHeight())
                    }
                    Timeline()
                }
                Dialogs(files)
            }
        }
    }
}

@Composable
private fun TopBar(files: FileOps, compact: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { Ui.leftOpen = !Ui.leftOpen }) { Text("☰", fontSize = 18.sp) }
            Menu("File") { close ->
                Item("New design") { close(); Cad.newDesign(); files.let { } }
                Item("Open…  (.sc, STEP, STL, 3MF, OBJ, IGES, DXF)") { close(); files.open() }
                Item("Insert into design…  (STEP, STL, 3MF, OBJ)") { close(); files.insert() }
                HorizontalDivider()
                Item("Save") { close(); files.save() }
                Item("Save as…") { close(); files.saveAs() }
                HorizontalDivider()
                Item("Export STL…") { close(); Ui.ask = Ask.ExportStl }
                Item("Export STEP…") { close(); files.export("step") }
                Item("Export 3MF…") { close(); files.export("3mf") }
                Item("Export OBJ…") { close(); files.export("obj") }
                Item("Export GLB…") { close(); files.export("glb") }
            }
            TextButton(onClick = { files.save() }) { Text(if (Cad.dirty) "Save •" else "Save") }
            TextButton(enabled = Cad.canUndo, onClick = { Cad.undo() }) { Text("↶ Undo") }
            TextButton(enabled = Cad.canRedo, onClick = { Cad.redo() }) { Text("↷ Redo") }
            Text(Cad.doc.optString("name", "Untitled"), modifier = Modifier.padding(horizontal = 10.dp).widthIn(max = if (compact) 110.dp else 180.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(Cad.doc.optString("units", "mm"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            if (Cad.busy > 0) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Menu("View") { close ->
                for ((n, a) in Camera.VIEWS) Item(n) { close(); Cad.view(a.first, a.second) }
                HorizontalDivider()
                Item("Fit all") { close(); Cad.fit() }
                if (Cad.sketch != null) Item("Look at sketch") { close(); Cad.lookAtSketch() }
                Item(if (Cad.camera.perspective) "✓ Perspective" else "Perspective") { Cad.camera = Cad.camera.copy(perspective = !Cad.camera.perspective) }
            }
            Menu("Display") { _ ->
                Check("Shaded", Cad.shaded) { Cad.shaded = it; Cad.push() }
                Check("Edges", Cad.edges) { Cad.edges = it; Cad.push() }
                Check("See-through", Cad.xray) { Cad.xray = it; Cad.push() }
                Check("Grid", Cad.grid) { Cad.grid = it; Cad.push() }
                Check("Snap to grid", Cad.snap) { Cad.snap = it }
                Check("Light background", Cad.light) { Cad.light = it; Cad.scene = com.gluform.app.engine.Scene(Cad.scene.bodies, Cad.scene.sketches); Cad.push() }
            }
            FilterChip(selected = Cad.measure, onClick = { Cad.measure = !Cad.measure }, label = { Text("Measure") })
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { Ui.ask = Ask.Palette }) { Text("All commands") }
        }
    }
}

@Composable
private fun Menu(label: String, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("$label ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { content { open = false } }
    }
}
@Composable private fun Item(text: String, onClick: () -> Unit) = DropdownMenuItem(text = { Text(text) }, onClick = onClick)
@Composable private fun Check(text: String, on: Boolean, set: (Boolean) -> Unit) =
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Checkbox(checked = on, onCheckedChange = null) }, onClick = { set(!on) })

@Composable
private fun ToolButton(label: String, selected: Boolean = false, primary: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val bg = if (primary) c.primary else if (selected) c.primaryContainer else c.surfaceVariant
    val fg = if (primary) c.onPrimary else if (selected) c.onPrimaryContainer else c.onSurface
    Box(
        Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .border(if (selected) 1.dp else 0.dp, c.primary, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = fg, fontSize = 13.sp, maxLines = 1, fontWeight = if (selected || primary) FontWeight.SemiBold else FontWeight.Normal)
    }
}
@Composable private fun GroupLabel(t: String) = Text(t.uppercase(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.padding(start = 10.dp, end = 4.dp))

@Composable
private fun Ribbon() {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        if (Cad.sketch == null) {
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (g in Tools.groups) FilterChip(
                        selected = Ui.toolGroup == g, onClick = { Ui.toolGroup = g },
                        label = { Text(g) }, modifier = Modifier.heightIn(min = 40.dp)
                    )
                    TextButton(onClick = { Ui.ask = Ask.Palette }) { Text("Search tools") }
                }
                Row(Modifier.fillMaxWidth().height(58.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val group = Ui.toolGroup.takeIf { it in Tools.groups } ?: Tools.groups.first()
                    for (t in Tools.all.filter { it.group == group }) {
                        ToolButton(t.label, selected = Cad.form?.tool?.id == t.id) { openTool(t.id) }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(58.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ToolButton("Finish sketch", primary = true) { Cad.finishChain(); Cad.exec("sketch.finish") }
                ToolButton("Cancel") { Cad.finishChain(); Cad.exec("sketch.cancel") }
                GroupLabel("Draw")
                for ((id, l) in listOf("select" to "Select / drag", "line" to "Line", "rect" to "Rectangle", "rectc" to "Centre rect", "circle" to "Circle", "circle2" to "2-pt circle",
                    "arc3" to "3-pt arc", "arcc" to "Centre arc", "polygon" to "Polygon ${Cad.polygonSides}", "slot" to "Slot", "ellipse" to "Ellipse", "spline" to "Spline", "point" to "Point"))
                    ToolButton(l, selected = Cad.sketchTool == id) {
                        if (id == "polygon" && Cad.sketchTool == "polygon") Ui.ask = Ask.Prompt("Polygon sides", listOf("Sides"), listOf(Cad.polygonSides.toString())) { v -> v[0].toIntOrNull()?.let { Cad.polygonSides = it.coerceIn(3, 64) } }
                        else Cad.pickSketchTool(id)
                    }
                if (Cad.placed.isNotEmpty()) ToolButton("Done", primary = true) { Cad.finishChain() }
                GroupLabel("Modify")
                ToolButton("Trim", selected = Cad.sketchTool == "trim") { Cad.pickSketchTool("trim") }
                ToolButton("Extend", selected = Cad.sketchTool == "extend") { Cad.pickSketchTool("extend") }
                ToolButton("Fillet") { sketchFillet() }
                ToolButton("Offset") { sketchOffset() }
                ToolButton("Mirror") { sketchMirror() }
                ToolButton("Project edge", selected = Cad.sketchTool == "project") { Cad.pickSketchTool("project") }
                ToolButton("Text") { Ui.ask = Ask.Prompt("Sketch text", listOf("Text", "Height (mm)", "X", "Y"), listOf("", "5", "0", "0")) { v ->
                    Cad.exec("sketch.text", JSONObject().put("text", v[0]).put("height", v[1].toDoubleOrNull() ?: 5.0).put("at", JSONArray().put(v[2].toDoubleOrNull() ?: 0.0).put(v[3].toDoubleOrNull() ?: 0.0))) } }
            }
        }
    }
}

private fun sketchFillet() {
    val s = Cad.skSel.toList()
    val ok = (s.size == 1 && Cad.skIsPoint(s[0])) || (s.size == 2 && s.all { Cad.skIsCurve(it) })
    if (!ok) { Cad.message = "Select a corner point, or the two curves that meet"; return }
    Ui.ask = Ask.Prompt("Sketch fillet", listOf("Radius"), listOf("2")) { v ->
        val j = JSONObject().put("radius", v[0].toDoubleOrNull() ?: v[0])
        if (s.size == 1) j.put("point", s[0]) else j.put("a", s[0]).put("b", s[1])
        Cad.exec("sketch.fillet", j)
    }
}
private fun sketchOffset() {
    val c = Cad.skSel.filter { Cad.skIsCurve(it) }
    if (c.isEmpty()) { Cad.message = "Select the curves to offset"; return }
    Ui.ask = Ask.Prompt("Offset curves", listOf("Distance"), listOf("2"), "A negative distance offsets to the other side.") { v -> Cad.exec("sketch.offset", JSONObject().put("curves", JSONArray(c)).put("distance", v[0].toDoubleOrNull() ?: v[0])) }
}
private fun sketchMirror() {
    val s = Cad.skSel.toList()
    if (s.size < 2 || !Cad.skIsCurve(s.last())) { Cad.message = "Select what to mirror, then the mirror line last"; return }
    Cad.exec("sketch.mirror", JSONObject().put("entities", JSONArray(s.dropLast(1))).put("line", s.last()))
}

@Composable
private fun Viewport() {
    val density = LocalDensity.current.density
    val renderer = remember { Renderer() }
    Box(Modifier.fillMaxSize().onSizeChanged { Cad.viewW = it.width; Cad.viewH = it.height; Cad.push() }) {
        AndroidView(factory = { ctx ->
            Cad.density = density
            CadView(ctx, renderer, Cad).also { v -> Cad.onFrame = { st -> renderer.state = st; v.requestRender() }; Cad.push() }
        }, modifier = Modifier.fillMaxSize(), onRelease = { Cad.onFrame = null })
        SketchOverlay()
        // status and hints
        Column(Modifier.align(Alignment.TopStart).padding(10.dp)) {
            val sk = Cad.sketch
            if (sk != null) {
                val full = sk.dof == 0
                Pill(if (full) "${sk.name} · fully constrained" else "${sk.name} · ${sk.dof} degrees of freedom" + if (sk.status != "solved") " · ${sk.status}" else "", if (full) Color(0xFF58B368) else MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(6.dp))
                val hint = when (Cad.sketchTool) {
                    "select" -> "Tap to select · drag geometry to move it · one finger pans, two fingers zoom"
                    "line" -> "Tap or drag to draw connected lines · double-tap or Done to stop"
                    "spline" -> "Tap points · Done to finish"
                    "trim" -> "Tap the piece of a curve to remove"; "extend" -> "Tap near the end to extend"
                    "project" -> "Tap a model edge or face to project it into the sketch"
                    "arc3" -> "Start, end, then a point on the arc"; "arcc" -> "Centre, start, then end (anticlockwise)"
                    "slot" -> "Two centres, then the width"; "ellipse" -> "Centre, major axis end, then a point for the minor axis"
                    else -> "Tap or drag to place"
                }
                Pill(hint, MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
            } else if (Cad.form?.activeField() != null) Pill("Tap the model to pick: ${Cad.form!!.activeField()!!.label}", MaterialTheme.colorScheme.primaryContainer)
            else if (Cad.scene.bodies.isEmpty() && Cad.scene.sketches.isEmpty()) Pill("Start with Sketch, or add a Box or Cylinder", MaterialTheme.colorScheme.surfaceVariant)
        }
        Cad.message?.let { m ->
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 560.dp), action = { TextButton(onClick = { Cad.message = null }) { Text("OK") } }) { Text(m, maxLines = 5, overflow = TextOverflow.Ellipsis) }
            LaunchedEffect(m) { kotlinx.coroutines.delay(7000); if (Cad.message == m) Cad.message = null }
        }
    }
}


/** Compact inspectors are reachable without obscuring the entire Fold canvas. */
@Composable
private fun CompactInspector() {
    if (Cad.form == null && Cad.sketch == null && Cad.selection.isEmpty() && !Cad.measure) return
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), tonalElevation = 4.dp) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                val label = when {
                    Cad.form != null -> Cad.form!!.tool.label
                    Cad.sketch != null -> "Sketch constraints"
                    Cad.measure -> "Measure · ${Cad.selection.size} selected"
                    else -> "Selection · ${Cad.selection.size}"
                }
                Text(label, Modifier.weight(1f).padding(start = 14.dp), fontWeight = FontWeight.SemiBold, maxLines = 1)
                TextButton(onClick = { Ui.inspectorExpanded = !Ui.inspectorExpanded }) {
                    Text(if (Ui.inspectorExpanded) "Hide ▾" else "Details ▴")
                }
            }
            if (Ui.inspectorExpanded) {
                HorizontalDivider()
                Box(Modifier.fillMaxWidth().heightIn(max = 270.dp)) { RightPanel(Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) = Box(Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 12.dp, vertical = 5.dp)) { Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface) }

/** Sketch points, dimension labels and measuring marks, drawn over the GL view. */
@Composable
private fun SketchOverlay() {
    val cam = Cad.camera; val sk = Cad.scene.active; val info = Cad.sketch
    val ink = if (Cad.light) Color(0xFF20242C) else Color.White
    Canvas(Modifier.fillMaxSize()) {
        if (sk != null && info != null) {
            for (p in info.points) {
                val s = cam.project(sk.toWorld(p.u, p.v), Cad.viewW, Cad.viewH) ?: continue
                val sel = p.id in Cad.skSel
                drawCircle(if (sel) Accent else if (p.id == "origin") Color(0xFFE05050) else if (p.full) ink else Color(0xFF4FC3F7), if (sel) 6.dp.toPx() else 3.5.dp.toPx(), Offset(s[0], s[1]))
            }
            for (p in Cad.placed + listOfNotNull(Cad.cursor)) {
                val s = cam.project(sk.toWorld(p.u, p.v), Cad.viewW, Cad.viewH) ?: continue
                drawCircle(Color(0xFF4FC3F7), 5.dp.toPx(), Offset(s[0], s[1])); if (p.ref != null) drawCircle(Accent, 9.dp.toPx(), Offset(s[0], s[1]), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            }
        } else for (p in Cad.selection) {
            val s = cam.project(p.point, Cad.viewW, Cad.viewH) ?: continue
            drawCircle(Accent, 4.dp.toPx(), Offset(s[0], s[1]))
        }
        if (Cad.measure && Cad.selection.size == 2) {
            val a = cam.project(Cad.selection[0].point, Cad.viewW, Cad.viewH); val b = cam.project(Cad.selection[1].point, Cad.viewW, Cad.viewH)
            if (a != null && b != null) drawLine(Accent, Offset(a[0], a[1]), Offset(b[0], b[1]), 2.dp.toPx())
        }
    }
    if (sk != null && info != null) for (d in info.dims) {
        val s = cam.project(sk.toWorld(d.u, d.v), Cad.viewW, Cad.viewH) ?: continue
        Box(Modifier.offset { IntOffset(s[0].roundToInt() + 14, s[1].roundToInt() - 44) }.clip(RoundedCornerShape(6.dp)).background(Color(0xFF2C3E50))
            .border(1.dp, Color(0xFF4FC3F7), RoundedCornerShape(6.dp)).clickable {
                Ui.ask = Ask.Prompt("Dimension ${d.param}", listOf("Value or expression"), listOf(d.expr), "Type DELETE to remove this dimension.") { v ->
                    if (v[0].trim() == "DELETE") Cad.exec("sketch.delete", JSONObject().put("entities", JSONArray().put(d.id)))
                    else Cad.exec("parameters.change", JSONObject().put("name", d.param).put("expression", v[0].trim()))
                }
            }.padding(horizontal = 8.dp, vertical = 4.dp)) { Text(d.expr.ifEmpty { d.param }, color = Color.White, fontSize = 13.sp) }
    }
}

@Composable
private fun Timeline() {
    val tl = Cad.doc.optJSONArray("timeline") ?: JSONArray()
    val items = (0 until tl.length()).map { tl.getJSONObject(it) }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        LazyRow(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            item { Text("TIMELINE", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), modifier = Modifier.padding(end = 8.dp)) }
            if (items.isEmpty()) item { Text("Features appear here · tap to edit, hold for options.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
            itemsIndexed(items) { i, f ->
                var open by remember(f.optLong("id")) { mutableStateOf(false) }
                val err = !f.isNull("error"); val off = f.optBoolean("suppressed") || f.optBoolean("rolled_back")
                val id = f.optLong("id"); val name = f.optString("name"); val isSketch = f.optString("type") == "Sketch"
                val editFeature = {
                    if (isSketch) {
                        Cad.form = null
                        Cad.exec("sketch.edit", JSONObject().put("sketch", id))
                    } else {
                        Cad.query("timeline.inspect", JSONObject().put("feature", id)) { r ->
                            if (r != null) Ui.ask = Ask.FeatureInputs(id, name, r)
                        }
                    }
                }
                Box {
                    Row(Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(8.dp)).background(if (err) Color(0xFF7A2A2A) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (off) 0.4f else 1f))
                        .combinedClickable(onClick = { editFeature() }, onLongClick = { open = true })
                        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isSketch) "✎" else "◆", color = if (isSketch) Color(0xFF4FC3F7) else Accent, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (off) 0.45f else 1f))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        if (err) DropdownMenuItem(text = { Text(f.optString("error"), color = Color(0xFFFF8A80), fontSize = 12.sp) }, onClick = {})
                        Item(if (isSketch) "Edit sketch" else "Edit values…") { open = false; editFeature() }
                        Item("Rename…") { open = false; Ui.ask = Ask.Prompt("Rename", listOf("Name"), listOf(name)) { v -> Cad.exec("timeline.rename", JSONObject().put("feature", id).put("name", v[0])) } }
                        Item(if (f.optBoolean("suppressed")) "Unsuppress" else "Suppress") { open = false; Cad.exec("timeline.suppress", JSONObject().put("feature", id)) }
                        Item("Roll back to here") { open = false; Cad.exec("timeline.roll_to", JSONObject().put("position", i + 1)) }
                        Item("Roll to end") { open = false; Cad.exec("timeline.roll_to", JSONObject().put("position", "end")) }
                        Item("Delete") { open = false; Cad.exec("timeline.delete", JSONObject().put("feature", id)) }
                    }
                }
            }
        }
    }
}

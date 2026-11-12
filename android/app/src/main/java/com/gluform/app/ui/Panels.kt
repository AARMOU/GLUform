@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.gluform.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gluform.app.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

@Composable private fun Head(t: String) = Text(t, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
@Composable private fun Dim(t: String) = Text(t, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
private fun arr(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }
private fun fmt(v: Double) = if (kotlin.math.abs(v) >= 1e5) "%.4g".format(v) else "%.3f".format(v).trimEnd('0').trimEnd('.')

@Composable
fun LeftPanel(modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column {
            TabRow(selectedTabIndex = Ui.leftTab) {
                Tab(Ui.leftTab == 0, onClick = { Ui.leftTab = 0 }, text = { Text("Browser") })
                Tab(Ui.leftTab == 1, onClick = { Ui.leftTab = 1 }, text = { Text("Parameters") })
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
                if (Ui.leftTab == 0) Browser() else Parameters()
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Browser() {
    val bodies = arr(Cad.doc.optJSONArray("bodies")); val sketches = arr(Cad.doc.optJSONArray("sketches"))
    Head("Bodies (${bodies.size})")
    if (bodies.isEmpty()) Dim("No bodies yet.")
    for (b in bodies) {
        val n = b.optString("name")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = b.optBoolean("visible", true), onCheckedChange = { Cad.exec("browser.visibility", JSONObject().put("visible", it).put("items", JSONArray().put(n))) })
            Text(n, Modifier.weight(1f).clickable { Ui.ask = Ask.Prompt("Rename body", listOf("Name"), listOf(n)) { v -> Cad.exec("body.rename", JSONObject().put("body", n).put("name", v[0])) } }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Head("Sketches (${sketches.size})")
    if (sketches.isEmpty()) Dim("No sketches yet.")
    for (s in sketches) {
        val id = s.optLong("id")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = s.optBoolean("visible"), onCheckedChange = { Cad.exec("browser.visibility", JSONObject().put("visible", it).put("items", JSONArray().put(id))) })
            Column(Modifier.weight(1f).clickable { Cad.form = null; Cad.exec("sketch.edit", JSONObject().put("sketch", id)) }) {
                Text(s.optString("name"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Dim("${s.optInt("curves")} curves · ${s.optInt("profiles")} regions · " + if (s.optInt("dof") == 0) "constrained" else "${s.optInt("dof")} free")
            }
        }
    }
    Head("Design")
    Dim("Units: ${Cad.doc.optString("units", "mm")}")
    TextButton(onClick = { Cad.query("inspect.measure") { r -> if (r != null) Ui.ask = Ask.Info("Properties", properties(r)) } }) { Text("Mass properties…") }
}

private fun properties(r: JSONObject): String {
    val sb = StringBuilder()
    for (b in arr(r.optJSONArray("bodies"))) {
        sb.append(b.optString("name")).append('\n')
        if (b.has("volume_mm3")) sb.append("  Volume  ${fmt(b.optDouble("volume_mm3"))} mm³\n")
        sb.append("  Area  ${fmt(b.optDouble("area_mm2"))} mm²\n")
        if (b.has("mass_g")) sb.append("  Mass  ${fmt(b.optDouble("mass_g"))} g  (${b.optString("material")}, ${b.optDouble("density_g_cm3")} g/cm³)\n")
        b.optJSONObject("bbox")?.let { bb -> val mn = bb.getJSONArray("min"); val mx = bb.getJSONArray("max")
            sb.append("  Size  ${fmt(mx.getDouble(0) - mn.getDouble(0))} × ${fmt(mx.getDouble(1) - mn.getDouble(1))} × ${fmt(mx.getDouble(2) - mn.getDouble(2))} mm\n") }
        b.optJSONArray("center_of_mass")?.let { c -> sb.append("  Centre of mass  ${fmt(c.getDouble(0))}, ${fmt(c.getDouble(1))}, ${fmt(c.getDouble(2))}\n") }
        sb.append("  ${b.optInt("faces")} faces · ${b.optInt("edges")} edges · ${if (b.optBoolean("closed")) "closed solid" else "open"}\n\n")
    }
    return if (sb.isEmpty()) "There are no bodies to measure." else sb.toString().trimEnd()
}

@Composable
private fun Parameters() {
    val ps = arr(Cad.doc.optJSONArray("params"))
    fun edit(p: JSONObject) { val n = p.optString("name")
        Ui.ask = Ask.Prompt("Parameter $n", listOf("Expression"), listOf(p.optString("expression")), if (p.optBoolean("model")) "" else "Type DELETE to remove this parameter.") { v ->
            if (v[0].trim() == "DELETE") Cad.exec("parameters.delete", JSONObject().put("name", n)) else Cad.exec("parameters.change", JSONObject().put("name", n).put("expression", v[0].trim())) } }
    Head("Your parameters")
    val user = ps.filter { !it.optBoolean("model") }
    if (user.isEmpty()) Dim("Name a size once and use it in any dimension or feature, e.g. width = 80 mm.")
    for (p in user) ParamRow(p) { edit(p) }
    TextButton(onClick = { Ui.ask = Ask.Prompt("New parameter", listOf("Name", "Expression"), listOf("", "10 mm")) { v -> if (v[0].isNotBlank()) Cad.exec("parameters.add", JSONObject().put("name", v[0].trim()).put("expression", v[1].trim())) } }) { Text("+ Add parameter") }
    Head("Model dimensions")
    val model = ps.filter { it.optBoolean("model") }
    if (model.isEmpty()) Dim("Sketch dimensions and feature sizes are listed here.")
    for (p in model) ParamRow(p) { edit(p) }
}

@Composable
private fun ParamRow(p: JSONObject, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.optString("name"), Modifier.width(72.dp), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(Modifier.weight(1f)) {
            Text(p.optString("expression"), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (p.isNull("error")) MaterialTheme.colorScheme.onSurface else Color(0xFFFF8A80))
            if (!p.isNull("error")) Dim(p.optString("error")) else Dim("= ${fmt(p.optDouble("value"))} ${p.optString("unit")}")
        }
    }
}

@Composable
fun RightPanel(modifier: Modifier) {
    val f = Cad.form
    val show = f != null || Cad.sketch != null || Cad.selection.isNotEmpty() || Cad.measure
    if (!show) return
    Surface(modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            when {
                f != null -> FormPanel(f)
                Cad.sketch != null -> SketchPanel(Cad.sketch!!)
                else -> SelectionPanel()
            }
        }
    }
}

@Composable
private fun Chips(options: List<Pair<String, String>>, selected: (String) -> Boolean, onClick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((v, l) in options) FilterChip(selected = selected(v), onClick = { onClick(v) }, label = { Text(l, maxLines = 1) })
    }
}

@Composable
private fun PickSlot(f: FormState, fd: Field, summary: String) {
    val on = f.active == fd.key
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
        .clickable { f.active = if (on) null else fd.key; Cad.push() }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(summary, Modifier.weight(1f), fontSize = 13.sp)
        if (!f.picks[fd.key].isNullOrEmpty()) Text("Clear", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, modifier = Modifier.clickable { f.picks.remove(fd.key); if (f.values[fd.key] == "Picked") f.values.remove(fd.key); Cad.push() })
    }
}

@Composable
private fun FormPanel(f: FormState) {
    val t = f.tool
    Text(t.label, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    if (t.help.isNotEmpty()) Dim(t.help)
    for (fd in t.fields) {
        Spacer(Modifier.height(10.dp))
        val v = f.values[fd.key] ?: ""
        when (fd.kind) {
            Kind.NUM, Kind.TEXT -> OutlinedTextField(v, { f.values[fd.key] = it }, label = { Text(fd.label + if (fd.optional) " (optional)" else "") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = if (fd.hint.isNotEmpty()) ({ Text(fd.hint) }) else null)
            Kind.BOOL -> Row(verticalAlignment = Alignment.CenterVertically) { Switch(v == "true", { f.values[fd.key] = it.toString() }); Spacer(Modifier.width(10.dp)); Text(fd.label) }
            Kind.ENUM -> { Dim(fd.label); Chips(fd.options.map { it to it.ifEmpty { "none" } }, { it == v }) { f.values[fd.key] = it } }
            Kind.EDGES, Kind.FACES -> { Dim(fd.label); val n = f.picks[fd.key]?.size ?: 0; PickSlot(f, fd, if (n == 0) "Tap in the view to pick" else "$n picked") }
            Kind.FACE, Kind.POINT -> { Dim(fd.label + if (fd.optional) " (optional)" else ""); val p = f.picks[fd.key]?.firstOrNull()
                PickSlot(f, fd, if (p == null) "Tap in the view to pick" else "${p.body} at ${fmt(p.point[0])}, ${fmt(p.point[1])}, ${fmt(p.point[2])}") }
            Kind.PLANE, Kind.AXIS -> { Dim(fd.label)
                Chips(fd.options.map { it to it } + listOf("Picked" to if (f.picks[fd.key].isNullOrEmpty()) "Pick in view" else "Picked ✓"), { it == v }) {
                    if (it == "Picked") { f.active = fd.key; f.values[fd.key] = "Picked" } else { f.values[fd.key] = it; f.picks.remove(fd.key); if (f.active == fd.key) f.active = null }
                    Cad.push() } }
            Kind.BODY, Kind.BODIES -> { Dim(fd.label + if (fd.optional) " (optional)" else "")
                val names = arr(Cad.doc.optJSONArray("bodies")).map { it.optString("name") }
                if (names.isEmpty()) Dim("No bodies.")
                Chips(names.map { it to it }, { if (fd.kind == Kind.BODY) v == it else it in f.list(fd.key) }) { f.toggle(fd.key, it, single = fd.kind == Kind.BODY) } }
            Kind.SKETCH, Kind.SKETCHES -> { Dim(fd.label)
                val sk = arr(Cad.doc.optJSONArray("sketches"))
                if (sk.isEmpty()) Dim("No sketches yet: create one first.")
                val order = f.list(fd.key)
                Chips(sk.map { it.optLong("id").toString() to (it.optString("name") + if (fd.kind == Kind.SKETCHES && it.optLong("id").toString() in order) "  #${order.indexOf(it.optLong("id").toString()) + 1}" else "") },
                    { if (fd.kind == Kind.SKETCH) v == it else it in order }) { f.toggle(fd.key, it, single = fd.kind == Kind.SKETCH) } }
            Kind.FEATURES -> { Dim(fd.label + if (fd.optional) " (optional)" else "")
                val fs = arr(Cad.doc.optJSONArray("timeline")).filter { it.optString("type") != "Sketch" }.map { it.optString("name") }
                if (fs.isEmpty()) Dim("No features yet.")
                Chips(fs.map { it to it }, { it in f.list(fd.key) }) { f.toggle(fd.key, it) } }
        }
    }
    Spacer(Modifier.height(14.dp))
    Row {
        Button(onClick = { submitForm() }, enabled = Cad.busy == 0) { Text("OK") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { Cad.form = null; Cad.push() }) { Text("Cancel") }
    }
}

@Composable
private fun SketchPanel(sk: SketchInfo) {
    Text("Sketch", fontWeight = FontWeight.Bold, fontSize = 17.sp)
    val sel = Cad.skSel.toList()
    Dim(if (sel.isEmpty()) "Nothing selected. Tap points and curves with Select." else "Selected: " + sel.joinToString(", "))
    if (sel.isNotEmpty()) Row {
        TextButton(onClick = { Cad.deleteSketchSelection() }) { Text("Delete") }
        TextButton(onClick = { Cad.toggleConstruction() }) { Text("Construction") }
        TextButton(onClick = { Cad.skSel.clear(); Cad.push() }) { Text("Clear") }
    }
    Head("Dimension")
    Dim("Select one or two entities, then add a size.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((ty, l) in listOf("auto" to "Dimension", "horizontal" to "Horizontal", "vertical" to "Vertical", "radius" to "Radius", "diameter" to "Diameter", "angle" to "Angle"))
            AssistChip(onClick = {
                if (Cad.skSel.isEmpty()) Cad.message = "Select what to dimension first"
                else Ui.ask = Ask.Prompt("$l dimension", listOf("Value or expression"), listOf(""), "Leave empty to keep the current size. Use a parameter name or a formula such as width / 2.") { v -> Cad.dimension(ty, v[0]) }
            }, label = { Text(l) })
    }
    Head("Constrain")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((k, l) in listOf("horizontal" to "Horizontal", "vertical" to "Vertical", "coincident" to "Coincident", "tangent" to "Tangent", "equal" to "Equal", "parallel" to "Parallel",
            "perpendicular" to "Perpendicular", "concentric" to "Concentric", "midpoint" to "Midpoint", "collinear" to "Collinear", "symmetry" to "Symmetric", "fix" to "Fix / unfix"))
            AssistChip(onClick = { Cad.constrain(k) }, label = { Text(l) })
    }
    TextButton(onClick = { Cad.exec("sketch.auto_constrain") }) { Text("Auto-constrain") }
    Head("Constraints (${sk.cons.size})")
    for (c in sk.cons) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${c.name}  ${c.on}", Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("✕", Modifier.clickable { Cad.exec("sketch.delete", JSONObject().put("entities", JSONArray().put(c.id))) }.padding(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
    if (sk.dims.isNotEmpty()) { Head("Dimensions (${sk.dims.size})"); Dim("Tap a blue label in the view to change it.") }
}

@Composable
private fun SelectionPanel() {
    val sel = Cad.selection.toList()
    Text(if (Cad.measure) "Measure" else "Selection", fontWeight = FontWeight.Bold, fontSize = 17.sp)
    if (sel.isEmpty()) { Dim("Tap faces, edges or corners of the model."); return }
    for (p in sel) Dim("${p.label}  (${fmt(p.point[0])}, ${fmt(p.point[1])}, ${fmt(p.point[2])})")
    if (sel.size == 2) {
        val a = sel[0].point; val b = sel[1].point; val d = doubleArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
        Head("Distance between picks"); Text("${fmt(sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]))} mm", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Dim("ΔX ${fmt(d[0])}   ΔY ${fmt(d[1])}   ΔZ ${fmt(d[2])}")
        val n1 = sel[0].normal; val n2 = sel[1].normal
        if (n1 != null && n2 != null) { val c = (n1[0] * n2[0] + n1[1] * n2[1] + n1[2] * n2[2]).coerceIn(-1.0, 1.0); Dim("Angle between faces ${fmt(Math.toDegrees(kotlin.math.acos(c)))}°") }
    }
    for (p in sel.filter { it.kind == "edge" && it.poly != null }) {
        var len = 0.0; val q = p.poly!!; var i = 0
        while (i + 5 < q.size) { val dx = q[i + 3] - q[i]; val dy = q[i + 4] - q[i + 1]; val dz = q[i + 5] - q[i + 2]; len += sqrt((dx * dx + dy * dy + dz * dz).toDouble()); i += 3 }
        Dim("Edge length ≈ ${fmt(len)} mm")
    }
    Head("Do with selection")
    val edges = sel.any { it.kind == "edge" }; val faces = sel.any { it.kind == "face" }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (edges) { AssistChip(onClick = { openTool("fillet") }, label = { Text("Fillet") }); AssistChip(onClick = { openTool("chamfer") }, label = { Text("Chamfer") }) }
        if (faces) { AssistChip(onClick = { openTool("sketch") }, label = { Text("Sketch on face") }); AssistChip(onClick = { openTool("presspull") }, label = { Text("Press / Pull") })
            AssistChip(onClick = { openTool("shell") }, label = { Text("Shell") }); AssistChip(onClick = { openTool("hole") }, label = { Text("Hole here") }); AssistChip(onClick = { openTool("offsetface") }, label = { Text("Offset face") }) }
        AssistChip(onClick = { openTool("move") }, label = { Text("Move body") }); AssistChip(onClick = { openTool("appearance") }, label = { Text("Colour") })
        AssistChip(onClick = { Cad.selection.clear(); Cad.push() }, label = { Text("Clear") })
    }
}

@Composable
fun Dialogs(files: FileOps) {
    when (val a = Ui.ask) {
        null -> {}
        is Ask.Prompt -> {
            val vals = remember(a) { mutableStateListOf<String>().also { it.addAll(a.initial) } }
            AlertDialog(onDismissRequest = { Ui.ask = null }, title = { Text(a.title) },
                text = { Column { a.labels.forEachIndexed { i, l -> OutlinedTextField(vals[i], { vals[i] = it }, label = { Text(l) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) }; if (a.note.isNotEmpty()) Dim(a.note) } },
                confirmButton = { Button(onClick = { Ui.ask = null; a.onOk(vals.toList()) }) { Text("OK") } }, dismissButton = { TextButton(onClick = { Ui.ask = null }) { Text("Cancel") } })
        }
        is Ask.Info -> AlertDialog(onDismissRequest = { Ui.ask = null }, title = { Text(a.title) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(a.text, fontSize = 13.sp) } }, confirmButton = { TextButton(onClick = { Ui.ask = null }) { Text("Close") } })
        is Ask.FeatureInputs -> {
            val inputs = arr(a.info.optJSONArray("inputs"))
            val vals = remember(a) { mutableStateListOf<String>().also { l -> inputs.forEach { l.add(it.optString("expression")) } } }
            AlertDialog(onDismissRequest = { Ui.ask = null }, title = { Text("Edit ${a.name}") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (!a.info.isNull("error")) Text(a.info.optString("error"), color = Color(0xFFFF8A80), fontSize = 12.sp)
                    if (inputs.isEmpty()) Dim("This feature has no numeric inputs. Its picks and options can be changed with timeline.edit under All commands.")
                    inputs.forEachIndexed { i, inp -> OutlinedTextField(vals[i], { vals[i] = it }, label = { Text("${inp.optString("label")} (${inp.optString("unit")})") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) }
                    Dim("Values can be numbers, parameter names or formulas.")
                } },
                confirmButton = { Button(onClick = {
                    Ui.ask = null
                    val steps = inputs.mapIndexedNotNull { i, inp -> if (vals[i].trim() != inp.optString("expression")) "parameters.change" to JSONObject().put("name", inp.optString("param")).put("expression", vals[i].trim()) else null }
                    if (steps.isNotEmpty()) Cad.execAll(steps)
                }) { Text("Apply") } }, dismissButton = { TextButton(onClick = { Ui.ask = null }) { Text("Cancel") } })
        }
        Ask.ExportStl -> {
            var q by remember { mutableStateOf("fine") }
            AlertDialog(onDismissRequest = { Ui.ask = null }, title = { Text("Export STL") },
                text = { Column { Dim("Mesh quality. Finer meshes follow curves more closely and make larger files."); Spacer(Modifier.height(8.dp)); Chips(listOf("standard" to "Standard", "fine" to "Fine", "finest" to "Finest"), { it == q }) { q = it } } },
                confirmButton = { Button(onClick = { Ui.ask = null; files.export("stl", q) }) { Text("Export") } }, dismissButton = { TextButton(onClick = { Ui.ask = null }) { Text("Cancel") } })
        }
        Ask.Palette -> Palette()
    }
}

/** Every engine command, searchable, run with JSON parameters: reaches what the toolbar does not. */
@Composable
private fun Palette() {
    var q by remember { mutableStateOf("") }
    var cur by remember { mutableStateOf<JSONObject?>(null) }
    var json by remember { mutableStateOf("{}") }
    var out by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { Ui.ask = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(24.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (cur == null) "All commands (${Cad.commands.size})" else cur!!.optString("label"), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    if (cur != null) TextButton(onClick = { cur = null; out = "" }) { Text("Back") }
                    TextButton(onClick = { Ui.ask = null }) { Text("Close") }
                }
                val c = cur
                if (c == null) {
                    OutlinedTextField(q, { q = it }, label = { Text("Search: loft, thread, sheet, joint, pattern…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val words = q.lowercase().split(' ').filter { it.isNotBlank() }
                    val list = Cad.commands.filter { cmd -> val hay = (cmd.optString("id") + " " + cmd.optString("label") + " " + cmd.optString("tab")).lowercase(); words.all { it in hay } }
                    LazyColumn(Modifier.weight(1f)) {
                        items(list) { cmd ->
                            Column(Modifier.fillMaxWidth().clickable { cur = cmd; json = "{}"; out = "" }.padding(vertical = 8.dp)) {
                                Text(cmd.optString("label"), fontWeight = FontWeight.Medium)
                                Dim(cmd.optString("id") + (if (cmd.optString("tab").isNotEmpty()) "  ·  ${cmd.optString("tab")} / ${cmd.optString("panel")}" else "") + if (!cmd.optBoolean("enabled", true)) "  ·  not available right now" else "")
                            }
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Dim(c.optString("id"))
                        if (!c.isNull("disabled_reason")) Text(c.optString("disabled_reason"), color = Color(0xFFFF8A80), fontSize = 12.sp)
                        Head("Parameters"); Text(c.optString("params").ifEmpty { "None." }, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(json, { json = it }, label = { Text("Parameters as JSON") }, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
                        Row {
                            Button(onClick = { val p = runCatching { JSONObject(json.ifBlank { "{}" }) }.getOrNull()
                                if (p == null) out = "The parameters are not valid JSON." else Cad.exec(c.optString("id"), p, quiet = true) { r -> out = r?.toString(2)?.take(6000) ?: ("Failed: " + (Cad.lastError ?: "")) } }) { Text("Run") }
                            Spacer(Modifier.width(8.dp))
                            for (p in Cad.selection.take(3)) TextButton(onClick = { json = json.trimEnd().let { s -> s + " [${p.point[0]}, ${p.point[1]}, ${p.point[2]}]" } }) { Text("+ ${p.kind} point") }
                        }
                        if (Cad.selection.isEmpty()) Dim("Tip: select faces or edges before opening this, to paste their points into picks.")
                        if (out.isNotEmpty()) { Head("Result"); Text(out, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

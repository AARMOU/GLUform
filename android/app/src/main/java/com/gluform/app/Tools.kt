package com.gluform.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

enum class Kind { NUM, TEXT, BOOL, ENUM, EDGES, FACES, FACE, POINT, PLANE, AXIS, BODY, BODIES, SKETCH, SKETCHES, FEATURES }

class Field(
    val key: String, val label: String, val kind: Kind, val default: String = "", val options: List<String> = emptyList(),
    val optional: Boolean = false, val hint: String = "", val mode: String = "",
)

class ToolDef(val id: String, val label: String, val group: String, val command: String, val fields: List<Field>, val help: String = "", val post: ((FormState, JSONObject) -> Unit)? = null)

class FormState(val tool: ToolDef) {
    val values = mutableStateMapOf<String, String>()
    val picks = mutableStateMapOf<String, List<Pick>>()
    var active by mutableStateOf<String?>(null)
    /** When editing is not creating: nothing here yet (features are edited through their inputs). */
    init {
        for (f in tool.fields) if (f.default.isNotEmpty()) values[f.key] = f.default
        active = tool.fields.firstOrNull { it.kind in PICKS && it.kind != Kind.PLANE && it.kind != Kind.AXIS }?.key
    }
    fun activeField(): Field? = tool.fields.firstOrNull { it.key == active }
    fun list(key: String): List<String> = values[key]?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
    fun toggle(key: String, v: String, single: Boolean = false) {
        val l = list(key).toMutableList()
        if (single) { values[key] = v; return }
        if (v in l) l.remove(v) else l.add(v)
        values[key] = l.joinToString("\n")
    }
    fun addPick(f: Field, p: Pick) {
        when (f.kind) {
            Kind.BODY -> values[f.key] = p.body
            Kind.BODIES -> toggle(f.key, p.body)
            Kind.FACE, Kind.POINT, Kind.PLANE, Kind.AXIS -> { picks[f.key] = listOf(p); if (f.kind == Kind.PLANE || f.kind == Kind.AXIS) values[f.key] = "Picked" }
            else -> {
                val l = (picks[f.key] ?: emptyList()).toMutableList()
                val i = l.indexOfFirst { it.kind == p.kind && it.body == p.body && it.index == p.index }
                if (i >= 0) l.removeAt(i) else l.add(p)
                picks[f.key] = l
            }
        }
    }

    private fun num(s: String): Any = s.trim().toDoubleOrNull() ?: s.trim()

    /** The command's parameters, or a message saying what is missing. */
    fun build(): Pair<JSONObject?, String?> {
        val j = JSONObject()
        for (f in tool.fields) {
            val v = values[f.key]?.trim() ?: ""
            val pk = picks[f.key] ?: emptyList()
            var missing = false
            when (f.kind) {
                Kind.NUM -> if (v.isEmpty()) missing = true else j.put(f.key, num(v))
                Kind.TEXT -> if (v.isEmpty()) missing = true else j.put(f.key, v)
                Kind.BOOL -> if (v == "true") j.put(f.key, true) else if (f.mode == "always") j.put(f.key, false)
                Kind.ENUM -> if (v.isNotEmpty() && v != "default") j.put(f.key, v)
                Kind.EDGES, Kind.FACES -> if (pk.isEmpty()) missing = true else { j.put(f.key, JSONArray().also { a -> pk.forEach { a.put(it.json()) } }); if (f.mode == "body") j.put("body", pk[0].body) }
                Kind.FACE, Kind.POINT -> if (pk.isEmpty()) missing = true else j.put(f.key, pk[0].json())
                Kind.PLANE -> when {
                    v == "Picked" && pk.isNotEmpty() -> {
                        val p = pk[0]
                        if (f.mode == "face") j.put(f.key, JSONObject().put("face", p.json()))
                        else j.put(f.key, JSONObject().put("origin", p.json()).put("normal", JSONArray().put(p.normal!![0]).put(p.normal[1]).put(p.normal[2])))
                    }
                    v.isEmpty() || v == "Picked" -> missing = true
                    else -> j.put(f.key, v)
                }
                Kind.AXIS -> when {
                    v == "Picked" && pk.isNotEmpty() -> j.put(f.key, JSONObject().put(pk[0].kind, pk[0].json()))
                    v.isEmpty() || v == "Picked" -> missing = true
                    else -> j.put(f.key, v)
                }
                Kind.BODY -> if (v.isEmpty()) missing = true else j.put(f.key, v)
                Kind.BODIES, Kind.FEATURES -> if (list(f.key).isEmpty()) missing = true else j.put(f.key, JSONArray(list(f.key)))
                Kind.SKETCH -> if (v.isEmpty()) missing = true else j.put(f.key, v.toLongOrNull() ?: v)
                Kind.SKETCHES -> if (list(f.key).size < 2) missing = true else j.put(f.key, JSONArray().also { a -> list(f.key).forEach { a.put(JSONObject().put("sketch", it.toLongOrNull() ?: it)) } })
            }
            if (missing && !f.optional) return null to "${f.label} is needed"
        }
        tool.post?.invoke(this, j)
        return j to null
    }

    companion object { val PICKS = setOf(Kind.EDGES, Kind.FACES, Kind.FACE, Kind.POINT, Kind.PLANE, Kind.AXIS, Kind.BODY, Kind.BODIES) }
}

object Tools {
    private val OP = Field("operation", "Operation", Kind.ENUM, "auto", listOf("auto", "new", "join", "cut", "intersect"))
    private val OPP = Field("operation", "Operation", Kind.ENUM, "new", listOf("new", "join", "cut", "intersect"))
    private val NAME = Field("name", "Name", Kind.TEXT, optional = true)
    private val PLANES = listOf("XY", "XZ", "YZ")
    private val AXES = listOf("X", "Y", "Z")
    private fun vec(v: String?): JSONArray = when (v) { "+X" -> JSONArray("[1,0,0]"); "-X" -> JSONArray("[-1,0,0]"); "+Y" -> JSONArray("[0,1,0]"); "-Y" -> JSONArray("[0,-1,0]"); "-Z" -> JSONArray("[0,0,-1]"); else -> JSONArray("[0,0,1]") }
    private val DIRS = listOf("+X", "-X", "+Y", "-Y", "+Z", "-Z")

    val all: List<ToolDef> = listOf(
        ToolDef("sketch", "Sketch", "Create", "sketch.create", listOf(Field("plane", "Plane or flat face", Kind.PLANE, "XY", PLANES, mode = "face"), Field("offset", "Offset", Kind.NUM, optional = true, hint = "mm, along the normal"), NAME),
            "Pick an origin plane, or tap a flat face of the model.", post = { f, j -> if (j.opt("plane") is JSONObject) j.remove("offset") }),
        ToolDef("extrude", "Extrude", "Create", "solid.extrude", listOf(
            Field("sketch", "Sketch", Kind.SKETCH), Field("distance", "Distance", Kind.NUM, "10", hint = "mm or expression"),
            Field("direction", "Direction", Kind.ENUM, "positive", listOf("positive", "negative", "symmetric")), OP,
            Field("through_all", "Through all", Kind.BOOL), Field("taper", "Taper angle", Kind.NUM, optional = true, hint = "e.g. 5 deg"), NAME),
            "Every closed region of the sketch is extruded; a region inside another is a hole.", post = { _, j -> if (j.optBoolean("through_all")) j.remove("distance") }),
        ToolDef("revolve", "Revolve", "Create", "solid.revolve", listOf(
            Field("sketch", "Sketch", Kind.SKETCH), Field("axis", "Axis", Kind.TEXT, "y", hint = "x or y (sketch axes), X Y Z (world), or a sketch line id such as l1"),
            Field("angle", "Angle", Kind.NUM, "360 deg"), OP, NAME)),
        ToolDef("sweep", "Sweep", "Create", "solid.sweep", listOf(
            Field("sketch", "Profile sketch", Kind.SKETCH), Field("path_sketch", "Path sketch", Kind.SKETCH), Field("path", "Path curves", Kind.TEXT, hint = "curve ids in order, e.g. l1, a1"), OP, NAME),
            post = { f, j -> j.put("path", JSONArray(j.optString("path").split(',', ' ').filter { it.isNotBlank() })) }),
        ToolDef("loft", "Loft", "Create", "solid.loft", listOf(Field("sections", "Section sketches, in order", Kind.SKETCHES), OP, NAME)),
        ToolDef("box", "Box", "Create", "solid.box", listOf(Field("length", "Length (X)", Kind.NUM, "20"), Field("width", "Width (Y)", Kind.NUM, "20"), Field("height", "Height (Z)", Kind.NUM, "20"),
            Field("center", "Centre (tap a face)", Kind.POINT, optional = true), OPP, NAME)),
        ToolDef("cylinder", "Cylinder", "Create", "solid.cylinder", listOf(Field("diameter", "Diameter", Kind.NUM, "20"), Field("height", "Height", Kind.NUM, "20"),
            Field("base", "Base centre (tap a face)", Kind.POINT, optional = true), Field("axis", "Axis", Kind.ENUM, "Z", AXES), OPP, NAME)),
        ToolDef("sphere", "Sphere", "Create", "solid.sphere", listOf(Field("diameter", "Diameter", Kind.NUM, "20"), Field("center", "Centre (tap a face)", Kind.POINT, optional = true), OPP, NAME)),
        ToolDef("cone", "Cone", "Create", "solid.cone", listOf(Field("diameter", "Base diameter", Kind.NUM, "20"), Field("top_diameter", "Top diameter", Kind.NUM, "0"), Field("height", "Height", Kind.NUM, "20"),
            Field("base", "Base centre (tap a face)", Kind.POINT, optional = true), OPP, NAME)),
        ToolDef("torus", "Torus", "Create", "solid.torus", listOf(Field("major", "Ring radius", Kind.NUM, "20"), Field("minor", "Tube radius", Kind.NUM, "5"), Field("center", "Centre (tap a face)", Kind.POINT, optional = true), OPP, NAME)),
        ToolDef("hole", "Hole", "Create", "solid.hole", listOf(
            Field("position", "Position (tap a face)", Kind.POINT), Field("diameter", "Diameter", Kind.NUM, "5"), Field("depth", "Depth", Kind.NUM, optional = true, hint = "blank = through all"),
            Field("type", "Type", Kind.ENUM, "simple", listOf("simple", "counterbore", "countersink")),
            Field("cb_diameter", "Counterbore diameter", Kind.NUM, optional = true), Field("cb_depth", "Counterbore depth", Kind.NUM, optional = true),
            Field("cs_diameter", "Countersink diameter", Kind.NUM, optional = true), Field("cs_angle", "Countersink angle", Kind.NUM, optional = true, hint = "e.g. 90 deg"), NAME),
            post = { _, j -> when (j.optString("type")) { "counterbore" -> { j.remove("cs_diameter"); j.remove("cs_angle") }; "countersink" -> { j.remove("cb_diameter"); j.remove("cb_depth") }; else -> listOf("cb_diameter", "cb_depth", "cs_diameter", "cs_angle").forEach { j.remove(it) } } }),
        ToolDef("thread", "Thread", "Create", "solid.thread", listOf(Field("face", "Cylindrical face", Kind.FACE), Field("designation", "Size", Kind.TEXT, optional = true, hint = "e.g. M8 or M8x1; blank = by diameter"), NAME)),
        ToolDef("pattern_r", "Rectangular pattern", "Create", "solid.pattern.rectangular", listOf(
            Field("features", "Features", Kind.FEATURES), Field("dir1", "Direction 1", Kind.ENUM, "+X", DIRS), Field("count1", "Count 1", Kind.NUM, "3"), Field("spacing1", "Spacing 1", Kind.NUM, "10"),
            Field("dir2", "Direction 2", Kind.ENUM, "", listOf("") + DIRS, optional = true), Field("count2", "Count 2", Kind.NUM, optional = true), Field("spacing2", "Spacing 2", Kind.NUM, optional = true), NAME),
            post = { _, j -> j.put("dir1", vec(j.optString("dir1"))); if (j.has("dir2")) j.put("dir2", vec(j.optString("dir2"))) else { j.remove("count2"); j.remove("spacing2") } }),
        ToolDef("pattern_c", "Circular pattern", "Create", "solid.pattern.circular", listOf(
            Field("features", "Features", Kind.FEATURES), Field("axis", "Axis (or tap a round edge or face)", Kind.AXIS, "Z", AXES), Field("count", "Count", Kind.NUM, "6"), Field("angle", "Total angle", Kind.NUM, "360 deg"), NAME)),
        ToolDef("mirror", "Mirror", "Create", "solid.mirror", listOf(
            Field("bodies", "Bodies", Kind.BODIES, optional = true), Field("features", "…or features", Kind.FEATURES, optional = true),
            Field("plane", "Mirror plane (or tap a flat face)", Kind.PLANE, "YZ", PLANES), Field("combine", "Join with original", Kind.BOOL), NAME)),

        ToolDef("fillet", "Fillet", "Modify", "solid.fillet", listOf(Field("edges", "Edges", Kind.EDGES, mode = "body"), Field("radius", "Radius", Kind.NUM, "2"), NAME), "Tap edges. One tap takes the whole tangent chain."),
        ToolDef("chamfer", "Chamfer", "Modify", "solid.chamfer", listOf(Field("edges", "Edges", Kind.EDGES, mode = "body"), Field("distance", "Distance", Kind.NUM, "1"), Field("angle", "Angle", Kind.NUM, optional = true, hint = "blank = 45°"), NAME)),
        ToolDef("shell", "Shell", "Modify", "solid.shell", listOf(Field("faces", "Faces to open", Kind.FACES), Field("thickness", "Wall thickness", Kind.NUM, "2"), NAME)),
        ToolDef("presspull", "Press / Pull", "Modify", "solid.press_pull", listOf(Field("face", "Face", Kind.FACE), Field("distance", "Distance", Kind.NUM, "5", hint = "negative cuts")),
            post = { f, j -> f.picks["face"]?.firstOrNull()?.let { j.put("body", it.body) } }),
        ToolDef("offsetface", "Offset face", "Modify", "solid.offset_face", listOf(Field("faces", "Faces", Kind.FACES, mode = "body"), Field("distance", "Distance", Kind.NUM, "1", hint = "positive = outward"))),
        ToolDef("draft", "Draft", "Modify", "solid.draft", listOf(Field("faces", "Faces", Kind.FACES, mode = "body"), Field("angle", "Angle", Kind.NUM, "3 deg"), Field("neutral", "Neutral plane", Kind.PLANE, "XY", PLANES))),
        ToolDef("combine", "Combine", "Modify", "solid.combine", listOf(Field("target", "Target body", Kind.BODY), Field("tools", "Tool bodies", Kind.BODIES), Field("operation", "Operation", Kind.ENUM, "join", listOf("join", "cut", "intersect")))),
        ToolDef("split", "Split body", "Modify", "solid.split_body", listOf(Field("body", "Body", Kind.BODY), Field("plane", "Plane (or tap a flat face)", Kind.PLANE, "XY", PLANES))),
        ToolDef("move", "Move / Copy", "Modify", "solid.move", listOf(
            Field("bodies", "Bodies", Kind.BODIES), Field("tx", "Move X", Kind.NUM, "0"), Field("ty", "Move Y", Kind.NUM, "0"), Field("tz", "Move Z", Kind.NUM, "0"),
            Field("rot", "Rotate about", Kind.ENUM, "", listOf("", "X", "Y", "Z"), optional = true), Field("angle", "Angle", Kind.NUM, optional = true, hint = "e.g. 90 deg"), Field("copy", "Keep original (copy)", Kind.BOOL)),
            post = { _, j ->
                j.put("translate", JSONArray().put(j.opt("tx")).put(j.opt("ty")).put(j.opt("tz"))); j.remove("tx"); j.remove("ty"); j.remove("tz")
                val r = j.optString("rot"); j.remove("rot")
                if (r.isNotEmpty() && j.has("angle")) j.put("axis", vec("+$r")) else j.remove("angle")
            }),
        ToolDef("scale", "Scale", "Modify", "solid.scale", listOf(Field("bodies", "Bodies", Kind.BODIES), Field("factor", "Factor", Kind.NUM, "2"))),
        ToolDef("remove", "Remove body", "Modify", "solid.remove", listOf(Field("bodies", "Bodies", Kind.BODIES))),
        ToolDef("appearance", "Colour", "Modify", "appearance.assign", listOf(Field("bodies", "Bodies", Kind.BODIES), Field("color", "Colour", Kind.ENUM, "#4F8EDC", listOf("#4F8EDC", "#D9534F", "#5CB85C", "#F0AD4E", "#9B59B6", "#ECECEC", "#3A3F47", "#C0A060")), Field("opacity", "Opacity (0–1)", Kind.NUM, "1"))),
        ToolDef("material", "Material", "Modify", "material.assign", listOf(Field("bodies", "Bodies", Kind.BODIES), Field("material", "Material", Kind.ENUM, "Steel", listOf("Steel", "Aluminum", "ABS Plastic")))),

        ToolDef("plane_offset", "Offset plane", "Construct", "construct.plane.offset", listOf(Field("base", "Base plane (or tap a flat face)", Kind.PLANE, "XY", PLANES, mode = "face"), Field("offset", "Offset", Kind.NUM, "10"), NAME)),
    )

    fun byId(id: String) = all.first { it.id == id }
    val groups = listOf("Create", "Modify", "Construct")
}

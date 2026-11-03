package com.megane.launcher

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

data class AppItem(val label: String, val pkg: String, val icon: ImageBitmap)

class MainActivity : ComponentActivity() {
    private val apps = mutableStateOf<List<AppItem>>(emptyList())
    private val pinned = mutableStateOf<List<String>>(emptyList())
    private val prefs by lazy { getSharedPreferences("dock", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pinned.value = prefs.getString("pkgs", "")!!.split(",").filter { it.isNotEmpty() }
        setContent { LauncherUI(apps.value, pinned.value, ::open, ::togglePin, ::openBluetooth) }
    }

    override fun onResume() {
        super.onResume()
        apps.value = loadApps()
    }

    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                AppItem(
                    it.loadLabel(pm).toString(),
                    it.activityInfo.packageName,
                    it.loadIcon(pm).toBitmap(128, 128).asImageBitmap()
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun open(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun openBluetooth() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    private fun togglePin(pkg: String) {
        val l = pinned.value
        pinned.value = if (pkg in l) l - pkg else (l + pkg).takeLast(5)
        prefs.edit().putString("pkgs", pinned.value.joinToString(",")).apply()
    }
}

// ---------- Voiture 3D (low-poly, dessinée en Canvas, aucune dépendance) ----------

private typealias V3 = Triple<Float, Float, Float>

private class Face(val p: List<V3>, val n: V3, val color: Color, val emissive: Boolean = false)

// Change la couleur de la carrosserie ici
private val BODY = Color(0xFFC5CCD6)
private val GLASS = Color(0xFF1C2A3A)

private fun edgeNormal(a: Pair<Float, Float>, b: Pair<Float, Float>): V3 =
    Triple(-(b.second - a.second), b.first - a.first, 0f)

private fun extrude(
    poly: List<Pair<Float, Float>>, w: (Float) -> Float,
    sideColor: Color, sideTilt: Float, edgeColor: (Int) -> Color
): List<Face> {
    val out = mutableListOf<Face>()
    out += Face(poly.map { V3(it.first, it.second, w(it.second)) }, V3(0f, sideTilt, 1f), sideColor)
    out += Face(poly.map { V3(it.first, it.second, -w(it.second)) }, V3(0f, sideTilt, -1f), sideColor)
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[(i + 1) % poly.size]
        out += Face(
            listOf(
                V3(a.first, a.second, -w(a.second)), V3(b.first, b.second, -w(b.second)),
                V3(b.first, b.second, w(b.second)), V3(a.first, a.second, w(a.second))
            ), edgeNormal(a, b), edgeColor(i)
        )
    }
    return out
}

private fun lightQuad(x: Float, y0: Float, y1: Float, z0: Float, z1: Float, nx: Float, c: Color, emissive: Boolean) =
    listOf(1f, -1f).map { s ->
        Face(listOf(V3(x, y0, s * z0), V3(x, y1, s * z0), V3(x, y1, s * z1), V3(x, y0, s * z1)), V3(nx, 0f, 0f), c, emissive)
    }

private fun wheel(cx: Float, cy: Float): List<Face> = listOf(1f, -1f).flatMap { s ->
    fun disc(r: Float, z: Float, c: Color) = Face(
        (0 until 20).map { k -> val t = k * 2f * PI.toFloat() / 20f; V3(cx + r * cos(t), cy + r * sin(t), s * z) },
        V3(0f, 0f, s), c
    )
    listOf(disc(0.34f, 0.905f, Color(0xFF101010)), disc(0.21f, 0.912f, Color(0xFFAAB2BC)), disc(0.07f, 0.918f, Color(0xFF333333)))
}

private val CAR: List<Face> by lazy {
    val bodyPoly = listOf(-2.1f to 0.3f, -2.1f to 0.85f, 1.2f to 0.85f, 2.1f to 0.68f, 2.1f to 0.3f)
    val cabinPoly = listOf(-1.9f to 0.85f, -1.3f to 1.40f, 0.1f to 1.42f, 0.95f to 0.85f)
    val f = mutableListOf<Face>()
    f += extrude(bodyPoly, { 0.9f }, BODY, 0f) { BODY }
    f += extrude(cabinPoly, { y -> 0.84f - (y - 0.85f) / 0.57f * 0.16f }, GLASS, 0.25f) {
        when (it) { 0, 2 -> GLASS; else -> BODY }
    }
    f += lightQuad(2.105f, 0.52f, 0.64f, 0.5f, 0.86f, 1f, Color(0xFFFFF3C4), true)   // phares
    f += lightQuad(2.105f, 0.38f, 0.50f, 0f, 0.5f, 1f, Color(0xFF15181C), false)     // calandre
    f += lightQuad(-2.105f, 0.60f, 0.78f, 0.45f, 0.86f, -1f, Color(0xFFE53935), true) // feux arrière
    f += wheel(1.35f, 0.34f)
    f += wheel(-1.30f, 0.34f)
    f
}

@Composable
fun Car3D(modifier: Modifier) {
    var yaw by remember { mutableFloatStateOf(0.7f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { t ->
            if (last != 0L && !dragging) yaw += (t - last) / 1e9f * 0.35f
            last = t
        }
    }
    Canvas(modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { dragging = true },
            onDragEnd = { dragging = false },
            onDragCancel = { dragging = false }
        ) { _, d -> yaw += d.x * 0.01f }
    }) {
        val pitch = 0.22f; val cd = 7.5f
        val f = min(size.width * 1.1f, size.height * 1.7f)
        val cx = size.width / 2f; val cy = size.height * 0.58f
        val ca = cos(yaw); val sa = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)

        fun view(x: Float, y: Float, z: Float): V3 {
            val x1 = x * ca + z * sa
            val z1 = -x * sa + z * ca
            return V3(x1, y * cp - z1 * sp, y * sp + z1 * cp)
        }
        fun pr(v: V3): Offset { val s = f / (cd - v.third); return Offset(cx + v.first * s, cy - v.second * s) }
        fun poly(pts: List<Offset>) = Path().apply {
            moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y); close()
        }

        // ombre au sol
        val shadow = (0 until 28).map { k ->
            val t = k * 2f * PI.toFloat() / 28f
            pr(view(2.4f * cos(t), -0.7f, 1.2f * sin(t)))
        }
        drawPath(poly(shadow), Color(0x66000000))

        val lx = -0.35f; val ly = 0.8f; val lz = 0.5f
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        for (fc in CAR) {
            val vs = fc.p.map { view(it.first, it.second - 0.7f, it.third) }
            val n = view(fc.n.first, fc.n.second, fc.n.third)
            val c = V3(vs.sumOf { it.first.toDouble() }.toFloat() / vs.size,
                vs.sumOf { it.second.toDouble() }.toFloat() / vs.size,
                vs.sumOf { it.third.toDouble() }.toFloat() / vs.size)
            val facing = n.first * (-c.first) + n.second * (-c.second) + n.third * (cd - c.third)
            if (facing <= 0f) continue
            val nl = sqrt(n.first * n.first + n.second * n.second + n.third * n.third)
            val light = 0.4f + 0.6f * max(0f, (n.first * lx + n.second * ly + n.third * lz) / (nl * ll))
            val col = if (fc.emissive) fc.color else Color(
                (fc.color.red * light).coerceIn(0f, 1f),
                (fc.color.green * light).coerceIn(0f, 1f),
                (fc.color.blue * light).coerceIn(0f, 1f)
            )
            val path = poly(vs.map { pr(it) })
            drawPath(path, col)
            drawPath(path, Color(0x55000000), style = Stroke(1.5f))
        }
    }
}

// ---------- Interface ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppIcon(a: AppItem, size: Int, showLabel: Boolean, onOpen: (String) -> Unit, onLong: (String) -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = { onOpen(a.pkg) }, onLongClick = { onLong(a.pkg) })
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(a.icon, a.label, Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)))
        if (showLabel) Text(
            a.label, color = Color.White, fontSize = 13.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun LauncherUI(apps: List<AppItem>, pinned: List<String>, onOpen: (String) -> Unit, onPin: (String) -> Unit, onBluetooth: () -> Unit) {
    val now by produceState(Date()) { while (true) { value = Date(); delay(1000) } }
    var showApps by remember { mutableStateOf(false) }
    val open = { p: String -> showApps = false; onOpen(p) }
    val bg = Brush.verticalGradient(listOf(Color(0xFF0B1020), Color(0xFF1B2A49)))

    Row(Modifier.fillMaxSize().background(bg)) {
        // Dock
        Column(
            Modifier.width(92.dp).fillMaxHeight().background(Color(0x40000000)).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x33FFFFFF))
                    .clickable { showApps = !showApps },
                contentAlignment = Alignment.Center
            ) { Text("▦", color = Color.White, fontSize = 30.sp) }
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1565C0))
                    .clickable { onBluetooth() },
                contentAlignment = Alignment.Center
            ) { Text("BT", color = Color.White, fontSize = 24.sp) }
            pinned.mapNotNull { p -> apps.find { it.pkg == p } }.forEach {
                AppIcon(it, 64, false, open, onPin)
            }
        }
        // Centre : voiture 3D + horloge
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Car3D(Modifier.fillMaxSize())
            Column(Modifier.padding(24.dp)) {
                Text(SimpleDateFormat("HH:mm", Locale.FRANCE).format(now), color = Color.White, fontSize = 56.sp)
                Text(
                    SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(now).replaceFirstChar { it.uppercase() },
                    color = Color(0xFFB0BEC5), fontSize = 18.sp
                )
            }
            if (showApps) LazyVerticalGrid(
                GridCells.Adaptive(104.dp),
                Modifier.fillMaxSize().background(Color(0xE60B1020)),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(apps, key = { it.pkg }) { AppIcon(it, 72, true, open, onPin) }
            }
        }
    }
}

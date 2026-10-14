package com.example.coldclearvs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.coldclearvs.core.Match
import com.example.coldclearvs.core.PieceKind
import com.example.coldclearvs.core.CellKind
import com.example.coldclearvs.core.GarbageEntry
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.withFrameNanos
import android.content.Context
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Undo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.coldclearvs.core.GameOverSide
import com.example.coldclearvs.core.MatchStats
import androidx.compose.material.icons.filled.Lightbulb
import com.example.coldclearvs.core.HintStep
import androidx.compose.material.icons.filled.Pause

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }


        setContent {
            GameScreen()
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 다른 화면 갔다 돌아오거나 하면 상태바가 다시 나타날 수 있어서, 포커스
        // 돌아올 때마다 다시 숨겨줍니다.
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

data class ButtonLayout(val xFraction: Float, val yFraction: Float, val scale: Float)

val defaultButtonIds = listOf("left", "right", "cw", "ccw", "softDrop", "hold", "hardDrop", "undo", "aiTurn")
val buttonColorDefault = Color(0xFF555555)
val buttonColorPressed = Color(0xFF999999)

val settingsAccentColor = Color(0xFF00AC7C)

fun serializeLayouts(map: Map<String, ButtonLayout>): String {
    return map.entries.joinToString(";") { (id, l) ->
        "$id:${l.xFraction}:${l.yFraction}:${l.scale}"
    }
}


data class GameSettings(
    val dasMs: Long = 150L,
    val arrMs: Long = 30L,
    val sdsLevel: Int = 3,
    val garbageSameColumnChance: Float = 0.7f,
    val aiMinNodes: Int = 100_000,
    val aiMaxNodes: Int = 2_000_000,
    val aiThreads: Int = 4,
    val aiSpeculate: Boolean = true,
    val garbageDelay: Int = 2,
)

fun sdsToArrMs(level: Int): Long = when (level) {
    1 -> 500L
    2 -> 200L
    3 -> 100L
    4 -> 50L
    5 -> 10L
    else -> 0L // 6: ARR 0이랑 동일한 방식(즉시 바닥까지) 재사용
}

fun sdsDescription(level: Int): String = when (level) {
    1 -> "0.5s"
    2 -> "0.2s"
    3 -> "0.1s"
    4 -> "0.05s"
    5 -> "0.01s"
    else -> "즉시"
}

fun msToFramesText(ms: Long): String {
    val frames = ms * 60.0 / 1000.0
    return "%.1fF".format(frames)
}

fun saveSettings(context: Context, s: GameSettings) {
    context.getSharedPreferences("tbcc_prefs", Context.MODE_PRIVATE)
        .edit()
        .putLong("das_ms", s.dasMs)
        .putLong("arr_ms", s.arrMs)
        .putInt("sds_level", s.sdsLevel)
        .putFloat("garbage_chance", s.garbageSameColumnChance)
        .putInt("ai_min_nodes", s.aiMinNodes)
        .putInt("ai_max_nodes", s.aiMaxNodes)
        .putInt("ai_threads", s.aiThreads)
        .putBoolean("ai_speculate", s.aiSpeculate)
        .putInt("garbage_delay", s.garbageDelay)
        .apply()
}

fun loadSettings(context: Context): GameSettings {
    val prefs = context.getSharedPreferences("tbcc_prefs", Context.MODE_PRIVATE)
    return GameSettings(
        dasMs = prefs.getLong("das_ms", 150L),
        arrMs = prefs.getLong("arr_ms", 30L),
        sdsLevel = prefs.getInt("sds_level", 3),
        garbageSameColumnChance = prefs.getFloat("garbage_chance", 0.7f),
        aiMinNodes = prefs.getInt("ai_min_nodes", 100_000),
        aiMaxNodes = prefs.getInt("ai_max_nodes", 2_000_000),
        aiThreads = prefs.getInt("ai_threads", 4),
        aiSpeculate = prefs.getBoolean("ai_speculate", true),
    )
}


@Composable
fun SettingsPanel(settings: GameSettings, onSettingsChange: (GameSettings) -> Unit, onClose: () -> Unit, onEditButtons: () -> Unit) {
    var tab by remember { mutableStateOf(0) } // 0 = 일반, 1 = AI 설정

    var dasText by remember { mutableStateOf(settings.dasMs.toString()) }
    var arrText by remember { mutableStateOf(settings.arrMs.toString()) }
    var chanceText by remember { mutableStateOf((settings.garbageSameColumnChance * 100).roundToInt().toString()) }
    var minNodesText by remember { mutableStateOf(settings.aiMinNodes.toString()) }
    var maxNodesText by remember { mutableStateOf(settings.aiMaxNodes.toString()) }
    var threadsText by remember { mutableStateOf(settings.aiThreads.toString()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                .padding(20.dp)
                .width(300.dp),
        ) {
            Text("설정", color = Color.White, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(12.dp))

            // 탭 헤더
            Row {
                listOf("일반", "AI 설정").forEachIndexed { i, label ->
                    Box(
                        modifier = Modifier
                            .padding(2.dp)
                            .background(if (tab == i) settingsAccentColor else Color(0xFF444444), RoundedCornerShape(6.dp))
                            .clickable { tab = i }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(label, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (tab == 0) {
                // ── 일반 탭: 예전 내용 그대로 ──
                Text("DAS (ms) — ${msToFramesText(dasText.toLongOrNull() ?: 0L)}", color = Color.White, fontSize = 12.sp)
                TextField(
                    value = dasText,
                    onValueChange = {
                        dasText = it.filter { c -> c.isDigit() }
                        dasText.toLongOrNull()?.let { v -> onSettingsChange(settings.copy(dasMs = v)) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text("ARR (ms) — ${msToFramesText(arrText.toLongOrNull() ?: 0L)}", color = Color.White, fontSize = 12.sp)
                TextField(
                    value = arrText,
                    onValueChange = {
                        arrText = it.filter { c -> c.isDigit() }
                        arrText.toLongOrNull()?.let { v -> onSettingsChange(settings.copy(arrMs = v)) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text("SDS (소프트드롭 속도)", color = Color.White, fontSize = 12.sp)
                Row {
                    for (level in 1..6) {
                        val selected = settings.sdsLevel == level
                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .background(if (selected) settingsAccentColor else Color(0xFF444444), RoundedCornerShape(6.dp))
                                .clickable { onSettingsChange(settings.copy(sdsLevel = level)) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text("$level", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
                Text(sdsDescription(settings.sdsLevel), color = Color.Gray, fontSize = 10.sp)

                Spacer(modifier = Modifier.height(12.dp))

                Text("가비지 구멍 직렬 확률 (%)", color = Color.White, fontSize = 12.sp)
                TextField(
                    value = chanceText,
                    onValueChange = {
                        chanceText = it.filter { c -> c.isDigit() }
                        chanceText.toIntOrNull()?.coerceIn(0, 100)?.let { v ->
                            onSettingsChange(settings.copy(garbageSameColumnChance = v / 100f))
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("가비지 딜레이 (턴)", color = Color.White, fontSize = 12.sp)
                var delayText by remember { mutableStateOf(settings.garbageDelay.toString()) }
                TextField(
                    value = delayText,
                    onValueChange = {
                        delayText = it.filter { c -> c.isDigit() }
                        delayText.toIntOrNull()?.let { v -> onSettingsChange(settings.copy(garbageDelay = v)) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onEditButtons,
                    colors = ButtonDefaults.buttonColors(containerColor = settingsAccentColor),
                ) { Text("버튼 편집") }
            } else {
                // ── AI 설정 탭 ──
                Text("최소 탐색량 (min_nodes)", color = Color.White, fontSize = 12.sp)
                //Text("낮으면 대충 두는 문제가 생길 수 있음", color = Color.Gray, fontSize = 10.sp)
                TextField(
                    value = minNodesText,
                    onValueChange = {
                        minNodesText = it.filter { c -> c.isDigit() }
                        minNodesText.toIntOrNull()?.let { v -> onSettingsChange(settings.copy(aiMinNodes = v)) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text("최대 탐색량 (max_nodes)", color = Color.White, fontSize = 12.sp)
                //Text("높을수록 강해질 여지 있지만 느려질 수 있음", color = Color.Gray, fontSize = 10.sp)
                TextField(
                    value = maxNodesText,
                    onValueChange = {
                        maxNodesText = it.filter { c -> c.isDigit() }
                        maxNodesText.toIntOrNull()?.let { v -> onSettingsChange(settings.copy(aiMaxNodes = v)) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text("스레드 수", color = Color.White, fontSize = 12.sp)
                TextField(
                    value = threadsText,
                    onValueChange = {
                        threadsText = it.filter { c -> c.isDigit() }
                        threadsText.toIntOrNull()?.let { v -> onSettingsChange(settings.copy(aiThreads = v.coerceAtLeast(1))) }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("미래 조각 추측(speculate)", color = Color.White, fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(if (settings.aiSpeculate) settingsAccentColor else Color(0xFF444444), RoundedCornerShape(6.dp))
                            .clickable { onSettingsChange(settings.copy(aiSpeculate = !settings.aiSpeculate)) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(if (settings.aiSpeculate) "ON" else "OFF", color = Color.White, fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = settingsAccentColor),
            ) { Text("닫기") }
        }
    }
}

fun deserializeLayouts(raw: String): Map<String, ButtonLayout> {
    val map = mutableMapOf<String, ButtonLayout>()
    if (raw.isBlank()) return map
    raw.split(";").forEach { entry ->
        val parts = entry.split(":")
        if (parts.size == 4) {
            val x = parts[1].toFloatOrNull()
            val y = parts[2].toFloatOrNull()
            val s = parts[3].toFloatOrNull()
            if (x != null && y != null && s != null) {
                map[parts[0]] = ButtonLayout(x, y, s)
            }
        }
    }
    return map
}

fun saveButtonLayouts(context: Context, map: Map<String, ButtonLayout>) {
    context.getSharedPreferences("tbcc_prefs", Context.MODE_PRIVATE)
        .edit()
        .putString("button_layouts", serializeLayouts(map))
        .apply()
}

fun loadButtonLayouts(context: Context): MutableMap<String, ButtonLayout> {
    val raw = context.getSharedPreferences("tbcc_prefs", Context.MODE_PRIVATE)
        .getString("button_layouts", null)

    // 기본값에서 시작해서, 저장된 값이 있으면 그걸로 덮어씁니다.
    // (나중에 버튼 종류가 늘어나도 새 버튼은 기본 위치로 채워지게)
    val result = defaultButtonLayouts()
    if (raw != null) {
        result.putAll(deserializeLayouts(raw))
    }
    return result
}

fun defaultButtonLayouts(): MutableMap<String, ButtonLayout> {
    val map = mutableMapOf<String, ButtonLayout>()
    map["left"] = ButtonLayout(xFraction = 0.167f, yFraction = 0.709f, scale = 1.00f)
    map["right"] = ButtonLayout(xFraction = 0.357f, yFraction = 0.709f, scale = 1.00f)
    map["softDrop"] = ButtonLayout(xFraction = 0.262f, yFraction = 0.794f, scale = 1.00f)
    map["cw"] = ButtonLayout(xFraction = 0.905f, yFraction = 0.794f, scale = 1.00f)
    map["ccw"] = ButtonLayout(xFraction = 0.714f, yFraction = 0.794f, scale = 1.00f)
    map["hardDrop"] = ButtonLayout(xFraction = 0.714f, yFraction = 0.709f, scale = 1.00f)
    map["hold"] = ButtonLayout(xFraction = 0.905f, yFraction = 0.709f, scale = 1.00f)
    map["undo"] = ButtonLayout(xFraction = 0.119f, yFraction = 0.614f, scale = 1.00f)
    map["aiTurn"] = ButtonLayout(xFraction = 0.119f, yFraction = 0.529f, scale = 1.00f)
    return map
}

@Composable
fun GridOverlay(gridSizePx: Float, screenWidthPx: Float, screenHeightPx: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (gridSizePx <= 0f) return@Canvas
        var x = 0f
        while (x <= screenWidthPx) {
            drawLine(Color(0x33FFFFFF), Offset(x, 0f), Offset(x, screenHeightPx), strokeWidth = 1f)
            x += gridSizePx
        }
        var y = 0f
        while (y <= screenHeightPx) {
            drawLine(Color(0x33FFFFFF), Offset(0f, y), Offset(screenWidthPx, y), strokeWidth = 1f)
            y += gridSizePx
        }
    }
}

@Composable
fun PositionedControl(
    id: String,
    layout: ButtonLayout,
    editMode: Boolean,
    baseSize: Dp,
    screenWidthPx: Float,
    screenHeightPx: Float,
    snapEnabled: Boolean,
    gridSizePx: Float,
    onLayoutChange: (ButtonLayout) -> Unit,
    onTapSelect: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val sizeDp = baseSize * layout.scale
    val sizePx = with(density) { sizeDp.toPx() }
    val xPx = layout.xFraction * screenWidthPx
    val yPx = layout.yFraction * screenHeightPx
    val currentLayout by rememberUpdatedState(layout)
    val currentSnap by rememberUpdatedState(snapEnabled)
    val currentGridPx by rememberUpdatedState(gridSizePx)

    fun snapIfNeeded(value: Float): Float {
        if (!currentSnap || currentGridPx <= 0f) return value
        return (value / currentGridPx).roundToInt() * currentGridPx
    }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (xPx - sizePx / 2).roundToInt(),
                    (yPx - sizePx / 2).roundToInt(),
                )
            }
            .size(sizeDp),
    ) {
        content()

        if (editMode) {
            // 스냅 여부와 상관없이 항상 "진짜" 위치(픽셀)를 그대로 누적합니다.
            // 화면에 보여줄 때만 이 값을 격자에 맞춰 반올림합니다.
            var rawX by remember { mutableStateOf(xPx) }
            var rawY by remember { mutableStateOf(yPx) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.dp, Color.Yellow, RoundedCornerShape(12.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                // 드래그 시작할 때, 누적값을 지금 실제 위치로 다시 맞춰둡니다.
                                rawX = currentLayout.xFraction * screenWidthPx
                                rawY = currentLayout.yFraction * screenHeightPx
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                rawX += dragAmount.x
                                rawY += dragAmount.y

                                val outX = snapIfNeeded(rawX)
                                val outY = snapIfNeeded(rawY)

                                onLayoutChange(
                                    currentLayout.copy(
                                        xFraction = (outX / screenWidthPx).coerceIn(0f, 1f),
                                        yFraction = (outY / screenHeightPx).coerceIn(0f, 1f),
                                    ),
                                )
                            },
                        )
                    },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(22.dp)
                    .background(Color.Yellow, RoundedCornerShape(50))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val current = currentLayout
                            val delta = dragAmount.x / sizePx
                            onLayoutChange(current.copy(scale = (current.scale + delta).coerceIn(0.5f, 2.0f)))
                        }
                    },
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-16).dp)
                    .background(Color.Yellow, RoundedCornerShape(4.dp))
                    .clickable { onTapSelect(id) }
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text("${(currentLayout.scale * 100).roundToInt()}%", color = Color.Black, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun TapControlContent(icon: ImageVector, onTrigger: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) buttonColorPressed else Color.Transparent)
            .border(2.dp, buttonColorDefault, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onTrigger()          // 누르는 즉시 실행
                        tryAwaitRelease()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = buttonColorDefault, modifier = Modifier.fillMaxSize(0.6f))
    }
}
@Composable
fun rememberHorizontalMoveState(
    dasMs: Long,
    arrMs: Long,
    onMoveLeft: () -> Boolean,
    onMoveRight: () -> Boolean,
): Pair<(Boolean) -> Unit, (Boolean) -> Unit> {
    val scope = rememberCoroutineScope()
    var leftHeld by remember { mutableStateOf(false) }
    var rightHeld by remember { mutableStateOf(false) }
    var leftCharged by remember { mutableStateOf(false) }
    var rightCharged by remember { mutableStateOf(false) }
    var activeDirection by remember { mutableStateOf<Int?>(null) }
    var moveJob by remember { mutableStateOf<Job?>(null) }

    fun startLoopFor(direction: Int, alreadyCharged: Boolean) {
        moveJob?.cancel()
        moveJob = scope.launch {
            val trigger = if (direction < 0) onMoveLeft else onMoveRight
            trigger() // 벽에 막혀 실패해도 DAS 충전 자체는 계속 진행돼야 하므로 결과 무시

            if (!alreadyCharged) {
                delay(dasMs)
                if (activeDirection != direction) return@launch
                if (direction < 0) leftCharged = true else rightCharged = true
            }

            while (isActive && activeDirection == direction) {
                val moved = trigger()
                if (moved) {
                    if (arrMs > 0L) delay(arrMs)
                    // arrMs<=0이고 이동 성공이면 딜레이 없이 바로 다음 칸 (기존 "즉시 쭉 밀기" 그대로)
                } else {
                    // 막혔을 때: 여기서 끝내지 않고, 최소 한 프레임 쉬었다가 계속 재시도.
                    // (조각이 바뀌거나 자리가 생기면 다음 프레임에 다시 움직일 수 있음)
                    withFrameNanos {}
                }
            }
        }
    }

    fun onPressChanged(direction: Int, pressed: Boolean) {
        if (direction < 0) leftHeld = pressed else rightHeld = pressed
        if (!pressed) {
            if (direction < 0) leftCharged = false else rightCharged = false
        }

        activeDirection = when {
            pressed -> direction
            leftHeld -> -1
            rightHeld -> 1
            else -> null
        }

        val newDir = activeDirection
        if (newDir != null) {
            val charged = if (newDir < 0) leftCharged else rightCharged
            startLoopFor(newDir, charged)
        } else {
            moveJob?.cancel()
        }
    }

    val onLeft: (Boolean) -> Unit = { pressed -> onPressChanged(-1, pressed) }
    val onRight: (Boolean) -> Unit = { pressed -> onPressChanged(1, pressed) }
    return onLeft to onRight
}





fun pieceColor(kind: PieceKind): Color = when (kind) {
    PieceKind.I -> Color(0xFF0F9BD7)
    PieceKind.O -> Color(0xFFE39F02)
    PieceKind.T -> Color(0xFFAF298A)
    PieceKind.L -> Color(0xFFE35B02)
    PieceKind.J -> Color(0xFF2141C6)
    PieceKind.S -> Color(0xFF59B101)
    PieceKind.Z -> Color(0xFFD70F37)
}

fun cellColor(kind: CellKind): Color = when (kind) {
    CellKind.EMPTY -> Color(0xFF000000)
    CellKind.GARBAGE -> Color(0xFF999999)
    CellKind.I -> Color(0xFF0F9BD7)
    CellKind.O -> Color(0xFFE39F02)
    CellKind.T -> Color(0xFFAF298A)
    CellKind.L -> Color(0xFFE35B02)
    CellKind.J -> Color(0xFF2141C6)
    CellKind.S -> Color(0xFF59B101)
    CellKind.Z -> Color(0xFFD70F37)
}

@Composable
fun TetrisBoardView(
    cells: List<CellKind>,
    modifier: Modifier = Modifier,
    showGrid: Boolean = true,
    ghost: List<CellKind>? = null,
    hint: List<HintStep>? = null
) {
    val columns = 10
    val nominalRows = 20
    val gridColor = Color(0xFF555555)

    Canvas(modifier = modifier) {
        val cellW = size.width / columns
        val cellH = size.height / nominalRows

        if (showGrid) {
            for (col in 0..columns) {
                val xPos = col * cellW
                drawLine(gridColor, Offset(xPos, 0f), Offset(xPos, nominalRows * cellH), strokeWidth = 1f)
            }
            for (row in 0..nominalRows) {
                val yPos = row * cellH
                drawLine(gridColor, Offset(0f, yPos), Offset(columns * cellW, yPos), strokeWidth = 1f)
            }
        }

        for (i in cells.indices) {
            if (cells[i] == CellKind.EMPTY) continue
            val x = i % columns
            val yFromBottom = i / columns
            val screenRow = nominalRows - 1 - yFromBottom
            drawRect(
                color = cellColor(cells[i]),
                topLeft = Offset(x * cellW, screenRow * cellH),
                size = Size(cellW + 0.5f, cellH + 0.5f),
            )
        }


        hint?.let { steps ->
            for (step in steps) {
                val isImmediate = step.depth == 0u
                val alpha = if (isImmediate) 0.8f else (0.5f - step.depth.toFloat() * 0.09f)
                if (alpha <= 0f) continue

                for (i in step.cells.indices) {
                    if (step.cells[i] == CellKind.EMPTY) continue
                    val x = i % columns
                    val yFromBottom = i / columns
                    val screenRow = nominalRows - 1 - yFromBottom
                    drawRect(
                        color = cellColor(step.cells[i]).copy(alpha = alpha),
                        topLeft = Offset(x * cellW, screenRow * cellH),
                        size = Size(cellW + 0.5f, cellH + 0.5f),
                    )
                }
            }
        }

        // 고스트: 테두리만 그려서 실제 블록이랑 구분되게 표시
        ghost?.let {
            for (i in it.indices) {
                if (it[i] == CellKind.EMPTY) continue
                val x = i % columns
                val yFromBottom = i / columns
                val screenRow = nominalRows - 1 - yFromBottom
                drawRect(
                    color = cellColor(it[i]),
                    topLeft = Offset(x * cellW + 1.5f, screenRow * cellH + 1.5f),
                    size = Size(cellW - 3f, cellH - 3f),
                    style = Stroke(width = 2f),
                )
            }
        }
        // 힌트 테두리
        hint?.let { steps ->
            val immediate = steps.firstOrNull { it.depth == 0u } ?: return@let
            for (i in immediate.cells.indices) {
                if (immediate.cells[i] == CellKind.EMPTY) continue
                val x = i % columns
                val yFromBottom = i / columns
                val screenRow = nominalRows - 1 - yFromBottom
                drawRect(
                    color = Color.White,
                    topLeft = Offset(x * cellW, screenRow * cellH),
                    size = Size(cellW + 0.5f, cellH + 0.5f),
                    style = Stroke(width = 2.5f),
                )
            }
        }


    }
}

val pieceShapes: Map<PieceKind, List<Pair<Int, Int>>> = mapOf(
    PieceKind.I to listOf(0 to 1, 1 to 1, 2 to 1, 3 to 1),
    PieceKind.O to listOf(1 to 0, 2 to 0, 1 to 1, 2 to 1),
    PieceKind.T to listOf(0 to 1, 1 to 1, 2 to 1, 1 to 0),
    PieceKind.L to listOf(0 to 1, 1 to 1, 2 to 1, 2 to 0),
    PieceKind.J to listOf(0 to 1, 1 to 1, 2 to 1, 0 to 0),
    PieceKind.S to listOf(1 to 0, 2 to 0, 0 to 1, 1 to 1),
    PieceKind.Z to listOf(0 to 0, 1 to 0, 1 to 1, 2 to 1),
)

@Composable
fun PieceBadge(kind: PieceKind?, boxSize: Int = 32) {
    Box(
        modifier = Modifier
            .width(boxSize.dp)
            .height((boxSize / 2).dp)
            .padding(1.dp),
    ) {
        if (kind != null) {
            val shape = pieceShapes[kind] ?: emptyList()
            val color = pieceColor(kind)
            // 조각마다 폭(2~4칸)이 달라서, 실제 차지하는 범위를 계산해
            // 4x2 기준 박스 안에서 항상 가운데 오도록 오프셋을 줍니다.
            val minX = shape.minOf { it.first }
            val maxX = shape.maxOf { it.first }
            val minY = shape.minOf { it.second }
            val maxY = shape.maxOf { it.second }
            val shapeW = maxX - minX + 1
            val shapeH = maxY - minY + 1

            Canvas(modifier = Modifier.fillMaxSize()) {
                val cellW = size.width / 4
                val cellH = size.height / 2
                val offsetX = (4 - shapeW) * cellW / 2f - minX * cellW
                val offsetY = (2 - shapeH) * cellH / 2f - minY * cellH
                for ((cx, cy) in shape) {
                    drawRect(
                        color = color,
                        topLeft = Offset(cx * cellW + offsetX, cy * cellH + offsetY),
                        size = Size(cellW + 0.5f, cellH + 0.5f),   // 0.5px씩 겹치게
                    )
                }
            }
        }
    }
}
@Composable
fun GarbageQueueBar(entries: List<GarbageEntry>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cellH = size.height / 20 // 보드 한 줄 높이랑 맞춤
        var y = size.height // 바닥부터 위로 쌓음 (큐 맨 앞 = 가장 먼저 활성화될 것)
        for (entry in entries) {
            if (y <= 0f) break
            val h = (cellH * entry.lines.toFloat()).coerceAtMost(y)
            drawRect(
                color = if (entry.active) Color(0xFFD70F37) else Color(0xFFE39F02),
                topLeft = Offset(0f, y - h),
                size = Size(size.width, h),
            )
            y -= h
        }
    }
}



@Composable
fun ButtonVisual(icon: ImageVector, pressed: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(if (pressed) buttonColorPressed else Color.Transparent)
            .border(2.dp, buttonColorDefault, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = buttonColorDefault, modifier = Modifier.fillMaxSize(0.6f))
    }
}


@Composable
fun rememberSoftDropState(
    dasMs: Long,
    arrMs: Long,
    onTrigger: () -> Boolean,
): (Boolean) -> Unit {
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    return { pressed ->
        job?.cancel()
        if (pressed) {
            job = scope.launch {
                onTrigger()
                if (dasMs > 0) delay(dasMs)
                while (isActive) {
                    val moved = onTrigger()
                    if (moved) {
                        if (arrMs > 0L) delay(arrMs)
                    } else {
                        withFrameNanos {}
                    }
                }
            }
        }
    }
}


data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

fun buttonRect(layout: ButtonLayout, baseSizePx: Float, screenWidthPx: Float, screenHeightPx: Float): Rect {
    val sizePx = baseSizePx * layout.scale
    val cx = layout.xFraction * screenWidthPx
    val cy = layout.yFraction * screenHeightPx
    return Rect(cx - sizePx / 2, cy - sizePx / 2, cx + sizePx / 2, cy + sizePx / 2)
}
@Composable
fun MovementSlideZone(
    leftLayout: ButtonLayout,
    rightLayout: ButtonLayout,
    softDropLayout: ButtonLayout,
    baseSizePx: Float,
    screenWidthPx: Float,
    screenHeightPx: Float,
    onLeftPress: (Boolean) -> Unit,
    onRightPress: (Boolean) -> Unit,
    onSoftDropPress: (Boolean) -> Unit,
) {
    // pointerInput(leftLayout, rightLayout, softDropLayout)은 버튼 위치가
    // 바뀔 때만 다시 시작되는데, 설정(DAS/ARR/SDS)이 바뀌어도 이 키들은
    // 안 바뀌니 제스처가 재시작되지 않습니다. 그래서 콜백을 직접 캡처하는
    // 대신 rememberUpdatedState로 감싸서, 제스처는 그대로 두고 "지금 이
    // 순간의 최신 콜백"을 매번 다시 읽어오도록 만듭니다.
    val currentOnLeftPress by rememberUpdatedState(onLeftPress)
    val currentOnRightPress by rememberUpdatedState(onRightPress)
    val currentOnSoftDropPress by rememberUpdatedState(onSoftDropPress)

    val leftRect = buttonRect(leftLayout, baseSizePx, screenWidthPx, screenHeightPx)
    val rightRect = buttonRect(rightLayout, baseSizePx, screenWidthPx, screenHeightPx)
    val softRect = buttonRect(softDropLayout, baseSizePx, screenWidthPx, screenHeightPx)

    val zoneLeft = minOf(leftRect.left, rightRect.left, softRect.left)
    val zoneTop = minOf(leftRect.top, rightRect.top, softRect.top)
    val zoneRight = maxOf(leftRect.right, rightRect.right, softRect.right)
    val zoneBottom = maxOf(leftRect.bottom, rightRect.bottom, softRect.bottom)

    fun hitTest(x: Float, y: Float): String? = when {
        leftRect.contains(x, y) -> "left"
        rightRect.contains(x, y) -> "right"
        softRect.contains(x, y) -> "soft"
        else -> null
    }

    fun fire(id: String?, pressed: Boolean) {
        when (id) {
            "left" -> currentOnLeftPress(pressed)
            "right" -> currentOnRightPress(pressed)
            "soft" -> currentOnSoftDropPress(pressed)
        }
    }

    val density = LocalDensity.current
    Box(
        modifier = with(density) {
            Modifier
                .offset(x = zoneLeft.toDp(), y = zoneTop.toDp())
                .size((zoneRight - zoneLeft).toDp(), (zoneBottom - zoneTop).toDp())
        }.pointerInput(leftLayout, rightLayout, softDropLayout) {
            val targetByPointer = mutableMapOf<PointerId, String?>()

            awaitEachGesture {
                do {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    for (change in event.changes) {
                        val pos = change.position + Offset(zoneLeft, zoneTop)
                        val newTarget = if (change.pressed) hitTest(pos.x, pos.y) else null
                        val oldTarget = targetByPointer[change.id]
                        if (oldTarget == newTarget) continue

                        targetByPointer[change.id] = newTarget

                        if (oldTarget != null && targetByPointer.values.none { it == oldTarget }) {
                            fire(oldTarget, false)
                        }
                        if (newTarget != null && targetByPointer.entries.count { it.value == newTarget } == 1) {
                            fire(newTarget, true)
                        }
                    }
                } while (event.changes.any { it.pressed })

                targetByPointer.values.filterNotNull().toSet().forEach { fire(it, false) }
                targetByPointer.clear()
            }
        },
    )
}

@Composable
fun RotationSlideZone(
    cwLayout: ButtonLayout,
    ccwLayout: ButtonLayout,
    baseSizePx: Float,
    screenWidthPx: Float,
    screenHeightPx: Float,
    onCwPressedChange: (Boolean) -> Unit,
    onCcwPressedChange: (Boolean) -> Unit,
    onCw: () -> Unit,
    onCcw: () -> Unit,
) {
    val cwRect = buttonRect(cwLayout, baseSizePx, screenWidthPx, screenHeightPx)
    val ccwRect = buttonRect(ccwLayout, baseSizePx, screenWidthPx, screenHeightPx)

    val zoneLeft = minOf(cwRect.left, ccwRect.left)
    val zoneTop = minOf(cwRect.top, ccwRect.top)
    val zoneRight = maxOf(cwRect.right, ccwRect.right)
    val zoneBottom = maxOf(cwRect.bottom, ccwRect.bottom)

    fun hitTest(x: Float, y: Float): String? = when {
        cwRect.contains(x, y) -> "cw"
        ccwRect.contains(x, y) -> "ccw"
        else -> null
    }

    val density = LocalDensity.current
    Box(
        modifier = with(density) {
            Modifier
                .offset(x = zoneLeft.toDp(), y = zoneTop.toDp())
                .size((zoneRight - zoneLeft).toDp(), (zoneBottom - zoneTop).toDp())
        }.pointerInput(cwLayout, ccwLayout) {
            // 손가락별로 지금 어느 버튼 위에 있는지 + 버튼별 누르고 있는 손가락 수
            val targetByPointer = mutableMapOf<PointerId, String?>()
            val pressCount = mutableMapOf<String, Int>()

            fun updateVisual(id: String) {
                val pressed = (pressCount[id] ?: 0) > 0
                if (id == "cw") onCwPressedChange(pressed) else onCcwPressedChange(pressed)
            }

            awaitEachGesture {
                do {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    for (change in event.changes) {
                        val pos = change.position + Offset(zoneLeft, zoneTop)
                        val newTarget = if (change.pressed) hitTest(pos.x, pos.y) else null
                        val oldTarget = targetByPointer[change.id]
                        if (oldTarget == newTarget) continue

                        targetByPointer[change.id] = newTarget

                        if (oldTarget != null) {
                            pressCount[oldTarget] = (pressCount[oldTarget] ?: 1) - 1
                            updateVisual(oldTarget)
                        }
                        if (newTarget != null) {
                            pressCount[newTarget] = (pressCount[newTarget] ?: 0) + 1
                            updateVisual(newTarget)
                            // 새로 들어올 때마다 탭 한 번으로 취급해서 즉시 실행
                            if (newTarget == "cw") onCw() else onCcw()
                        }
                    }
                } while (event.changes.any { it.pressed })

                targetByPointer.values.filterNotNull().toSet().forEach { id ->
                    pressCount[id] = 0
                    updateVisual(id)
                }
                targetByPointer.clear()
            }
        },
    )
}


@Composable
fun ResultScreen(won: Boolean, stats: MatchStats, canUndo: Boolean, onUndo: () -> Unit, onRestart: () -> Unit) {
    var buttonsEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(500L)
        buttonsEnabled = true
    }

    val app = if (stats.playerPieces > 0u) stats.playerTotalAttack.toDouble() / stats.playerPieces.toDouble() else 0.0
    val vs = if (stats.playerPieces > 0u)
        (stats.playerTotalAttack + stats.playerGarbageCleared).toDouble() / stats.playerPieces.toDouble() * 200.0
    else 0.0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE000000))
            // 이 오버레이가 떠 있는 동안은 뒤에 있는 보드/버튼이 터치되지
            // 않도록 여기서 터치를 다 먹어버립니다.
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                .padding(24.dp)
                .width(280.dp),
        ) {
            Text(
                if (won) "WIN" else "LOSE",
                color = if (won) settingsAccentColor else Color(0xFFD70F37),
                fontSize = 28.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(modifier = Modifier.height(20.dp))

            @Composable
            fun row(label: String, value: String) {
                Row(
                    modifier = Modifier.padding(vertical = 3.dp).width(230.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = Color.Gray, fontSize = 13.sp, maxLines = 1, modifier = Modifier.width(150.dp))
                    Text(value, color = Color.White, fontSize = 13.sp)
                }
            }

            row("총 미노", "${stats.playerPieces}")
            row("총 공격", "${stats.playerTotalAttack}")
            row("보낸 줄", "${stats.playerLinesSent}")
            row("APP", "%.2f".format(app))
            row("VS", "%.2f".format(vs))
            Spacer(modifier = Modifier.height(10.dp))
            row("AI 공격", "${stats.aiTotalAttack}")
            row("AI가 보낸 줄", "${stats.aiLinesSent}")
            row("상쇄한 방해 줄", "${stats.playerGarbageCancelled}")
            row("생성된 방해 줄", "${stats.playerGarbageCreated}")
            row("지운 방해 줄", "${stats.playerGarbageCleared}")

            Spacer(modifier = Modifier.height(20.dp))

            if (canUndo) {
                Button(
                    onClick = onUndo,
                    enabled = buttonsEnabled,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444)),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("되돌리기") }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = onRestart,
                enabled = buttonsEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = settingsAccentColor),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("다시하기") }
        }
    }
}

@Composable
fun GameScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var match by remember { mutableStateOf(Match()) }
    var playerGarbage by remember { mutableStateOf(match.getPlayerGarbageQueue()) }
    var aiGarbage by remember { mutableStateOf(match.getAiGarbageQueue()) }
    var playerField by remember { mutableStateOf(match.getPlayerField()) }
    var aiField by remember { mutableStateOf(match.getAiField()) }
    var playerHold by remember { mutableStateOf(match.getPlayerHold()) }
    var playerNext by remember { mutableStateOf(match.getPlayerNextQueue()) }
    var playerGhost by remember { mutableStateOf(match.getPlayerGhostField()) }
    var leftPressed by remember { mutableStateOf(false) }
    var rightPressed by remember { mutableStateOf(false) }
    var softDropPressed by remember { mutableStateOf(false) }
    var cwPressed by remember { mutableStateOf(false) }
    var ccwPressed by remember { mutableStateOf(false) }
    var selectedButtonId by remember { mutableStateOf<String?>(null) }
    var sizeInputText by remember { mutableStateOf("") }
    var snapEnabled by remember { mutableStateOf(false) }
    var gridSizeDp by remember { mutableStateOf(20.dp) }
    var gridSizeInputText by remember { mutableStateOf("20") }
    var settings by remember { mutableStateOf(loadSettings(context)) }
    var showSettings by remember { mutableStateOf(false) }
    var matchStats by remember { mutableStateOf(match.getMatchStats()) }
    var gameOverSide by remember { mutableStateOf(match.getGameOverSide()) }
    var undoCount by remember { mutableStateOf(match.getUndoCount()) }
    var hintEnabled by remember { mutableStateOf(false) }
    var playerHintPlan by remember { mutableStateOf<List<HintStep>>(emptyList()) }




    LaunchedEffect(settings.garbageSameColumnChance) {
        match.setGarbageSameColumnChance(settings.garbageSameColumnChance.toDouble())
    }
    LaunchedEffect(settings.garbageSameColumnChance) {
        match.setGarbageSameColumnChance(settings.garbageSameColumnChance.toDouble())
    }
    LaunchedEffect(settings.aiMinNodes) { match.setAiMinNodes(settings.aiMinNodes.toUInt()) }
    LaunchedEffect(settings.aiMaxNodes) { match.setAiMaxNodes(settings.aiMaxNodes.toUInt()) }
    LaunchedEffect(settings.aiThreads) { match.setAiThreads(settings.aiThreads.toUInt()) }
    LaunchedEffect(settings.aiSpeculate) { match.setAiSpeculate(settings.aiSpeculate) }
    LaunchedEffect(settings.garbageDelay) {
        match.setGarbageDelay(settings.garbageDelay.toUInt())
    }


    fun refresh() {
        playerField = match.getPlayerField()
        aiField = match.getAiField()
        playerHold = match.getPlayerHold()
        playerNext = match.getPlayerNextQueue()
        playerGarbage = match.getPlayerGarbageQueue()
        aiGarbage = match.getAiGarbageQueue()
        playerGhost = match.getPlayerGhostField()
        matchStats = match.getMatchStats()
        gameOverSide = match.getGameOverSide()
        undoCount = match.getUndoCount()

        //Log.d("ColdClearVS", "gameOver=${match.isGameOver()}, side=$gameOverSide")
    }

    suspend fun refreshHint() {
        playerHintPlan = if (hintEnabled) {
            withContext(Dispatchers.Default) { match.getPlayerHintPlan(5u) }
        } else {
            emptyList()
        }
    }

    var boardWidth by remember { mutableStateOf(64.dp) } // 보드 Row에서 계산되면 갱신됨
    var editMode by remember { mutableStateOf(false) }

    val buttonLayouts = remember { mutableStateMapOf<String, ButtonLayout>().apply { putAll(loadButtonLayouts(context)) } }
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black).padding(12.dp)) {
        // 메인 레이아웃

        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val baseButtonSize = (boardWidth / 10) * 3

        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(100.dp))

            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val sidePanelWidth = 56.dp   // HOLD/NEXT 배지 크기
                val garbageBarWidth = 10.dp
                val spacerWidth = 4.dp * 3   // Spacer 3개

                // 가로로 들어갈 수 있는 만큼만 보드 폭으로 쓰고, 세로는 거기서 역산(10:20 비율)
                val computedBoardWidth = maxWidth - sidePanelWidth * 2 - garbageBarWidth - spacerWidth
                boardWidth = computedBoardWidth
                val boardHeight = computedBoardWidth * 2f

                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(sidePanelWidth),
                    ) {
                        Text("HOLD", color = Color.White, fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(15.dp))
                        PieceBadge(playerHold, boxSize = 56)
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    GarbageQueueBar(
                        playerGarbage,
                        modifier = Modifier.width(garbageBarWidth).height(boardHeight),
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    Box(
                        modifier = Modifier.width(computedBoardWidth).height(boardHeight),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        TetrisBoardView(
                            playerField,
                            modifier = Modifier.fillMaxSize(),
                            ghost = playerGhost,
                            hint = playerHintPlan,
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(15.dp),
                        modifier = Modifier.width(sidePanelWidth),
                    ) {
                        Text("NEXT", color = Color.White, fontSize = 10.sp)
                        playerNext.forEach { PieceBadge(it, boxSize = 56) }
                    }
                }
            }




        }
        val (onLeftPressLogic, onRightPressLogic) = rememberHorizontalMoveState(
            dasMs = settings.dasMs, arrMs = settings.arrMs,
            onMoveLeft = { val m = match.playerMoveLeft(); refresh(); m },
            onMoveRight = { val m = match.playerMoveRight(); refresh(); m },
        )

        if (editMode && snapEnabled) {
            GridOverlay(
                gridSizePx = with(density) { gridSizeDp.toPx() },
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                modifier = Modifier.matchParentSize(),
            )
        }

        for (id in defaultButtonIds) {
            val layout = buttonLayouts[id] ?: continue
            PositionedControl(
                id = id,
                layout = layout,
                editMode = editMode,
                baseSize = baseButtonSize,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                snapEnabled = snapEnabled,
                gridSizePx = with(density) { gridSizeDp.toPx() },
                onLayoutChange = { buttonLayouts[id] = it },
                onTapSelect = { tappedId ->
                    selectedButtonId = tappedId
                    sizeInputText = ((buttonLayouts[tappedId]?.scale ?: 1f) * 100).roundToInt().toString()
                },
            ) {
                when (id) {
                    "left" -> ButtonVisual(Icons.Filled.KeyboardArrowLeft, pressed = leftPressed)
                    "right" -> ButtonVisual(Icons.Filled.KeyboardArrowRight, pressed = rightPressed)
                    "softDrop" -> ButtonVisual(Icons.Filled.KeyboardArrowDown, pressed = softDropPressed)
                    "cw" -> ButtonVisual(Icons.Filled.RotateRight, pressed = cwPressed)
                    "ccw" -> ButtonVisual(Icons.Filled.RotateLeft, pressed = ccwPressed)
                    "hold" -> TapControlContent(Icons.Filled.SwapHoriz) { match.playerHold(); refresh() }
                    "hardDrop" -> TapControlContent(Icons.Filled.KeyboardDoubleArrowDown) {
                        match.playerHardDrop()
                        refresh()
                        scope.launch {
                            withContext(Dispatchers.Default) { match.aiTakeTurn() }
                            refresh()
                            refreshHint()   // AI 턴 끝난 뒤, 새로 스폰된 조각 기준으로 힌트 갱신
                        }
                    }
                    "undo" -> TapControlContent(Icons.Filled.Undo) {
                        Log.d("ColdClearVS", "언도 전 NEXT: $playerNext")
                        match.undo()
                        refresh()
                        Log.d("ColdClearVS", "언도 후 NEXT: $playerNext")
                        scope.launch { refreshHint() }
                    }
                    "aiTurn" -> TapControlContent(Icons.Filled.Pause) {
                        scope.launch {
                            withContext(Dispatchers.Default) { match.aiOnlyTurn() }
                            refresh()
                            refreshHint()
                        }
                    }
                }
            }
        }
        val onSoftDropLogic = rememberSoftDropState(
            dasMs = 0L, arrMs = sdsToArrMs(settings.sdsLevel),
            onTrigger = { val m = match.playerSoftDrop(); refresh(); m },
        )
        val onLeftPress: (Boolean) -> Unit = { p -> leftPressed = p; onLeftPressLogic(p) }
        val onRightPress: (Boolean) -> Unit = { p -> rightPressed = p; onRightPressLogic(p) }
        val onSoftDropPress: (Boolean) -> Unit = { p -> softDropPressed = p; onSoftDropLogic(p) }

        if (!editMode) {
            MovementSlideZone(
                leftLayout = buttonLayouts["left"]!!,
                rightLayout = buttonLayouts["right"]!!,
                softDropLayout = buttonLayouts["softDrop"]!!,
                baseSizePx = with(density) { baseButtonSize.toPx() },
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                onLeftPress = onLeftPress,
                onRightPress = onRightPress,
                onSoftDropPress = onSoftDropPress,
            )
        }
        if (!editMode) {
            RotationSlideZone(
                cwLayout = buttonLayouts["cw"]!!,
                ccwLayout = buttonLayouts["ccw"]!!,
                baseSizePx = with(density) { baseButtonSize.toPx() },
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                onCwPressedChange = { cwPressed = it },
                onCcwPressedChange = { ccwPressed = it },
                onCw = { match.playerRotateCw(); refresh() },
                onCcw = { match.playerRotateCcw(); refresh() },
            )
        }

//       //임시
//        Box(
//            modifier = Modifier
//                .background(Color(0xFF444444), RoundedCornerShape(6.dp))
//                .clickable {
//                    val lines = buttonLayouts.entries.joinToString("\n") { (id, l) ->
//                        "    map[\"$id\"] = ButtonLayout(xFraction = ${"%.3f".format(l.xFraction)}f, yFraction = ${"%.3f".format(l.yFraction)}f, scale = ${"%.2f".format(l.scale)}f)"
//                    }
//                    Log.d("ButtonLayout", "\n$lines")
//                }
//                .padding(horizontal = 100.dp, vertical = 60.dp),
//        ) {
//            Text("좌표 출력", color = Color.White, fontSize = 12.sp)
//        }






        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (editMode) Color.Yellow else Color(0xFF444444))
                .clickable {
                    if (editMode) {
                        saveButtonLayouts(context, buttonLayouts)
                        editMode = false
                    } else {
                        showSettings = true
                    }
                }
                .padding(8.dp),
        ) {
            Icon(
                imageVector = if (editMode) Icons.Filled.Check else Icons.Filled.Settings,
                contentDescription = null,
                tint = if (editMode) Color.Black else Color.White,
                modifier = Modifier.size(20.dp),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 8.dp, start = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (hintEnabled) settingsAccentColor else Color(0xFF444444), RoundedCornerShape(6.dp))
                .clickable {
                    hintEnabled = !hintEnabled
                    scope.launch { refreshHint() }
                }
                .padding(8.dp),
        ) {
            Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }


        if (editMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 48.dp, start = 8.dp)
                    .background(Color(0xFF222222), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .background(if (snapEnabled) Color.Yellow else Color(0xFF444444), RoundedCornerShape(6.dp))
                        .clickable { snapEnabled = !snapEnabled }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("그리드", color = if (snapEnabled) Color.Black else Color.White, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextField(
                    value = gridSizeInputText,
                    onValueChange = {
                        gridSizeInputText = it.filter { c -> c.isDigit() }
                        gridSizeInputText.toIntOrNull()?.let { v -> gridSizeDp = v.coerceIn(4, 100).dp }
                    },
                    modifier = Modifier.width(60.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("dp", fontSize = 10.sp) },
                )
            }
        }

        if (editMode && selectedButtonId != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .background(Color(0xFF222222), RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("크기(%)", color = Color.White, fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    TextField(
                        value = sizeInputText,
                        onValueChange = { sizeInputText = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.width(70.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        val id = selectedButtonId ?: return@Button
                        val percent = sizeInputText.toIntOrNull()?.coerceIn(50, 200) ?: 100
                        val current = buttonLayouts[id] ?: return@Button
                        buttonLayouts[id] = current.copy(scale = percent / 100f)
                        selectedButtonId = null
                    }) { Text("적용") }
                    Spacer(modifier = Modifier.width(4.dp))
                    Button(onClick = { selectedButtonId = null }) { Text("닫기") }
                }
            }
        }

        // AI 필드: 우상단, 기존(64dp)의 절반인 32dp
        // AI 필드: 우상단, 기존(64dp)의 절반인 32dp
        Column(
            modifier = Modifier.align(Alignment.TopEnd).width(53.dp).padding(end = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(350.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                val aiBoardWidth = 48.dp
                val aiBoardHeight = aiBoardWidth * 2f // 10:20 비율

                GarbageQueueBar(
                    aiGarbage,
                    modifier = Modifier.width(2.dp).height(aiBoardHeight),
                )

                Spacer(modifier = Modifier.width(2.dp))

                TetrisBoardView(
                    aiField,
                    modifier = Modifier
                        .width(aiBoardWidth)
                        .height(aiBoardHeight)
                        .border(1.dp, Color(0xFF555555)),
                    showGrid = false,
                )
            }
        }

        //설정창. 맨 밑에 있어야 함
        if (showSettings) {
            SettingsPanel(
                settings = settings,
                onSettingsChange = { settings = it },
                onClose = {
                    saveSettings(context, settings)
                    showSettings = false
                },
                onEditButtons = {
                    saveSettings(context, settings)
                    showSettings = false
                    editMode = true
                },
            )
        }
        //종료 후 통계라서 진짜 맨 밑임;;
        gameOverSide?.let { side ->
            ResultScreen(
                won = side == GameOverSide.AI,
                stats = matchStats,
                canUndo = undoCount > 0u,
                onUndo = {
                    match.undo()
                    refresh()
                    scope.launch { refreshHint() }
                },
                onRestart = {
                    match = Match()
                    refresh()
                },
            )
        }


    }
}
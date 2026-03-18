package com.example.railwaypagerdemod

import android.media.RingtoneManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

// ── Colour tokens ─────────────────────────────────────────────────────────────
private val BlueStart  = Color(0xFF1565C0)
private val BlueEnd    = Color(0xFF1E88E5)
private val CardBg     = Color(0x26FFFFFF)   // white 15% alpha → frosted glass
private val OnCard     = Color.White
private val DimOnCard  = Color(0xCCFFFFFF)   // white 80%
private val LogBg      = Color(0xFF0D1B2A)   // deep navy for log pane
private val LogText    = Color(0xFF90CAF9)   // light-blue monospace

@Suppress("DEPRECATION")
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary   = BlueStart,
                    secondary = BlueEnd,
                    surface   = Color(0xFF0D1B2A),
                    background = Color(0xFF0D1B2A)
                )
            ) {
                RailwayPagerApp(onNewMessage = { playNotificationSound() })
            }
        }
    }

    private fun playNotificationSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(applicationContext, uri)?.play()
        } catch (_: Exception) {}
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Root composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun RailwayPagerApp(
    vm: MainViewModel = viewModel(),
    onNewMessage: () -> Unit = {}
) {
    val state by vm.state.collectAsState()

    // Notify on new POCSAG messages
    var prevLog by remember { mutableStateOf("") }
    LaunchedEffect(state.logMessages) {
        if (state.logMessages.length > prevLog.length && state.logMessages.contains("[MSG]")) {
            onNewMessage()
        }
        prevLog = state.logMessages
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(BlueStart, BlueEnd))
            )
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // App title
            AppHeader(isConnected = state.isConnected, signalStrength = state.signalStrength)

            // Train info cards (two side-by-side)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TrainInfoCard(
                    modifier = Modifier.weight(1f),
                    title    = "列车位置",
                    items    = listOf(
                        "车号"  to state.vehicleId.ifEmpty { "—" },
                        "线路"  to state.route.ifEmpty     { "—" },
                        "纬度"  to state.latitude.ifEmpty  { "—" },
                        "经度"  to state.longitude.ifEmpty { "—" }
                    )
                )
                TrainInfoCard(
                    modifier = Modifier.weight(1f),
                    title    = "运行数据",
                    items    = listOf(
                        "车次号" to state.trainNo.ifEmpty { "—" },
                        "速度"   to if (state.speed.isNotEmpty())   "${state.speed} km/h"  else "—",
                        "公里标" to if (state.mileage.isNotEmpty()) "${state.mileage} km" else "—"
                    )
                )
            }

            // Connection config card
            ConnectionCard(
                isConnected   = state.isConnected,
                onConnect     = { host, port -> vm.connect(host, port) },
                onDisconnect  = { vm.disconnect() }
            )

            // Raw log
            LogPane(
                modifier = Modifier.weight(1f),
                text     = state.logMessages
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AppHeader(isConnected: Boolean, signalStrength: Float) {
    val animatedStrength by animateFloatAsState(
        targetValue = signalStrength,
        label       = "signal"
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment         = Alignment.CenterVertically,
        horizontalArrangement     = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text       = "铁路调度报文",
                color      = Color.White,
                fontSize   = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text     = "Railway Pager Demodulator",
                color    = DimOnCard,
                fontSize = 12.sp
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Signal icon + bar
            Icon(
                imageVector = Icons.Default.SignalCellular4Bar,
                contentDescription = "信号强度",
                tint   = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp)
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text     = "${(animatedStrength * 100).toInt()}%",
                    color    = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                LinearProgressIndicator(
                    progress    = { animatedStrength },
                    modifier    = Modifier.width(72.dp).height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color       = Color.White,
                    trackColor  = Color.White.copy(alpha = 0.25f)
                )
            }

            // Connection status dot
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (isConnected) Color(0xFF69F0AE)   // bright green
                        else             Color(0xFFFF5252)   // red
                    )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Info card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TrainInfoCard(
    modifier: Modifier = Modifier,
    title: String,
    items: List<Pair<String, String>>
) {
    Surface(
        modifier = modifier,
        shape    = RoundedCornerShape(14.dp),
        color    = CardBg,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text       = title,
                color      = Color.White,
                fontSize   = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f), thickness = 0.5.dp)
            items.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text     = label,
                        color    = DimOnCard,
                        fontSize = 12.sp
                    )
                    Text(
                        text       = value,
                        color      = OnCard,
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Connection card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConnectionCard(
    isConnected:  Boolean,
    onConnect:    (String, Int) -> Unit,
    onDisconnect: () -> Unit
) {
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("14423") }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CardBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text       = "连接设置",
                color      = Color.White,
                fontSize   = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f), thickness = 0.5.dp)

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassTextField(
                    modifier      = Modifier.weight(2f),
                    value         = host,
                    onValueChange = { host = it },
                    label         = "主机名 / IP",
                    enabled       = !isConnected
                )
                GlassTextField(
                    modifier        = Modifier.weight(1f),
                    value           = port,
                    onValueChange   = { port = it },
                    label           = "端口",
                    enabled         = !isConnected,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            Button(
                onClick  = {
                    if (isConnected) {
                        onDisconnect()
                    } else {
                        val portInt = port.toIntOrNull() ?: return@Button
                        onConnect(host.trim(), portInt)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape    = RoundedCornerShape(10.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) Color(0x40FF5252) else Color(0xFF1565C0),
                    contentColor   = Color.White
                )
            ) {
                Text(
                    text       = if (isConnected) "断开连接" else "建立连接",
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Log pane
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LogPane(modifier: Modifier = Modifier, text: String) {
    val scrollState = rememberScrollState()
    LaunchedEffect(text) { scrollState.animateScrollTo(scrollState.maxValue) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        color    = LogBg
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF69F0AE))
                )
                Text(
                    text       = "接收日志",
                    color      = LogText,
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
            }
            HorizontalDivider(
                color     = Color.White.copy(alpha = 0.08f),
                thickness = 0.5.dp
            )
            Text(
                text     = text.ifEmpty { "等待数据…" },
                color    = if (text.isEmpty()) LogText.copy(alpha = 0.4f) else LogText,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helper – glass-style text field
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GlassTextField(
    modifier:        Modifier        = Modifier,
    value:           String,
    onValueChange:   (String) -> Unit,
    label:           String,
    enabled:         Boolean         = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    OutlinedTextField(
        value           = value,
        onValueChange   = onValueChange,
        label           = { Text(label, fontSize = 11.sp) },
        singleLine      = true,
        enabled         = enabled,
        keyboardOptions = keyboardOptions,
        modifier        = modifier,
        textStyle       = LocalTextStyle.current.copy(fontSize = 13.sp),
        colors          = OutlinedTextFieldDefaults.colors(
            focusedTextColor        = Color.White,
            unfocusedTextColor      = Color.White,
            disabledTextColor       = Color.White.copy(alpha = 0.5f),
            focusedBorderColor      = Color.White,
            unfocusedBorderColor    = Color.White.copy(alpha = 0.4f),
            disabledBorderColor     = Color.White.copy(alpha = 0.2f),
            focusedLabelColor       = Color.White,
            unfocusedLabelColor     = DimOnCard,
            disabledLabelColor      = DimOnCard.copy(alpha = 0.4f),
            cursorColor             = Color.White,
            focusedContainerColor   = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor  = Color.Transparent
        ),
        shape = RoundedCornerShape(8.dp)
    )
}


package com.example.railwaypagerdemod

import android.media.RingtoneManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/** Brand blue used throughout the UI. */
private val BrandBlue = Color(0xFF3F83E9)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
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

@Composable
fun RailwayPagerApp(
    viewModel:     MainViewModel = viewModel(),
    onNewMessage:  () -> Unit    = {}
) {
    val state by viewModel.state.collectAsState()

    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("14423") }

    // Play notification sound when new messages arrive
    var prevLog by remember { mutableStateOf("") }
    LaunchedEffect(state.logMessages) {
        if (state.logMessages != prevLog && state.logMessages.contains("[MSG]")) {
            onNewMessage()
        }
        prevLog = state.logMessages
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandBlue)
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ── Status info ───────────────────────────────────────────────────────
        InfoRow(label = "车号",   value = state.vehicleId.ifEmpty { "-" })
        InfoRow(label = "线路",   value = state.route.ifEmpty     { "-" }, bold = true, fontSize = 18)
        InfoRow(label = "纬度",   value = state.latitude.ifEmpty  { "-" })
        InfoRow(label = "经度",   value = state.longitude.ifEmpty { "-" })
        InfoRow(label = "车次号", value = state.trainNo.ifEmpty   { "-" })
        InfoRow(label = "速度",   value = if (state.speed.isNotEmpty()) "${state.speed} km/h" else "-")
        InfoRow(label = "公里标", value = if (state.mileage.isNotEmpty()) "${state.mileage} km" else "-")

        // ── Signal strength ───────────────────────────────────────────────────
        val pct = (state.signalStrength * 100).toInt()
        InfoRow(label = "信号强度", value = "$pct%")
        LinearProgressIndicator(
            progress         = { state.signalStrength },
            modifier         = Modifier.fillMaxWidth().height(8.dp),
            color            = Color.White,
            trackColor       = Color.White.copy(alpha = 0.3f)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ── Connection inputs ─────────────────────────────────────────────────
        OutlinedTextField(
            value         = host,
            onValueChange = { host = it },
            label         = { Text("主机名", color = Color.White) },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            colors        = whiteOutlinedTextFieldColors()
        )
        OutlinedTextField(
            value         = port,
            onValueChange = { port = it },
            label         = { Text("端口号", color = Color.White) },
            singleLine    = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier      = Modifier.fillMaxWidth(),
            colors        = whiteOutlinedTextFieldColors()
        )

        // ── Log output ────────────────────────────────────────────────────────
        val scrollState = rememberScrollState()
        LaunchedEffect(state.logMessages) { scrollState.animateScrollTo(scrollState.maxValue) }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.White)
        ) {
            Text(
                text     = state.logMessages,
                color    = Color.Black,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(10.dp)
            )
        }

        // ── Connect / Disconnect button ───────────────────────────────────────
        Button(
            onClick  = {
                if (state.isConnected) {
                    viewModel.disconnect()
                } else {
                    val portInt = port.toIntOrNull() ?: return@Button
                    viewModel.connect(host.trim(), portInt)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(containerColor = Color.White)
        ) {
            Text(
                text  = if (state.isConnected) "断开" else "连接",
                color = BrandBlue,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ── Helper composables ────────────────────────────────────────────────────────

@Composable
private fun InfoRow(
    label:    String,
    value:    String,
    bold:     Boolean = false,
    fontSize: Int     = 15
) {
    Text(
        text       = "$label: $value",
        color      = Color.White,
        fontSize   = fontSize.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        modifier   = Modifier.padding(vertical = 1.dp)
    )
}

@Composable
private fun whiteOutlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor        = Color.White,
    unfocusedTextColor      = Color.White,
    focusedBorderColor      = Color.White,
    unfocusedBorderColor    = Color.White.copy(alpha = 0.6f),
    cursorColor             = Color.White,
    focusedLabelColor       = Color.White,
    unfocusedLabelColor     = Color.White.copy(alpha = 0.6f)
)

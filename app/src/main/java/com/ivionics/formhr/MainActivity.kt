package com.ivionics.formhr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

private val Ink = Color(0xFFF4F7FA)
private val Muted = Color(0xFF8F9AA7)
private val Panel = Color(0xFF12171D)
private val PanelSelected = Color(0xFF173029)
private val Mint = Color(0xFF55E6B1)
private val Red = Color(0xFFFF5F70)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: MainViewModel = viewModel()
            val state by model.state.collectAsState()
            val startWithPermissions = startWithPermissions(model)

            DisposableEffect(state.running) {
                if (state.running) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }

            FormHrTheme {
                FormHrScreen(
                    state = state,
                    onStart = startWithPermissions,
                    onStop = model::stop,
                )
            }
        }
    }

}

@Composable
private fun MainActivity.startWithPermissions(model: MainViewModel): () -> Unit {
    val requestedPermissions = requiredPermissions()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (requestedPermissions.all(::hasPermission)) model.start() else model.permissionDenied()
    }
    return {
        val missing = requestedPermissions.filterNot(::hasPermission)
        if (missing.isEmpty()) model.start() else launcher.launch(missing.toTypedArray())
    }
}

private fun MainActivity.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun requiredPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= 36) {
        add("android.permission.health.READ_HEART_RATE")
    } else {
        add(Manifest.permission.BODY_SENSORS)
    }
}

@Composable
private fun FormHrScreen(
    state: MainUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(state.running) {
        if (state.running) scrollState.animateScrollTo(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070A))
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusPill(state)
        Spacer(Modifier.height(12.dp))

        Text(
            text = if (state.beatsPerMinute > 0) state.beatsPerMinute.toString() else "—",
            color = Ink,
            fontSize = 58.sp,
            lineHeight = 58.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "BPM",
            color = Red,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(10.dp))

        if (!state.running) {
            SectionLabel("SOURCE")
            Text(
                text = "Pixel Watch optical heart-rate sensor",
                color = Ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PanelSelected)
                    .padding(horizontal = 13.dp, vertical = 14.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.sensorStatus,
                color = Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            if (state.peripheralStatus.detail != "Stopped") {
                Spacer(Modifier.height(7.dp))
                MessageCard(state.peripheralStatus.detail)
            }
            Spacer(Modifier.height(5.dp))
        } else {
            RunningDetails(state)
            Spacer(Modifier.height(14.dp))
            Text(
                text = state.sensorStatus,
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
        }


        if (state.events.isNotEmpty()) {
            EventTrail(state.events)
            Spacer(Modifier.height(14.dp))
        }

        Button(
            onClick = if (state.running) onStop else onStart,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.running) Color(0xFF392127) else Mint,
                contentColor = if (state.running) Red else Color(0xFF04130E),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
        ) {
            Text(
                text = if (state.running) "Stop watch HR" else "Start watch HR",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun MessageCard(message: String) {
    Text(
        text = message,
        color = Red,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF28171C))
            .padding(11.dp),
    )
}

@Composable
private fun EventTrail(events: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = "GATT TRACE",
            color = Muted,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
        )
        Spacer(Modifier.height(5.dp))
        events.forEachIndexed { index, event ->
            Text(
                text = "${index + 1}  $event",
                color = if (index == events.lastIndex) Mint else Muted,
                fontSize = 9.sp,
                lineHeight = 13.sp,
            )
        }
    }
}

@Composable
private fun StatusPill(state: MainUiState) {
    val connected = state.peripheralStatus.subscribedDevices > 0
    val active = state.running
    val color = when {
        connected -> Mint
        active -> Color(0xFFFFC857)
        else -> Muted
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Panel)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(
            text = when {
                connected -> "STREAMING"
                active -> "ON AIR"
                else -> "READY"
            },
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.3.sp,
        )
    }
}

@Composable
private fun RunningDetails(state: MainUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Panel)
            .padding(15.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.discoveryProfile.advertisedName,
            color = Ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = state.peripheralStatus.detail,
            color = if (state.peripheralStatus.subscribedDevices > 0) Mint else Muted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            Metric("LINKS", state.peripheralStatus.connectedDevices.toString())
            Metric("SUBS", state.peripheralStatus.subscribedDevices.toString())
            Metric("UUID", "180D")
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(text = label, color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Muted,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 7.dp, top = 5.dp),
    )
}

@Composable
private fun FormHrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Mint,
            secondary = Red,
            background = Color(0xFF05070A),
            surface = Panel,
            onBackground = Ink,
            onSurface = Ink,
        ),
        content = content,
    )
}

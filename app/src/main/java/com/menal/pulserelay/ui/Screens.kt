package com.menal.pulserelay.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.text.InputType
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.menal.pulserelay.data.SettingsValidation
import com.menal.pulserelay.data.SettingsValidationResult
import com.menal.pulserelay.data.StoredSettings
import com.menal.pulserelay.data.ValidSettings
import com.menal.pulserelay.monitor.ConnectionState
import com.menal.pulserelay.monitor.MonitorSnapshot
import com.menal.pulserelay.net.ConnectionProbe
import com.menal.pulserelay.permissions.PermissionStatus
import kotlinx.coroutines.launch

enum class AppScreen {
    MAIN,
    SETTINGS,
    SETUP,
}

@Composable
fun MainScreen(
    snapshot: MonitorSnapshot,
    permissions: PermissionStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onSetup: () -> Unit,
) {
    val listState = rememberScalingLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            state = listState,
            autoCentering = null,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = snapshot.bpm?.toString() ?: "--",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Text(
                    text = "bpm",
                    fontSize = 11.sp,
                    color = Color(0xFFB0B0B0),
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Text(
                    text = connectionLabel(snapshot.connection),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
            if (snapshot.error != null) {
                item {
                    Text(
                        text = snapshot.error,
                        fontSize = 10.sp,
                        color = Color(0xFFFF8A80),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (!permissions.ready) {
                item {
                    Text(
                        text = "Setup needed",
                        fontSize = 11.sp,
                        color = Color(0xFFFFCC80),
                        textAlign = TextAlign.Center,
                    )
                }
            } else if (permissions.batteryOptimizationSupported && !permissions.batteryExempt) {
                item {
                    Text(
                        text = "Battery optimization is on",
                        fontSize = 11.sp,
                        color = Color(0xFFFFCC80),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                Button(
                    onClick = if (snapshot.enabled) onStop else onStart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (snapshot.enabled) "Stop" else "Start")
                }
            }
            item {
                Button(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Settings")
                }
            }
            item {
                Button(onClick = onSetup, modifier = Modifier.fillMaxWidth()) {
                    Text("Setup")
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    initial: StoredSettings,
    onSaved: (ValidSettings) -> Unit,
    onBack: () -> Unit,
) {
    var endpoint by remember { mutableStateOf(initial.endpoint) }
    var token by remember { mutableStateOf(initial.token) }
    var interval by remember { mutableStateOf(initial.intervalSec.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val listState = rememberScalingLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            state = listState,
            autoCentering = null,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text("Connection settings", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            item {
                ConfigTextField(
                    label = "WebSocket endpoint",
                    value = endpoint,
                    kind = FieldKind.URI,
                    onValueChange = { endpoint = it; error = null; testMessage = null },
                )
            }
            item {
                ConfigTextField(
                    label = "Bearer token",
                    value = token,
                    kind = FieldKind.TEXT,
                    password = true,
                    onValueChange = { token = it; error = null; testMessage = null },
                )
            }
            item {
                ConfigTextField(
                    label = "Send interval (seconds)",
                    value = interval,
                    kind = FieldKind.NUMBER,
                    onValueChange = {
                        interval = it.filter(Char::isDigit).take(4)
                        error = null
                        testMessage = null
                    },
                )
            }
            if (endpoint.trim().lowercase().startsWith("ws://")) {
                item {
                    Text(
                        text = "Unencrypted connection: the token and readings are visible on the network.",
                        fontSize = 10.sp,
                        color = Color(0xFFFFCC80),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (error != null) {
                item {
                    Text(
                        text = error!!,
                        fontSize = 10.sp,
                        color = Color(0xFFFF8A80),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (testMessage != null) {
                item {
                    Text(
                        text = testMessage!!,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        val result = SettingsValidation.validate(endpoint, token, interval)
                        if (result is SettingsValidationResult.Invalid) {
                            error = result.message
                            return@Button
                        }
                        val valid = (result as SettingsValidationResult.Valid).settings
                        testing = true
                        testMessage = "Testing..."
                        scope.launch {
                            val probe = ConnectionProbe.test(valid.endpoint, valid.token)
                            testing = false
                            testMessage = probe.fold(
                                onSuccess = { "Connection OK" },
                                onFailure = { "Failed: ${it.message}" },
                            )
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (testing) "Testing..." else "Test connection")
                }
            }
            item {
                Button(
                    onClick = {
                        when (val result = SettingsValidation.validate(endpoint, token, interval)) {
                            is SettingsValidationResult.Valid -> onSaved(result.settings)
                            is SettingsValidationResult.Invalid -> error = result.message
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save")
                }
            }
            item {
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back")
                }
            }
        }
    }
}

@Composable
fun SetupScreen(
    permissions: PermissionStatus,
    onRequestForeground: () -> Unit,
    onRequestBackground: () -> Unit,
    onExactAlarms: () -> Unit,
    onOpenSettings: () -> Unit,
    onBattery: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            state = listState,
            autoCentering = null,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text("Permissions", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            item {
                PermissionRow(
                    title = "Heart-rate sensor",
                    granted = permissions.sensors,
                    action = "Allow",
                    onAction = onRequestForeground,
                )
            }
            item {
                PermissionRow(
                    title = "Background sensors",
                    granted = permissions.backgroundSensors,
                    action = "Allow",
                    onAction = onRequestBackground,
                )
            }
            item {
                Text(
                    text = "Choose All the time in Settings > Apps > Pulse Relay > Permissions > Sensors.",
                    fontSize = 9.sp,
                    color = Color(0xFFB0B0B0),
                    textAlign = TextAlign.Center,
                )
            }
            item {
                PermissionRow(
                    title = "Notifications",
                    granted = permissions.notifications,
                    action = "Allow",
                    onAction = onRequestForeground,
                )
            }
            if (Build.VERSION.SDK_INT >= 31) {
                item {
                    PermissionRow(
                        title = "Exact alarms",
                        granted = permissions.exactAlarms,
                        action = "Allow",
                        onAction = onExactAlarms,
                    )
                }
                item {
                    Text(
                        text = "Needed for reliable 20-30 second sampling windows.",
                        fontSize = 9.sp,
                        color = Color(0xFFB0B0B0),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                if (permissions.batteryOptimizationSupported) {
                    PermissionRow(
                        title = "Battery optimization",
                        granted = permissions.batteryExempt,
                        action = "Fix",
                        onAction = onBattery,
                    )
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Battery optimization: managed by Wear OS",
                            fontSize = 11.sp,
                            color = Color(0xFFB0B0B0),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "This watch does not expose a separate exemption setting. No action needed.",
                            fontSize = 9.sp,
                            color = Color(0xFFB0B0B0),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item {
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open app settings")
                }
            }
            item {
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back")
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$title: ${if (granted) "granted" else "missing"}",
            fontSize = 11.sp,
            color = if (granted) Color(0xFF81C784) else Color(0xFFFFCC80),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!granted) {
            Spacer(Modifier.height(2.dp))
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                Text(action)
            }
        }
    }
}

private enum class FieldKind(val inputType: Int) {
    TEXT(InputType.TYPE_CLASS_TEXT),
    URI(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
    NUMBER(InputType.TYPE_CLASS_NUMBER),
}

@Composable
private fun ConfigTextField(
    label: String,
    value: String,
    kind: FieldKind,
    password: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val entered = result.data?.getStringExtra(FieldEditActivity.EXTRA_TEXT)
            ?: return@rememberLauncherForActivityResult
        currentOnValueChange(entered)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color(0xFFB0B0B0),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1B1B1B), RoundedCornerShape(8.dp))
                .clickable {
                    launcher.launch(
                        Intent(context, FieldEditActivity::class.java)
                            .putExtra(FieldEditActivity.EXTRA_LABEL, label)
                            .putExtra(FieldEditActivity.EXTRA_TEXT, value)
                            .putExtra(FieldEditActivity.EXTRA_INPUT_TYPE, kind.inputType)
                    )
                }
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            Text(
                text = when {
                    value.isEmpty() -> "Tap to enter"
                    password -> "••••••••"
                    else -> value
                },
                fontSize = 15.sp,
                color = if (value.isEmpty()) Color(0xFF808080) else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun connectionLabel(state: ConnectionState): String = when (state) {
    ConnectionState.DISCONNECTED -> "Stopped"
    ConnectionState.CONNECTING -> "Connecting..."
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.OFFLINE -> "Offline - waiting for internet"
    ConnectionState.RECONNECTING -> "Reconnecting..."
    ConnectionState.ERROR -> "Error"
}



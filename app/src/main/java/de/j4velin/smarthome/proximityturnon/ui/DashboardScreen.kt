package de.j4velin.smarthome.proximityturnon.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.j4velin.smarthome.proximityturnon.LightSensorService
import de.j4velin.smarthome.proximityturnon.Settings
import de.j4velin.smarthome.proximityturnon.ui.theme.ProximityTurnOnTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    val context = LocalContext.current
    val isRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val state by viewModel.serviceState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                viewModel.toggleService(context)
            }
        }
    )
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                viewModel.setConfirmWithCamera(true)
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Proximity Turn On") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        DashboardContent(
            isRunning = isRunning,
            state = state,
            onToggleService = {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

                if (!isRunning && !hasPermission) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.toggleService(context)
                }
            },
            onEnabledChanged = viewModel::setEnabled,
            onWakeOnShadowChanged = viewModel::setWakeOnShadow,
            onShadowDropPercentChanged = viewModel::setShadowDropPercent,
            onBeepOnShadowChanged = viewModel::setBeepOnShadow,
            onConfirmWithCameraChanged = { enabled ->
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

                if (enabled && !hasPermission) {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    viewModel.setConfirmWithCamera(enabled)
                }
            },
            onTestCamera = viewModel::testCameraCheck,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
fun DashboardContent(
    isRunning: Boolean,
    state: LightSensorService.State,
    onToggleService: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onWakeOnShadowChanged: (Boolean) -> Unit,
    onShadowDropPercentChanged: (Int) -> Unit,
    onBeepOnShadowChanged: (Boolean) -> Unit,
    onConfirmWithCameraChanged: (Boolean) -> Unit,
    onTestCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth > 600.dp

        if (isWide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    StatusCard(isRunning, state, onToggleService, onEnabledChanged)
                    LightCard(state, onWakeOnShadowChanged, onShadowDropPercentChanged, onBeepOnShadowChanged)
                    CameraCard(state, onConfirmWithCameraChanged, onTestCamera)
                    ScreenOffTestCard(state)
                }
                LogCard(state.log, modifier = Modifier.weight(1f))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatusCard(isRunning, state, onToggleService, onEnabledChanged)
                    LightCard(state, onWakeOnShadowChanged, onShadowDropPercentChanged, onBeepOnShadowChanged)
                    CameraCard(state, onConfirmWithCameraChanged, onTestCamera)
                    ScreenOffTestCard(state)
                }
                LogCard(state.log, modifier = Modifier.height(240.dp))
            }
        }
    }
}

@Composable
fun StatusCard(
    isRunning: Boolean,
    state: LightSensorService.State,
    onToggleService: () -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor by animateColorAsState(
        targetValue = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        label = "StatusColor"
    )

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isRunning) Icons.Rounded.PlayCircle else Icons.Rounded.StopCircle,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = statusColor
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = if (isRunning) "Service is Running" else "Service is Stopped",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Switch(
                checked = isRunning,
                onCheckedChange = { onToggleService() },
                modifier = Modifier.scale(1.3f)
            )
        }
        if (isRunning) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.settings.enabled) "Detection active" else "Detection paused",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Also switchable by Home Assistant via broadcast intent " +
                                LightSensorService.ACTION_ENABLE + " / …DISABLE",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Switch(checked = state.settings.enabled, onCheckedChange = onEnabledChanged)
            }
        }
    }
}

@Composable
fun LightCard(
    state: LightSensorService.State,
    onWakeChanged: (Boolean) -> Unit,
    onDropPercentChanged: (Int) -> Unit,
    onBeepChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "Light Sensor",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = state.sensorName?.let { it + if (state.sensorIsWakeUp) " · wake-up" else " · non-wake-up" }
                    ?: "not available on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(16.dp))

            val lux = state.lux
            if (lux == null) {
                Text(
                    text = "Waiting for data...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            } else {
                DataRow(icon = Icons.Rounded.LightMode, label = "Current", value = "%.1f lx".format(lux))
                Spacer(modifier = Modifier.height(16.dp))
                DataRow(icon = Icons.Rounded.Timeline, label = "Baseline", value = "%.1f lx".format(state.baseline ?: 0f))
                Spacer(modifier = Modifier.height(16.dp))
                DataRow(icon = Icons.Rounded.ArrowDownward, label = "Trigger below", value = "%.1f lx".format(state.triggerLux))
                Spacer(modifier = Modifier.height(16.dp))
                // the larger of the two drops applies, so it is obvious when the
                // noise margin overrides the configured percentage
                val noiseWins = state.noiseDrop > state.percentDrop
                DataRow(
                    icon = Icons.Rounded.Rule,
                    label = "Required drop",
                    value = if (noiseWins) "%.1f lx (noise margin)".format(state.noiseDrop)
                    else "%.1f lx (%d %%)".format(state.percentDrop, state.settings.shadowDropPercent),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Wake screen on shadow",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Switch(checked = state.settings.wakeOnShadow, onCheckedChange = onWakeChanged)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Beep on shadow (marks when light-only would wake)",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Switch(checked = state.settings.beepOnShadow, onCheckedChange = onBeepChanged)
            }

            Spacer(modifier = Modifier.height(16.dp))
            // slider is driven locally while dragging, persisted on release
            var percent by remember(state.settings.shadowDropPercent) { mutableIntStateOf(state.settings.shadowDropPercent) }
            Text(
                text = "Shadow threshold: %d %% drop (sensor noise ±%.1f lx, margin %.1f lx)".format(percent, state.noise, state.noiseDrop),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Slider(
                value = percent.toFloat(),
                onValueChange = { percent = it.toInt() },
                onValueChangeFinished = { onDropPercentChanged(percent) },
                valueRange = 2f..50f,
                steps = 47,
            )
        }
    }
}

/**
 * Second stage: after a shadow was detected the front camera is opened for a
 * moment and the screen only wakes if a face is visible.
 */
@Composable
fun CameraCard(
    state: LightSensorService.State,
    onConfirmChanged: (Boolean) -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "Camera Confirmation",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "After a shadow is detected, only wake if the front camera sees a face (max. 3 s).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Confirm with camera",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Switch(checked = state.settings.confirmWithCamera, onCheckedChange = onConfirmChanged)
            }
            Spacer(modifier = Modifier.height(16.dp))
            DataRow(icon = Icons.Rounded.Face, label = "Face found", value = state.cameraConfirmed.toString())
            Spacer(modifier = Modifier.height(16.dp))
            DataRow(icon = Icons.Rounded.VisibilityOff, label = "No face", value = state.cameraRejected.toString())
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onTest, enabled = !state.cameraChecking) {
                if (state.cameraChecking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Checking...")
                } else {
                    Text("Test camera check now")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            OverlayPermissionRow()
        }
    }
}

/**
 * Android only lets the service use the camera after a reboot if the app may
 * draw over other apps. Shows the state and opens the system setting to grant it.
 */
@Composable
private fun OverlayPermissionRow() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    // re-check when coming back from the system settings screen
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = android.provider.Settings.canDrawOverlays(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (granted) "Works after reboot" else "Camera unavailable after reboot",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "Requires \"Display over other apps\" so the service may use the camera " +
                        "when it was started at boot rather than from this screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )
        }
        if (!granted) {
            Spacer(modifier = Modifier.width(16.dp))
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }) { Text("Grant") }
        } else {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Summarises whether sensor events still arrive while the screen is off. To run
 * the test: start the service, turn the screen off, wave a hand in front of the
 * tablet a few times, turn the screen back on and check the counters here.
 */
@Composable
fun ScreenOffTestCard(state: LightSensorService.State, modifier: Modifier = Modifier) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "Screen-off Test",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = "Turn the screen off, wave in front of the tablet, turn it back on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            InfoRow("Events while screen ON", state.eventsScreenOn.toString())
            InfoRow(
                "Events while screen OFF",
                state.eventsScreenOff.toString(),
                highlight = state.eventsScreenOff > 0
            )
            InfoRow("Last event", state.lastEventAt?.let { timeFormat.format(Date(it)) } ?: "–")
            InfoRow("Last event (screen off)", state.lastEventWhileScreenOff?.let { timeFormat.format(Date(it)) } ?: "–")
            InfoRow("Screen wakes triggered", state.wakeCount.toString())
        }
    }
}

@Composable
fun LogCard(log: List<String>, modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Log",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(log) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, highlight: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

@Composable
fun DataRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

private val previewState = LightSensorService.State(
    sensorName = "LIGHT (MTK)",
    lux = 300f,
    baseline = 344f,
    noise = 1.2f,
    triggerLux = 292.4f,
    percentDrop = 51.6f,
    noiseDrop = 4.8f,
    eventsScreenOn = 42,
    eventsScreenOff = 7,
    settings = Settings(shadowDropPercent = 15),
    log = listOf("12:00:00.000 shadow: lux=300.0 base=344.0 noise=1.2 screen=OFF", "11:59:59.000 screen OFF")
)

@Preview(showBackground = true, device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
fun DashboardWidePreview() {
    ProximityTurnOnTheme {
        DashboardContent(
            isRunning = true,
            state = previewState,
            onToggleService = {},
            onEnabledChanged = {},
            onWakeOnShadowChanged = {},
            onShadowDropPercentChanged = {},
            onBeepOnShadowChanged = {},
            onConfirmWithCameraChanged = {},
            onTestCamera = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DashboardMobilePreview() {
    ProximityTurnOnTheme {
        DashboardContent(
            isRunning = false,
            state = LightSensorService.State(),
            onToggleService = {},
            onEnabledChanged = {},
            onWakeOnShadowChanged = {},
            onShadowDropPercentChanged = {},
            onBeepOnShadowChanged = {},
            onConfirmWithCameraChanged = {},
            onTestCamera = {}
        )
    }
}

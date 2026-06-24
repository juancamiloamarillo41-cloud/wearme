package com.example.wearme_01

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.wearme_01.ui.theme.WearMe_01Theme
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var sensorDataManager: SensorDataManager
    private lateinit var bleConnectionManager: BleConnectionManager
    private lateinit var observationHistoryManager: ObservationHistoryManager
    private lateinit var sessionVideoRecorder: SessionVideoRecorder
    private var pendingCsvExport by mutableStateOf<CsvExportPayload?>(null)
    private var pendingObservationExport by mutableStateOf<ObservationTextExportPayload?>(null)
    private var pendingVideoExportFile: File? = null
    private var pendingVideoRecordingMetadata: SessionMetadata? = null

    private val blePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val permissionsGranted = hasBlePermissions()
            bleConnectionManager.refreshSystemState(permissionsGranted)
            if (permissionsGranted) {
                bleConnectionManager.startScan()
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val permissionsGranted = hasBlePermissions()
            bleConnectionManager.refreshSystemState(permissionsGranted)
            if (permissionsGranted) {
                bleConnectionManager.startScan()
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val metadata = pendingVideoRecordingMetadata ?: return@registerForActivityResult
            pendingVideoRecordingMetadata = null

            if (granted) {
                startRecordingSession(metadata, shouldRecordVideo = true)
            } else {
                sessionVideoRecorder.markPermissionDenied()
                sensorDataManager.startRecording(metadata)
            }
        }

    private val createCsvDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val exportPayload = pendingCsvExport

            if (uri == null || exportPayload == null) {
                sensorDataManager.setExportNotice("Guardado cancelado.")
                pendingCsvExport = null
                return@registerForActivityResult
            }

            runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(exportPayload.content)
                } ?: error("No se pudo abrir la salida del archivo.")
            }.onSuccess {
                sensorDataManager.setExportNotice(
                    "CSV guardado en el telefono con ${exportPayload.rowCount} muestras."
                )
            }.onFailure {
                sensorDataManager.setExportNotice(
                    "No se pudo guardar el CSV: ${it.message ?: "error desconocido"}."
                )
            }

            pendingCsvExport = null
        }

    private val createObservationDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val exportPayload = pendingObservationExport

            if (uri == null || exportPayload == null) {
                observationHistoryManager.setNotice("Exportacion TXT cancelada.")
                pendingObservationExport = null
                return@registerForActivityResult
            }

            runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(exportPayload.content)
                } ?: error("No se pudo abrir la salida del archivo.")
            }.onSuccess {
                observationHistoryManager.setNotice(
                    "TXT guardado con ${exportPayload.entryCount} observaciones."
                )
            }.onFailure {
                observationHistoryManager.setNotice(
                    "No se pudo guardar el TXT: ${it.message ?: "error desconocido"}."
                )
            }

            pendingObservationExport = null
        }

    private val createVideoDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
            val videoFile = pendingVideoExportFile

            if (uri == null || videoFile == null) {
                sessionVideoRecorder.setNotice("Guardado de video cancelado.")
                pendingVideoExportFile = null
                return@registerForActivityResult
            }

            runCatching {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    videoFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: error("No se pudo abrir la salida del archivo.")
            }.onSuccess {
                sessionVideoRecorder.setNotice("Video guardado en el telefono.")
            }.onFailure {
                sessionVideoRecorder.setNotice(
                    "No se pudo guardar el video: ${it.message ?: "error desconocido"}."
                )
            }

            pendingVideoExportFile = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorDataManager = SensorDataManager()
        observationHistoryManager = ObservationHistoryManager(applicationContext)
        sessionVideoRecorder = SessionVideoRecorder(applicationContext)
        bleConnectionManager = BleConnectionManager(
            applicationContext,
            sensorDataManager
        )
        enableEdgeToEdge()
        setContent {
            WearMe_01Theme {
                val uiState by bleConnectionManager.uiState.collectAsState()
                val dashboardState by sensorDataManager.dashboardState.collectAsState()
                val observationHistoryState by observationHistoryManager.state.collectAsState()
                val sessionVideoState by sessionVideoRecorder.state.collectAsState()
                WearMeBleApp(
                    uiState = uiState,
                    dashboardState = dashboardState,
                    observationHistoryState = observationHistoryState,
                    sessionVideoState = sessionVideoState,
                    pendingCsvExport = pendingCsvExport,
                    onRequestPermissions = ::requestBlePermissions,
                    onEnableBluetooth = ::requestEnableBluetooth,
                    onStartScan = bleConnectionManager::startScan,
                    onConnect = bleConnectionManager::connect,
                    onDisconnect = bleConnectionManager::disconnect,
                    onEnterTestMode = sensorDataManager::startDemoSession,
                    onExitTestMode = sensorDataManager::stopDemoSession,
                    onSendCleaningCommand = bleConnectionManager::sendCleaningCommand,
                    onSetConstantFanEnabled = bleConnectionManager::setConstantFanEnabled,
                    onStartRecording = ::startRecordingSession,
                    onSetPastandoActive = sensorDataManager::setPastandoActive,
                    onSetRumiandoActive = sensorDataManager::setRumiandoActive,
                    onSetCurrentObservation = sensorDataManager::setCurrentObservation,
                    onSaveObservation = ::saveObservationToHistory,
                    onVideoPreviewReady = { previewView ->
                        sessionVideoRecorder.attachPreview(previewView, this)
                    },
                    onStopRecording = ::prepareCsvExportOptions,
                    onSaveCsv = ::savePendingCsv,
                    onShareCsv = ::sharePendingCsv,
                    onDismissCsvExport = ::dismissPendingCsvExport,
                    onSaveVideo = ::saveSessionVideo,
                    onShareVideo = ::shareSessionVideo,
                    onExportObservationHistory = ::exportObservationHistory,
                    onDeleteObservation = observationHistoryManager::deleteObservation,
                    onClearObservationHistory = observationHistoryManager::clearHistory
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bleConnectionManager.refreshSystemState(hasBlePermissions())
    }

    override fun onDestroy() {
        bleConnectionManager.close()
        sensorDataManager.close()
        sessionVideoRecorder.close()
        super.onDestroy()
    }

    private fun requestBlePermissions() {
        blePermissionLauncher.launch(requiredBlePermissions())
    }

    private fun requestEnableBluetooth() {
        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    private fun hasBlePermissions(): Boolean {
        return requiredBlePermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredBlePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun prepareCsvExportOptions() {
        sessionVideoRecorder.stopRecording()
        val exportPayload = sensorDataManager.stopRecordingAndBuildCsv() ?: return
        pendingCsvExport = exportPayload
        sensorDataManager.setExportNotice(
            "CSV listo para guardar o compartir desde la app."
        )
    }

    private fun startRecordingSession(
        sessionMetadata: SessionMetadata,
        shouldRecordVideo: Boolean
    ) {
        if (!shouldRecordVideo) {
            sessionVideoRecorder.reset()
            sensorDataManager.startRecording(sessionMetadata)
            return
        }

        if (!hasCameraPermission()) {
            pendingVideoRecordingMetadata = sessionMetadata
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        sessionVideoRecorder.requestRecording(this, sessionMetadata)
        sensorDataManager.startRecording(sessionMetadata)
    }

    private fun savePendingCsv() {
        val exportPayload = pendingCsvExport ?: return
        createCsvDocumentLauncher.launch(exportPayload.suggestedFileName)
    }

    private fun sharePendingCsv() {
        val exportPayload = pendingCsvExport ?: return

        runCatching {
            val exportsDir = File(cacheDir, "exports").apply { mkdirs() }
            val csvFile = File(exportsDir, exportPayload.suggestedFileName)
            csvFile.writeText(exportPayload.content)

            val contentUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                csvFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, exportPayload.suggestedFileName)
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Compartir CSV de sesion"))
            sensorDataManager.setExportNotice(
                "CSV preparado para compartir por WhatsApp, Drive, correo u otra app."
            )
            pendingCsvExport = null
        }.onFailure {
            sensorDataManager.setExportNotice(
                "No se pudo compartir el CSV: ${it.message ?: "error desconocido"}."
            )
        }
    }

    private fun saveSessionVideo() {
        val videoFile = sessionVideoRecorder.state.value.outputFile
            ?.takeIf { it.exists() }
            ?: run {
                sessionVideoRecorder.setNotice("No hay video listo para guardar.")
                return
            }

        pendingVideoExportFile = videoFile
        createVideoDocumentLauncher.launch(videoFile.name)
    }

    private fun shareSessionVideo() {
        val videoFile = sessionVideoRecorder.state.value.outputFile
            ?.takeIf { it.exists() }
            ?: run {
                sessionVideoRecorder.setNotice("No hay video listo para compartir.")
                return
            }

        runCatching {
            val contentUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                videoFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_SUBJECT, videoFile.name)
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Compartir video de sesion"))
            sessionVideoRecorder.setNotice(
                "Video preparado para compartir por WhatsApp, Drive, correo u otra app."
            )
        }.onFailure {
            sessionVideoRecorder.setNotice(
                "No se pudo compartir el video: ${it.message ?: "error desconocido"}."
            )
        }
    }

    private fun dismissPendingCsvExport() {
        pendingCsvExport = null
        sensorDataManager.setExportNotice("Exportacion cancelada.")
    }

    private fun saveObservationToHistory() {
        val dashboardSnapshot = sensorDataManager.dashboardState.value
        if (dashboardSnapshot.currentObservation.isBlank()) {
            sensorDataManager.setExportNotice("Escribe una observacion antes de guardarla.")
            return
        }

        if (observationHistoryManager.saveObservation(dashboardSnapshot)) {
            sensorDataManager.setCurrentObservation("")
            sensorDataManager.setExportNotice("Observacion guardada en historial.")
        }
    }

    private fun exportObservationHistory() {
        val exportPayload = observationHistoryManager.buildTxtExportPayload()
        if (exportPayload == null) {
            observationHistoryManager.setNotice("Todavia no hay observaciones para exportar.")
            return
        }

        pendingObservationExport = exportPayload
        createObservationDocumentLauncher.launch(exportPayload.suggestedFileName)
    }
}

@Composable
private fun WearMeBleApp(
    uiState: BleUiState,
    dashboardState: SensorDashboardState,
    observationHistoryState: ObservationHistoryState,
    sessionVideoState: SessionVideoState,
    pendingCsvExport: CsvExportPayload?,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onStartScan: () -> Unit,
    onConnect: (BleDeviceCandidate) -> Unit,
    onDisconnect: () -> Unit,
    onEnterTestMode: () -> Unit,
    onExitTestMode: () -> Unit,
    onSendCleaningCommand: () -> Unit,
    onSetConstantFanEnabled: (Boolean) -> Unit,
    onStartRecording: (SessionMetadata, Boolean) -> Unit,
    onSetPastandoActive: (Boolean) -> Unit,
    onSetRumiandoActive: (Boolean) -> Unit,
    onSetCurrentObservation: (String) -> Unit,
    onSaveObservation: () -> Unit,
    onVideoPreviewReady: (PreviewView) -> Unit,
    onStopRecording: () -> Unit,
    onSaveCsv: () -> Unit,
    onShareCsv: () -> Unit,
    onDismissCsvExport: () -> Unit,
    onSaveVideo: () -> Unit,
    onShareVideo: () -> Unit,
    onExportObservationHistory: () -> Unit,
    onDeleteObservation: (Long) -> Unit,
    onClearObservationHistory: () -> Unit
) {
    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.Connection) }
    var showSessionMetadataDialog by rememberSaveable { mutableStateOf(false) }
    var showVideoRecordingDialog by rememberSaveable { mutableStateOf(false) }
    var metadataDraft by remember { mutableStateOf(dashboardState.currentSessionMetadata ?: SessionMetadata()) }
    var pendingRecordingMetadata by remember { mutableStateOf<SessionMetadata?>(null) }

    LaunchedEffect(uiState.isConnected, dashboardState.isRecording) {
        currentScreen = when {
            uiState.isConnected -> {
                onExitTestMode()
                AppScreen.NextBle
            }
            currentScreen == AppScreen.NextBle && dashboardState.isRecording -> AppScreen.NextBle
            currentScreen == AppScreen.NextBle -> AppScreen.Connection
            else -> currentScreen
        }
    }

    LaunchedEffect(dashboardState.currentSessionMetadata) {
        dashboardState.currentSessionMetadata?.let { metadataDraft = it }
    }

    if (showSessionMetadataDialog) {
        SessionMetadataDialog(
            initialMetadata = metadataDraft,
            onDismiss = { showSessionMetadataDialog = false },
            onConfirm = { metadata ->
                metadataDraft = metadata
                showSessionMetadataDialog = false
                pendingRecordingMetadata = metadata
                showVideoRecordingDialog = true
            }
        )
    }

    if (showVideoRecordingDialog) {
        pendingRecordingMetadata?.let { metadata ->
            VideoRecordingPromptDialog(
                onRecordVideo = {
                    pendingRecordingMetadata = null
                    showVideoRecordingDialog = false
                    onStartRecording(metadata, true)
                },
                onCsvOnly = {
                    pendingRecordingMetadata = null
                    showVideoRecordingDialog = false
                    onStartRecording(metadata, false)
                },
                onDismiss = {
                    pendingRecordingMetadata = null
                    showVideoRecordingDialog = false
                }
            )
        }
    }

    if (pendingCsvExport != null) {
        ExportOptionsDialog(
            sessionMetadata = pendingCsvExport.sessionMetadata,
            sessionVideoState = sessionVideoState,
            onSave = onSaveCsv,
            onShare = onShareCsv,
            onSaveVideo = onSaveVideo,
            onShareVideo = onShareVideo,
            onDismiss = onDismissCsvExport
        )
    }

    BackHandler(
        enabled = showSessionMetadataDialog ||
            showVideoRecordingDialog ||
            pendingCsvExport != null ||
            currentScreen == AppScreen.ObservationHistory
    ) {
        when {
            showSessionMetadataDialog -> showSessionMetadataDialog = false
            showVideoRecordingDialog -> {
                pendingRecordingMetadata = null
                showVideoRecordingDialog = false
            }
            pendingCsvExport != null -> onDismissCsvExport()
            currentScreen == AppScreen.ObservationHistory -> currentScreen = AppScreen.Connection
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        FieldBackdrop()

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent
        ) { innerPadding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                color = Color.Transparent
            ) {
                when (currentScreen) {
                    AppScreen.Connection -> ConnectionScreen(
                        uiState = uiState,
                        observationHistoryState = observationHistoryState,
                        onRequestPermissions = onRequestPermissions,
                        onEnableBluetooth = onEnableBluetooth,
                        onStartScan = onStartScan,
                        onConnect = onConnect,
                        onOpenObservationHistory = {
                            currentScreen = AppScreen.ObservationHistory
                        },
                        onEnterTestMode = {
                            onEnterTestMode()
                            currentScreen = AppScreen.NextTest
                        }
                    )

                    AppScreen.NextBle -> NextScreen(
                        dashboardState = dashboardState,
                        sessionVideoState = sessionVideoState,
                        uiState = uiState,
                        isTestMode = false,
                        onSendCleaningCommand = onSendCleaningCommand,
                        onSetConstantFanEnabled = onSetConstantFanEnabled,
                        onSetPastandoActive = onSetPastandoActive,
                        onSetRumiandoActive = onSetRumiandoActive,
                        onSetCurrentObservation = onSetCurrentObservation,
                        onSaveObservation = onSaveObservation,
                        onVideoPreviewReady = onVideoPreviewReady,
                        onDisconnect = onDisconnect,
                        onStartRecording = {
                            metadataDraft = dashboardState.currentSessionMetadata ?: metadataDraft
                            showSessionMetadataDialog = true
                        },
                        onStopRecording = onStopRecording
                    )

                    AppScreen.ObservationHistory -> ObservationHistoryScreen(
                        observationHistoryState = observationHistoryState,
                        onBack = {
                            currentScreen = AppScreen.Connection
                        },
                        onExportObservationHistory = onExportObservationHistory,
                        onDeleteObservation = onDeleteObservation,
                        onClearObservationHistory = onClearObservationHistory
                    )

                    AppScreen.NextTest -> NextScreen(
                        dashboardState = dashboardState,
                        sessionVideoState = sessionVideoState,
                        uiState = uiState,
                        isTestMode = true,
                        onSendCleaningCommand = {},
                        onSetConstantFanEnabled = {},
                        onSetPastandoActive = onSetPastandoActive,
                        onSetRumiandoActive = onSetRumiandoActive,
                        onSetCurrentObservation = onSetCurrentObservation,
                        onSaveObservation = onSaveObservation,
                        onVideoPreviewReady = onVideoPreviewReady,
                        onStartRecording = {
                            metadataDraft = dashboardState.currentSessionMetadata ?: metadataDraft
                            showSessionMetadataDialog = true
                        },
                        onStopRecording = onStopRecording,
                        onDisconnect = {
                            onExitTestMode()
                            currentScreen = AppScreen.Connection
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionMetadataDialog(
    initialMetadata: SessionMetadata,
    onDismiss: () -> Unit,
    onConfirm: (SessionMetadata) -> Unit
) {
    var draft by remember(initialMetadata) { mutableStateOf(initialMetadata) }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Datos de la sesion")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = draft.sessionNumber,
                    onValueChange = { draft = draft.copy(sessionNumber = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Numero de sesion") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = draft.animalId,
                    onValueChange = { draft = draft.copy(animalId = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("ID de vaca") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = draft.weightKg,
                    onValueChange = { draft = draft.copy(weightKg = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Peso (kg)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = draft.breed,
                    onValueChange = { draft = draft.copy(breed = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Raza") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = draft.feeding,
                    onValueChange = { draft = draft.copy(feeding = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Alimentacion") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = draft.age,
                    onValueChange = { draft = draft.copy(age = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Edad (Años)") },
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Gestacion",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (draft.gestation) "Si" else "No",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Switch(
                        checked = draft.gestation,
                        onCheckedChange = { draft = draft.copy(gestation = it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Estado productivo",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (draft.productiveState) "Si" else "No",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Switch(
                        checked = draft.productiveState,
                        onCheckedChange = { draft = draft.copy(productiveState = it) }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(draft) }) {
                Text("Iniciar CSV")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun VideoRecordingPromptDialog(
    onRecordVideo: () -> Unit,
    onCsvOnly: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("¿Desea grabar la sesion?")
        },
        text = {
            Text(
                text = "La app puede grabar video con la camara trasera mientras se registran los datos y se visualizan las graficas. El video no incluye audio."
            )
        },
        confirmButton = {
            Button(onClick = onRecordVideo) {
                Text("Si, grabar sesion")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("Cancelar")
                }
                TextButton(onClick = onCsvOnly) {
                    Text("No, solo CSV")
                }
            }
        }
    )
}

@Composable
private fun ExportOptionsDialog(
    sessionMetadata: SessionMetadata?,
    sessionVideoState: SessionVideoState,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onSaveVideo: () -> Unit,
    onShareVideo: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Exportar CSV")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = buildString {
                        append("La sesion ya esta lista para exportarse.")
                        sessionMetadata?.sessionNumber
                            ?.takeIf { it.isNotBlank() }
                            ?.let {
                                append(" Numero de sesion: ")
                                append(it)
                                append('.')
                            }
                    }
                )

                when (sessionVideoState.status) {
                    SessionVideoStatus.Stopping,
                    SessionVideoStatus.Preparing,
                    SessionVideoStatus.Recording -> {
                        Text(
                            text = sessionVideoState.message.ifBlank {
                                "El video de la sesion se esta finalizando."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    SessionVideoStatus.ReadyToExport -> {
                        Text(
                            text = sessionVideoState.message.ifBlank {
                                "Video listo para guardar o compartir."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onSaveVideo,
                                enabled = sessionVideoState.canExportVideo
                            ) {
                                Text("Guardar video")
                            }
                            OutlinedButton(
                                onClick = onShareVideo,
                                enabled = sessionVideoState.canExportVideo
                            ) {
                                Text("Compartir video")
                            }
                        }
                    }

                    SessionVideoStatus.Error -> {
                        Text(
                            text = sessionVideoState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    SessionVideoStatus.Idle -> Unit
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text("Guardar")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("Cerrar")
                }
                TextButton(onClick = onShare) {
                    Text("Compartir")
                }
            }
        }
    )
}

@Composable
private fun FieldBackdrop() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.secondaryContainer
                    )
                )
            )
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 56.dp, y = (-48).dp)
                .size(220.dp)
                .alpha(0.22f)
                .background(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = CircleShape
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(240.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(topStart = 64.dp, topEnd = 64.dp)
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-36).dp, y = 32.dp)
                .width(220.dp)
                .height(120.dp)
                .alpha(0.65f)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                    shape = CircleShape
                )
        )
    }
}

@Composable
private fun ConnectionScreen(
    uiState: BleUiState,
    observationHistoryState: ObservationHistoryState,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onStartScan: () -> Unit,
    onConnect: (BleDeviceCandidate) -> Unit,
    onOpenObservationHistory: () -> Unit,
    onEnterTestMode: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "WearME",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Text(
                text = "Busca tu XIAO nRF52840 Sense y conectala por BLE para ver graficas en tiempo real. Si no tienes la placa contigo, usa Prueba para abrir el mismo dashboard con datos simulados.",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        item {
            StatusCard(message = uiState.statusMessage)
        }

        item {
            ActionCard(
                uiState = uiState,
                onRequestPermissions = onRequestPermissions,
                onEnableBluetooth = onEnableBluetooth,
                onStartScan = onStartScan,
                onEnterTestMode = onEnterTestMode
            )
        }

        item {
            ObservationHistoryShortcutCard(
                observationHistoryState = observationHistoryState,
                onOpenObservationHistory = onOpenObservationHistory
            )
        }

        if (uiState.permissionsGranted && uiState.bluetoothEnabled && uiState.devices.isEmpty()) {
            item {
                Text(
                    text = "Aun no hay dispositivos encontrados. Asegurate de que la XIAO este encendida, cerca del telefono y anunciando por BLE.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (uiState.devices.isNotEmpty()) {
            item {
                HorizontalDivider()
            }
        }

        items(uiState.devices, key = { it.address }) { device ->
            DeviceCard(
                device = device,
                isConnecting = uiState.connectingDeviceAddress == device.address,
                actionsEnabled = !uiState.isConnecting,
                onConnect = { onConnect(device) }
            )
        }
    }
}

@Composable
private fun ActionCard(
    uiState: BleUiState,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onStartScan: () -> Unit,
    onEnterTestMode: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                !uiState.bluetoothSupported -> {
                    Text(
                        text = "Este dispositivo no soporta Bluetooth Low Energy.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                !uiState.permissionsGranted -> {
                    Text(
                        text = "La app necesita permisos BLE para descubrir y conectarse a la XIAO.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Button(onClick = onRequestPermissions) {
                        Text("Conceder permisos BLE")
                    }
                    OutlinedButton(onClick = onEnterTestMode) {
                        Text("Prueba")
                    }
                }

                !uiState.bluetoothEnabled -> {
                    Text(
                        text = "Bluetooth esta apagado. Activalo para iniciar la busqueda.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Button(onClick = onEnableBluetooth) {
                        Text("Activar Bluetooth")
                    }
                    OutlinedButton(onClick = onEnterTestMode) {
                        Text("Prueba")
                    }
                }

                else -> {
                    Text(
                        text = "Haz un escaneo y luego toca Conectar sobre el dispositivo correcto.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Button(
                        onClick = onStartScan,
                        enabled = !uiState.isScanning && !uiState.isConnecting
                    ) {
                        if (uiState.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 12.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        Text(
                            text = if (uiState.isScanning) {
                                "Buscando..."
                            } else {
                                "Buscar dispositivos BLE"
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = onEnterTestMode,
                        enabled = !uiState.isConnecting
                    ) {
                        Text("Prueba")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: BleDeviceCandidate,
    isConnecting: Boolean,
    actionsEnabled: Boolean,
    onConnect: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (device.isLikelyTarget) {
                Text(
                    text = "Candidato XIAO",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = device.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "RSSI: ${device.rssi} dBm",
                style = MaterialTheme.typography.bodySmall
            )

            if (isConnecting) {
                Text(
                    text = "Intentando abrir la conexion GATT...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            OutlinedButton(
                onClick = onConnect,
                enabled = actionsEnabled
            ) {
                Text(if (isConnecting) "Conectando..." else "Conectar")
            }
        }
    }
}

@Composable
private fun NextScreen(
    dashboardState: SensorDashboardState,
    sessionVideoState: SessionVideoState,
    uiState: BleUiState,
    isTestMode: Boolean,
    onSendCleaningCommand: () -> Unit,
    onSetConstantFanEnabled: (Boolean) -> Unit,
    onSetPastandoActive: (Boolean) -> Unit,
    onSetRumiandoActive: (Boolean) -> Unit,
    onSetCurrentObservation: (String) -> Unit,
    onSaveObservation: () -> Unit,
    onVideoPreviewReady: (PreviewView) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onDisconnect: () -> Unit
) {
    SensorDashboardScreen(
        dashboardState = dashboardState,
        sessionVideoState = sessionVideoState,
        connectionMessage = if (isTestMode) {
            "Modo de prueba activo. El dashboard usa datos simulados con la misma estructura del paquete BLE de la XIAO."
        } else {
            buildString {
                append(uiState.statusMessage)
                uiState.connectedDevice?.let { device ->
                    append(" Dispositivo: ")
                    append(device.displayName)
                    append(" (")
                    append(device.address)
                    append(").")
                }
            }
        },
        isTestMode = isTestMode,
        isBleCommandAvailable = !isTestMode && uiState.isConnected && uiState.commandCharacteristicAvailable,
        isBleCommandInFlight = uiState.isCommandInFlight,
        bleCommandStatusMessage = uiState.commandStatusMessage,
        constantFanEnabled = uiState.constantFanEnabled,
        onSendCleaningCommand = onSendCleaningCommand,
        onSetConstantFanEnabled = onSetConstantFanEnabled,
        onStartRecording = onStartRecording,
        onSetPastandoActive = onSetPastandoActive,
        onSetRumiandoActive = onSetRumiandoActive,
        onSetCurrentObservation = onSetCurrentObservation,
        onSaveObservation = onSaveObservation,
        onVideoPreviewReady = onVideoPreviewReady,
        onStopRecording = onStopRecording,
        onExit = onDisconnect
    )
}

@Composable
private fun ObservationHistoryShortcutCard(
    observationHistoryState: ObservationHistoryState,
    onOpenObservationHistory: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Historial de observaciones",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (observationHistoryState.entries.isEmpty()) {
                    "Todavia no hay observaciones guardadas. Entra aqui para revisar o exportar tus notas mas adelante."
                } else {
                    "Tienes ${observationHistoryState.entries.size} observaciones guardadas. Entra para revisarlas, borrar las que no sirvan o exportarlas en TXT."
                },
                style = MaterialTheme.typography.bodyLarge
            )

            observationHistoryState.notice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedButton(onClick = onOpenObservationHistory) {
                Text("Abrir historial de observaciones")
            }
        }
    }
}

@Composable
private fun ObservationHistoryScreen(
    observationHistoryState: ObservationHistoryState,
    onBack: () -> Unit,
    onExportObservationHistory: () -> Unit,
    onDeleteObservation: (Long) -> Unit,
    onClearObservationHistory: () -> Unit
) {
    var pendingDeleteObservationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showClearHistoryDialog by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val normalizedSearchQuery = searchQuery.trim()
    val filteredEntries = observationHistoryState.entries.filter { entry ->
        normalizedSearchQuery.isBlank() || listOf(
            entry.note,
            entry.behavior,
            entry.sessionNumber,
            entry.animalId,
            entry.sourceMode
        ).any { field ->
            field.contains(normalizedSearchQuery, ignoreCase = true)
        }
    }

    if (pendingDeleteObservationId != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteObservationId = null },
            title = { Text("Eliminar observacion") },
            text = {
                Text("Esta observacion se quitara del historial de forma permanente.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteObservationId?.let(onDeleteObservation)
                        pendingDeleteObservationId = null
                    }
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteObservationId = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Limpiar historial") },
            text = {
                Text("Se eliminaran todas las observaciones guardadas en el historial.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearObservationHistory()
                        showClearHistoryDialog = false
                    }
                ) {
                    Text("Limpiar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Historial de observaciones",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Text(
                text = "Aqui puedes revisar tus observaciones guardadas, exportarlas en TXT o limpiar el historial.",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        observationHistoryState.notice?.let { notice ->
            item {
                StatusCard(message = notice)
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onExportObservationHistory,
                    enabled = observationHistoryState.entries.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Exportar TXT")
                }
                OutlinedButton(
                    onClick = { showClearHistoryDialog = true },
                    enabled = observationHistoryState.entries.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Limpiar historial")
                }
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Buscar en observaciones") },
                placeholder = { Text("Texto, ID de vaca o actividad") },
                singleLine = true
            )
        }

        item {
            Text(
                text = "Mostrando ${filteredEntries.size} de ${observationHistoryState.entries.size} observaciones.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (observationHistoryState.entries.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = "Todavia no hay observaciones guardadas.",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        } else if (filteredEntries.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = "No hay observaciones que coincidan con la busqueda.",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        } else {
            items(filteredEntries, key = { it.id }) { entry ->
                ObservationHistoryEntryCard(
                    entry = entry,
                    onDelete = {
                        pendingDeleteObservationId = entry.id
                    }
                )
            }
        }

        item {
            OutlinedButton(onClick = onBack) {
                Text("Volver")
            }
        }
    }
}

@Composable
private fun ObservationHistoryEntryCard(
    entry: ObservationEntry,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = entry.note,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = formatObservationTimestamp(entry.createdAtMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = buildString {
                    append("Actividad: ${entry.behavior}")
                    append(" | Tiempo CSV: ${entry.elapsedTimeMin} min")
                    append(" | Origen: ${entry.sourceMode}")
                    entry.sessionNumber.takeIf { it.isNotBlank() }?.let {
                        append(" | Sesion: ")
                        append(it)
                    }
                    entry.animalId.takeIf { it.isNotBlank() }?.let {
                        append(" | ID: ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onDelete,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Borrar")
            }
        }
    }
}

@Composable
private fun StatusCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectionScreenPreview() {
    WearMe_01Theme {
        ConnectionScreen(
            uiState = BleUiState(
                permissionsGranted = true,
                bluetoothEnabled = true,
                statusMessage = "Listo para buscar la XIAO nRF52840 Sense.",
                devices = listOf(
                    BleDeviceCandidate(
                        name = "XIAO nRF52840 Sense",
                        address = "D8:3A:DD:12:34:56",
                        rssi = -48,
                        isLikelyTarget = true
                    )
                )
            ),
            observationHistoryState = ObservationHistoryState(
                entries = listOf(
                    ObservationEntry(
                        id = 1L,
                        createdAtMillis = 0L,
                        sessionNumber = "1",
                        animalId = "V-12",
                        behavior = "Pastando",
                        elapsedTimeMin = "0.4",
                        sourceMode = "ble",
                        note = "Se movio hacia el comedero."
                    )
                )
            ),
            onRequestPermissions = {},
            onEnableBluetooth = {},
            onStartScan = {},
            onConnect = {},
            onOpenObservationHistory = {},
            onEnterTestMode = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NextScreenPreview() {
    WearMe_01Theme {
        NextScreen(
            dashboardState = SensorDashboardState(
                sourceMode = SensorSourceMode.Ble,
                samples = listOf(
                    SensorSample(
                        timestampMillis = 0L,
                        accelX = 1200,
                        accelY = -800,
                        accelZ = 9800,
                        gyroX = 420,
                        gyroY = -390,
                        gyroZ = 160,
                        methane = 515,
                        refTemperature = 24.8f,
                        refHumidity = 48.2f,
                        figTemperature = 28.4f,
                        figHumidity = 60.1f
                    )
                ),
                totalSamples = 1,
                latestSample = SensorSample(
                    timestampMillis = 0L,
                    accelX = 1200,
                    accelY = -800,
                    accelZ = 9800,
                    gyroX = 420,
                    gyroY = -390,
                    gyroZ = 160,
                    methane = 515,
                    refTemperature = 24.8f,
                    refHumidity = 48.2f,
                    figTemperature = 28.4f,
                    figHumidity = 60.1f
                )
            ),
            sessionVideoState = SessionVideoState(),
            uiState = BleUiState(
                permissionsGranted = true,
                bluetoothEnabled = true,
                isConnected = true,
                statusMessage = "XIAO conectada. La app ya puede pasar a la siguiente pantalla.",
                commandCharacteristicAvailable = true,
                commandStatusMessage = "Comandos BLE listos para limpieza y ventilador.",
                connectedDevice = BleDeviceCandidate(
                    name = "XIAO nRF52840 Sense",
                    address = "D8:3A:DD:12:34:56",
                    rssi = -42,
                    isLikelyTarget = true
                )
            ),
            isTestMode = false,
            onSendCleaningCommand = {},
            onSetConstantFanEnabled = {},
            onSetPastandoActive = {},
            onSetRumiandoActive = {},
            onSetCurrentObservation = {},
            onSaveObservation = {},
            onVideoPreviewReady = { _ -> },
            onStartRecording = {},
            onStopRecording = {},
            onDisconnect = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NextScreenTestPreview() {
    WearMe_01Theme {
        NextScreen(
            dashboardState = SensorDashboardState(
                sourceMode = SensorSourceMode.Test,
                samples = listOf(
                    SensorSample(
                        timestampMillis = 0L,
                        accelX = 3200,
                        accelY = -2500,
                        accelZ = 8700,
                        gyroX = 1100,
                        gyroY = -900,
                        gyroZ = 650,
                        methane = 640,
                        refTemperature = 24.5f,
                        refHumidity = 47.8f,
                        figTemperature = 29.1f,
                        figHumidity = 63.4f
                    )
                ),
                totalSamples = 1,
                latestSample = SensorSample(
                    timestampMillis = 0L,
                    accelX = 3200,
                    accelY = -2500,
                    accelZ = 8700,
                    gyroX = 1100,
                    gyroY = -900,
                    gyroZ = 650,
                    methane = 640,
                    refTemperature = 24.5f,
                    refHumidity = 47.8f,
                    figTemperature = 29.1f,
                    figHumidity = 63.4f
                )
            ),
            sessionVideoState = SessionVideoState(),
            uiState = BleUiState(),
            isTestMode = true,
            onSendCleaningCommand = {},
            onSetConstantFanEnabled = {},
            onSetPastandoActive = {},
            onSetRumiandoActive = {},
            onSetCurrentObservation = {},
            onSaveObservation = {},
            onVideoPreviewReady = { _ -> },
            onStartRecording = {},
            onStopRecording = {},
            onDisconnect = {}
        )
    }
}

private enum class AppScreen {
    Connection,
    ObservationHistory,
    NextBle,
    NextTest
}

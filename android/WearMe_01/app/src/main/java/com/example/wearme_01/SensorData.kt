package com.example.wearme_01

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SensorSample(
    val timestampMillis: Long,
    val accelX: Int,
    val accelY: Int,
    val accelZ: Int,
    val gyroX: Int,
    val gyroY: Int,
    val gyroZ: Int,
    val methane: Int,
    val refTemperature: Float,
    val refHumidity: Float,
    val figTemperature: Float,
    val figHumidity: Float,
    val modelProbability: Float? = null,
    val fanOn: Boolean? = null,
    val fanReason: FanReason = FanReason.Unknown
)

data class RecordedSample(
    val sample: SensorSample,
    val pastandoActive: Boolean,
    val rumiandoActive: Boolean
)

enum class FanReason(
    val code: Int,
    val csvValue: String,
    val displayLabel: String
) {
    Off(0, "off", "Apagado"),
    Constant(1, "constant", "Constante"),
    Cleaning(2, "cleaning", "Limpieza"),
    Model(3, "model", "Modelo"),
    Unknown(-1, "", "Desconocido");

    companion object {
        fun fromCode(code: Int): FanReason {
            return entries.firstOrNull { it.code == code } ?: Unknown
        }
    }
}

data class SessionMetadata(
    val animalId: String = "",
    val weightKg: String = "",
    val sessionNumber: String = "",
    val gestation: Boolean = false,
    val breed: String = "",
    val feeding: String = "",
    val age: String = "",
    val productiveState: Boolean = false
) {
    val gestationLabel: String
        get() = if (gestation) "si" else "no"

    val productiveStateLabel: String
        get() = if (productiveState) "si" else "no"

    fun hasDetails(): Boolean {
        return animalId.isNotBlank() ||
            weightKg.isNotBlank() ||
            sessionNumber.isNotBlank() ||
            breed.isNotBlank() ||
            feeding.isNotBlank() ||
            age.isNotBlank()
    }
}

enum class SensorSourceMode {
    None,
    Ble,
    Test
}

data class SensorDashboardState(
    val sourceMode: SensorSourceMode = SensorSourceMode.None,
    val samples: List<SensorSample> = emptyList(),
    val totalSamples: Int = 0,
    val latestSample: SensorSample? = null,
    val isRecording: Boolean = false,
    val isPastandoActive: Boolean = false,
    val isRumiandoActive: Boolean = false,
    val currentObservation: String = "",
    val currentSessionMetadata: SessionMetadata? = null,
    val recordingStartTimestampMillis: Long? = null,
    val recordedSamplesCount: Int = 0,
    val modelProbability: Float? = null,
    val fanOn: Boolean? = null,
    val fanReason: FanReason = FanReason.Unknown,
    val exportNotice: String? = null
)

data class CsvExportPayload(
    val suggestedFileName: String,
    val content: String,
    val rowCount: Int,
    val sessionMetadata: SessionMetadata?
)

private data class DashboardLiveSnapshot(
    val sourceMode: SensorSourceMode,
    val samples: List<SensorSample>,
    val totalSamples: Int,
    val latestSample: SensorSample?,
    val recordedSamplesCount: Int,
    val modelProbability: Float?,
    val fanOn: Boolean?,
    val fanReason: FanReason
)

object SensorBleProfile {
    val serviceUuid: UUID = UUID.fromString("645b8880-fe33-4f14-847f-c7f5f238690c")
    val sensorCharacteristicUuid: UUID = UUID.fromString("54190178-caf3-4b54-9152-c8c27cf089e5")
    val commandCharacteristicUuid: UUID = UUID.fromString("54190179-caf3-4b54-9152-c8c27cf089e5")
    val modelProbabilityCharacteristicUuid: UUID = UUID.fromString("7c0e56ae-dc0f-45e2-8b03-4a27a4b1f3cb")
    val actuatorStatusCharacteristicUuid: UUID = UUID.fromString("5419017a-caf3-4b54-9152-c8c27cf089e5")
    val clientConfigDescriptorUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val legacyPacketSize = 30
    const val expectedPacketSize = 32
    const val actuatorStatusPacketSize = 2
    const val minimumRequiredMtu = expectedPacketSize + 3
    const val requestedMtu = 64

    fun isSupportedSensorPacketSize(size: Int): Boolean {
        return size == legacyPacketSize || size == expectedPacketSize
    }
}

object SensorPacketParser {
    fun parse(packet: ByteArray, timestampMillis: Long = System.currentTimeMillis()): SensorSample? {
        if (!SensorBleProfile.isSupportedSensorPacketSize(packet.size)) {
            return null
        }

        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val accelX = buffer.short.toInt()
        val accelY = buffer.short.toInt()
        val accelZ = buffer.short.toInt()
        val gyroX = buffer.short.toInt()
        val gyroY = buffer.short.toInt()
        val gyroZ = buffer.short.toInt()
        val refTemperature = buffer.float
        val refHumidity = buffer.float
        val figTemperature = buffer.float
        val figHumidity = buffer.float
        val methane = (packet[28].toInt() and 0xFF) or ((packet[29].toInt() and 0xFF) shl 8)
        val fanOn = if (packet.size >= SensorBleProfile.expectedPacketSize) {
            packet[30].toInt() != 0
        } else {
            null
        }
        val fanReason = if (packet.size >= SensorBleProfile.expectedPacketSize) {
            FanReason.fromCode(packet[31].toInt() and 0xFF)
        } else {
            FanReason.Unknown
        }

        return SensorSample(
            timestampMillis = timestampMillis,
            accelX = accelX,
            accelY = accelY,
            accelZ = accelZ,
            gyroX = gyroX,
            gyroY = gyroY,
            gyroZ = gyroZ,
            methane = methane,
            refTemperature = refTemperature,
            refHumidity = refHumidity,
            figTemperature = figTemperature,
            figHumidity = figHumidity,
            fanOn = fanOn,
            fanReason = fanReason
        )
    }
}

class SensorDataManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _dashboardState = MutableStateFlow(SensorDashboardState())
    private val liveDataLock = Any()
    private val liveSamples = mutableListOf<SensorSample>()

    private var demoJob: Job? = null
    private var liveSourceMode = SensorSourceMode.None
    private var liveTotalSamples = 0
    private var liveLatestSample: SensorSample? = null
    private var liveRecordedSamplesCount = 0
    private var liveModelProbability: Float? = null
    private var liveFanOn: Boolean? = null
    private var liveFanReason: FanReason = FanReason.Unknown
    private var liveRefTemperature: Float? = null
    private var liveRefHumidity: Float? = null
    private var liveFigTemperature: Float? = null
    private var liveFigHumidity: Float? = null
    private var hasPendingDashboardRefresh = false

    val dashboardState: StateFlow<SensorDashboardState> = _dashboardState

    init {
        scope.launch {
            while (isActive) {
                delay(DASHBOARD_REFRESH_INTERVAL_MS)
                publishLiveSnapshotIfNeeded()
            }
        }
    }

    fun beginBleSession() {
        stopDemoSession()
        resetLiveState(SensorSourceMode.Ble)
        _dashboardState.value = SensorDashboardState(sourceMode = SensorSourceMode.Ble)
    }

    fun onBlePacket(packet: ByteArray) {
        val sample = SensorPacketParser.parse(packet) ?: return
        appendSample(sample, SensorSourceMode.Ble)
    }

    fun onBleModelProbability(probability: Float?) {
        synchronized(liveDataLock) {
            liveModelProbability = probability
            hasPendingDashboardRefresh = true
        }
        _dashboardState.update { current ->
            current.copy(modelProbability = probability)
        }
    }

    fun onBleFanStatus(
        fanOn: Boolean,
        fanReason: FanReason
    ) {
        synchronized(liveDataLock) {
            liveFanOn = fanOn
            liveFanReason = fanReason
            hasPendingDashboardRefresh = true
        }
        _dashboardState.update { current ->
            current.copy(
                fanOn = fanOn,
                fanReason = fanReason
            )
        }
    }

    fun startDemoSession() {
        stopDemoSession()
        resetLiveState(SensorSourceMode.Test)
        _dashboardState.value = SensorDashboardState(sourceMode = SensorSourceMode.Test)

        demoJob = scope.launch {
            var tick = 0
            while (isActive) {
                appendSample(generateDemoSample(tick), SensorSourceMode.Test)
                tick++
                delay(DEMO_INTERVAL_MS)
            }
        }
    }

    fun stopDemoSession() {
        demoJob?.cancel()
        demoJob = null
    }

    fun startRecording(sessionMetadata: SessionMetadata) {
        synchronized(liveDataLock) {
            recordedSamples.clear()
            liveRecordedSamplesCount = 0
        }
        _dashboardState.update { current ->
            current.copy(
                isRecording = true,
                isPastandoActive = false,
                isRumiandoActive = false,
                currentObservation = "",
                currentSessionMetadata = sessionMetadata,
                recordingStartTimestampMillis = System.currentTimeMillis(),
                recordedSamplesCount = 0,
                exportNotice = "Registro CSV iniciado. El tiempo exportado comenzara en 0."
            )
        }
    }

    fun setPastandoActive(isActive: Boolean) {
        _dashboardState.update { current ->
            if (!current.isRecording) {
                current
            } else {
                current.copy(
                    isPastandoActive = isActive,
                    isRumiandoActive = if (isActive) false else current.isRumiandoActive
                )
            }
        }
    }

    fun setRumiandoActive(isActive: Boolean) {
        _dashboardState.update { current ->
            if (!current.isRecording) {
                current
            } else {
                current.copy(
                    isRumiandoActive = isActive,
                    isPastandoActive = if (isActive) false else current.isPastandoActive
                )
            }
        }
    }

    fun setCurrentObservation(observation: String) {
        _dashboardState.update { current ->
            if (!current.isRecording) {
                current
            } else {
                current.copy(currentObservation = observation)
            }
        }
    }

    fun stopRecordingAndBuildCsv(): CsvExportPayload? {
        val snapshot = synchronized(liveDataLock) {
            recordedSamples.toList().also {
                recordedSamples.clear()
                liveRecordedSamplesCount = 0
            }
        }
        val dashboardSnapshot = _dashboardState.value
        val sourceMode = dashboardSnapshot.sourceMode
        val sessionMetadata = dashboardSnapshot.currentSessionMetadata

        _dashboardState.update { current ->
            current.copy(
                isRecording = false,
                isPastandoActive = false,
                isRumiandoActive = false,
                currentObservation = "",
                recordingStartTimestampMillis = null,
                recordedSamplesCount = 0,
                exportNotice = if (snapshot.isEmpty()) {
                    "No se registraron muestras para exportar."
                } else {
                    "Registro detenido. Elige donde guardar el CSV."
                }
            )
        }

        if (snapshot.isEmpty()) {
            return null
        }

        return CsvExportPayload(
            suggestedFileName = buildFileName(sourceMode, sessionMetadata),
            content = buildCsv(snapshot, sourceMode, sessionMetadata),
            rowCount = snapshot.size,
            sessionMetadata = sessionMetadata
        )
    }

    fun setExportNotice(message: String) {
        _dashboardState.update { current ->
            current.copy(exportNotice = message)
        }
    }

    fun clear() {
        stopDemoSession()
        synchronized(liveDataLock) {
            recordedSamples.clear()
        }
        resetLiveState(SensorSourceMode.None)
        _dashboardState.value = SensorDashboardState()
    }

    fun close() {
        scope.cancel()
    }

    private fun appendSample(sample: SensorSample, sourceMode: SensorSourceMode) {
        val dashboardSnapshot = _dashboardState.value

        synchronized(liveDataLock) {
            val sourceChanged = liveSourceMode != sourceMode
            if (sourceChanged) {
                liveSourceMode = sourceMode
                liveSamples.clear()
                liveTotalSamples = 0
                liveLatestSample = null
            }

            if (sourceMode == SensorSourceMode.Test) {
                liveModelProbability = sample.modelProbability
            }

            if (sample.fanOn != null) {
                liveFanOn = sample.fanOn
                liveFanReason = sample.fanReason
            }

            updateLatestEnvironmentalValues(sample)

            val stableSample = sample.copy(
                refTemperature = liveRefTemperature ?: sample.refTemperature,
                refHumidity = liveRefHumidity ?: sample.refHumidity,
                figTemperature = liveFigTemperature ?: sample.figTemperature,
                figHumidity = liveFigHumidity ?: sample.figHumidity
            )

            val enrichedSample = stableSample.copy(
                modelProbability = liveModelProbability,
                fanOn = liveFanOn,
                fanReason = liveFanReason
            )

            if (dashboardSnapshot.isRecording) {
                recordedSamples.add(
                    RecordedSample(
                        sample = enrichedSample,
                        pastandoActive = dashboardSnapshot.isPastandoActive,
                        rumiandoActive = dashboardSnapshot.isRumiandoActive
                    )
                )
                liveRecordedSamplesCount = recordedSamples.size
            }

            liveSamples.add(enrichedSample)
            if (liveSamples.size > MAX_SAMPLES) {
                liveSamples.removeAt(0)
            }

            liveTotalSamples = if (sourceChanged) 1 else liveTotalSamples + 1
            liveLatestSample = enrichedSample
            hasPendingDashboardRefresh = true
        }
    }

    private fun resetLiveState(sourceMode: SensorSourceMode) {
        synchronized(liveDataLock) {
            liveSourceMode = sourceMode
            liveSamples.clear()
            liveTotalSamples = 0
            liveLatestSample = null
            liveRecordedSamplesCount = 0
            liveModelProbability = null
            liveFanOn = null
            liveFanReason = FanReason.Unknown
            liveRefTemperature = null
            liveRefHumidity = null
            liveFigTemperature = null
            liveFigHumidity = null
            hasPendingDashboardRefresh = false
        }
    }

    private fun updateLatestEnvironmentalValues(sample: SensorSample) {
        if (isFiniteSensorValue(sample.refTemperature)) {
            liveRefTemperature = sample.refTemperature
        }
        if (isFiniteSensorValue(sample.refHumidity)) {
            liveRefHumidity = sample.refHumidity
        }
        if (isFiniteSensorValue(sample.figTemperature)) {
            liveFigTemperature = sample.figTemperature
        }
        if (isFiniteSensorValue(sample.figHumidity)) {
            liveFigHumidity = sample.figHumidity
        }
    }

    private fun isFiniteSensorValue(value: Float): Boolean {
        return !value.isNaN() && !value.isInfinite()
    }

    private fun publishLiveSnapshotIfNeeded(force: Boolean = false) {
        val snapshot = synchronized(liveDataLock) {
            if (!force && !hasPendingDashboardRefresh) {
                null
            } else {
                hasPendingDashboardRefresh = false
                DashboardLiveSnapshot(
                    sourceMode = liveSourceMode,
                    samples = liveSamples.toList(),
                    totalSamples = liveTotalSamples,
                    latestSample = liveLatestSample,
                    recordedSamplesCount = liveRecordedSamplesCount,
                    modelProbability = liveModelProbability,
                    fanOn = liveFanOn,
                    fanReason = liveFanReason
                )
            }
        } ?: return

        _dashboardState.update { current ->
            current.copy(
                sourceMode = snapshot.sourceMode,
                samples = snapshot.samples,
                totalSamples = snapshot.totalSamples,
                latestSample = snapshot.latestSample,
                recordedSamplesCount = if (current.isRecording) {
                    snapshot.recordedSamplesCount
                } else {
                    current.recordedSamplesCount
                },
                modelProbability = snapshot.modelProbability,
                fanOn = snapshot.fanOn,
                fanReason = snapshot.fanReason
            )
        }
    }

    private fun generateDemoSample(tick: Int): SensorSample {
        val t = tick / 10f
        val methanePulse = if ((tick / 20) % 2 == 0) 180 else 40

        return SensorSample(
            timestampMillis = System.currentTimeMillis(),
            accelX = (sin(t * 1.20f) * 8200f).toInt(),
            accelY = (sin(t * 0.90f + 1.3f) * 6400f).toInt(),
            accelZ = (sin(t * 1.45f + 2.2f) * 9100f).toInt(),
            gyroX = (sin(t * 1.80f) * 4200f).toInt(),
            gyroY = (sin(t * 1.15f + 0.8f) * 3600f).toInt(),
            gyroZ = (sin(t * 1.55f + 2.8f) * 3900f).toInt(),
            methane = 420 + methanePulse + (sin(t * 0.55f) * 55f).toInt(),
            refTemperature = 24.2f + sin(t * 0.18f) * 0.7f,
            refHumidity = 47.0f + sin(t * 0.13f + 1.1f) * 3.5f,
            figTemperature = 28.0f + sin(t * 0.24f + 0.4f) * 1.8f,
            figHumidity = 61.0f + sin(t * 0.17f + 1.8f) * 5.5f,
            modelProbability = (0.45f + sin(t * 0.15f) * 0.25f).coerceIn(0f, 1f),
            fanOn = false,
            fanReason = FanReason.Off
        )
    }

    private companion object {
        const val MAX_SAMPLES = 180
        const val DEMO_INTERVAL_MS = 100L
        const val DASHBOARD_REFRESH_INTERVAL_MS = 100L
    }

    private val recordedSamples = mutableListOf<RecordedSample>()

    private fun buildFileName(
        sourceMode: SensorSourceMode,
        sessionMetadata: SessionMetadata?
    ): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val stamp = formatter.format(Date())
        val sessionTag = sessionMetadata?.sessionNumber
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val animalTag = sessionMetadata?.animalId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "_")

        val nameParts = buildList {
            add("wearme")
            add(sourceMode.name.lowercase(Locale.US))
            sessionTag?.let { add("sesion_$it") }
            animalTag?.let { add("animal_$it") }
            add(stamp)
        }

        return "${nameParts.joinToString("_")}.csv"
    }

    private fun buildCsv(
        samples: List<RecordedSample>,
        sourceMode: SensorSourceMode,
        sessionMetadata: SessionMetadata?
    ): String {
        val builder = StringBuilder()
        val csvStartTimestampMillis = samples.firstOrNull()?.sample?.timestampMillis ?: 0L
        appendCsvRow(
            builder,
            listOf(
                "session_number",
                "animal_id",
                "weight_kg",
                "gestation",
                "breed",
                "feeding",
                "age",
                "productive_state",
                "sample_index",
                "elapsed_time_min",
                "timestamp_ms",
                "pastando",
                "rumiando",
                "source_mode",
                "accel_x",
                "accel_y",
                "accel_z",
                "gyro_x",
                "gyro_y",
                "gyro_z",
                "methane",
                "model_probability",
                "fan_on",
                "fan_reason",
                "ref_temperature_c",
                "ref_humidity_rh",
                "fig_temperature_c",
                "fig_humidity_rh"
            )
        )

        samples.forEachIndexed { index, sample ->
            val sensorSample = sample.sample
            val elapsedTimeMillis = (sensorSample.timestampMillis - csvStartTimestampMillis).coerceAtLeast(0L)
            val elapsedTimeMinutes = String.format(Locale.US, "%.1f", elapsedTimeMillis / 60000f)
            appendCsvRow(
                builder,
                listOf(
                    sessionMetadata?.sessionNumber.orEmpty(),
                    sessionMetadata?.animalId.orEmpty(),
                    sessionMetadata?.weightKg.orEmpty(),
                    sessionMetadata?.gestationLabel.orEmpty(),
                    sessionMetadata?.breed.orEmpty(),
                    sessionMetadata?.feeding.orEmpty(),
                    sessionMetadata?.age.orEmpty(),
                    sessionMetadata?.productiveStateLabel.orEmpty(),
                    index.toString(),
                    elapsedTimeMinutes,
                    sensorSample.timestampMillis.toString(),
                    if (sample.pastandoActive) "si" else "",
                    if (sample.rumiandoActive) "si" else "",
                    sourceMode.name.lowercase(Locale.US),
                    sensorSample.accelX.toString(),
                    sensorSample.accelY.toString(),
                    sensorSample.accelZ.toString(),
                    sensorSample.gyroX.toString(),
                    sensorSample.gyroY.toString(),
                    sensorSample.gyroZ.toString(),
                    sensorSample.methane.toString(),
                    formatCsvFloat(sensorSample.modelProbability),
                    formatCsvFanOn(sensorSample.fanOn),
                    sensorSample.fanReason.csvValue,
                    formatCsvMeasurement(sensorSample.refTemperature),
                    formatCsvMeasurement(sensorSample.refHumidity),
                    formatCsvMeasurement(sensorSample.figTemperature),
                    formatCsvMeasurement(sensorSample.figHumidity)
                )
            )
        }

        return builder.toString()
    }

    private fun formatCsvFloat(value: Float?): String {
        value ?: return ""
        if (value.isNaN() || value.isInfinite()) {
            return ""
        }
        return String.format(Locale.US, "%.6f", value)
    }

    private fun formatCsvMeasurement(value: Float): String {
        if (value.isNaN() || value.isInfinite()) {
            return ""
        }
        return String.format(Locale.US, "%.4f", value)
    }

    private fun formatCsvFanOn(value: Boolean?): String {
        return when (value) {
            true -> "si"
            false -> "no"
            null -> ""
        }
    }

    private fun appendCsvRow(builder: StringBuilder, values: List<String>) {
        values.forEachIndexed { index, value ->
            if (index > 0) {
                builder.append(',')
            }
            builder.append(escapeCsv(value))
        }
        builder.append('\n')
    }

    private fun escapeCsv(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n')
        if (!needsQuotes) {
            return value
        }

        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}

package com.example.wearme_01

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import kotlin.math.abs

private data class ChartSeries(
    val name: String,
    val color: Color,
    val values: List<Float>
)

private data class RenderableChartSeries(
    val name: String,
    val color: Color,
    val values: List<Float>,
    val rawPointCount: Int,
    val latestValue: Float?
)

private data class ChartValueBounds(
    val min: Float,
    val max: Float
)

private val ChartHeight = 210.dp
private val ChartInnerPadding = 12.dp
private val YAxisWidth = 56.dp
private const val ChartTickCount = 5

@Composable
fun SensorDashboardScreen(
    dashboardState: SensorDashboardState,
    sessionVideoState: SessionVideoState,
    connectionMessage: String,
    isTestMode: Boolean,
    isBleCommandAvailable: Boolean,
    isBleCommandInFlight: Boolean,
    bleCommandStatusMessage: String?,
    constantFanEnabled: Boolean,
    onSendCleaningCommand: () -> Unit,
    onSetConstantFanEnabled: (Boolean) -> Unit,
    onStartRecording: () -> Unit,
    onSetPastandoActive: (Boolean) -> Unit,
    onSetRumiandoActive: (Boolean) -> Unit,
    onSetCurrentObservation: (String) -> Unit,
    onSaveObservation: () -> Unit,
    onVideoPreviewReady: (PreviewView) -> Unit,
    onStopRecording: () -> Unit,
    onExit: () -> Unit
) {
    val latestSample = dashboardState.latestSample
    val chartSamples = if (
        dashboardState.isRecording &&
        dashboardState.recordingStartTimestampMillis != null
    ) {
        dashboardState.samples.filter { sample ->
            sample.timestampMillis >= dashboardState.recordingStartTimestampMillis
        }
    } else {
        dashboardState.samples
    }
    val recordingElapsedMillis = if (
        dashboardState.isRecording &&
        dashboardState.recordingStartTimestampMillis != null
    ) {
        val latestTimestamp = chartSamples.lastOrNull()?.timestampMillis ?: latestSample?.timestampMillis
        latestTimestamp?.let { timestamp ->
            (timestamp - dashboardState.recordingStartTimestampMillis).coerceAtLeast(0L)
        } ?: 0L
    } else {
        null
    }
    val rootView = LocalView.current
    DisposableEffect(sessionVideoState.shouldShowPreview, rootView) {
        val previousKeepScreenOn = rootView.keepScreenOn
        rootView.keepScreenOn = sessionVideoState.shouldShowPreview
        onDispose {
            rootView.keepScreenOn = previousKeepScreenOn
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Dashboard de Sensores",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Text(
                    text = if (isTestMode) {
                        "Estas viendo señales simuladas con el mismo formato BLE de la XIAO para probar la interfaz y las gráficas."
                    } else {
                        "Estas viendo las señales recibidas por BLE desde la XIAO nRF52840 Sense anunciada como SensorHub."
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            item {
                InfoCard(
                    title = if (isTestMode) "Modo de prueba" else "Estado BLE",
                    body = connectionMessage
                )
            }

            item {
                SummaryCard(
                    dashboardState = dashboardState,
                    latestSample = latestSample
                )
            }

            item {
                ModelFanCard(dashboardState = dashboardState)
            }

            if (!isTestMode) {
                item {
                    BleCommandCard(
                        isCommandAvailable = isBleCommandAvailable,
                        isCommandInFlight = isBleCommandInFlight,
                        commandStatusMessage = bleCommandStatusMessage,
                        constantFanEnabled = constantFanEnabled,
                        onSendCleaningCommand = onSendCleaningCommand,
                        onSetConstantFanEnabled = onSetConstantFanEnabled
                    )
                }
            }

            item {
                RecordingCard(
                    dashboardState = dashboardState,
                    sessionVideoState = sessionVideoState,
                    onStartRecording = onStartRecording,
                    onSetPastandoActive = onSetPastandoActive,
                    onSetRumiandoActive = onSetRumiandoActive,
                    onSetCurrentObservation = onSetCurrentObservation,
                    onSaveObservation = onSaveObservation,
                    onStopRecording = onStopRecording
                )
            }

            dashboardState.currentSessionMetadata
                ?.takeIf { it.hasDetails() }
                ?.let { metadata ->
                    item {
                        SessionMetadataCard(metadata = metadata)
                    }
                }

            if (latestSample == null) {
                item {
                    InfoCard(
                        title = "Esperando datos",
                        body = if (isTestMode) {
                            "El generador de prueba debería empezar a producir muestras en unos instantes."
                        } else {
                            "La conexión BLE ya está lista. En cuanto la XIAO envíe notificaciones, las gráficas empezarán a llenarse."
                        }
                    )
                }
            }

            item {
                LineChartCard(
                    title = "Metano",
                    unit = "ADC",
                    recordingElapsedMillis = recordingElapsedMillis,
                    series = listOf(
                        ChartSeries("Metano", Color(0xFFF2CC8F), chartSamples.map { it.methane.toFloat() })
                    )
                )
            }

            item {
                LineChartCard(
                    title = "Probabilidad del modelo",
                    unit = "%",
                    recordingElapsedMillis = recordingElapsedMillis,
                    series = listOf(
                        ChartSeries(
                            "Evento",
                            Color(0xFF7C3AED),
                            chartSamples.map { sample ->
                                sample.modelProbability?.times(100f) ?: Float.NaN
                            }
                        )
                    )
                )
            }

            item {
                LineChartCard(
                    title = "Temperatura",
                    unit = "°C",
                    recordingElapsedMillis = recordingElapsedMillis,
                    series = listOf(
                        ChartSeries("Referencia", Color(0xFF118AB2), chartSamples.map { it.refTemperature }),
                        ChartSeries("Figaro", Color(0xFFEF476F), chartSamples.map { it.figTemperature })
                    )
                )
            }

            item {
                LineChartCard(
                    title = "Humedad",
                    unit = "%RH",
                    recordingElapsedMillis = recordingElapsedMillis,
                    series = listOf(
                        ChartSeries("Referencia", Color(0xFF06D6A0), chartSamples.map { it.refHumidity }),
                        ChartSeries("Figaro", Color(0xFF6C757D), chartSamples.map { it.figHumidity })
                    )
                )
            }

            item {
                LineChartCard(
                    title = "Acelerómetro IMU",
                    unit = "raw",
                    recordingElapsedMillis = recordingElapsedMillis,
                    series = listOf(
                        ChartSeries("Accel X", MaterialTheme.colorScheme.primary, chartSamples.map { it.accelX.toFloat() }),
                        ChartSeries("Accel Y", MaterialTheme.colorScheme.tertiary, chartSamples.map { it.accelY.toFloat() }),
                        ChartSeries("Accel Z", MaterialTheme.colorScheme.secondary, chartSamples.map { it.accelZ.toFloat() })
                    )
                )
            }

            item {
                LineChartCard(
                    title = "Giroscopio IMU",
                    unit = "raw",
                    recordingElapsedMillis = recordingElapsedMillis,
                    series = listOf(
                        ChartSeries("Gyro X", Color(0xFFE07A5F), chartSamples.map { it.gyroX.toFloat() }),
                        ChartSeries("Gyro Y", Color(0xFF3D405B), chartSamples.map { it.gyroY.toFloat() }),
                        ChartSeries("Gyro Z", Color(0xFF81B29A), chartSamples.map { it.gyroZ.toFloat() })
                    )
                )
            }

            item {
                OutlinedButton(
                    onClick = onExit,
                    enabled = !dashboardState.isRecording
                ) {
                    Text(if (isTestMode) "Volver" else "Desconectar y volver")
                }
            }
        }

        if (sessionVideoState.shouldShowPreview) {
            SessionVideoPreviewCard(
                sessionVideoState = sessionVideoState,
                onVideoPreviewReady = onVideoPreviewReady,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .width(190.dp)
            )
        }
    }
}

@Composable
private fun SummaryCard(
    dashboardState: SensorDashboardState,
    latestSample: SensorSample?
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
            Text(
                text = "Resumen",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Origen: ${dashboardState.sourceMode.name} | Total recibidas: ${dashboardState.totalSamples}",
                style = MaterialTheme.typography.bodyMedium
            )

            if (latestSample != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricCard(
                        title = "Metano",
                        value = "${latestSample.methane} ADC",
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = "Temp Figaro",
                        value = "${formatValue(latestSample.figTemperature)} °C",
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricCard(
                        title = "Temp Ref",
                        value = "${formatValue(latestSample.refTemperature)} °C",
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = "Humedad Figaro",
                        value = "${formatValue(latestSample.figHumidity)} %RH",
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricCard(
                        title = "Humedad Ref",
                        value = "${formatValue(latestSample.refHumidity)} %RH",
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = "Muestras",
                        value = dashboardState.totalSamples.toString(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelFanCard(
    dashboardState: SensorDashboardState
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
                text = "Modelo y ventilador",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    title = "Probabilidad",
                    value = formatProbability(dashboardState.modelProbability),
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    title = "Ventilador",
                    value = formatFanStatus(dashboardState.fanOn),
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "Motivo: ${formatFanReason(dashboardState.fanReason, dashboardState.fanOn)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SessionMetadataCard(
    metadata: SessionMetadata
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Datos de la sesion",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            SessionMetadataRow("Sesion", metadata.sessionNumber)
            SessionMetadataRow("ID vaca", metadata.animalId)
            SessionMetadataRow("Peso", metadata.weightKg.takeIf { it.isNotBlank() }?.let { "$it kg" }.orEmpty())
            SessionMetadataRow("Raza", metadata.breed)
            SessionMetadataRow("Alimentacion", metadata.feeding)
            SessionMetadataRow("Edad (Años)", metadata.age)
            SessionMetadataRow("Estado productivo", metadata.productiveStateLabel.replaceFirstChar { it.uppercase() })
            SessionMetadataRow("Gestacion", metadata.gestationLabel.replaceFirstChar { it.uppercase() })
        }
    }
}

@Composable
private fun SessionMetadataRow(
    label: String,
    value: String
) {
    if (value.isBlank()) {
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun BleCommandCard(
    isCommandAvailable: Boolean,
    isCommandInFlight: Boolean,
    commandStatusMessage: String?,
    constantFanEnabled: Boolean,
    onSendCleaningCommand: () -> Unit,
    onSetConstantFanEnabled: (Boolean) -> Unit
) {
    val controlsEnabled = isCommandAvailable && !isCommandInFlight

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
                text = "Comandos XIAO",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Button(
                onClick = onSendCleaningCommand,
                enabled = controlsEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isCommandInFlight) "Enviando..." else "Limpieza")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Ventilador constante",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (constantFanEnabled) "Activo" else "Inactivo",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = constantFanEnabled,
                    onCheckedChange = onSetConstantFanEnabled,
                    enabled = controlsEnabled
                )
            }

            commandStatusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecordingCard(
    dashboardState: SensorDashboardState,
    sessionVideoState: SessionVideoState,
    onStartRecording: () -> Unit,
    onSetPastandoActive: (Boolean) -> Unit,
    onSetRumiandoActive: (Boolean) -> Unit,
    onSetCurrentObservation: (String) -> Unit,
    onSaveObservation: () -> Unit,
    onStopRecording: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Registro CSV",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (dashboardState.isRecording) {
                    "Grabando ${dashboardState.recordedSamplesCount} muestras para exportar. Puedes marcar Pastando o Rumiando y guardar observaciones aparte en el historial, sin mezclarlas con el CSV."
                } else {
                    "Pulsa el boton para diligenciar los datos de la sesion y comenzar a registrar las muestras en CSV. Al iniciar, el tiempo exportado se reinicia en 0."
                },
                style = MaterialTheme.typography.bodyLarge
            )

            dashboardState.exportNotice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            if (dashboardState.isRecording) {
                OutlinedButton(onClick = onStopRecording) {
                    Text("Parar registro CSV")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    BehaviorToggleButton(
                        label = "Pastando",
                        isActive = dashboardState.isPastandoActive,
                        onToggle = {
                            onSetPastandoActive(!dashboardState.isPastandoActive)
                        },
                        modifier = Modifier.weight(1f)
                    )
                    BehaviorToggleButton(
                        label = "Rumiando",
                        isActive = dashboardState.isRumiandoActive,
                        onToggle = {
                            onSetRumiandoActive(!dashboardState.isRumiandoActive)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (sessionVideoState.status == SessionVideoStatus.Error) {
                    Text(
                        text = sessionVideoState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                OutlinedTextField(
                    value = dashboardState.currentObservation,
                    onValueChange = onSetCurrentObservation,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Observaciones") },
                    placeholder = { Text("Escribe aqui cualquier observacion o inconveniente") },
                    minLines = 3
                )

                Button(
                    onClick = onSaveObservation,
                    enabled = dashboardState.currentObservation.isNotBlank()
                ) {
                    Text("Guardar observacion en historial")
                }

                Text(
                    text = "La observacion se guardara como un registro independiente con su fecha, actividad y tiempo del CSV.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                OutlinedButton(onClick = onStartRecording) {
                    Text("Comenzar registro CSV")
                }
            }
        }
    }
}

@Composable
private fun SessionVideoPreviewCard(
    sessionVideoState: SessionVideoState,
    onVideoPreviewReady: (PreviewView) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Video de sesion",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (sessionVideoState.status == SessionVideoStatus.Recording) {
                        "Grabando"
                    } else {
                        "Preparando"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            AndroidView(
                factory = { context ->
                    PreviewView(context).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        onVideoPreviewReady(this)
                    }
                },
                update = { previewView ->
                    onVideoPreviewReady(previewView)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp)
                    )
            )

            Text(
                text = sessionVideoState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BehaviorToggleButton(
    label: String,
    isActive: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isActive) {
        Button(
            onClick = onToggle,
            modifier = modifier
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onToggle,
            modifier = modifier
        ) {
            Text(label)
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun LineChartCard(
    title: String,
    unit: String,
    recordingElapsedMillis: Long?,
    series: List<ChartSeries>
) {
    val seriesNames = series.map { it.name }
    var hiddenSeriesNames by remember(title, seriesNames) { mutableStateOf(emptySet<String>()) }
    val renderableSeries = series.map { it.toRenderableChartSeries() }
    val visibleSeries = renderableSeries.filterNot { it.name in hiddenSeriesNames }
    val hasRenderableValues = visibleSeries.any { it.values.isNotEmpty() }
    val hasRawValues = visibleSeries.any { it.rawPointCount > 0 }
    val valueBounds = visibleSeries.findChartValueBounds()
    val minValue = valueBounds?.min
    val maxValue = valueBounds?.max
    val axisValues = buildAxisValues(minValue, maxValue)
    val visiblePointCount = visibleSeries.maxOfOrNull { it.rawPointCount } ?: 0

    Card {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Max: ${formatValue(maxValue)} $unit",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "${visibleSeries.size}/${series.size} series | $visiblePointCount muestras",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (!hasRenderableValues) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ChartHeight)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(18.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (series.isNotEmpty() && visibleSeries.isEmpty()) {
                            "Selecciona al menos una serie para volver a ver la gráfica."
                        } else if (hasRawValues) {
                            "Esperando valores válidos para graficar..."
                        } else {
                            "Esperando muestras para graficar..."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ChartHeight),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .width(YAxisWidth)
                            .fillMaxHeight()
                            .padding(top = ChartInnerPadding, end = 10.dp, bottom = ChartInnerPadding),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End
                    ) {
                        axisValues.forEach { axisValue ->
                            Text(
                                text = formatValue(axisValue),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(18.dp)
                            )
                            .padding(ChartInnerPadding)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val chartWidth = size.width
                            val chartHeight = size.height
                            val safeMin = minValue ?: 0f
                            val safeMax = maxValue ?: 1f
                            val range = if (abs(safeMax - safeMin) < 0.0001f) 1f else safeMax - safeMin

                            for (index in 0 until ChartTickCount) {
                                val y = if (ChartTickCount == 1) {
                                    chartHeight / 2f
                                } else {
                                    chartHeight * index / (ChartTickCount - 1).toFloat()
                                }
                                drawLine(
                                    color = Color.White.copy(alpha = if (index == ChartTickCount - 1) 0.35f else 0.22f),
                                    start = Offset(0f, y),
                                    end = Offset(chartWidth, y),
                                    strokeWidth = 1.dp.toPx()
                                )
                            }

                            drawLine(
                                color = Color.White.copy(alpha = 0.30f),
                                start = Offset(0f, 0f),
                                end = Offset(0f, chartHeight),
                                strokeWidth = 1.dp.toPx()
                            )

                            visibleSeries.forEach { chartSeries ->
                                drawChartSeries(
                                    values = chartSeries.values,
                                    color = chartSeries.color,
                                    chartWidth = chartWidth,
                                    chartHeight = chartHeight,
                                    safeMin = safeMin,
                                    range = range,
                                    pointRadius = 4.dp.toPx(),
                                    strokeWidth = 2.5.dp.toPx()
                                )
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Min: ${formatValue(minValue)} $unit",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = if (recordingElapsedMillis != null) "Tiempo CSV" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (recordingElapsedMillis != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.width(YAxisWidth))
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp + ChartInnerPadding, end = ChartInnerPadding),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                text = formatElapsedMinutes(recordingElapsedMillis),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                renderableSeries.forEach { chartSeries ->
                    LegendRow(
                        color = chartSeries.color,
                        label = chartSeries.name,
                        value = chartSeries.latestValue,
                        selected = chartSeries.name !in hiddenSeriesNames,
                        onToggle = {
                            hiddenSeriesNames = if (chartSeries.name in hiddenSeriesNames) {
                                hiddenSeriesNames - chartSeries.name
                            } else {
                                hiddenSeriesNames + chartSeries.name
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun buildAxisValues(minValue: Float?, maxValue: Float?): List<Float> {
    val safeMin = minValue ?: 0f
    val safeMax = maxValue ?: safeMin
    val range = if (abs(safeMax - safeMin) < 0.0001f) 1f else safeMax - safeMin
    return List(ChartTickCount) { index ->
        safeMax - (range * index / (ChartTickCount - 1).toFloat())
    }
}

private fun ChartSeries.toRenderableChartSeries(): RenderableChartSeries {
    val renderableValues = values.toHoldLastRenderableValues()
    return RenderableChartSeries(
        name = name,
        color = color,
        values = renderableValues,
        rawPointCount = values.size,
        latestValue = renderableValues.lastOrNull()
    )
}

private fun List<Float>.toHoldLastRenderableValues(): List<Float> {
    val firstRenderableValue = firstOrNull(::isRenderableChartValue) ?: return emptyList()
    val renderableValues = ArrayList<Float>(size)
    var latestValue = firstRenderableValue

    for (value in this) {
        if (isRenderableChartValue(value)) {
            latestValue = value
        }
        renderableValues.add(latestValue)
    }

    return renderableValues
}

private fun List<RenderableChartSeries>.findChartValueBounds(): ChartValueBounds? {
    var minValue: Float? = null
    var maxValue: Float? = null

    for (chartSeries in this) {
        for (value in chartSeries.values) {
            minValue = minOf(minValue ?: value, value)
            maxValue = maxOf(maxValue ?: value, value)
        }
    }

    val min = minValue ?: return null
    val max = maxValue ?: return null
    return ChartValueBounds(min = min, max = max)
}

private fun DrawScope.drawChartSeries(
    values: List<Float>,
    color: Color,
    chartWidth: Float,
    chartHeight: Float,
    safeMin: Float,
    range: Float,
    pointRadius: Float,
    strokeWidth: Float
) {
    if (values.isEmpty()) {
        return
    }

    if (values.size == 1) {
        val value = values.first()
        val y = chartHeight - ((value - safeMin) / range) * chartHeight
        drawCircle(
            color = color,
            radius = pointRadius,
            center = Offset(chartWidth / 2f, y)
        )
        return
    }

    val path = Path()
    values.forEachIndexed { index, value ->
        val x = chartWidth * index / values.lastIndex.toFloat()
        val y = chartHeight - ((value - safeMin) / range) * chartHeight
        if (index == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round
        )
    )
}

@Composable
private fun LegendRow(
    color: Color,
    label: String,
    value: Float?,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .alpha(if (selected) 1f else 0.58f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color = color, shape = CircleShape)
            )
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = formatValue(value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun formatValue(value: Float?): String {
    value ?: return "--"
    if (!isRenderableChartValue(value)) {
        return "--"
    }
    return when {
        abs(value) >= 100f -> String.format(Locale.US, "%.0f", value)
        abs(value) >= 10f -> String.format(Locale.US, "%.1f", value)
        else -> String.format(Locale.US, "%.2f", value)
    }
}

private fun formatProbability(value: Float?): String {
    value ?: return "--"
    if (!isRenderableChartValue(value)) {
        return "--"
    }
    return String.format(Locale.US, "%.1f %%", value.coerceIn(0f, 1f) * 100f)
}

private fun formatFanStatus(value: Boolean?): String {
    return when (value) {
        true -> "Encendido"
        false -> "Apagado"
        null -> "--"
    }
}

private fun formatFanReason(
    fanReason: FanReason,
    fanOn: Boolean?
): String {
    return when {
        fanOn == null -> "--"
        fanOn == false -> FanReason.Off.displayLabel
        fanReason == FanReason.Unknown -> "--"
        else -> fanReason.displayLabel
    }
}

private fun isRenderableChartValue(value: Float): Boolean {
    return !value.isNaN() && !value.isInfinite()
}

private fun formatElapsedMinutes(valueMillis: Long): String {
    val valueMinutes = valueMillis / 60000f
    return String.format(Locale.US, "%.1f min", valueMinutes)
}

package com.example.wearme_01

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

data class ObservationEntry(
    val id: Long,
    val createdAtMillis: Long,
    val sessionNumber: String,
    val animalId: String,
    val behavior: String,
    val elapsedTimeMin: String,
    val sourceMode: String,
    val note: String
)

data class ObservationHistoryState(
    val entries: List<ObservationEntry> = emptyList(),
    val notice: String? = null
)

data class ObservationTextExportPayload(
    val suggestedFileName: String,
    val content: String,
    val entryCount: Int
)

class ObservationHistoryManager(context: Context) {
    private val storageFile = File(context.filesDir, STORAGE_FILE_NAME)
    private val _state = MutableStateFlow(
        ObservationHistoryState(entries = loadEntries())
    )

    val state: StateFlow<ObservationHistoryState> = _state

    fun saveObservation(dashboardState: SensorDashboardState): Boolean {
        val note = dashboardState.currentObservation.trim()
        if (note.isBlank()) {
            return false
        }

        val createdAtMillis = System.currentTimeMillis()
        val timestampReference = dashboardState.latestSample?.timestampMillis ?: createdAtMillis
        val elapsedTimeMillis = dashboardState.recordingStartTimestampMillis
            ?.let { start -> (timestampReference - start).coerceAtLeast(0L) }
            ?: 0L

        val entry = ObservationEntry(
            id = createdAtMillis,
            createdAtMillis = createdAtMillis,
            sessionNumber = dashboardState.currentSessionMetadata?.sessionNumber.orEmpty(),
            animalId = dashboardState.currentSessionMetadata?.animalId.orEmpty(),
            behavior = when {
                dashboardState.isPastandoActive -> "Pastando"
                dashboardState.isRumiandoActive -> "Rumiando"
                else -> "Sin marcar"
            },
            elapsedTimeMin = String.format(Locale.US, "%.1f", elapsedTimeMillis / 60000f),
            sourceMode = dashboardState.sourceMode.name.lowercase(Locale.US),
            note = note
        )

        val nextEntries = listOf(entry) + _state.value.entries
        _state.value = _state.value.copy(
            entries = nextEntries,
            notice = "Observacion guardada en historial."
        )
        persist(nextEntries)
        return true
    }

    fun buildTxtExportPayload(): ObservationTextExportPayload? {
        val entries = _state.value.entries
        if (entries.isEmpty()) {
            return null
        }

        return ObservationTextExportPayload(
            suggestedFileName = buildExportFileName(),
            content = buildTxt(entries),
            entryCount = entries.size
        )
    }

    fun deleteObservation(id: Long) {
        val nextEntries = _state.value.entries.filterNot { it.id == id }
        _state.value = _state.value.copy(
            entries = nextEntries,
            notice = "Observacion eliminada del historial."
        )
        persist(nextEntries)
    }

    fun clearHistory() {
        _state.value = _state.value.copy(
            entries = emptyList(),
            notice = "Historial de observaciones limpiado."
        )
        persist(emptyList())
    }

    fun setNotice(message: String?) {
        _state.update { current ->
            current.copy(notice = message)
        }
    }

    private fun loadEntries(): List<ObservationEntry> {
        val raw = runCatching {
            if (storageFile.exists()) storageFile.readText() else "[]"
        }.getOrDefault("[]")

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        ObservationEntry(
                            id = item.optLong("id"),
                            createdAtMillis = item.optLong("createdAtMillis"),
                            sessionNumber = item.optString("sessionNumber"),
                            animalId = item.optString("animalId"),
                            behavior = item.optString("behavior"),
                            elapsedTimeMin = item.optString("elapsedTimeMin"),
                            sourceMode = item.optString("sourceMode"),
                            note = item.optString("note")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(entries: List<ObservationEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("createdAtMillis", entry.createdAtMillis)
                    .put("sessionNumber", entry.sessionNumber)
                    .put("animalId", entry.animalId)
                    .put("behavior", entry.behavior)
                    .put("elapsedTimeMin", entry.elapsedTimeMin)
                    .put("sourceMode", entry.sourceMode)
                    .put("note", entry.note)
            )
        }

        runCatching {
            storageFile.writeText(array.toString())
        }.onFailure {
            _state.update { current ->
                current.copy(notice = "No se pudo guardar el historial de observaciones.")
            }
        }
    }

    private fun buildTxt(entries: List<ObservationEntry>): String {
        val builder = StringBuilder()
        builder.appendLine("WearME - Historial de observaciones")
        builder.appendLine("Generado: ${formatObservationTimestamp(System.currentTimeMillis())}")
        builder.appendLine("Total de observaciones: ${entries.size}")
        builder.appendLine()

        entries.forEachIndexed { index, entry ->
            builder.appendLine("Observacion ${index + 1}")
            builder.appendLine("Fecha: ${formatObservationTimestamp(entry.createdAtMillis)}")
            builder.appendLine("Sesion: ${entry.sessionNumber.ifBlank { "Sin dato" }}")
            builder.appendLine("ID vaca: ${entry.animalId.ifBlank { "Sin dato" }}")
            builder.appendLine("Actividad: ${entry.behavior}")
            builder.appendLine("Tiempo CSV: ${entry.elapsedTimeMin} min")
            builder.appendLine("Origen: ${entry.sourceMode}")
            builder.appendLine("Nota: ${entry.note}")
            builder.appendLine()
        }

        return builder.toString()
    }

    private fun buildExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "wearme_observaciones_${formatter.format(Date())}.txt"
    }

    private companion object {
        const val STORAGE_FILE_NAME = "wearme_observation_history.json"
    }
}

fun formatObservationTimestamp(timestampMillis: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return formatter.format(Date(timestampMillis))
}

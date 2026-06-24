package com.example.wearme_01

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class SessionVideoStatus {
    Idle,
    Preparing,
    Recording,
    Stopping,
    ReadyToExport,
    Error
}

data class SessionVideoState(
    val status: SessionVideoStatus = SessionVideoStatus.Idle,
    val message: String = "",
    val outputFile: File? = null
) {
    val shouldShowPreview: Boolean
        get() = status == SessionVideoStatus.Preparing || status == SessionVideoStatus.Recording

    val canExportVideo: Boolean
        get() = status == SessionVideoStatus.ReadyToExport && outputFile?.exists() == true
}

class SessionVideoRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val exportsDir = File(appContext.cacheDir, "exports")
    private val _state = MutableStateFlow(SessionVideoState())

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var previewView: PreviewView? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var requestedMetadata: SessionMetadata? = null
    private var recordingRequested = false
    private var isBindingCamera = false

    val state: StateFlow<SessionVideoState> = _state

    fun requestRecording(
        owner: LifecycleOwner,
        sessionMetadata: SessionMetadata?
    ) {
        reset()
        lifecycleOwner = owner
        requestedMetadata = sessionMetadata
        recordingRequested = true
        _state.value = SessionVideoState(
            status = SessionVideoStatus.Preparing,
            message = "Preparando camara trasera para grabar la sesion..."
        )
        bindCameraAndStartIfPossible()
    }

    fun attachPreview(
        view: PreviewView,
        owner: LifecycleOwner
    ) {
        val previewChanged = previewView !== view || lifecycleOwner !== owner
        previewView = view
        lifecycleOwner = owner
        if (previewChanged && activeRecording != null) {
            view.post {
                if (previewView === view && activeRecording != null) {
                    previewUseCase?.setSurfaceProvider(view.surfaceProvider)
                }
            }
        }
        if (previewChanged && recordingRequested && activeRecording == null && !isBindingCamera) {
            view.post {
                if (previewView === view && recordingRequested && activeRecording == null && !isBindingCamera) {
                    bindCameraAndStartIfPossible()
                }
            }
        }
    }

    fun stopRecording() {
        recordingRequested = false
        val recording = activeRecording

        if (recording == null) {
            if (_state.value.status == SessionVideoStatus.Preparing) {
                releaseCamera()
                _state.value = SessionVideoState(
                    status = SessionVideoStatus.Idle,
                    message = "Grabacion de video cancelada antes de iniciar."
                )
            }
            return
        }

        _state.update { current ->
            current.copy(
                status = SessionVideoStatus.Stopping,
                message = "Finalizando video de la sesion..."
            )
        }
        recording.stop()
    }

    fun markPermissionDenied() {
        reset()
        _state.value = SessionVideoState(
            status = SessionVideoStatus.Error,
            message = "Permiso de camara denegado. El CSV se inicio sin video."
        )
    }

    fun setNotice(message: String) {
        _state.update { current ->
            current.copy(message = message)
        }
    }

    fun reset() {
        recordingRequested = false
        activeRecording?.close()
        activeRecording = null
        isBindingCamera = false
        releaseCamera()
        previewView = null
        lifecycleOwner = null
        requestedMetadata = null
        _state.value = SessionVideoState()
    }

    fun close() {
        stopRecording()
        releaseCamera()
    }

    @SuppressLint("MissingPermission")
    private fun bindCameraAndStartIfPossible() {
        val owner = lifecycleOwner ?: return
        val currentPreviewView = previewView ?: return
        if (!recordingRequested || activeRecording != null || isBindingCamera) {
            return
        }

        isBindingCamera = true
        val cameraProviderFuture = ProcessCameraProvider.getInstance(appContext)
        cameraProviderFuture.addListener(
            {
                val provider = runCatching { cameraProviderFuture.get() }
                    .getOrElse { throwable ->
                        failVideoStart("No se pudo abrir la camara: ${throwable.message ?: "error desconocido"}.")
                        return@addListener
                    }

                if (!recordingRequested) {
                    isBindingCamera = false
                    provider.unbindAll()
                    return@addListener
                }

                exportsDir.mkdirs()
                val outputFile = buildOutputFile(requestedMetadata)
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                        )
                    )
                    .build()
                val nextVideoCapture = VideoCapture.withOutput(recorder)
                val nextPreviewUseCase = Preview.Builder().build().also { cameraPreview ->
                    cameraPreview.setSurfaceProvider(currentPreviewView.surfaceProvider)
                }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        owner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        nextPreviewUseCase,
                        nextVideoCapture
                    )
                }.onFailure { throwable ->
                    failVideoStart("No se pudo iniciar la camara trasera: ${throwable.message ?: "error desconocido"}.")
                    return@addListener
                }

                cameraProvider = provider
                previewUseCase = nextPreviewUseCase
                videoCapture = nextVideoCapture
                isBindingCamera = false
                _state.value = SessionVideoState(
                    status = SessionVideoStatus.Preparing,
                    message = "Camara lista. Iniciando grabacion de video...",
                    outputFile = outputFile
                )

                val outputOptions = FileOutputOptions.Builder(outputFile).build()
                activeRecording = runCatching {
                    recorder.prepareRecording(appContext, outputOptions)
                        .start(mainExecutor) { event ->
                            handleVideoEvent(event, outputFile)
                        }
                }.getOrElse { throwable ->
                    failVideoStart("No se pudo comenzar a grabar video: ${throwable.message ?: "error desconocido"}.")
                    null
                }
            },
            mainExecutor
        )
    }

    private fun handleVideoEvent(
        event: VideoRecordEvent,
        outputFile: File
    ) {
        when (event) {
            is VideoRecordEvent.Start -> {
                isBindingCamera = false
                _state.value = SessionVideoState(
                    status = SessionVideoStatus.Recording,
                    message = "Grabando video de la sesion.",
                    outputFile = outputFile
                )
            }

            is VideoRecordEvent.Finalize -> {
                activeRecording = null
                isBindingCamera = false
                releaseCamera()
                recordingRequested = false

                if (event.error == VideoRecordEvent.Finalize.ERROR_NONE && outputFile.exists()) {
                    _state.value = SessionVideoState(
                        status = SessionVideoStatus.ReadyToExport,
                        message = "Video listo para guardar o compartir.",
                        outputFile = outputFile
                    )
                } else {
                    _state.value = SessionVideoState(
                        status = SessionVideoStatus.Error,
                        message = buildFinalizeErrorMessage(event)
                    )
                }
            }
        }
    }

    private fun failVideoStart(message: String) {
        recordingRequested = false
        activeRecording = null
        isBindingCamera = false
        releaseCamera()
        _state.value = SessionVideoState(
            status = SessionVideoStatus.Error,
            message = message
        )
    }

    private fun buildFinalizeErrorMessage(event: VideoRecordEvent.Finalize): String {
        return when (event.error) {
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ->
                "No se pudo finalizar el video porque la camara se desactivo durante la grabacion. Manten la app abierta y evita bloquear la pantalla mientras corre el CSV."

            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA ->
                "No se pudo finalizar el video porque CameraX no recibio frames validos. Manten la app abierta y evita cambiar de app o bloquear la pantalla mientras se graba."

            else ->
                "No se pudo finalizar el video: ${event.cause?.message ?: "error ${event.error}"}."
        }
    }

    private fun releaseCamera() {
        runCatching {
            cameraProvider?.unbindAll()
        }
        cameraProvider = null
        previewUseCase = null
        videoCapture = null
    }

    private fun buildOutputFile(sessionMetadata: SessionMetadata?): File {
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
            add("wearme_video")
            sessionTag?.let { add("sesion_$it") }
            animalTag?.let { add("animal_$it") }
            add(stamp)
        }

        return File(exportsDir, "${nameParts.joinToString("_")}.mp4")
    }
}

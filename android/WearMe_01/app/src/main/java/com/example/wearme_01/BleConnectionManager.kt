package com.example.wearme_01

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class BleDeviceCandidate(
    val name: String?,
    val address: String,
    val rssi: Int,
    val isLikelyTarget: Boolean
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: "Sin nombre"
}

data class BleUiState(
    val bluetoothSupported: Boolean = true,
    val permissionsGranted: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val isScanning: Boolean = false,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val statusMessage: String = "",
    val devices: List<BleDeviceCandidate> = emptyList(),
    val connectedDevice: BleDeviceCandidate? = null,
    val connectingDeviceAddress: String? = null,
    val commandCharacteristicAvailable: Boolean = false,
    val isCommandInFlight: Boolean = false,
    val commandStatusMessage: String? = null,
    val constantFanEnabled: Boolean = false
)

private enum class BleCommand(
    val payload: String,
    val label: String,
    val successMessage: String,
    val fanEnabledAfterSuccess: Boolean? = null
) {
    Cleaning(
        payload = "CLEAN\n",
        label = "limpieza",
        successMessage = "Comando de limpieza enviado a la XIAO."
    ),
    FanConstantOn(
        payload = "FAN_CONSTANT_ON\n",
        label = "ventilador constante",
        successMessage = "Ventilador constante activado.",
        fanEnabledAfterSuccess = true
    ),
    FanConstantOff(
        payload = "FAN_CONSTANT_OFF\n",
        label = "ventilador constante",
        successMessage = "Ventilador constante desactivado.",
        fanEnabledAfterSuccess = false
    )
}

class BleConnectionManager(
    context: Context,
    private val sensorDataManager: SensorDataManager
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private val discoveredDevices = linkedMapOf<String, BleDeviceCandidate>()

    private var bluetoothGatt: BluetoothGatt? = null
    private var sensorCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var modelProbabilityCharacteristic: BluetoothGattCharacteristic? = null
    private var actuatorStatusCharacteristic: BluetoothGattCharacteristic? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null
    private var commandTimeoutRunnable: Runnable? = null
    private var pendingCommand: BleCommand? = null
    private val notificationDescriptorQueue = ArrayDeque<BluetoothGattDescriptor>()
    private var servicesDiscoveryStarted = false
    private var lastInvalidPacketSize: Int? = null
    private var lastConnectedDevice: BleDeviceCandidate? = null
    private var manualDisconnectRequested = false
    private var reconnectAttempts = 0

    private val _uiState = MutableStateFlow(
        BleUiState(
            bluetoothSupported = bluetoothAdapter != null,
            bluetoothEnabled = bluetoothAdapter?.isEnabled == true,
            statusMessage = if (bluetoothAdapter == null) {
                "Este telefono no soporta Bluetooth Low Energy."
            } else {
                "Concede los permisos BLE para buscar la XIAO nRF52840 Sense."
            }
        )
    )
    val uiState: StateFlow<BleUiState> = _uiState

    fun refreshSystemState(permissionsGranted: Boolean) {
        val supported = bluetoothAdapter != null
        val enabled = bluetoothAdapter?.isEnabled == true
        val currentState = _uiState.value

        if (!permissionsGranted || !enabled) {
            stopScan(clearStatus = false)
            cancelPendingReconnect()
            manualDisconnectRequested = true
            closeGatt()
        }

        _uiState.value = currentState.copy(
            bluetoothSupported = supported,
            permissionsGranted = permissionsGranted,
            bluetoothEnabled = enabled,
            isScanning = if (permissionsGranted && enabled) currentState.isScanning else false,
            isConnecting = if (permissionsGranted && enabled) currentState.isConnecting else false,
            isConnected = if (permissionsGranted && enabled) currentState.isConnected else false,
            connectedDevice = if (permissionsGranted && enabled) currentState.connectedDevice else null,
            connectingDeviceAddress = if (permissionsGranted && enabled) {
                currentState.connectingDeviceAddress
            } else {
                null
            },
            commandCharacteristicAvailable = if (permissionsGranted && enabled) {
                currentState.commandCharacteristicAvailable
            } else {
                false
            },
            isCommandInFlight = if (permissionsGranted && enabled) currentState.isCommandInFlight else false,
            commandStatusMessage = if (permissionsGranted && enabled) currentState.commandStatusMessage else null,
            constantFanEnabled = if (permissionsGranted && enabled) currentState.constantFanEnabled else false,
            statusMessage = buildStatusMessage(
                bluetoothSupported = supported,
                permissionsGranted = permissionsGranted,
                bluetoothEnabled = enabled,
                currentState = currentState
            )
        )
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val state = _uiState.value

        when {
            !state.bluetoothSupported -> {
                setStatus("Este telefono no soporta Bluetooth Low Energy.")
                return
            }

            !state.permissionsGranted -> {
                setStatus("Concede los permisos BLE para buscar la XIAO nRF52840 Sense.")
                return
            }

            !state.bluetoothEnabled -> {
                setStatus("Activa Bluetooth para iniciar la busqueda BLE.")
                return
            }
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            setStatus("No se pudo acceder al escaner BLE del telefono.")
            return
        }

        stopScan(clearStatus = false)
        discoveredDevices.clear()
        scanTimeoutRunnable?.let(mainHandler::removeCallbacks)

        _uiState.value = state.copy(
            isScanning = true,
            isConnecting = false,
            connectingDeviceAddress = null,
            devices = emptyList(),
            statusMessage = "Buscando dispositivos BLE cercanos..."
        )

        scanTimeoutRunnable = Runnable {
            stopScan(clearStatus = false)
            val message = if (_uiState.value.devices.isEmpty()) {
                "No se encontraron dispositivos. Verifica que la XIAO este anunciando por BLE."
            } else {
                "Busqueda completada. Selecciona la XIAO para conectarte."
            }
            setStatus(message)
        }

        mainHandler.postDelayed(scanTimeoutRunnable!!, SCAN_WINDOW_MS)

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, scanCallback)
        } catch (_: SecurityException) {
            handlePermissionLoss()
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BleDeviceCandidate) {
        connectToDevice(device, isReconnect = false)
    }

    fun disconnect() {
        manualDisconnectRequested = true
        reconnectAttempts = 0
        cancelPendingReconnect()
        stopScan(clearStatus = false)
        closeGatt()
        val state = _uiState.value
        _uiState.value = state.copy(
            isConnected = false,
            isConnecting = false,
            connectedDevice = null,
            connectingDeviceAddress = null,
            commandCharacteristicAvailable = false,
            isCommandInFlight = false,
            commandStatusMessage = null,
            constantFanEnabled = false,
            statusMessage = "Conexion BLE finalizada. Puedes volver a buscar la XIAO."
        )
    }

    fun sendCleaningCommand() {
        sendBleCommand(BleCommand.Cleaning)
    }

    fun setConstantFanEnabled(enabled: Boolean) {
        sendBleCommand(
            if (enabled) {
                BleCommand.FanConstantOn
            } else {
                BleCommand.FanConstantOff
            }
        )
    }

    fun close() {
        manualDisconnectRequested = true
        reconnectAttempts = 0
        cancelPendingReconnect()
        stopScan(clearStatus = false)
        closeGatt()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(
        device: BleDeviceCandidate,
        isReconnect: Boolean
    ) {
        val state = _uiState.value

        when {
            !state.permissionsGranted -> {
                setStatus("Concede los permisos BLE antes de conectarte.")
                return
            }

            !state.bluetoothEnabled -> {
                setStatus("Activa Bluetooth para conectarte a la XIAO.")
                return
            }
        }

        cancelPendingReconnect()
        manualDisconnectRequested = false

        val remoteDevice = bluetoothAdapter?.getRemoteDevice(device.address)
        if (remoteDevice == null) {
            setStatus("No se pudo recuperar el dispositivo BLE seleccionado.")
            return
        }

        stopScan(clearStatus = false)
        closeGatt()
        servicesDiscoveryStarted = false
        lastInvalidPacketSize = null

        _uiState.value = state.copy(
            isScanning = false,
            isConnecting = true,
            isConnected = false,
            connectedDevice = null,
            connectingDeviceAddress = device.address,
            commandCharacteristicAvailable = false,
            isCommandInFlight = false,
            commandStatusMessage = null,
            constantFanEnabled = false,
            statusMessage = if (isReconnect) {
                "Reconectando con ${device.displayName}..."
            } else {
                "Conectando con ${device.displayName}..."
            }
        )

        try {
            bluetoothGatt = remoteDevice.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )

            if (bluetoothGatt == null) {
                _uiState.value = _uiState.value.copy(
                    isConnecting = false,
                    connectingDeviceAddress = null,
                    commandCharacteristicAvailable = false,
                    isCommandInFlight = false,
                    commandStatusMessage = null,
                    constantFanEnabled = false,
                    statusMessage = "No se pudo abrir la conexion GATT."
                )
            }
        } catch (_: SecurityException) {
            handlePermissionLoss()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(clearStatus: Boolean) {
        val state = _uiState.value
        if (!state.isScanning && scanTimeoutRunnable == null) {
            return
        }

        scanTimeoutRunnable?.let(mainHandler::removeCallbacks)
        scanTimeoutRunnable = null

        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            handlePermissionLoss()
            return
        } catch (_: IllegalStateException) {
            // Ignore scanner teardown races when Bluetooth toggles quickly.
        }

        if (state.isScanning) {
            _uiState.value = state.copy(
                isScanning = false,
                statusMessage = if (clearStatus) {
                    "Busqueda BLE detenida."
                } else {
                    state.statusMessage
                }
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        sensorCharacteristic = null
        commandCharacteristic = null
        modelProbabilityCharacteristic = null
        actuatorStatusCharacteristic = null
        notificationDescriptorQueue.clear()
        pendingCommand = null
        cancelCommandTimeout()
        servicesDiscoveryStarted = false
        lastInvalidPacketSize = null
        val gatt = bluetoothGatt ?: return
        bluetoothGatt = null

        try {
            gatt.disconnect()
        } catch (_: SecurityException) {
            // Ignore permission loss while tearing down the connection.
        }

        gatt.close()
    }

    private fun cancelPendingReconnect() {
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
    }

    private fun cancelCommandTimeout() {
        commandTimeoutRunnable?.let(mainHandler::removeCallbacks)
        commandTimeoutRunnable = null
    }

    private fun setStatus(message: String) {
        _uiState.value = _uiState.value.copy(statusMessage = message)
    }

    private fun setCommandStatus(message: String) {
        _uiState.value = _uiState.value.copy(commandStatusMessage = message)
    }

    @SuppressLint("MissingPermission")
    private fun sendBleCommand(command: BleCommand) {
        val state = _uiState.value
        val gatt = bluetoothGatt
        val characteristic = commandCharacteristic

        when {
            !state.permissionsGranted -> {
                setCommandStatus("Concede los permisos BLE antes de enviar comandos.")
                return
            }

            !state.bluetoothEnabled -> {
                setCommandStatus("Activa Bluetooth para enviar comandos a la XIAO.")
                return
            }

            !state.isConnected || gatt == null -> {
                setCommandStatus("Conectate a la XIAO antes de enviar comandos.")
                return
            }

            characteristic == null -> {
                setCommandStatus("La XIAO conectada no expuso la caracteristica writable de comandos.")
                return
            }

            state.isCommandInFlight -> {
                setCommandStatus("Espera a que termine el comando BLE en curso.")
                return
            }
        }

        val writeType = commandWriteType(characteristic)
        val payload = command.payload.toByteArray(StandardCharsets.UTF_8)
        pendingCommand = command
        _uiState.value = state.copy(
            isCommandInFlight = true,
            commandStatusMessage = "Enviando comando de ${command.label}..."
        )

        val writeStarted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, payload, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = writeType
                characteristic.value = payload
                gatt.writeCharacteristic(characteristic)
            }
        } catch (_: SecurityException) {
            false
        }

        if (!writeStarted) {
            pendingCommand = null
            _uiState.value = _uiState.value.copy(
                isCommandInFlight = false,
                commandStatusMessage = "No se pudo iniciar el envio del comando de ${command.label}."
            )
            return
        }

        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            completeCommandSuccess(command)
        } else {
            scheduleCommandTimeout(command)
        }
    }

    private fun scheduleCommandTimeout(command: BleCommand) {
        cancelCommandTimeout()
        commandTimeoutRunnable = Runnable {
            if (pendingCommand == command) {
                pendingCommand = null
                _uiState.value = _uiState.value.copy(
                    isCommandInFlight = false,
                    commandStatusMessage = "No hubo confirmacion BLE para el comando de ${command.label}."
                )
            }
        }.also { runnable ->
            mainHandler.postDelayed(runnable, COMMAND_TIMEOUT_MS)
        }
    }

    private fun completeCommandSuccess(command: BleCommand) {
        pendingCommand = null
        cancelCommandTimeout()
        val currentState = _uiState.value
        _uiState.value = currentState.copy(
            isCommandInFlight = false,
            commandStatusMessage = command.successMessage,
            constantFanEnabled = command.fanEnabledAfterSuccess ?: currentState.constantFanEnabled
        )
    }

    private fun completeCommandFailure(command: BleCommand, status: Int) {
        pendingCommand = null
        cancelCommandTimeout()
        _uiState.value = _uiState.value.copy(
            isCommandInFlight = false,
            commandStatusMessage = "La XIAO no confirmo el comando de ${command.label} (codigo $status)."
        )
    }

    private fun handlePermissionLoss() {
        cancelPendingReconnect()
        closeGatt()
        _uiState.value = _uiState.value.copy(
            isScanning = false,
            isConnecting = false,
            isConnected = false,
            connectedDevice = null,
            connectingDeviceAddress = null,
            commandCharacteristicAvailable = false,
            isCommandInFlight = false,
            commandStatusMessage = null,
            constantFanEnabled = false,
            statusMessage = "Los permisos BLE ya no estan disponibles."
        )
    }

    private fun buildStatusMessage(
        bluetoothSupported: Boolean,
        permissionsGranted: Boolean,
        bluetoothEnabled: Boolean,
        currentState: BleUiState
    ): String {
        return when {
            !bluetoothSupported -> "Este telefono no soporta Bluetooth Low Energy."
            !permissionsGranted -> "Concede los permisos BLE para buscar la XIAO nRF52840 Sense."
            !bluetoothEnabled -> "Activa Bluetooth para iniciar la busqueda BLE."
            currentState.isConnected -> currentState.statusMessage
            currentState.isConnecting -> currentState.statusMessage
            currentState.isScanning -> "Buscando dispositivos BLE cercanos..."
            currentState.devices.isEmpty() -> "Listo para buscar la XIAO nRF52840 Sense."
            else -> "Selecciona la XIAO que deseas conectar."
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateDiscoveredDevice(result: ScanResult) {
        val address = result.device.address
        val nameFromRecord = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
        val name = nameFromRecord ?: result.device.name?.takeIf { it.isNotBlank() }
        val candidate = BleDeviceCandidate(
            name = name,
            address = address,
            rssi = result.rssi,
            isLikelyTarget = matchesTargetDevice(name)
        )

        discoveredDevices[address] = candidate
        _uiState.value = _uiState.value.copy(
            devices = discoveredDevices.values
                .sortedWith(
                    compareByDescending<BleDeviceCandidate> { it.isLikelyTarget }
                        .thenByDescending { it.rssi }
                        .thenBy { it.displayName.lowercase(Locale.ROOT) }
                )
        )
    }

    @SuppressLint("MissingPermission")
    private fun beginServiceDiscovery(
        gatt: BluetoothGatt,
        statusMessage: String
    ): Boolean {
        if (servicesDiscoveryStarted) {
            return true
        }

        servicesDiscoveryStarted = true
        _uiState.value = _uiState.value.copy(statusMessage = statusMessage)

        val discoveryStarted = try {
            gatt.discoverServices()
        } catch (_: SecurityException) {
            false
        }

        if (!discoveryStarted) {
            servicesDiscoveryStarted = false
            _uiState.value = _uiState.value.copy(
                isConnecting = false,
                connectingDeviceAddress = null,
                statusMessage = "No se pudieron descubrir los servicios BLE."
            )
            closeGatt()
            return false
        }

        return true
    }

    @SuppressLint("MissingPermission")
    private fun requestPreferredMtu(gatt: BluetoothGatt) {
        try {
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        } catch (_: SecurityException) {
            // Ignore priority upgrade failures and keep the connection setup flow going.
        }

        _uiState.value = _uiState.value.copy(
            statusMessage = "Enlace BLE establecido. Solicitando MTU para paquetes de sensores..."
        )

        val mtuRequested = try {
            gatt.requestMtu(SensorBleProfile.requestedMtu)
        } catch (_: SecurityException) {
            false
        }

        if (!mtuRequested) {
            beginServiceDiscovery(
                gatt,
                "No se pudo solicitar un MTU mayor. Descubriendo servicios BLE..."
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun activateSensorNotifications(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(SensorBleProfile.serviceUuid)
        val characteristic = service?.getCharacteristic(SensorBleProfile.sensorCharacteristicUuid)

        if (characteristic == null) {
            _uiState.value = _uiState.value.copy(
                isConnecting = false,
                connectingDeviceAddress = null,
                commandCharacteristicAvailable = false,
                isCommandInFlight = false,
                commandStatusMessage = null,
                constantFanEnabled = false,
                statusMessage = "La XIAO no expuso la caracteristica BLE esperada para sensores."
            )
            closeGatt()
            return false
        }

        sensorCharacteristic = characteristic
        commandCharacteristic = service
            ?.getCharacteristic(SensorBleProfile.commandCharacteristicUuid)
            ?.takeIf(::supportsCommandWrite)
        modelProbabilityCharacteristic = service
            ?.getCharacteristic(SensorBleProfile.modelProbabilityCharacteristicUuid)
            ?.takeIf(::supportsNotify)
        actuatorStatusCharacteristic = service
            ?.getCharacteristic(SensorBleProfile.actuatorStatusCharacteristicUuid)
            ?.takeIf(::supportsNotify)
        notificationDescriptorQueue.clear()

        if (!enableNotificationLocally(gatt, characteristic)) {
            _uiState.value = _uiState.value.copy(
                isConnecting = false,
                connectingDeviceAddress = null,
                commandCharacteristicAvailable = false,
                isCommandInFlight = false,
                commandStatusMessage = null,
                constantFanEnabled = false,
                statusMessage = "No se pudieron activar las notificaciones BLE."
            )
            closeGatt()
            return false
        }
        queueNotificationDescriptor(characteristic)

        modelProbabilityCharacteristic?.let { optionalCharacteristic ->
            if (enableNotificationLocally(gatt, optionalCharacteristic)) {
                queueNotificationDescriptor(optionalCharacteristic)
            }
        }

        actuatorStatusCharacteristic?.let { optionalCharacteristic ->
            if (enableNotificationLocally(gatt, optionalCharacteristic)) {
                queueNotificationDescriptor(optionalCharacteristic)
            }
        }

        if (notificationDescriptorQueue.isEmpty()) {
            sensorDataManager.beginBleSession()
            markConnected(gatt, "XIAO conectada. Esperando paquetes BLE...")
            return true
        }

        return writeNextNotificationDescriptor(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun enableNotificationLocally(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        return try {
            gatt.setCharacteristicNotification(characteristic, true)
        } catch (_: SecurityException) {
            false
        }
    }

    private fun queueNotificationDescriptor(characteristic: BluetoothGattCharacteristic) {
        characteristic
            .getDescriptor(SensorBleProfile.clientConfigDescriptorUuid)
            ?.let(notificationDescriptorQueue::add)
    }

    @SuppressLint("MissingPermission")
    private fun writeNextNotificationDescriptor(gatt: BluetoothGatt): Boolean {
        val descriptor = notificationDescriptorQueue.poll() ?: run {
            sensorDataManager.beginBleSession()
            markConnected(gatt, buildConnectedStatusMessage())
            return true
        }

        val writeStarted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        } catch (_: SecurityException) {
            false
        }

        if (writeStarted) {
            return true
        }

        return if (descriptor.characteristic.uuid == SensorBleProfile.sensorCharacteristicUuid) {
            sensorDataManager.beginBleSession()
            markConnected(gatt, "XIAO conectada. No se pudo confirmar el descriptor, pero la sesion BLE ya esta lista.")
            true
        } else {
            writeNextNotificationDescriptor(gatt)
        }
    }

    private fun buildConnectedStatusMessage(): String {
        val optionalMessages = buildList {
            if (modelProbabilityCharacteristic != null) {
                add("modelo")
            }
            if (actuatorStatusCharacteristic != null) {
                add("ventilador")
            }
        }
        return if (optionalMessages.isEmpty()) {
            "XIAO conectada. Recibiendo paquetes BLE de sensores..."
        } else {
            "XIAO conectada. Recibiendo sensores, ${optionalMessages.joinToString(" y ")}..."
        }
    }

    private fun supportsNotify(characteristic: BluetoothGattCharacteristic): Boolean {
        val properties = characteristic.properties
        return properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
            properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
    }

    private fun supportsCommandWrite(characteristic: BluetoothGattCharacteristic): Boolean {
        val properties = characteristic.properties
        return properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
            properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    }

    private fun commandWriteType(characteristic: BluetoothGattCharacteristic): Int {
        val properties = characteristic.properties
        return if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
    }

    @SuppressLint("MissingPermission")
    private fun markConnected(gatt: BluetoothGatt, statusMessage: String) {
        val address = gatt.device.address
        val fallbackName = gatt.device.name?.takeIf { it.isNotBlank() }
        val connectedDevice = discoveredDevices[address] ?: BleDeviceCandidate(
            name = fallbackName,
            address = address,
            rssi = 0,
            isLikelyTarget = matchesTargetDevice(fallbackName)
        )
        lastConnectedDevice = connectedDevice
        reconnectAttempts = 0
        cancelPendingReconnect()

        _uiState.value = _uiState.value.copy(
            isConnecting = false,
            isConnected = true,
            connectedDevice = connectedDevice,
            connectingDeviceAddress = null,
            commandCharacteristicAvailable = commandCharacteristic != null,
            isCommandInFlight = false,
            commandStatusMessage = if (commandCharacteristic != null) {
                "Comandos BLE listos para limpieza y ventilador."
            } else {
                "La XIAO conectada no expuso la caracteristica writable de comandos."
            },
            constantFanEnabled = false,
            statusMessage = statusMessage
        )
        readInitialActuatorStatus(gatt)
    }

    @SuppressLint("MissingPermission")
    private fun readInitialActuatorStatus(gatt: BluetoothGatt) {
        val characteristic = actuatorStatusCharacteristic ?: return
        try {
            gatt.readCharacteristic(characteristic)
        } catch (_: SecurityException) {
            // The first notification will still update the dashboard once available.
        }
    }

    private fun matchesTargetDevice(name: String?): Boolean {
        val normalized = name?.lowercase(Locale.ROOT) ?: return false
        return TARGET_KEYWORDS.any { normalized.contains(it) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            mainHandler.post {
                updateDiscoveredDevice(result)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            mainHandler.post {
                results.forEach(::updateDiscoveredDevice)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            mainHandler.post {
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    statusMessage = "El escaneo BLE fallo con codigo $errorCode."
                )
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (bluetoothGatt !== gatt) {
                gatt.close()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    mainHandler.post {
                        servicesDiscoveryStarted = false
                        lastInvalidPacketSize = null
                        requestPreferredMtu(gatt)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    mainHandler.post {
                        val previousState = _uiState.value
                        val failedWhileConnecting = previousState.isConnecting
                        val shouldAttemptReconnect =
                            (previousState.isConnected || reconnectAttempts > 0) &&
                                !manualDisconnectRequested &&
                                previousState.permissionsGranted &&
                                previousState.bluetoothEnabled &&
                                lastConnectedDevice != null

                        closeGatt()

                        if (shouldAttemptReconnect) {
                            scheduleReconnect()
                        } else {
                            _uiState.value = _uiState.value.copy(
                                isConnecting = false,
                                isConnected = false,
                                connectedDevice = null,
                                connectingDeviceAddress = null,
                                commandCharacteristicAvailable = false,
                                isCommandInFlight = false,
                                commandStatusMessage = null,
                                constantFanEnabled = false,
                                statusMessage = if (status == BluetoothGatt.GATT_SUCCESS && !failedWhileConnecting) {
                                    "La conexion BLE se cerro correctamente."
                                } else {
                                    "No se pudo mantener la conexion con la XIAO."
                                }
                            )
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (bluetoothGatt !== gatt) {
                gatt.close()
                return
            }

            mainHandler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    _uiState.value = _uiState.value.copy(
                        isConnecting = false,
                        connectingDeviceAddress = null,
                        commandCharacteristicAvailable = false,
                        isCommandInFlight = false,
                        commandStatusMessage = null,
                        constantFanEnabled = false,
                        statusMessage = "La busqueda de servicios BLE devolvio codigo $status."
                    )
                    closeGatt()
                    return@post
                }

                _uiState.value = _uiState.value.copy(
                    statusMessage = "Servicios BLE listos. Activando notificaciones de sensores..."
                )
                activateSensorNotifications(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (bluetoothGatt !== gatt) {
                gatt.close()
                return
            }

            mainHandler.post {
                val message = when {
                    status != BluetoothGatt.GATT_SUCCESS ->
                        "No se pudo negociar el MTU BLE (codigo $status). Descubriendo servicios..."
                    mtu < SensorBleProfile.minimumRequiredMtu ->
                        "MTU BLE negociado en $mtu bytes. Es menor a lo requerido para paquetes de ${SensorBleProfile.expectedPacketSize} bytes, pero se intentara continuar."
                    else ->
                        "MTU BLE negociado en $mtu bytes. Descubriendo servicios..."
                }

                beginServiceDiscovery(gatt, message)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (bluetoothGatt !== gatt) {
                gatt.close()
                return
            }

            mainHandler.post {
                val characteristicUuid = descriptor.characteristic.uuid
                if (
                    characteristicUuid == SensorBleProfile.sensorCharacteristicUuid &&
                    status != BluetoothGatt.GATT_SUCCESS
                ) {
                    _uiState.value = _uiState.value.copy(
                        isConnecting = false,
                        connectingDeviceAddress = null,
                        commandCharacteristicAvailable = false,
                        isCommandInFlight = false,
                        commandStatusMessage = null,
                        constantFanEnabled = false,
                        statusMessage = "No se pudo habilitar la notificacion BLE de sensores (codigo $status)."
                    )
                    closeGatt()
                    return@post
                }

                writeNextNotificationDescriptor(gatt)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (bluetoothGatt !== gatt) {
                gatt.close()
                return
            }

            if (characteristic.uuid != SensorBleProfile.commandCharacteristicUuid) {
                return
            }

            mainHandler.post {
                val command = pendingCommand ?: return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    completeCommandSuccess(command)
                } else {
                    completeCommandFailure(command, status)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            handleCharacteristicUpdate(characteristic, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicUpdate(characteristic, value)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                return
            }

            val value = characteristic.value ?: return
            handleCharacteristicUpdate(characteristic, value)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicUpdate(characteristic, value)
            }
        }
    }

    private fun handleCharacteristicUpdate(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        when (characteristic.uuid) {
            SensorBleProfile.sensorCharacteristicUuid -> {
                if (!SensorBleProfile.isSupportedSensorPacketSize(value.size)) {
                    if (lastInvalidPacketSize != value.size) {
                        lastInvalidPacketSize = value.size
                        mainHandler.post {
                            _uiState.value = _uiState.value.copy(
                                statusMessage = "Paquete BLE recibido con ${value.size} bytes; se esperaban ${SensorBleProfile.legacyPacketSize} o ${SensorBleProfile.expectedPacketSize}. Revisa la negociacion del MTU."
                            )
                        }
                    }
                    return
                }

                lastInvalidPacketSize = null
                sensorDataManager.onBlePacket(value)
            }

            SensorBleProfile.modelProbabilityCharacteristicUuid -> {
                sensorDataManager.onBleModelProbability(parseModelProbability(value))
            }

            SensorBleProfile.actuatorStatusCharacteristicUuid -> {
                parseActuatorStatus(value)?.let { status ->
                    sensorDataManager.onBleFanStatus(
                        fanOn = status.first,
                        fanReason = status.second
                    )
                }
            }
        }
    }

    private fun parseModelProbability(value: ByteArray): Float? {
        if (value.size < MODEL_PROBABILITY_PACKET_SIZE) {
            return null
        }

        val probability = ByteBuffer.wrap(value)
            .order(ByteOrder.LITTLE_ENDIAN)
            .float

        return probability.takeUnless { it.isNaN() || it.isInfinite() }
    }

    private fun parseActuatorStatus(value: ByteArray): Pair<Boolean, FanReason>? {
        if (value.size < SensorBleProfile.actuatorStatusPacketSize) {
            return null
        }

        val fanOn = value[0].toInt() != 0
        val reason = FanReason.fromCode(value[1].toInt() and 0xFF)
        return fanOn to reason
    }

    private fun scheduleReconnect() {
        val targetDevice = lastConnectedDevice ?: return
        cancelPendingReconnect()
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts = 0
            _uiState.value = _uiState.value.copy(
                isConnecting = false,
                isConnected = false,
                connectedDevice = null,
                connectingDeviceAddress = null,
                commandCharacteristicAvailable = false,
                isCommandInFlight = false,
                commandStatusMessage = null,
                constantFanEnabled = false,
                statusMessage = "No se pudo reconectar automaticamente con la XIAO. Intenta reconectar manualmente."
            )
            return
        }

        reconnectAttempts++
        _uiState.value = _uiState.value.copy(
            isConnecting = true,
            isConnected = false,
            connectedDevice = null,
            connectingDeviceAddress = targetDevice.address,
            commandCharacteristicAvailable = false,
            isCommandInFlight = false,
            commandStatusMessage = null,
            constantFanEnabled = false,
            statusMessage = "Conexion perdida. Reintentando con ${targetDevice.displayName} (${reconnectAttempts}/$MAX_RECONNECT_ATTEMPTS)..."
        )

        reconnectRunnable = Runnable {
            connectToDevice(targetDevice, isReconnect = true)
        }.also { runnable ->
            mainHandler.postDelayed(runnable, RECONNECT_DELAY_MS)
        }
    }

    private companion object {
        const val SCAN_WINDOW_MS = 12_000L
        const val RECONNECT_DELAY_MS = 2_500L
        const val COMMAND_TIMEOUT_MS = 5_000L
        const val MODEL_PROBABILITY_PACKET_SIZE = 4
        const val MAX_RECONNECT_ATTEMPTS = 3
        val TARGET_KEYWORDS = listOf("xiao", "seeed", "nrf52840", "sense", "sensorhub")
    }
}

package com.ivionics.formhr

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

data class PeripheralStatus(
    val advertising: Boolean = false,
    val connectedDevices: Int = 0,
    val subscribedDevices: Int = 0,
    val detail: String = "Stopped",
)

@SuppressLint("MissingPermission", "ApplySharedPref", "UseKtx")
class BleHeartRatePeripheral(
    context: Context,
    private val onStatus: (PeripheralStatus) -> Unit,
    private val onEvent: (String) -> Unit = {},
) {
    private val applicationContext = context.applicationContext
    private val bluetoothManager = applicationContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var measurementCharacteristic: BluetoothGattCharacteristic? = null
    private var batteryCharacteristic: BluetoothGattCharacteristic? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private val connectedDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val subscribedDevices = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val batterySubscribers = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val pftpSubscribers = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val servicesToAdd = ArrayDeque<BluetoothGattService>()
    private var currentBpm = 72
    private var profile: DiscoveryProfile? = null
    private var advertising = false
    private var detail = "Stopped"

    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    init {
        restoreBluetoothNameAfterInterruptedRun()
    }

    fun isSupported(): Boolean =
        adapter?.isEnabled == true &&
            adapter?.bluetoothLeAdvertiser != null &&
            adapter?.isMultipleAdvertisementSupported == true

    fun start(profile: DiscoveryProfile, initialBpm: Int) {
        stop()
        emitEvent("Start ${profile.title}")

        val localAdapter = adapter
        if (localAdapter == null || !localAdapter.isEnabled) {
            publish("Bluetooth is off")
            return
        }
        if (!localAdapter.isMultipleAdvertisementSupported || localAdapter.bluetoothLeAdvertiser == null) {
            publish("This watch cannot advertise as a BLE peripheral")
            return
        }

        currentBpm = initialBpm.coerceIn(30, 240)
        this.profile = profile

        if (!setTemporaryBluetoothName(localAdapter, profile.advertisedName)) {
            publish("Could not set the BLE name")
            this.profile = null
            return
        }

        val server = bluetoothManager.openGattServer(applicationContext, serverCallback)
        if (server == null) {
            restoreBluetoothName(localAdapter)
            publish("Could not open the GATT server")
            this.profile = null
            return
        }
        gattServer = server

        detail = "Starting GATT server…"
        publish()
        servicesToAdd.clear()
        servicesToAdd.addLast(createHeartRateService())
        if (profile.includePolarCompanyId) {
            servicesToAdd.addLast(createBatteryService())
            servicesToAdd.addLast(createDeviceInformationService())
        }
        if (profile.includePolarFileTransfer) {
            servicesToAdd.addLast(createPolarFileTransferService())
        }
        addNextService()
    }

    fun updateHeartRate(beatsPerMinute: Int) {
        currentBpm = beatsPerMinute.coerceIn(30, 240)
        val server = gattServer ?: return
        val characteristic = measurementCharacteristic ?: return
        val value = HeartRatePacket.encode(currentBpm)

        subscribedDevices.toList().forEach { device ->
            val sent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.setValue(value)
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
            if (!sent) {
                detail = "Connected; notification queue busy"
                publish()
            }
        }
    }

    fun stop() {
        val callback = advertiseCallback
        if (callback != null) {
            runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
        }
        advertiseCallback = null
        advertising = false

        connectedDevices.toList().forEach { device ->
            runCatching { gattServer?.cancelConnection(device) }
        }
        connectedDevices.clear()
        subscribedDevices.clear()
        batterySubscribers.clear()
        pftpSubscribers.clear()
        servicesToAdd.clear()
        runCatching { gattServer?.clearServices() }
        runCatching { gattServer?.close() }
        gattServer = null
        measurementCharacteristic = null
        batteryCharacteristic = null

        adapter?.let(::restoreBluetoothName)
        profile = null
        detail = "Stopped"
        publish()
    }

    private fun createHeartRateService(): BluetoothGattService {
        val service = BluetoothGattService(HEART_RATE_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val measurement = BluetoothGattCharacteristic(
            HEART_RATE_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        measurement.addDescriptor(
            BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIGURATION,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        measurementCharacteristic = measurement
        service.addCharacteristic(measurement)

        val bodyLocation = BluetoothGattCharacteristic(
            BODY_SENSOR_LOCATION,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        @Suppress("DEPRECATION")
        bodyLocation.setValue(byteArrayOf(BODY_LOCATION_OTHER))
        service.addCharacteristic(bodyLocation)

        return service
    }

    private fun createBatteryService(): BluetoothGattService {
        val service = BluetoothGattService(BATTERY_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val batteryLevel = BluetoothGattCharacteristic(
            BATTERY_LEVEL,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        batteryLevel.addDescriptor(
            BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIGURATION,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        @Suppress("DEPRECATION")
        batteryLevel.setValue(byteArrayOf(BATTERY_LEVEL_FULL))
        batteryCharacteristic = batteryLevel
        service.addCharacteristic(batteryLevel)
        return service
    }

    private fun createDeviceInformationService(): BluetoothGattService {
        val modelName = if (profile?.advertisedName?.startsWith("Polar OH1") == true) {
            "Polar OH1"
        } else {
            "Polar Verity Sense"
        }
        val service = BluetoothGattService(DEVICE_INFORMATION_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(readOnlyString(MANUFACTURER_NAME, "Polar Electro Oy"))
        service.addCharacteristic(readOnlyString(MODEL_NUMBER, modelName))
        service.addCharacteristic(readOnlyString(SERIAL_NUMBER, profile?.polarDeviceId ?: EMULATED_POLAR_DEVICE_ID))
        service.addCharacteristic(readOnlyString(FIRMWARE_REVISION, "2.2.6"))
        service.addCharacteristic(readOnlyString(HARDWARE_REVISION, "1.0.0"))
        service.addCharacteristic(readOnlyString(SOFTWARE_REVISION, "2.2.6"))
        return service
    }

    private fun createPolarFileTransferService(): BluetoothGattService {
        val service = BluetoothGattService(POLAR_FILE_TRANSFER_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val mtu = BluetoothGattCharacteristic(
            POLAR_FILE_TRANSFER_MTU,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        mtu.addDescriptor(
            BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIGURATION,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(mtu)

        val deviceToHost = BluetoothGattCharacteristic(
            POLAR_FILE_TRANSFER_DEVICE_TO_HOST,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,
        )
        deviceToHost.addDescriptor(
            BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIGURATION,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(deviceToHost)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                POLAR_FILE_TRANSFER_HOST_TO_DEVICE,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        return service
    }

    private fun readOnlyString(uuid: UUID, value: String): BluetoothGattCharacteristic =
        BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).also { characteristic ->
            @Suppress("DEPRECATION")
            characteristic.setValue(value.toByteArray(Charsets.UTF_8))
        }

    private fun addNextService() {
        val server = gattServer ?: return
        val next = servicesToAdd.removeFirstOrNull()
        if (next == null) {
            waitForNameThenAdvertise()
            return
        }
        if (!server.addService(next)) {
            stopWithError("Could not add GATT service ${shortUuid(next.uuid)}")
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                stopWithError("Service ${shortUuid(service.uuid)} failed: GATT $status")
                return
            }
            emitEvent("Service ${shortUuid(service.uuid)} ready")
            addNextService()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevices += device
                    detail = "FORM candidate connected"
                    emitEvent("Central connected · status $status")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices -= device
                    subscribedDevices -= device
                    batterySubscribers -= device
                    pftpSubscribers -= device
                    detail = if (advertising) "Advertising; waiting for FORM" else "Stopped"
                    emitEvent("Central disconnected · status $status")
                }
            }
            publish()
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = when (characteristic.uuid) {
                HEART_RATE_MEASUREMENT -> HeartRatePacket.encode(currentBpm)
                BODY_SENSOR_LOCATION -> byteArrayOf(BODY_LOCATION_OTHER)
                BATTERY_LEVEL -> byteArrayOf(BATTERY_LEVEL_FULL)
                MANUFACTURER_NAME -> "Polar Electro Oy".toByteArray()
                MODEL_NUMBER -> if (profile?.advertisedName?.startsWith("Polar OH1") == true) {
                    "Polar OH1".toByteArray()
                } else {
                    "Polar Verity Sense".toByteArray()
                }
                SERIAL_NUMBER -> (profile?.polarDeviceId ?: EMULATED_POLAR_DEVICE_ID).toByteArray()
                FIRMWARE_REVISION -> "2.2.6".toByteArray()
                HARDWARE_REVISION -> "1.0.0".toByteArray()
                SOFTWARE_REVISION -> "2.2.6".toByteArray()
                else -> null
            }
            emitEvent("Read ${shortUuid(characteristic.uuid)} · offset $offset")
            respondToRead(device, requestId, offset, value)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val characteristicUuid = descriptor.characteristic.uuid
            val subscriptions = subscriptionsFor(characteristicUuid)
            val value = if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIGURATION) {
                if (subscriptions != null && device in subscriptions) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
            } else {
                null
            }
            emitEvent("Read CCCD for ${shortUuid(characteristicUuid)}")
            respondToRead(device, requestId, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            var status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            val characteristicUuid = descriptor.characteristic.uuid
            val subscriptions = subscriptionsFor(characteristicUuid)
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIGURATION &&
                subscriptions != null &&
                offset == 0 &&
                !preparedWrite
            ) {
                status = when {
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> {
                        subscriptions += device
                        if (characteristicUuid == HEART_RATE_MEASUREMENT) {
                            detail = "Streaming $currentBpm BPM"
                        }
                        BluetoothGatt.GATT_SUCCESS
                    }

                    value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                        subscriptions -= device
                        if (characteristicUuid == HEART_RATE_MEASUREMENT) {
                            detail = "Connected; notifications off"
                        }
                        BluetoothGatt.GATT_SUCCESS
                    }

                    else -> BluetoothGatt.GATT_FAILURE
                }
                val enabled = device in subscriptions
                emitEvent("${shortUuid(characteristicUuid)} notify ${if (enabled) "on" else "off"} · GATT $status")
                publish()
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, status, 0, null)
            }
            if (status == BluetoothGatt.GATT_SUCCESS && device in subscribedDevices) {
                updateHeartRate(currentBpm)
            }
            if (status == BluetoothGatt.GATT_SUCCESS && device in batterySubscribers) {
                notifyBatteryLevel(device)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            emitEvent("Unexpected write ${shortUuid(characteristic.uuid)} · ${value.toHexString()}")
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    offset,
                    null,
                )
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            emitEvent("MTU $mtu")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) emitEvent("Notify failed · GATT $status")
        }
    }

    private fun respondToRead(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        value: ByteArray?,
    ) {
        if (value == null) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            return
        }
        if (offset > value.size) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            return
        }
        gattServer?.sendResponse(
            device,
            requestId,
            BluetoothGatt.GATT_SUCCESS,
            offset,
            value.copyOfRange(offset, value.size),
        )
    }

    private fun waitForNameThenAdvertise() {
        val expectedName = profile?.advertisedName ?: return
        if (adapter?.name == expectedName) {
            beginAdvertising()
            return
        }

        // Bluetooth name changes are asynchronous on some Wear OS builds.
        Handler(Looper.getMainLooper()).postDelayed({
            if (profile?.advertisedName != expectedName) return@postDelayed
            if (adapter?.name == expectedName) {
                beginAdvertising()
            } else {
                stopWithError("Bluetooth name did not change to $expectedName")
            }
        }, NAME_CHANGE_GRACE_PERIOD_MS)
    }

    private fun notifyBatteryLevel(device: BluetoothDevice) {
        val server = gattServer ?: return
        val characteristic = batteryCharacteristic ?: return
        val value = byteArrayOf(BATTERY_LEVEL_FULL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, value)
        } else {
            @Suppress("DEPRECATION")
            characteristic.setValue(value)
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    private fun beginAdvertising() {
        val activeProfile = profile ?: return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: run {
            stopWithError("BLE advertiser disappeared")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val dataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(HEART_RATE_SERVICE))
        if (activeProfile.includePolarFileTransfer) {
            dataBuilder.addServiceUuid(ParcelUuid(POLAR_FILE_TRANSFER_SERVICE))
        }
        if (activeProfile.includePolarCompanyId) {
            // Android encodes company 0x006B; payload follows Polar's full public OH1 example.
            val manufacturerData = PolarManufacturerData.encode(
                beatsPerMinute = currentBpm,
                embeddedDeviceId = checkNotNull(activeProfile.embeddedManufacturerDeviceId),
            )
            dataBuilder.addManufacturerData(POLAR_COMPANY_ID, manufacturerData)
            emitEvent("ADV 006B · ${manufacturerData.toHexString()}")
        }
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                advertising = true
                detail = "Advertising; waiting for FORM"
                emitEvent("Advertising as ${activeProfile.advertisedName}")
                publish()
            }

            override fun onStartFailure(errorCode: Int) {
                val reason = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "advertisement is too large"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "no advertiser slot is available"
                    ADVERTISE_FAILED_ALREADY_STARTED -> "advertising already started"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "Bluetooth internal error"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "advertising is unsupported"
                    else -> "advertising error $errorCode"
                }
                stopWithError("Could not advertise: $reason")
            }
        }
        advertiseCallback = callback
        advertiser.startAdvertising(settings, dataBuilder.build(), scanResponse, callback)
    }

    private fun setTemporaryBluetoothName(localAdapter: BluetoothAdapter, requestedName: String): Boolean {
        val originalName = localAdapter.name.orEmpty()
        if (!preferences.getBoolean(KEY_NAME_CHANGED, false)) {
            // Commit before changing the global adapter name so crash recovery cannot lose it.
            preferences.edit()
                .putString(KEY_ORIGINAL_NAME, originalName)
                .putBoolean(KEY_NAME_CHANGED, true)
                .commit()
        }
        return localAdapter.name == requestedName || localAdapter.setName(requestedName)
    }

    private fun restoreBluetoothName(localAdapter: BluetoothAdapter) {
        if (!preferences.getBoolean(KEY_NAME_CHANGED, false)) return
        val originalName = preferences.getString(KEY_ORIGINAL_NAME, null)
        val restored = if (originalName.isNullOrBlank()) {
            true
        } else {
            runCatching {
                localAdapter.name == originalName || localAdapter.setName(originalName)
            }.getOrDefault(false)
        }
        if (restored) {
            // Commit keeps the recovery flag aligned with the already-restored adapter name.
            preferences.edit().clear().commit()
        }
    }

    private fun restoreBluetoothNameAfterInterruptedRun() {
        val localAdapter = adapter ?: return
        if (preferences.getBoolean(KEY_NAME_CHANGED, false)) {
            runCatching { restoreBluetoothName(localAdapter) }
        }
    }

    private fun stopWithError(message: String) {
        stop()
        detail = message
        publish()
    }

    private fun publish(overrideDetail: String? = null) {
        if (overrideDetail != null) detail = overrideDetail
        onStatus(
            PeripheralStatus(
                advertising = advertising,
                connectedDevices = connectedDevices.size,
                subscribedDevices = subscribedDevices.size,
                detail = detail,
            ),
        )
    }

    private fun emitEvent(message: String) {
        Log.i(TAG, message)
        onEvent(message)
    }

    private fun subscriptionsFor(characteristicUuid: UUID): MutableSet<BluetoothDevice>? =
        when (characteristicUuid) {
            HEART_RATE_MEASUREMENT -> subscribedDevices
            BATTERY_LEVEL -> batterySubscribers
            POLAR_FILE_TRANSFER_MTU, POLAR_FILE_TRANSFER_DEVICE_TO_HOST -> pftpSubscribers
            else -> null
        }

    private fun shortUuid(uuid: UUID): String {
        val text = uuid.toString()
        return if (text.endsWith("-0000-1000-8000-00805f9b34fb")) {
            text.substring(4, 8).uppercase()
        } else {
            text.substring(0, 8).uppercase()
        }
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = " ") { byte ->
        "%02X".format(byte.toInt() and 0xFF)
    }

    companion object {
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val BODY_SENSOR_LOCATION: UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val DEVICE_INFORMATION_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val MANUFACTURER_NAME: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        val MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        val SERIAL_NUMBER: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
        val FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        val HARDWARE_REVISION: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
        val SOFTWARE_REVISION: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")
        val POLAR_FILE_TRANSFER_SERVICE: UUID = UUID.fromString("0000feee-0000-1000-8000-00805f9b34fb")
        val POLAR_FILE_TRANSFER_MTU: UUID = UUID.fromString("fb005c51-02e7-f387-1cad-8acd2d8df0c8")
        val POLAR_FILE_TRANSFER_DEVICE_TO_HOST: UUID = UUID.fromString("fb005c52-02e7-f387-1cad-8acd2d8df0c8")
        val POLAR_FILE_TRANSFER_HOST_TO_DEVICE: UUID = UUID.fromString("fb005c53-02e7-f387-1cad-8acd2d8df0c8")
        val CLIENT_CHARACTERISTIC_CONFIGURATION: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val POLAR_COMPANY_ID = 0x006B
        private const val BODY_LOCATION_OTHER: Byte = 0x00
        private const val BATTERY_LEVEL_FULL: Byte = 100
        private const val PREFERENCES = "ble_name_restore"
        private const val KEY_ORIGINAL_NAME = "original_name"
        private const val KEY_NAME_CHANGED = "name_changed"
        private const val NAME_CHANGE_GRACE_PERIOD_MS = 500L
        private const val TAG = "FormHrGatt"
    }
}

/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.echo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.ArrayDeque
import java.util.UUID

private const val TAG = "BluetoothLeService"

@SuppressLint("MissingPermission")
// [START android_bluetooth_service_all]
// [START android_bluetooth_binder]
class BluetoothLeService : Service() {

    private val binder = LocalBinder()
    // [START_EXCLUDE silent]

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectionState = STATE_DISCONNECTED
    private var connecting = false

    private val descriptorWriteQueue = ArrayDeque<BluetoothGattDescriptor>()
    private var writingDescriptor = false

    companion object {
        const val ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED"
        const val ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED"
        const val ACTION_DATA_AVAILABLE =
            "com.example.echo.ACTION_DATA_AVAILABLE"

        const val EXTRA_SENDER = "com.example.echo.EXTRA_SENDER"
        const val EXTRA_MESSAGE = "com.example.echo.EXTRA_MESSAGE"
        const val EXTRA_TIMESTAMP = "com.example.echo.EXTRA_TIMESTAMP"
        const val EXTRA_RAW_DATA = "com.example.echo.EXTRA_RAW_DATA"
        const val EXTRA_CHARACTERISTIC_UUID =
            "com.example.echo.EXTRA_CHARACTERISTIC_UUID"

        /**
         * Defaults match the Nordic UART Service (NUS), which is widely used by
         * ESP32 BLE examples. Change these UUIDs if your firmware exposes a
         * custom service/characteristic instead.
         */
        val MESSAGE_SERVICE_UUID: UUID =
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val MESSAGE_RX_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val MESSAGE_TX_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val STATE_DISCONNECTED = 0
        private const val STATE_CONNECTED = 2
    }

    private val bluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            connecting = false
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectionState = STATE_CONNECTED
                    broadcastUpdate(ACTION_GATT_CONNECTED)
                    // A larger MTU allows longer messenger payloads. If the request
                    // cannot be started, fall back to discovering services directly.
                    if (gatt?.requestMtu(517) != true) {
                        gatt?.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectionState = STATE_DISCONNECTED
                    broadcastUpdate(ACTION_GATT_DISCONNECTED)
                    descriptorWriteQueue.clear()
                    writingDescriptor = false
                    gatt?.close()
                    if (bluetoothGatt === gatt) {
                        bluetoothGatt = null
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt?.let { enableNotifications(it) }
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED)
            } else {
                Log.w(TAG, "onServicesDiscovered received: $status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu (status=$status)")
            gatt.discoverServices()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(
                    TAG,
                    "onDescriptorWrite failed for ${descriptor.characteristic.uuid}: $status"
                )
            }
            writingDescriptor = false
            writeNextDescriptor()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicValue(characteristic, characteristic.value ?: ByteArray(0))
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicValue(characteristic, value)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicValue(characteristic, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicValue(characteristic, value)
        }
    }
    // [END_EXCLUDE]

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothLeService {
            return this@BluetoothLeService
        }
    }
    // [START_EXCLUDE silent]

    fun initialize(): Boolean {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.")
            return false
        }
        return true
    }

    fun getSupportedGattServices(): List<BluetoothGattService?>? {
        return bluetoothGatt?.services
    }

    @Suppress("DEPRECATION")
    fun readCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val gatt = bluetoothGatt
        if (gatt == null) {
            Log.w(TAG, "BluetoothGatt not initialized")
            return
        }
        gatt.readCharacteristic(characteristic)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(address: String): Boolean {
        if (connectionState == STATE_CONNECTED || connecting) {
            return true
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            Log.w(TAG, "BluetoothAdapter not initialized")
            return false
        }

        return try {
            val device = adapter.getRemoteDevice(address)
            bluetoothGatt?.close()
            val gatt = device.connectGatt(this, false, bluetoothGattCallback)
            bluetoothGatt = gatt
            connecting = gatt != null
            gatt != null
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Device not found with provided address. Unable to connect.")
            connecting = false
            false
        }
    }

    /**
     * Sends a UTF-8 text message to the ESP32.
     *
     * Uses the Nordic UART Service RX characteristic when available, otherwise
     * the first writable characteristic on the device.
     */
    @Suppress("DEPRECATION")
    fun sendMessage(text: String): Boolean {
        val gatt = bluetoothGatt ?: return false
        val characteristic = findWritableCharacteristic(gatt) ?: return false
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = text.toByteArray(Charsets.UTF_8)
        return gatt.writeCharacteristic(characteristic)
    }

    private fun findWritableCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        gatt.getService(MESSAGE_SERVICE_UUID)
            ?.getCharacteristic(MESSAGE_RX_CHARACTERISTIC_UUID)
            ?.let { return it }

        return gatt.services
            .asSequence()
            .flatMap { it.characteristics.asSequence() }
            .firstOrNull { characteristic ->
                characteristic.properties and (
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                    ) != 0
            }
    }

    /**
     * Subscribes to every characteristic that supports notifications or
     * indications. This makes the messenger work even if the ESP32 uses a
     * custom service UUID.
     */
    @Suppress("DEPRECATION")
    private fun enableNotifications(gatt: BluetoothGatt) {
        gatt.services.forEach { service ->
            service.characteristics.forEach { characteristic ->
                val properties = characteristic.properties
                val supportsNotify =
                    properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val supportsIndicate =
                    properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                if (supportsNotify || supportsIndicate) {
                    setCharacteristicNotification(gatt, characteristic, supportsNotify)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setCharacteristicNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        useNotification: Boolean
    ) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.w(TAG, "setCharacteristicNotification failed for ${characteristic.uuid}")
            return
        }

        val descriptor =
            characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return
        descriptor.value = if (useNotification) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        descriptorWriteQueue.addLast(descriptor)
        writeNextDescriptor()
    }

    @Suppress("DEPRECATION")
    private fun writeNextDescriptor() {
        if (writingDescriptor) return
        val gatt = bluetoothGatt ?: return
        val descriptor = descriptorWriteQueue.pollFirst() ?: return
        writingDescriptor = true
        if (!gatt.writeDescriptor(descriptor)) {
            Log.w(TAG, "writeDescriptor failed for ${descriptor.characteristic.uuid}")
            writingDescriptor = false
            writeNextDescriptor()
        }
    }

    private fun handleCharacteristicValue(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic, value)
    }

    // [START android_bluetooth_broadcast]
    private fun broadcastUpdate(action: String) {
        val intent = Intent(action)
        sendBroadcast(intent)
    }
    // [END android_bluetooth_broadcast]

    /**
     * Converts an incoming BLE payload into a messenger event.
     *
     * Supported UTF-8 payloads:
     *  - JSON:  {"from":"alice","text":"hello","timestamp":1699999999999}
     *  - Text:  "alice|hello"
     *  - Text:  "alice: hello"
     *  - Plain: "hello" (sender falls back to the BLE device name/address)
     */
    private fun broadcastUpdate(
        action: String,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (value.isEmpty()) return

        val raw = value.toString(Charsets.UTF_8).trim()
        if (raw.isEmpty()) return

        val fallbackSender = bluetoothGatt?.device?.let { device ->
            device.name ?: device.address
        } ?: "ESP32"

        val message = MessengerMessageParser.parse(raw, fallbackSender)
        val intent = Intent(action).apply {
            putExtra(EXTRA_SENDER, message.sender)
            putExtra(EXTRA_MESSAGE, message.text)
            putExtra(EXTRA_TIMESTAMP, message.timestamp)
            putExtra(EXTRA_RAW_DATA, raw)
            putExtra(EXTRA_CHARACTERISTIC_UUID, characteristic.uuid.toString())
        }
        sendBroadcast(intent)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onUnbind(intent: Intent?): Boolean {
        close()
        return super.onUnbind(intent)
    }

    private fun close() {
        connecting = false
        descriptorWriteQueue.clear()
        writingDescriptor = false
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
    // [END_EXCLUDE]
}
// [END android_bluetooth_binder]

// [END android_bluetooth_service_all]

/**
 * Namespaces for simplified versions of BluetoothLeService to match documentation.
 */
private object ConnectSimpleNamespace {
    class BluetoothLeService {
        private var bluetoothAdapter: BluetoothAdapter? = null
        private val TAG = "BluetoothLeService"

        // [START android_bluetooth_connect_simple]
        fun connect(address: String): Boolean {
            bluetoothAdapter?.let { adapter ->
                try {
                    val device = adapter.getRemoteDevice(address)
                } catch (exception: IllegalArgumentException) {
                    Log.w(TAG, "Device not found with provided address.")
                    return false
                }
                // connect to the GATT server on the device
                return true
            } ?: run {
                Log.w(TAG, "BluetoothAdapter not initialized")
                return false
            }
        }
        // [END android_bluetooth_connect_simple]
    }
}

private object CallbackSimpleNamespace {
    class BluetoothLeService {
        // [START android_bluetooth_callback_simple]
        private val bluetoothGattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // successfully connected to the GATT Server
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // disconnected from the GATT Server
                }
            }
        }
        // [END android_bluetooth_callback_simple]
    }
}

private object InitializeNamespace {
    // [START android_bluetooth_initialize]
    private const val TAG = "BluetoothLeService"

    class BluetoothLeService : Service() {

        private var bluetoothAdapter: BluetoothAdapter? = null

        fun initialize(): Boolean {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter == null) {
                Log.e(TAG, "Unable to obtain a BluetoothAdapter.")
                return false
            }
            return true
        }

        // [START_EXCLUDE]
        override fun onBind(intent: Intent): IBinder? {
            return null
        }
        // [END_EXCLUDE]
    }
    // [END android_bluetooth_initialize]
}

@SuppressLint("MissingPermission")
private object ConnectGattNamespace {
    class BluetoothLeService : Service() {
        // [START android_bluetooth_connect_gatt]
        // [START_EXCLUDE silent]
        fun placeholder(device: BluetoothDevice, bluetoothGattCallback: BluetoothGattCallback) {
        // [END_EXCLUDE]
            var bluetoothGatt: BluetoothGatt? = null
            // ...
            bluetoothGatt = device.connectGatt(this, false, bluetoothGattCallback)
        // [START_EXCLUDE silent]
        }
        // [END_EXCLUDE]
        // [END android_bluetooth_connect_gatt]
        override fun onBind(intent: Intent): IBinder? = null
    }
}

@SuppressLint("MissingPermission")
private object ConnectNamespace {
    // [START android_bluetooth_connect]
    class BluetoothLeService : Service() {

        // [START_EXCLUDE silent]
        private var bluetoothAdapter: BluetoothAdapter? = null
        private val bluetoothGattCallback = object : BluetoothGattCallback() {}
        private val TAG = "BluetoothLeService"
        // [END_EXCLUDE]
        // [START_EXCLUDE]
        override fun onBind(intent: Intent): IBinder? = null
        fun placeholder() {}
        // [END_EXCLUDE]
        private var bluetoothGatt: BluetoothGatt? = null

        // [START_EXCLUDE]
        fun placeholder2() {}
        // [END_EXCLUDE]
        fun connect(address: String): Boolean {
            bluetoothAdapter?.let { adapter ->
                try {
                    val device = adapter.getRemoteDevice(address)
                    // connect to the GATT server on the device
                    bluetoothGatt = device.connectGatt(this, false, bluetoothGattCallback)
                    return true
                } catch (exception: IllegalArgumentException) {
                    Log.w(TAG, "Device not found with provided address.  Unable to connect.")
                    return false
                }
            } ?: run {
                Log.w(TAG, "BluetoothAdapter not initialized")
                return false
            }
        }
    }
    // [END android_bluetooth_connect]
}

@SuppressLint("MissingPermission")
private object CallbackNamespace {
    // [START android_bluetooth_callback]
    class BluetoothLeService : Service() {

        // [START_EXCLUDE silent]
        private val binder = LocalBinder()
        private var bluetoothAdapter: BluetoothAdapter? = null
        private var bluetoothGatt: BluetoothGatt? = null
        private fun broadcastUpdate(action: String) {}
        override fun onBind(intent: Intent): IBinder? = null
        inner class LocalBinder : Binder()
        // [END_EXCLUDE]
        private var connectionState = STATE_DISCONNECTED

        private val bluetoothGattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // successfully connected to the GATT Server
                    connectionState = STATE_CONNECTED
                    broadcastUpdate(ACTION_GATT_CONNECTED)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // disconnected from the GATT Server
                    connectionState = STATE_DISCONNECTED
                    broadcastUpdate(ACTION_GATT_DISCONNECTED)
                }
            }
        }

        // [START_EXCLUDE]
        fun placeholder() {}
        // [END_EXCLUDE]
        companion object {
            const val ACTION_GATT_CONNECTED =
                "com.example.bluetooth.le.ACTION_GATT_CONNECTED"
            const val ACTION_GATT_DISCONNECTED =
                "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED"

            private const val STATE_DISCONNECTED = 0
            private const val STATE_CONNECTED = 2
        }
    }
    // [END android_bluetooth_callback]
}

@SuppressLint("MissingPermission")
private object CloseNamespace {
    // [START android_bluetooth_close]
    class BluetoothLeService : Service() {

        // [START_EXCLUDE]
        private var bluetoothGatt: BluetoothGatt? = null
        override fun onBind(intent: Intent): IBinder? = null
        // [END_EXCLUDE]
        override fun onUnbind(intent: Intent?): Boolean {
            close()
            return super.onUnbind(intent)
        }

        private fun close() {
            bluetoothGatt?.let { gatt ->
                gatt.close()
                bluetoothGatt = null
            }
        }
    }
    // [END android_bluetooth_close]
}


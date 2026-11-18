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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    private val pendingDescriptorValues = mutableMapOf<BluetoothGattDescriptor, ByteArray>()
    private var writingDescriptor = false

    private data class OutgoingWrite(
        val payload: ByteArray,
        val messageId: Long? = null,
        val isLastForMessage: Boolean = false
    )

    private val characteristicWriteQueue = ArrayDeque<OutgoingWrite>()
    private var writingCharacteristic = false
    private var currentOutgoingWrite: OutgoingWrite? = null
    private var negotiatedMtu = 23
    private val handler = Handler(Looper.getMainLooper())

    private data class IncomingImage(
        val sender: String,
        val msgId: Int,
        val totalChunks: Int,
        val mimeType: String,
        val totalSize: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf()
    )

    private val incomingImages = mutableMapOf<Int, IncomingImage>()

    companion object {
        const val ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED"
        const val ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED"
        const val ACTION_DATA_AVAILABLE =
            "com.example.echo.ACTION_DATA_AVAILABLE"
        const val ACTION_IMAGE_AVAILABLE =
            "com.example.echo.ACTION_IMAGE_AVAILABLE"
        const val ACTION_MESSAGE_SENT =
            "com.example.echo.ACTION_MESSAGE_SENT"

        const val EXTRA_SENDER = "com.example.echo.EXTRA_SENDER"
        const val EXTRA_MESSAGE = "com.example.echo.EXTRA_MESSAGE"
        const val EXTRA_TIMESTAMP = "com.example.echo.EXTRA_TIMESTAMP"
        const val EXTRA_RAW_DATA = "com.example.echo.EXTRA_RAW_DATA"
        const val EXTRA_IMAGE_DATA = "com.example.echo.EXTRA_IMAGE_DATA"
        const val EXTRA_IMAGE_MIME = "com.example.echo.EXTRA_IMAGE_MIME"
        const val EXTRA_MESSAGE_ID = "com.example.echo.EXTRA_MESSAGE_ID"
        const val EXTRA_CHARACTERISTIC_UUID =
            "com.example.echo.EXTRA_CHARACTERISTIC_UUID"

        private val IMAGE_MAGIC = byteArrayOf(0xEC.toByte(), 0x01)
        private const val PKT_IMAGE_START: Byte = 0x02
        private const val PKT_IMAGE_CHUNK: Byte = 0x03
        private const val PKT_IMAGE_END: Byte = 0x04

        /**
         * UUIDs for the ECHO retranslator (NimBLE, little-endian in firmware).
         *   service fea60001-0324-45f8-8acc-193637d7af72
         *   RX char fea60002-0324-45f8-8acc-193637d7af72 (write)
         *   TX char fea60003-0324-45f8-8acc-193637d7af72 (notify)
         */
        val MESSAGE_SERVICE_UUID: UUID =
            UUID.fromString("fea60001-0324-45f8-8acc-193637d7af72")
        val MESSAGE_RX_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("fea60002-0324-45f8-8acc-193637d7af72")
        val MESSAGE_TX_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("fea60003-0324-45f8-8acc-193637d7af72")

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
                    pendingDescriptorValues.clear()
                    writingDescriptor = false
                    characteristicWriteQueue.clear()
                    writingCharacteristic = false
                    currentOutgoingWrite = null
                    incomingImages.clear()
                    handler.removeCallbacksAndMessages(null)
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
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            }
            Log.d(TAG, "MTU changed to $mtu (status=$status), using $negotiatedMtu")
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

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val completed = currentOutgoingWrite
            currentOutgoingWrite = null
            writingCharacteristic = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onCharacteristicWrite failed: $status")
            } else if (completed != null && completed.isLastForMessage && completed.messageId != null) {
                broadcastMessageSent(completed.messageId)
            }
            writeNextCharacteristic()
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

    fun disconnect() {
        val gatt = bluetoothGatt
        if (gatt == null) {
            connectionState = STATE_DISCONNECTED
            connecting = false
            return
        }
        gatt.disconnect()
    }

    /**
     * Sends a UTF-8 text message to the retranslator via the RX (write) characteristic.
     */
    fun sendMessage(text: String, messageId: Long = 0L): Boolean {
        return enqueueRawWrite(text.toByteArray(Charsets.UTF_8), messageId)
    }

    /**
     * Sends an image as chunked binary packets over BLE.
     */
    fun sendImage(data: ByteArray, mimeType: String = "image/jpeg", messageId: Long = 0L): Boolean {
        if (data.isEmpty()) return false
        val gatt = bluetoothGatt ?: return false
        if (findWritableCharacteristic(gatt) == null) return false

        val msgId = (System.currentTimeMillis() and 0xFFFF).toInt()
        val mimeBytes = mimeType.toByteArray(Charsets.UTF_8).take(255).toByteArray()
        val payloadCapacity = (negotiatedMtu - 3 - 10).coerceAtLeast(20) // header overhead for chunks
        val totalChunks = ((data.size + payloadCapacity - 1) / payloadCapacity).coerceAtLeast(1)

        val start = buildImageStartPacket(msgId, totalChunks, mimeBytes, data.size)
        characteristicWriteQueue.addLast(OutgoingWrite(start, messageId, false))

        var offset = 0
        var chunkIndex = 0
        while (offset < data.size) {
            val end = minOf(offset + payloadCapacity, data.size)
            val chunk = data.copyOfRange(offset, end)
            characteristicWriteQueue.addLast(
                OutgoingWrite(buildImageChunkPacket(msgId, chunkIndex, chunk), messageId, false)
            )
            offset = end
            chunkIndex++
        }
        characteristicWriteQueue.addLast(
            OutgoingWrite(buildImageEndPacket(msgId), messageId, messageId != 0L)
        )

        writeNextCharacteristic()
        return true
    }

    private fun enqueueRawWrite(payload: ByteArray, messageId: Long = 0L): Boolean {
        val gatt = bluetoothGatt ?: return false
        if (findWritableCharacteristic(gatt) == null) return false
        characteristicWriteQueue.addLast(
            OutgoingWrite(payload, messageId, messageId != 0L)
        )
        writeNextCharacteristic()
        return true
    }

    @Suppress("DEPRECATION")
    private fun writeNextCharacteristic() {
        if (writingCharacteristic) return
        val gatt = bluetoothGatt ?: return
        val characteristic = findWritableCharacteristic(gatt) ?: run {
            characteristicWriteQueue.clear()
            currentOutgoingWrite = null
            return
        }
        val outgoing = characteristicWriteQueue.pollFirst() ?: return
        currentOutgoingWrite = outgoing

        val writeType =
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

        writingCharacteristic = true
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, outgoing.payload, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.writeType = writeType
            characteristic.value = outgoing.payload
            gatt.writeCharacteristic(characteristic)
        }

        if (!started) {
            Log.w(TAG, "Failed to start characteristic write")
            writingCharacteristic = false
            currentOutgoingWrite = null
            characteristicWriteQueue.clear()
        } else if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            // Pace no-response writes to avoid overrunning the BLE stack.
            val completed = outgoing
            handler.postDelayed({
                writingCharacteristic = false
                currentOutgoingWrite = null
                if (completed.isLastForMessage && completed.messageId != null && completed.messageId != 0L) {
                    broadcastMessageSent(completed.messageId)
                }
                writeNextCharacteristic()
            }, 30)
        }
    }

    private fun broadcastMessageSent(messageId: Long) {
        val intent = Intent(ACTION_MESSAGE_SENT).apply {
            setPackage(packageName)
            putExtra(EXTRA_MESSAGE_ID, messageId)
        }
        sendBroadcast(intent)
    }

    private fun buildImageStartPacket(
        msgId: Int,
        totalChunks: Int,
        mimeBytes: ByteArray,
        totalSize: Int
    ): ByteArray {
        val size = 2 + 1 + 2 + 2 + 1 + mimeBytes.size + 4
        val buf = java.nio.ByteBuffer.allocate(size)
        buf.put(IMAGE_MAGIC)
        buf.put(PKT_IMAGE_START)
        buf.putShort(msgId.toShort())
        buf.putShort(totalChunks.toShort())
        buf.put(mimeBytes.size.toByte())
        buf.put(mimeBytes)
        buf.putInt(totalSize)
        return buf.array()
    }

    private fun buildImageChunkPacket(msgId: Int, chunkIndex: Int, data: ByteArray): ByteArray {
        val size = 2 + 1 + 2 + 2 + 2 + data.size
        val buf = java.nio.ByteBuffer.allocate(size)
        buf.put(IMAGE_MAGIC)
        buf.put(PKT_IMAGE_CHUNK)
        buf.putShort(msgId.toShort())
        buf.putShort(chunkIndex.toShort())
        buf.putShort(data.size.toShort())
        buf.put(data)
        return buf.array()
    }

    private fun buildImageEndPacket(msgId: Int): ByteArray {
        val size = 2 + 1 + 2
        val buf = java.nio.ByteBuffer.allocate(size)
        buf.put(IMAGE_MAGIC)
        buf.put(PKT_IMAGE_END)
        buf.putShort(msgId.toShort())
        return buf.array()
    }

    private fun findWritableCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        gatt.getService(MESSAGE_SERVICE_UUID)
            ?.getCharacteristic(MESSAGE_RX_CHARACTERISTIC_UUID)
            ?.takeIf { characteristic ->
                characteristic.properties and (
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                    ) != 0
            }
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
     * Subscribes to the TX (notify) characteristic for incoming messages.
     * Falls back to any notifiable/indicatable characteristic if needed.
     */
    @Suppress("DEPRECATION")
    private fun enableNotifications(gatt: BluetoothGatt) {
        val txCharacteristic = gatt.getService(MESSAGE_SERVICE_UUID)
            ?.getCharacteristic(MESSAGE_TX_CHARACTERISTIC_UUID)
        if (txCharacteristic != null) {
            val properties = txCharacteristic.properties
            val supportsNotify =
                properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val supportsIndicate =
                properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (supportsNotify || supportsIndicate) {
                if (setCharacteristicNotification(gatt, txCharacteristic, supportsNotify)) {
                    return
                }
                Log.w(TAG, "Failed to enable notifications for TX characteristic, falling back")
            } else {
                Log.w(TAG, "TX characteristic ${txCharacteristic.uuid} does not support notify/indicate")
            }
        } else {
            Log.w(TAG, "TX characteristic $MESSAGE_TX_CHARACTERISTIC_UUID not found, falling back")
        }

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
    ): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.w(TAG, "setCharacteristicNotification failed for ${characteristic.uuid}")
            return false
        }

        val descriptor =
            characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: run {
                Log.w(TAG, "CCCD not found for ${characteristic.uuid}")
                // Local notification enabled; some peripherals may still notify without CCCD write.
                return true
            }
        val value = if (useNotification) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        descriptor.value = value
        descriptorWriteQueue.addLast(descriptor)
        pendingDescriptorValues[descriptor] = value
        writeNextDescriptor()
        return true
    }

    @Suppress("DEPRECATION")
    private fun writeNextDescriptor() {
        if (writingDescriptor) return
        val gatt = bluetoothGatt ?: return
        val descriptor = descriptorWriteQueue.pollFirst() ?: return
        val value = pendingDescriptorValues.remove(descriptor)
            ?: descriptor.value
            ?: BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        writingDescriptor = true

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
        if (!started) {
            Log.w(TAG, "writeDescriptor failed for ${descriptor.characteristic.uuid}")
            writingDescriptor = false
            writeNextDescriptor()
        }
    }

    private fun handleCharacteristicValue(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (value.isEmpty()) return
        if (isImagePacket(value)) {
            handleImagePacket(value)
            return
        }
        broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic, value)
    }

    private fun isImagePacket(value: ByteArray): Boolean {
        return value.size >= 3 &&
            value[0] == IMAGE_MAGIC[0] &&
            value[1] == IMAGE_MAGIC[1] &&
            (value[2] == PKT_IMAGE_START || value[2] == PKT_IMAGE_CHUNK || value[2] == PKT_IMAGE_END)
    }

    private fun handleImagePacket(value: ByteArray) {
        try {
            val buf = java.nio.ByteBuffer.wrap(value)
            buf.get() // magic 0
            buf.get() // magic 1
            val type = buf.get()
            val fallbackSender = bluetoothGatt?.device?.let { device ->
                device.name ?: device.address
            } ?: "ESP32"

            when (type) {
                PKT_IMAGE_START -> {
                    val msgId = buf.short.toInt() and 0xFFFF
                    val totalChunks = buf.short.toInt() and 0xFFFF
                    val mimeLen = buf.get().toInt() and 0xFF
                    val mimeBytes = ByteArray(mimeLen)
                    buf.get(mimeBytes)
                    val totalSize = buf.int
                    val mime = mimeBytes.toString(Charsets.UTF_8)
                    incomingImages[msgId] = IncomingImage(
                        sender = fallbackSender,
                        msgId = msgId,
                        totalChunks = totalChunks,
                        mimeType = mime.ifBlank { "image/jpeg" },
                        totalSize = totalSize
                    )
                }

                PKT_IMAGE_CHUNK -> {
                    val msgId = buf.short.toInt() and 0xFFFF
                    val chunkIndex = buf.short.toInt() and 0xFFFF
                    val len = buf.short.toInt() and 0xFFFF
                    val data = ByteArray(len)
                    buf.get(data)
                    incomingImages[msgId]?.chunks?.put(chunkIndex, data)
                }

                PKT_IMAGE_END -> {
                    val msgId = buf.short.toInt() and 0xFFFF
                    val incoming = incomingImages.remove(msgId) ?: return
                    val output = java.io.ByteArrayOutputStream()
                    for (i in 0 until incoming.totalChunks) {
                        val chunk = incoming.chunks[i] ?: return
                        output.write(chunk)
                    }
                    val imageBytes = output.toByteArray()
                    broadcastImage(incoming.sender, imageBytes, incoming.mimeType)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse image packet", e)
        }
    }

    private fun broadcastImage(sender: String, imageData: ByteArray, mimeType: String) {
        val intent = Intent(ACTION_IMAGE_AVAILABLE).apply {
            setPackage(packageName)
            putExtra(EXTRA_SENDER, sender)
            putExtra(EXTRA_IMAGE_DATA, imageData)
            putExtra(EXTRA_IMAGE_MIME, mimeType)
            putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
        }
        sendBroadcast(intent)
    }

    // [START android_bluetooth_broadcast]
    private fun broadcastUpdate(action: String) {
        val intent = Intent(action).setPackage(packageName)
        sendBroadcast(intent)
    }
    // [END android_bluetooth_broadcast]

    /**
     * Converts an incoming BLE text payload into a messenger event.
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
            setPackage(packageName)
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
        pendingDescriptorValues.clear()
        writingDescriptor = false
        characteristicWriteQueue.clear()
        writingCharacteristic = false
        currentOutgoingWrite = null
        incomingImages.clear()
        handler.removeCallbacksAndMessages(null)
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


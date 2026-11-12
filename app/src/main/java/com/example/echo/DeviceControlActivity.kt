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
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.echo.BluetoothLeService.LocalBinder

private const val TAG = "DeviceControlActivity"

// [START android_bluetooth_activity_all]
// [START android_bluetooth_bind_service]
class DeviceControlActivity : AppCompatActivity() {

    private var bluetoothService: BluetoothLeService? = null
    // [START_EXCLUDE silent]
    private var deviceAddress: String? = null
    private var connected = false
    private var bound = false

    private lateinit var messageListAdapter: MessageListAdapter
    private lateinit var messageList: ListView
    private lateinit var messageInput: EditText
    // [END_EXCLUDE]

    // [START android_bluetooth_service_connection]
    // Code to manage Service lifecycle.
    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        // [START_EXCLUDE silent]
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        // [END_EXCLUDE]
        override fun onServiceConnected(
            componentName: ComponentName,
            service: IBinder
        ) {
            bluetoothService = (service as LocalBinder).getService()
            bluetoothService?.let { bluetooth ->
                // [START_EXCLUDE silent]
                if (!bluetooth.initialize()) {
                    Log.e(TAG, "Unable to initialize Bluetooth")
                    finish()
                    return@let
                }
                // perform device connection
                deviceAddress?.let { bluetooth.connect(it) }
                // [END_EXCLUDE]
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            bluetoothService = null
        }
    }
    // [END android_bluetooth_service_connection]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messenger)
        // [START_EXCLUDE silent]

        deviceAddress = intent.getStringExtra(DeviceScanActivity.EXTRA_DEVICE_ADDRESS)
        findViewById<TextView>(R.id.device_address).text = deviceAddress.orEmpty()

        messageListAdapter = MessageListAdapter(this)
        messageList = findViewById(R.id.message_list)
        messageList.adapter = messageListAdapter

        messageInput = findViewById(R.id.message_input)
        findViewById<Button>(R.id.send_button).setOnClickListener {
            sendMessage()
        }
        // [END_EXCLUDE]

        val gattServiceIntent = Intent(this, BluetoothLeService::class.java)
        bound = bindService(gattServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    // [START_EXCLUDE silent]

    private val gattUpdateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothLeService.ACTION_GATT_CONNECTED -> {
                    connected = true
                    updateConnectionState(R.string.connected)
                }
                BluetoothLeService.ACTION_GATT_DISCONNECTED -> {
                    connected = false
                    updateConnectionState(R.string.disconnected)
                }
                BluetoothLeService.ACTION_DATA_AVAILABLE -> {
                    val text = intent.getStringExtra(BluetoothLeService.EXTRA_MESSAGE)
                        ?: return
                    val sender = intent.getStringExtra(BluetoothLeService.EXTRA_SENDER)
                        ?: "ESP32"
                    val timestamp = intent.getLongExtra(
                        BluetoothLeService.EXTRA_TIMESTAMP,
                        System.currentTimeMillis()
                    )
                    addMessage(MessengerMessage(sender, text, timestamp))
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            gattUpdateReceiver,
            makeGattUpdateIntentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        bluetoothService?.let { service ->
            deviceAddress?.let { address ->
                val result = service.connect(address)
                Log.d(TAG, "Connect request result=$result")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(gattUpdateReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    private fun makeGattUpdateIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(BluetoothLeService.ACTION_GATT_CONNECTED)
            addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED)
            addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED)
            addAction(BluetoothLeService.ACTION_DATA_AVAILABLE)
        }
    }

    private fun updateConnectionState(resourceId: Int) {
        findViewById<TextView>(R.id.connection_state).text = getString(resourceId)
    }

    private fun addMessage(message: MessengerMessage) {
        messageListAdapter.add(message)
        messageList.setSelection(messageListAdapter.count - 1)
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return

        val service = bluetoothService
        if (service == null || !service.sendMessage(text)) {
            Toast.makeText(this, R.string.send_failed, Toast.LENGTH_SHORT).show()
            return
        }

        messageInput.setText("")
        addMessage(MessengerMessage(getString(R.string.you), text))
    }
    // [END_EXCLUDE]
}
// [END android_bluetooth_bind_service]
// [END android_bluetooth_activity_all]

/**
 * Namespaces for progressive versions of ServiceConnection to match documentation.
 */
private object ServiceConnectionSimpleNamespace {
    class DeviceControlActivity {
        private var bluetoothService: BluetoothLeService? = null

        // [START android_bluetooth_service_connection_simple]
        // Code to manage Service lifecycle.
        val serviceConnection: ServiceConnection = object : ServiceConnection {
            override fun onServiceConnected(
                componentName: ComponentName,
                service: IBinder
            ) {
                bluetoothService = (service as LocalBinder).getService()
                bluetoothService?.let { bluetooth ->
                    // call functions on service to check connection and connect to devices
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                bluetoothService = null
            }
        }
        // [END android_bluetooth_service_connection_simple]
    }
}

private object ServiceConnectionInitializeNamespace {
    // [START android_bluetooth_service_connection_initialize]
    class DeviceControlActivity : AppCompatActivity() {

        // [START_EXCLUDE silent]
        private var bluetoothService: BluetoothLeService? = null
        private val TAG = "DeviceControlActivity"
        // [END_EXCLUDE]
        // Code to manage Service lifecycle.
        private val serviceConnection: ServiceConnection = object : ServiceConnection {
            override fun onServiceConnected(
                componentName: ComponentName,
                service: IBinder
            ) {
                bluetoothService = (service as LocalBinder).getService()
                bluetoothService?.let { bluetooth ->
                    if (!bluetooth.initialize()) {
                        Log.e(TAG, "Unable to initialize Bluetooth")
                        finish()
                    }
                    // perform device connection
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                bluetoothService = null
            }
        }

        // [START_EXCLUDE]
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
        }
        // [END_EXCLUDE]
    }
    // [END android_bluetooth_service_connection_initialize]
}

@SuppressLint("MissingPermission")
private object ServiceConnectionConnectNamespace {
    class DeviceControlActivity : AppCompatActivity() {

        private var bluetoothService: BluetoothLeService? = null
        private var deviceAddress: String? = null
        private val TAG = "DeviceControlActivity"

        // [START android_bluetooth_service_connection_connect]
        // Code to manage Service lifecycle.
        private val serviceConnection: ServiceConnection = object : ServiceConnection {
            // [START_EXCLUDE silent]
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            // [END_EXCLUDE]
            override fun onServiceConnected(
                componentName: ComponentName,
                service: IBinder
            ) {
                bluetoothService = (service as LocalBinder).getService()
                bluetoothService?.let { bluetooth ->
                    if (!bluetooth.initialize()) {
                        Log.e(TAG, "Unable to initialize Bluetooth")
                        finish()
                    }
                    // perform device connection
                    deviceAddress?.let { bluetooth.connect(it) }
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                bluetoothService = null
            }
        }
        // [END android_bluetooth_service_connection_connect]

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
        }
    }
}

@SuppressLint("MissingPermission")
private object UpdateReceiverNamespace {
    // [START android_bluetooth_update_receiver]
    class DeviceControlActivity : AppCompatActivity() {

        // [START_EXCLUDE silent]
        private var bluetoothService: BluetoothLeService? = null
        private var deviceAddress: String? = null
        private var connected = false
        private val TAG = "DeviceControlActivity"
        private fun updateConnectionState(resourceId: Int) {}
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
        }
        // [END_EXCLUDE]
        private val gattUpdateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothLeService.ACTION_GATT_CONNECTED -> {
                        connected = true
                        updateConnectionState(R.string.connected)
                    }
                    BluetoothLeService.ACTION_GATT_DISCONNECTED -> {
                        connected = false
                        updateConnectionState(R.string.disconnected)
                    }
                }
            }
        }

        // [START_EXCLUDE silent]
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        // [END_EXCLUDE]
        override fun onResume() {
            super.onResume()
            registerReceiver(gattUpdateReceiver, makeGattUpdateIntentFilter())
            bluetoothService?.let { service ->
                deviceAddress?.let { address ->
                    val result = service.connect(address)
                    Log.d(TAG, "Connect request result=$result")
                }
            }
        }

        override fun onPause() {
            super.onPause()
            unregisterReceiver(gattUpdateReceiver)
        }

        private fun makeGattUpdateIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                addAction(BluetoothLeService.ACTION_GATT_CONNECTED)
                addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED)
            }
        }
    }
    // [END android_bluetooth_update_receiver]
}


package com.example.echo

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.echo.BluetoothLeService.LocalBinder

private const val TAG = "MainActivity"

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    private var bluetoothService: BluetoothLeService? = null
    private var deviceAddress: String? = null
    private var connected = false
    private var bound = false

    private lateinit var messageListAdapter: MessageListAdapter
    private lateinit var messageList: ListView
    private lateinit var messageInput: EditText
    private lateinit var scanButton: Button
    private lateinit var deviceAddressView: TextView
    private lateinit var connectionStateView: TextView

    private var scanDialog: AlertDialog? = null
    private var leDeviceListAdapter: LeDeviceListAdapter? = null

    private val scanPeriod = 10_000L

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
            bluetoothService = (service as LocalBinder).getService()
            bluetoothService?.let { bluetooth ->
                if (!bluetooth.initialize()) {
                    Log.e(TAG, "Unable to initialize Bluetooth")
                    finish()
                    return@let
                }
                deviceAddress?.let { bluetooth.connect(it) }
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            bluetoothService = null
        }
    }

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
                    val text = intent.getStringExtra(BluetoothLeService.EXTRA_MESSAGE) ?: return
                    val sender = intent.getStringExtra(BluetoothLeService.EXTRA_SENDER)
                        ?: deviceAddress
                        ?: getString(R.string.unknown_device)
                    val timestamp = intent.getLongExtra(
                        BluetoothLeService.EXTRA_TIMESTAMP,
                        System.currentTimeMillis()
                    )
                    addMessage(MessengerMessage(sender, text, timestamp))
                }
            }
        }
    }

    private val leScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            runOnUiThread { leDeviceListAdapter?.addDevice(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            runOnUiThread {
                Toast.makeText(this@MainActivity, R.string.bluetooth_scan_failed, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messenger)

        deviceAddressView = findViewById(R.id.device_address)
        connectionStateView = findViewById(R.id.connection_state)
        scanButton = findViewById(R.id.scan_button)
        messageList = findViewById(R.id.message_list)
        messageInput = findViewById(R.id.message_input)

        messageListAdapter = MessageListAdapter(this)
        messageList.adapter = messageListAdapter

        scanButton.setOnClickListener { ensurePermissionsAndStartScan() }
        findViewById<Button>(R.id.send_button).setOnClickListener { sendMessage() }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.bluetooth_not_supported, Toast.LENGTH_SHORT).show()
            scanButton.isEnabled = false
            return
        }

        val gattServiceIntent = Intent(this, BluetoothLeService::class.java)
        bound = bindService(gattServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun ensurePermissionsAndStartScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startScanningIfEnabled()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun startScanningIfEnabled() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            @Suppress("DEPRECATION")
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            return
        }
        bluetoothLeScanner = adapter.bluetoothLeScanner
        showScanDialogAndScan()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun showScanDialogAndScan() {
        if (scanning) return

        val deviceAdapter = LeDeviceListAdapter(this)
        leDeviceListAdapter = deviceAdapter

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.select_device)
            .setAdapter(deviceAdapter) { d, which ->
                val device = deviceAdapter.getItem(which) ?: return@setAdapter
                stopScan()
                connectToDevice(device.address, device.name ?: device.address)
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { d, _ ->
                stopScan()
                d.dismiss()
            }
            .setOnDismissListener {
                stopScan()
                scanDialog = null
                leDeviceListAdapter = null
            }
            .create()

        scanDialog = dialog
        dialog.show()

        scanning = true
        handler.postDelayed({ stopScan() }, scanPeriod)
        bluetoothLeScanner?.startScan(leScanCallback)
    }

    private fun stopScan() {
        if (scanning) {
            scanning = false
            try {
                bluetoothLeScanner?.stopScan(leScanCallback)
            } catch (_: Exception) {
            }
        }
        handler.removeCallbacksAndMessages(null)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectToDevice(address: String, displayName: String) {
        if (deviceAddress == address && connected) return
        deviceAddress = address
        deviceAddressView.text = displayName
        updateConnectionState(R.string.disconnected)

        val service = bluetoothService
        if (service != null) {
            if (!service.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth")
                return
            }
            service.connect(address)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startScanningIfEnabled()
            } else {
                Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_LONG).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                startScanningIfEnabled()
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
        stopScan()
        scanDialog?.dismiss()
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
        connectionStateView.text = getString(resourceId)
    }

    private fun addMessage(message: MessengerMessage) {
        messageListAdapter.add(message)
        messageList.setSelection(messageListAdapter.count - 1)
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return

        val service = bluetoothService
        if (service == null || !connected || !service.sendMessage(text)) {
            Toast.makeText(this, R.string.send_failed, Toast.LENGTH_SHORT).show()
            return
        }

        messageInput.setText("")
        addMessage(MessengerMessage(getString(R.string.you), text))
    }

    companion object {
        private const val REQUEST_ENABLE_BT = 1
        private const val REQUEST_PERMISSIONS = 2
    }
}

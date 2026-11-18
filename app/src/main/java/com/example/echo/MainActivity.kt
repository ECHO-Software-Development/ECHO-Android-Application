package com.example.echo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.echo.BluetoothLeService.LocalBinder
import com.example.echo.ui.theme.ECHOTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "MainActivity"

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    private var bluetoothService: BluetoothLeService? = null
    private var deviceAddress: String? = null
    private var connected by mutableStateOf(false)
    private var bound = false

    private var deviceName by mutableStateOf<String?>(null)
    private var connectionStatusRes by mutableStateOf(R.string.disconnected)
    private val messages = mutableStateListOf<MessengerMessage>()
    private var inputText by mutableStateOf("")

    private var showScanDialog by mutableStateOf(false)
    private val scannedDevices = mutableStateListOf<BluetoothDevice>()

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
                    connectionStatusRes = R.string.connected
                }

                BluetoothLeService.ACTION_GATT_DISCONNECTED -> {
                    connected = false
                    connectionStatusRes = R.string.disconnected
                    messages.clear()
                }

                BluetoothLeService.ACTION_DATA_AVAILABLE -> {
                    val text = intent.getStringExtra(BluetoothLeService.EXTRA_MESSAGE) ?: return
                    val sender = intent.getStringExtra(BluetoothLeService.EXTRA_SENDER)
                        ?: deviceName
                        ?: deviceAddress
                        ?: getString(R.string.unknown_device)
                    val timestamp = intent.getLongExtra(
                        BluetoothLeService.EXTRA_TIMESTAMP,
                        System.currentTimeMillis()
                    )
                    messages.add(MessengerMessage(sender, text, timestamp))
                }
            }
        }
    }

    private val leScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            runOnUiThread {
                if (scannedDevices.none { it.address == device.address }) {
                    scannedDevices.add(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, R.string.bluetooth_scan_failed, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                startScanningIfEnabled()
            } else {
                Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_LONG).show()
            }
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startScanningIfEnabled()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setContent {
            ECHOTheme {
                MessengerScreen(
                    deviceName = deviceName,
                    deviceAddress = deviceAddress,
                    connected = connected,
                    connectionStatus = stringResource(connectionStatusRes),
                    messages = messages,
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onSend = { sendMessage() },
                    onScanClick = { onScanRequested() },
                    onDisconnectClick = { onDisconnectRequested() },
                    showScanDialog = showScanDialog,
                    scannedDevices = scannedDevices,
                    scanning = scanning,
                    onDeviceSelected = { device -> onDeviceSelected(device) },
                    onDismissScan = { dismissScanDialog() }
                )
            }
        }

        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.bluetooth_not_supported, Toast.LENGTH_SHORT).show()
        } else {
            val gattServiceIntent = Intent(this, BluetoothLeService::class.java)
            bound = bindService(gattServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun onScanRequested() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.bluetooth_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startScanningIfEnabled()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startScanningIfEnabled() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        bluetoothLeScanner = adapter.bluetoothLeScanner
        startScanDialog()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startScanDialog() {
        if (scanning) return
        scannedDevices.clear()
        showScanDialog = true
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

    private fun dismissScanDialog() {
        stopScan()
        showScanDialog = false
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun onDeviceSelected(device: BluetoothDevice) {
        dismissScanDialog()
        val name = try {
            device.name ?: device.address
        } catch (_: SecurityException) {
            device.address
        }
        connectToDevice(device.address, name)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectToDevice(address: String, displayName: String) {
        if (deviceAddress == address && connected) return
        deviceAddress = address
        deviceName = displayName
        connectionStatusRes = R.string.disconnected

        val service = bluetoothService
        if (service != null) {
            if (!service.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth")
                return
            }
            service.connect(address)
        }
    }

    private fun onDisconnectRequested() {
        bluetoothService?.disconnect()
        connected = false
        connectionStatusRes = R.string.disconnected
        deviceAddress = null
        deviceName = null
        messages.clear()
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
        showScanDialog = false
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

    private fun sendMessage() {
        val text = inputText.trim()
        if (text.isEmpty()) return

        val service = bluetoothService
        if (service == null || !connected || !service.sendMessage(text)) {
            Toast.makeText(this, R.string.send_failed, Toast.LENGTH_SHORT).show()
            return
        }

        inputText = ""
        messages.add(MessengerMessage(getString(R.string.you), text))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessengerScreen(
    deviceName: String?,
    deviceAddress: String?,
    connected: Boolean,
    connectionStatus: String,
    messages: List<MessengerMessage>,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onScanClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    showScanDialog: Boolean,
    scannedDevices: List<BluetoothDevice>,
    scanning: Boolean,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onDismissScan: () -> Unit
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(connected) {
        if (!connected) {
            focusManager.clearFocus()
        }
    }

    if (showScanDialog) {
        ScanDialog(
            devices = scannedDevices,
            scanning = scanning,
            onDeviceSelected = onDeviceSelected,
            onDismiss = onDismissScan
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = deviceName ?: stringResource(R.string.app_name),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (deviceName != null || deviceAddress != null) {
                                "$connectionStatus · ${deviceAddress ?: ""}"
                            } else {
                                connectionStatus
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (connected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    if (connected) {
                        TextButton(onClick = onDisconnectClick) {
                            Text(stringResource(R.string.disconnect))
                        }
                    } else {
                        TextButton(onClick = onScanClick) {
                            Text(stringResource(R.string.scan_connect))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            if (connected) {
                MessageInputBar(
                    value = inputText,
                    onValueChange = onInputChange,
                    onSend = onSend,
                    enabled = true
                )
            }
        }
    ) { innerPadding ->
        if (messages.isEmpty()) {
            EmptyChatHint(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                connected = connected,
                hasDevice = deviceName != null || deviceAddress != null
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    val isMine = message.sender == stringResource(R.string.you)
                    MessageBubble(message = message, isMine = isMine)
                }
            }
        }
    }
}

@Composable
fun EmptyChatHint(modifier: Modifier = Modifier, connected: Boolean, hasDevice: Boolean) {
    val hint = when {
        connected -> "Connected. Messages will appear here."
        hasDevice -> "Connecting…"
        else -> "Tap scan to connect"
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp)
        )
    }
}

@Composable
fun MessageBubble(message: MessengerMessage, isMine: Boolean) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val bubbleColor = if (isMine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = if (isMine) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = bubbleColor,
            contentColor = textColor,
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isMine) 20.dp else 6.dp,
                bottomEnd = if (isMine) 6.dp else 20.dp
            ),
            tonalElevation = 1.dp,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (!isMine) {
                    Text(
                        text = message.sender,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = timeFormat.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.75f),
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@Composable
fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.message_hint)) },
                enabled = enabled,
                shape = RoundedCornerShape(24.dp),
                maxLines = 4
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = enabled && value.trim().isNotEmpty()
            ) {
                Text("➤", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ScanDialog(
    devices: List<BluetoothDevice>,
    scanning: Boolean,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_device)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (scanning) {
                    Text(
                        text = stringResource(R.string.scanning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (devices.isEmpty()) {
                    Text(
                        text = if (scanning) "Looking for nearby devices…" else stringResource(R.string.no_devices_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        items(devices, key = { it.address }) { device ->
                            val unknownName = stringResource(R.string.unknown_device)
                            val name = try {
                                device.name
                            } catch (_: SecurityException) {
                                null
                            } ?: unknownName
                            val address = try {
                                device.address
                            } catch (_: SecurityException) {
                                ""
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onDeviceSelected(device) }
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

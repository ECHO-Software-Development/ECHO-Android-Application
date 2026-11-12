/*
 * Copyright 2025 The Android Open Source Project
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
import android.app.ListActivity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ListView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat

/**
 * Activity for scanning and displaying available BLE devices.
 */
@SuppressLint("MissingPermission")
class DeviceScanActivity : ListActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var leDeviceListAdapter: LeDeviceListAdapter

    // Stops scanning after 10 seconds.
    private val scanPeriod = 10_000L

    // Device scan callback.
    private val leScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            leDeviceListAdapter.addDevice(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Toast.makeText(this@DeviceScanActivity, R.string.bluetooth_scan_failed, Toast.LENGTH_SHORT)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        leDeviceListAdapter = LeDeviceListAdapter(this)
        listAdapter = leDeviceListAdapter

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.bluetooth_not_supported, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        ensurePermissionsAndStart()
    }

    private fun ensurePermissionsAndStart() {
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

    @SuppressLint("MissingPermission")
    private fun startScanningIfEnabled() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            @Suppress("DEPRECATION")
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            return
        }
        bluetoothLeScanner = adapter.bluetoothLeScanner
        scanLeDevice()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun scanLeDevice() {
        if (scanning) return
        scanning = true
        handler.postDelayed({
            scanning = false
            bluetoothLeScanner?.stopScan(leScanCallback)
        }, scanPeriod)
        bluetoothLeScanner?.startScan(leScanCallback)
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
                finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                startScanningIfEnabled()
            } else {
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (scanning) {
            scanning = false
            bluetoothLeScanner?.stopScan(leScanCallback)
        }
        handler.removeCallbacksAndMessages(null)
    }

    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        val device = leDeviceListAdapter.getItem(position) ?: return
        val intent = Intent(this, DeviceControlActivity::class.java)
        intent.putExtra(EXTRA_DEVICE_ADDRESS, device.address)
        startActivity(intent)
    }

    companion object {
        private const val REQUEST_ENABLE_BT = 1
        private const val REQUEST_PERMISSIONS = 2

        const val EXTRA_DEVICE_ADDRESS = "EXTRAS_DEVICE_ADDRESS"
    }
}

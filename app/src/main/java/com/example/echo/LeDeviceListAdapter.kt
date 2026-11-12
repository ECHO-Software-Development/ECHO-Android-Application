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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

class LeDeviceListAdapter(context: Context) : ArrayAdapter<BluetoothDevice>(context, R.layout.list_item) {

    private val devices = mutableListOf<BluetoothDevice>()

    override fun getCount(): Int = devices.size

    override fun getItem(position: Int): BluetoothDevice? = devices.getOrNull(position)

    fun addDevice(device: BluetoothDevice) {
        if (!devices.contains(device)) {
            devices.add(device)
            notifyDataSetChanged()
        }
    }

    @SuppressLint("MissingPermission")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.list_item, parent, false)
        val device = getItem(position)
        view.findViewById<TextView>(R.id.device_name).text =
            device?.name ?: context.getString(R.string.unknown_device)
        view.findViewById<TextView>(R.id.device_address).text = device?.address.orEmpty()
        return view
    }
}

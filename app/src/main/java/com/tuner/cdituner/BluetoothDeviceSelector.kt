package com.tuner.cdituner

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

class BluetoothDeviceSelector(
    private val context: Context,
    private val onDeviceSelected: (BluetoothDevice) -> Unit
) {
    
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    
    @SuppressLint("MissingPermission")
    fun showDeviceSelectionDialog() {
        if (bluetoothAdapter == null) {
            AlertDialog.Builder(context)
                .setTitle("Bluetooth Not Supported")
                .setMessage("This device does not support Bluetooth")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        
        if (!bluetoothAdapter.isEnabled) {
            AlertDialog.Builder(context)
                .setTitle("Bluetooth Disabled")
                .setMessage("Please enable Bluetooth in your device settings")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        
        // Get paired devices
        val pairedDevices = bluetoothAdapter.bondedDevices.toList()
        
        if (pairedDevices.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("No Paired Devices")
                .setMessage("Please pair your CDI device in Bluetooth settings first")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        
        // Create custom adapter for device list
        val adapter = BluetoothDeviceAdapter(context, pairedDevices)
        
        AlertDialog.Builder(context)
            .setTitle("Select Bluetooth Device")
            .setAdapter(adapter) { _, which ->
                onDeviceSelected(pairedDevices[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    @SuppressLint("MissingPermission")
    private class BluetoothDeviceAdapter(
        context: Context,
        private val devices: List<BluetoothDevice>
    ) : ArrayAdapter<BluetoothDevice>(context, 0, devices) {
        
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            
            val device = devices[position]
            val text1 = view.findViewById<TextView>(android.R.id.text1)
            val text2 = view.findViewById<TextView>(android.R.id.text2)
            
            text1.text = device.name ?: "Unknown Device"
            text2.text = device.address
            
            return view
        }
    }
}
package com.tuner.cdituner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tuner.cdituner.ui.theme.CDITunerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var connectionService: ConnectionService? = null
    private var isServiceBound by mutableStateOf(false)
    private var deviceInfo by mutableStateOf<String?>(null)
    private var connectionMode by mutableStateOf(ConnectionMode.USB)
    
    enum class ConnectionMode {
        USB, BLUETOOTH
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ConnectionService.ConnectionBinder
            connectionService = binder.getService()
            isServiceBound = true
            
            // Auto-connect based on current mode
            when (connectionMode) {
                ConnectionMode.USB -> connectionService?.findAndConnectUsb()
                ConnectionMode.BLUETOOTH -> initiateBluetooth()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connectionService = null
            isServiceBound = false
        }
    }

    // Bluetooth permission launcher
    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            enableBluetooth()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required for Bluetooth connection", Toast.LENGTH_LONG).show()
        }
    }

    // Enable Bluetooth launcher
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            connectBluetooth()
        } else {
            Toast.makeText(this, "Bluetooth must be enabled for Bluetooth connection", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        handleIntent(intent)
        
        setContent {
            CDITunerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppContent(
                        connectionService = connectionService,
                        connectionMode = connectionMode,
                        onConnectionModeChange = { mode ->
                            connectionMode = mode
                            when (mode) {
                                ConnectionMode.USB -> connectUsb()
                                ConnectionMode.BLUETOOTH -> initiateBluetooth()
                            }
                        },
                        deviceInfo = deviceInfo
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let {
                deviceInfo = "USB: Vendor ${it.vendorId}, Product ${it.productId}"
                // Auto-switch to USB mode when USB device is attached
                connectionMode = ConnectionMode.USB
                connectionService?.findAndConnectUsb()
            }
        }
    }

    private fun connectUsb() {
        connectionService?.findAndConnectUsb()
    }

    private fun initiateBluetooth() {
        val service = connectionService
        if (service == null) {
            Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (!service.isBluetoothAvailable()) {
            Toast.makeText(this, "Bluetooth not available on this device", Toast.LENGTH_LONG).show()
            return
        }

        // Check permissions
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
        } else if (!service.isBluetoothEnabled()) {
            enableBluetooth()
        } else {
            connectBluetooth()
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        bluetoothPermissionLauncher.launch(permissions)
    }

    private fun enableBluetooth() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBluetoothLauncher.launch(enableBtIntent)
    }

    private fun connectBluetooth() {
        lifecycleScope.launch {
            val targetDevice = connectionService?.findTargetBluetoothDevice()
            if (targetDevice != null) {
                deviceInfo = "Bluetooth: ${ConnectionService.TARGET_DEVICE_NAME}"
                connectionService?.connectToBluetooth()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "Device '${ConnectionService.TARGET_DEVICE_NAME}' not found.\n" +
                    "Please pair it first in Android Bluetooth settings with password '${ConnectionService.DEVICE_PASSWORD}'",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, ConnectionService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionService?.disconnect()
    }
}

@Composable
fun AppContent(
    connectionService: ConnectionService?,
    connectionMode: MainActivity.ConnectionMode,
    onConnectionModeChange: (MainActivity.ConnectionMode) -> Unit,
    deviceInfo: String?
) {
    // Get connection state and status
    val connectionState by connectionService?.connectionState?.collectAsState() 
        ?: remember { mutableStateOf(ConnectionService.ConnectionState.DISCONNECTED) }
    val connectionStatus by connectionService?.connectionStatus?.collectAsState() 
        ?: remember { mutableStateOf("Service not connected") }
    val connectionType by connectionService?.connectionType?.collectAsState()
        ?: remember { mutableStateOf(ConnectionService.ConnectionType.NONE) }
    
    // Get CDI data
    val cdiData by connectionService?.receivedData?.collectAsState() 
        ?: remember { mutableStateOf(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Connection mode selector
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Connection Mode",
                    style = MaterialTheme.typography.titleMedium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // USB Button
                    OutlinedButton(
                        onClick = { onConnectionModeChange(MainActivity.ConnectionMode.USB) },
                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                        colors = if (connectionMode == MainActivity.ConnectionMode.USB) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Text("USB")
                    }
                    
                    // Bluetooth Button
                    OutlinedButton(
                        onClick = { onConnectionModeChange(MainActivity.ConnectionMode.BLUETOOTH) },
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                        colors = if (connectionMode == MainActivity.ConnectionMode.BLUETOOTH) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Text("Bluetooth")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Status display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Status: ",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = connectionStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (connectionState) {
                            ConnectionService.ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                            ConnectionService.ConnectionState.ERROR -> MaterialTheme.colorScheme.error
                            ConnectionService.ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
                
                // Device info if available
                deviceInfo?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Bluetooth pairing instructions
                if (connectionMode == MainActivity.ConnectionMode.BLUETOOTH) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = "Bluetooth Setup:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "1. Pair device '${ConnectionService.TARGET_DEVICE_NAME}' in Android settings",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "2. Use password: ${ConnectionService.DEVICE_PASSWORD}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
        
        // Terminal view takes the rest of the space
        TerminalView(cdiData, modifier = Modifier.weight(1f))
    }
}

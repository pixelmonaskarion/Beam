package com.chrissytopher.beam

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.chrissytopher.beam.ui.theme.BeamTheme
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    private val strategy = Strategy.P2P_CLUSTER
    private val serviceId = "com.chrissytopher.beam"

    private lateinit var bluetoothAdapter: BluetoothAdapter

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var gyroscopeSensor: Sensor
    private lateinit var sensorManager: SensorManager

    private lateinit var myEndpointId: String
    private var bluetoothServer = false
    private lateinit var targetBTName: String

    private fun sendPayload(endpointId: String, header: Int, message: ByteArray) {
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(arrayOf(header.toByte()).toByteArray()+message))
    }

    private val receiver = object : BroadcastReceiver() {

        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            Log.d("Beam", "received broadcast")
            when(intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    Log.d("Beam", "device found")
                    // Discovery has found a device. Get the BluetoothDevice
                    // object and its info from the Intent.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }?.let {device ->
                        val rssi: Int = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        val close = isDeviceVeryClose(rssi, -50, 2.0)
                        Log.d("BluetoothDevice", "Device: ${device.name} - $rssi, close: $close")
                        Log.d("Beam Bluetooth Discovery", "found device: $device, ${device.name}")
                        if (device.name == targetBTName) {
                            Log.d("Beam", "the device is the correct device")
                        }
                    }
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == USER_FINISHED_DISCOVER_REQ) {
            Log.d("Beam", "user finished discover req: $resultCode")
            if (resultCode != RESULT_CANCELED) {
                BluetoothServerThread(bluetoothAdapter) {
                    Log.d("Beam", "connected to bluetooth")
                }.start()
            }
        }
    }

    fun isDeviceVeryClose(rssi: Int, rssiAtOneMeter: Int, pathLossExponent: Double): Boolean {
        // Convert inches to meters. 6 inches is approximately 0.1524 meters.
        val thresholdDistance = 0.1524

        // Using the path-loss model to estimate distance.
        val estimatedDistance = 10.0.pow((rssiAtOneMeter - rssi) / (10 * pathLossExponent))

        return estimatedDistance <= thresholdDistance
    }


    private val payloadCallback = object : PayloadCallback() {
        @SuppressLint("MissingPermission")
        override fun onPayloadReceived(endpointId: String, payloadRaw: Payload) {
            payloadRaw.asBytes()?.let {payload ->
                val messageHeader = payload[0].toInt()
                val message = payload.slice(1 until payload.size).toByteArray()
                if (messageHeader == ENDPOINT_EXCHANGE_HEADER) {
                    myEndpointId = message.decodeToString()
                    Log.d("Beam", "my endpoint is $myEndpointId")
                    bluetoothServer = arrayOf(endpointId, myEndpointId).apply { sort() }[0] == myEndpointId
                    sendPayload(endpointId, BLUETOOTH_NAME_EXCHANGE_HEADER, bluetoothAdapter.name.encodeToByteArray())
                }
                if (messageHeader == BLUETOOTH_NAME_EXCHANGE_HEADER) {
                    targetBTName = message.decodeToString()
                    Log.d("Beam", "target Bluetooth name $targetBTName")
                    setContent {
                        Column {
                            Text("Connected to $endpointId, negotiating bluetooth")
                            Text("Looking for connect to bluetooth from device $targetBTName")
                            Text("I am ${if (bluetoothServer) "Server" else "Client"}")
                        }
                    }
                    if (!bluetoothServer) {
                        Log.d("Beam", "starting bluetooth discovery client")
                        if (!bluetoothAdapter.startDiscovery()) {
                            Log.e("Beam", "failed to start bluetooth discovery client")
                        }
                        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
                        registerReceiver(receiver, filter)
                        sendPayload(endpointId, STARTED_BLUETOOTH_CLIENT, ByteArray(0))
                    }
                }
                if (messageHeader == STARTED_BLUETOOTH_CLIENT) {
                    if (bluetoothServer) {
                        Log.d("Beam", "starting bluetooth server")
                        val requestCode = USER_FINISHED_DISCOVER_REQ
                        val discoverableIntent: Intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                        }
                        startActivityForResult(discoverableIntent, requestCode)
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, status: PayloadTransferUpdate) {
            // Handle payload transfer status updates
        }
    }

    private var currentGyroData = arrayOf(0F,0F,0F)

    private val gyroscopeEventListener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // handle changes in sensor accuracy, if necessary
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
                currentGyroData = event.values.toTypedArray()
            }
        }
    }

    private var activeConnections = arrayListOf<String>()

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.d("Beam", "connection request from $endpointId, accepting")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.d("Beam", "connection to $endpointId succeeded")
                connectionsClient.stopDiscovery()
                connectionsClient.stopAdvertising()
                activeConnections.add(endpointId)
                sendPayload(endpointId, ENDPOINT_EXCHANGE_HEADER, endpointId.encodeToByteArray())
                setContent {
                    Text("Connected to $endpointId, negotiating bluetooth")
                }
            } else {
                Log.d("Beam", "connection to $endpointId failed, ${result.status}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            activeConnections.remove(endpointId)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d("Beam", "found endpoint $endpointId, requesting connection")
            connectionsClient.requestConnection("Beam Device", endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectionsClient = Nearby.getConnectionsClient(this)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorManager.registerListener(gyroscopeEventListener, gyroscopeSensor, SensorManager.SENSOR_DELAY_NORMAL)

        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.adapter

        setContent {
            BeamTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Greeting("Android")
                }
            }
        }

        // Start advertising
        connectionsClient.startAdvertising(
            "Beam Device", serviceId, connectionLifecycleCallback, AdvertisingOptions.Builder().setStrategy(strategy).build()
        ).addOnCompleteListener {
            Log.d("Beam", "advertise task completed $it ${it.exception} ${it.isSuccessful}")
        }

        // Start discovering
        connectionsClient.startDiscovery(
            serviceId, endpointDiscoveryCallback, DiscoveryOptions.Builder().setStrategy(strategy).build()
        ).addOnCompleteListener {
            Log.d("Beam", "discover task completed $it ${it.exception} ${it.isSuccessful}")
        }
    }

    override fun onResume() {
        super.onResume()
        connectionsClient.startAdvertising(
            "Beam Device", serviceId, connectionLifecycleCallback, AdvertisingOptions.Builder().setStrategy(strategy).build()
        ).addOnCompleteListener {
            Log.d("Beam", "advertise task completed $it ${it.exception} ${it.isSuccessful}")
        }

        // Start discovering
        connectionsClient.startDiscovery(
            serviceId, endpointDiscoveryCallback, DiscoveryOptions.Builder().setStrategy(strategy).build()
        ).addOnCompleteListener {
            Log.d("Beam", "discover task completed $it ${it.exception} ${it.isSuccessful}")
        }
        sensorManager.registerListener(gyroscopeEventListener, gyroscopeSensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onPause() {
        super.onPause()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        sensorManager.unregisterListener(gyroscopeEventListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(receiver)
            Log.d("Beam", "stopping bluetooth discovery")
        } catch (_: IllegalArgumentException) {}
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    BeamTheme {
        Greeting("Android")
    }
}
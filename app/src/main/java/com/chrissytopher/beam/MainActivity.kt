package com.chrissytopher.beam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

class MainActivity : ComponentActivity() {
    private val strategy = Strategy.P2P_CLUSTER
    private val serviceId = "com.chrissytopher.beam"

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var gyroscopeSensor: Sensor
    private lateinit var sensorManager: SensorManager

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.decodeToString()?.let {payloadText ->
                Log.d("Beam", "received message from $endpointId, $payloadText")
                if (payloadText.startsWith("G")) {
                    try {
                        val remoteGyroData = payloadText.substring(1).split(";").map { it.toFloat() }
                        val gyroDot = remoteGyroData[0]*currentGyroData[0]+remoteGyroData[1]*currentGyroData[1]+remoteGyroData[2]*currentGyroData[2]
                        if (gyroDot > 0.9) {
                            Toast.makeText(this@MainActivity, "Beam!", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
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
                activeConnections.add(endpointId)
                Thread {
                    while (activeConnections.contains(endpointId)) {
                        Log.d("Beam", "sending message to $endpointId")
                        val bytesPayload = Payload.fromBytes("G${currentGyroData[0]};${currentGyroData[1]};${currentGyroData[2]}".toByteArray(Charsets.UTF_8))
                        connectionsClient.sendPayload(endpointId, bytesPayload)
                        Thread.sleep(100)
                    }
                }.start()
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
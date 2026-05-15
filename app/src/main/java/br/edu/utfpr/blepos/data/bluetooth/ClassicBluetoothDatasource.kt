package br.edu.utfpr.blepos.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.util.*

class ClassicBluetoothDatasource(
    private val context: Context,
    private val sppUuid: UUID,
    private val deviceName: String,
    private val onPacketReceived: (ByteArray) -> Unit,
    private val onStatusChanged: (String) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit
) {
    private val bluetoothAdapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var classicSocket: BluetoothSocket? = null
    private var isRunning = false

    @SuppressLint("MissingPermission")
    fun connect() {
        onStatusChanged("Status: procurando Clássico...")
        val device = bluetoothAdapter?.bondedDevices?.firstOrNull { it.name == deviceName }

        if (device == null) {
            onStatusChanged("Status: pareie o ESP32 primeiro")
            return
        }

        Thread {
            try {
                isRunning = true
                classicSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                classicSocket?.connect()
                onConnectionStateChanged(true)
                onStatusChanged("Status: Clássico conectado")

                val input = classicSocket!!.inputStream
                val buffer = ByteArray(45)
                while (isRunning) {
                    var bytesLidos = 0
                    while (bytesLidos < 45) {
                        val lidos = input.read(buffer, bytesLidos, 45 - bytesLidos)
                        if (lidos == -1) throw IOException("Desconectado")
                        bytesLidos += lidos
                    }
                    onPacketReceived(buffer.copyOf())
                }
            } catch (e: Exception) {
                disconnect()
                onStatusChanged("Clássico desconectado")
            }
        }.start()
    }

    fun disconnect() {
        isRunning = false
        onConnectionStateChanged(false)
        try {
            classicSocket?.close()
        } catch (_: Exception) {}
        classicSocket = null
    }
}

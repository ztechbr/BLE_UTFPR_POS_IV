package br.edu.utfpr.blepos.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.*

class BleDatasource(
    private val context: Context,
    private val serviceUuid: UUID,
    private val characteristicUuid: UUID,
    private val onPacketReceived: (ByteArray) -> Unit,
    private val onStatusChanged: (String) -> Unit,
    private val onRssiUpdate: (Int) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit
) {
    private val bluetoothAdapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var bleGatt: BluetoothGatt? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isScanning = false

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatusChanged("Status: BLE conectado")
                gatt.requestMtu(64)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onConnectionStateChanged(false)
                onStatusChanged("Status: BLE desconectado")
                gatt.close()
                bleGatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            onStatusChanged("Status: BLE MTU negociado: $mtu")
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onConnectionStateChanged(false)
                onStatusChanged("Erro: falha ao descobrir serviços BLE")
                return
            }

            val service = gatt.getService(serviceUuid)
            if (service == null) {
                onConnectionStateChanged(false)
                onStatusChanged("Erro: SERVICE_UUID não encontrado")
                return
            }

            val characteristic = service.getCharacteristic(characteristicUuid)
            if (characteristic == null) {
                onConnectionStateChanged(false)
                onStatusChanged("Erro: CHARACTERISTIC_UUID não encontrada")
                return
            }

            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }
            onStatusChanged("Status: habilitando notificações BLE...")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onPacketReceived(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { onPacketReceived(it) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onConnectionStateChanged(true)
                onStatusChanged("Status: BLE notificações habilitadas")
            } else {
                onConnectionStateChanged(false)
                onStatusChanged("Erro: BLE não habilitou notificações")
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onRssiUpdate(rssi)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val nomeDevice = device.name ?: ""
            val nomeScan = result.scanRecord?.deviceName ?: ""
            
            val matches = nomeDevice.contains("ESP32", ignoreCase = true) ||
                        nomeScan.contains("ESP32", ignoreCase = true) ||
                        nomeScan.contains("MONITOR", ignoreCase = true) ||
                        result.scanRecord?.serviceUuids?.any { it.uuid == serviceUuid } == true

            if (matches) {
                stopScan()
                onStatusChanged("Status: BLE encontrado: ${nomeScan.ifBlank { nomeDevice.ifBlank { device.address } }}")
                conectarGatt(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            onStatusChanged("Erro no scan BLE: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (isScanning) return

        // Garante que qualquer conexão anterior seja fechada antes de um novo scan
        disconnect()

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            onStatusChanged("Erro: Bluetooth desativado")
            return
        }

        bluetoothAdapter.cancelDiscovery()

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            onStatusChanged("Erro: scanner BLE indisponível")
            return
        }

        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        onStatusChanged("Status: procurando BLE...")
        isScanning = true
        scanner.startScan(null, settings, scanCallback)
        
        mainHandler.postDelayed({
            if (isScanning) {
                stopScan()
                onStatusChanged("BLE não encontrado. Verifique advertising.")
            }
        }, 15000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (isScanning) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun conectarGatt(device: BluetoothDevice) {
        // Evita vazamento fechando qualquer conexão existente antes de abrir uma nova
        bleGatt?.let {
            it.disconnect()
            it.close()
        }
        bleGatt = null
        
        onStatusChanged("Status: conectando GATT...")
        bleGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun readRssi() {
        bleGatt?.readRemoteRssi()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        bleGatt?.let { gatt ->
            gatt.disconnect()
            gatt.close()
        }
        bleGatt = null
        onConnectionStateChanged(false)
        onStatusChanged("Status: BLE desconectado")
    }
}

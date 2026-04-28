package br.edu.utfpr.blepos

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

/*
 * --- RESUMO DAS TECNOLOGIAS BLUETOOTH NESTE APP ---
 *
 * 1. BLUETOOTH CLÁSSICO (RFCOMM):
 *    - É como se fosse um cabo serial invisível (RS232).
 *    - Ideal para fluxo contínuo de dados. A gente abre um "Socket" e fica lendo o que vem.
 *    - Gasta mais bateria, mas é muito estável para mandar JSONs grandes de uma vez.
 *    - Fluxo contínuo via Socket.
 *
 * 2. BLUETOOTH LOW ENERGY (BLE):
 *    - Funciona por "eventos". A gente não fica a ler o tempo inteiro, o Android avisa quando o valor muda.
 *    - É bem mais econômico, mas chato de configurar (precisa negociar MTU e Descritores).
 *    - O fluxo aqui é: Conectar -> Negociar pacote (MTU) -> Descobrir Serviços -> Habilitar Notificações.
 *    - Baseado em eventos e notificações GATT.
 * */

class BluetoothViewModel(application: Application) : AndroidViewModel(application) {

    // Estados da tela
    var status by mutableStateOf("Status: desconectado")
        private set

    // Valores individuais para a tabela profissional
    var temperatura by mutableStateOf("--")
        private set
    var umidade by mutableStateOf("--")
        private set
    var tensao by mutableStateOf("--")
        private set
    var corrente by mutableStateOf("--")
        private set
    var potencia by mutableStateOf("--")
        private set

    var bleConectado by mutableStateOf(false)
        private set

    var btClassicoConectado by mutableStateOf(false)
        private set

    var ultimaAtualizacao by mutableStateOf("")
        private set

    // Propriedade para saber se há alguma comunicação ativa
    val estaComunicando: Boolean get() = bleConectado || btClassicoConectado

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = application.getSystemService(Application.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var bleGatt: BluetoothGatt? = null
    private var bufferBle = ""
    private var classicSocket: BluetoothSocket? = null

    private val serviceUuid = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val characteristicUuid = UUID.fromString("abcd1234-1234-1234-1234-abcdef123456")
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val classicDeviceName = "ESP32_MONITOR_BT"

    @SuppressLint("MissingPermission")
    fun toggleBleConnection() {
        if (bleConectado) desconectarBle() else conectarBle()
    }

    @SuppressLint("MissingPermission")
    fun toggleClassicConnection() {
        if (btClassicoConectado) desconectarBluetoothClassico() else conectarBluetoothClassico()
    }

    @SuppressLint("MissingPermission")
    private fun conectarBle() {
        status = "Status: procurando BLE..."
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val nomeEncontrado = device.name ?: result.scanRecord?.deviceName ?: ""

                if (nomeEncontrado.contains("ESP32", ignoreCase = true)) {
                    scanner.stopScan(this)
                    status = "Status: BLE encontrado"
                    conectarGatt(device)
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun conectarGatt(device: BluetoothDevice) {
        bleGatt?.close()
        status = "Status: conectando GATT..."
        bleGatt = device.connectGatt(getApplication(), false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun desconectarBle() {
        bleGatt?.disconnect()
        bleGatt?.close()
        bleGatt = null
        bleConectado = false
        status = "Status: BLE desconectado"
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                this@BluetoothViewModel.status = "Status: BLE conectado"
                Thread.sleep(600)
                gatt.requestMtu(100)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                bleConectado = false
                this@BluetoothViewModel.status = "Status: BLE desconectado"
                gatt.close()
                bleGatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(serviceUuid)
                val characteristic = service?.getCharacteristic(characteristicUuid)

                if (characteristic != null) {
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
                        bleConectado = true
                        this@BluetoothViewModel.status = "Status: BLE recebendo dados"
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val texto = String(value, Charsets.UTF_8)
            processarDadosBle(texto)
        }
    }

    private fun processarDadosBle(texto: String) {
        bufferBle += texto
        if (bufferBle.contains("\n")) {
            val mensagem = bufferBle.substringBefore("\n")
            bufferBle = bufferBle.substringAfter("\n", "")
            atualizarDadosNaTela(mensagem)
        }
    }

    @SuppressLint("MissingPermission")
    private fun conectarBluetoothClassico() {
        status = "Status: procurando Clássico..."
        val device = bluetoothAdapter?.bondedDevices?.firstOrNull { it.name == classicDeviceName }

        if (device == null) {
            status = "Status: pareie o ESP32 primeiro"
            return
        }

        Thread {
            try {
                classicSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                classicSocket?.connect()
                btClassicoConectado = true
                status = "Status: Clássico conectado"

                val reader = BufferedReader(InputStreamReader(classicSocket!!.inputStream))
                while (btClassicoConectado) {
                    val linha = reader.readLine()
                    if (linha != null) {
                        atualizarDadosNaTela(linha)
                    }
                }
            } catch (_: Exception) {
                btClassicoConectado = false
                status = "Clássico desconectado"
            }
        }.start()
    }

    private fun desconectarBluetoothClassico() {
        btClassicoConectado = false
        try { 
            classicSocket?.close() 
        } catch (_: Exception) { }
        classicSocket = null
        status = "Status: Clássico desconectado"
    }

    private fun atualizarDadosNaTela(jsonTexto: String) {
        try {
            val json = JSONObject(jsonTexto)
            temperatura = "${json.optDouble("temp", 0.0)} °C"
            umidade = "${json.optDouble("hum", 0.0)} %"
            tensao = "${json.optDouble("voltage", 0.0)} V"
            corrente = "${json.optDouble("current", 0.0)} mA"
            potencia = "${json.optDouble("power", 0.0)} mW"
            
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            ultimaAtualizacao = "Última atualização às ${sdf.format(Date())}"
            
        } catch (_: Exception) {
            // Se não for JSON, não atualiza os campos individuais
        }
    }

    override fun onCleared() {
        super.onCleared()
        desconectarBle()
        desconectarBluetoothClassico()
    }
}

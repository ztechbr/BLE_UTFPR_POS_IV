package br.edu.utfpr.blepos

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.annotation.SuppressLint
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var btnBle: Button
    private lateinit var btnClassic: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtDados: TextView

    private var bleGatt: BluetoothGatt? = null
    private var bufferBle = ""
    private var classicSocket: BluetoothSocket? = null
    private var bleConectado = false
    private var btClassicoConectado = false

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private val bleDeviceName = "ESP32_MONITOR_BLE"
    private val classicDeviceName = "ESP32_MONITOR_BT"

    private val serviceUuid =
        UUID.fromString("12345678-1234-1234-1234-1234567890ab")

    private val characteristicUuid =
        UUID.fromString("abcd1234-1234-1234-1234-abcdef123456")

    private val sppUuid =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnBle = findViewById(R.id.btnBle)
        btnClassic = findViewById(R.id.btnClassic)
        txtStatus = findViewById(R.id.txtStatus)
        txtDados = findViewById(R.id.txtDados)

        pedirPermissoes()

        btnBle.setOnClickListener {
            if (bleConectado) {
                desconectarBle()
            } else {
                conectarBle()
            }
        }

        btnClassic.setOnClickListener {
            if (btClassicoConectado) {
                desconectarBluetoothClassico()
            } else {
                conectarBluetoothClassico()
            }
        }
    }

    private fun pedirPermissoes() {

        val permissoes = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissoes.add(Manifest.permission.BLUETOOTH_SCAN)
            permissoes.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        ActivityCompat.requestPermissions(
            this,
            permissoes.toTypedArray(),
            100
        )
    }

    private fun temPermissao(): Boolean {

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val scanOk = ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

            val connectOk = ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

            return scanOk && connectOk
        }

        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // -------------------------------------------------------------------------
    // BLE
    // -------------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    private fun conectarBle() {
        if (!temPermissao()) {
            pedirPermissoes()
            return
        }

        txtStatus.text = "Status: procurando BLE..."

        val scanner = bluetoothAdapter?.bluetoothLeScanner

        scanner?.startScan(object : ScanCallback() {

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device

                val nomeDevice = device.name
                val nomeScan = result.scanRecord?.deviceName

                val nomeEncontrado = nomeDevice ?: nomeScan ?: ""

                if (nomeEncontrado.contains("ESP32", ignoreCase = true)) {
                    scanner.stopScan(this)

                    runOnUiThread {
                        txtStatus.text = "Status: BLE encontrado: $nomeEncontrado"
                    }

                    conectarGatt(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                runOnUiThread {
                    txtStatus.text = "Erro no scan BLE: $errorCode"
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun conectarGatt(device: BluetoothDevice) {
        if (!temPermissao()) {
            pedirPermissoes()
            return
        }

        bleGatt?.close()
        bleGatt = null

        runOnUiThread {
            txtStatus.text = "Status: conectando GATT BLE..."
        }

        bleGatt = device.connectGatt(
            this,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }
    @SuppressLint("MissingPermission")
    private fun desconectarBle() {
        bleGatt?.disconnect()
        bleGatt?.close()
        bleGatt = null

        bleConectado = false

        runOnUiThread {
            txtStatus.text = "Status: BLE desconectado pelo usuario"
            btnBle.text = "Conectar via BLE"
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {

                runOnUiThread {
                    txtStatus.text = "Status: BLE conectado. Procurando servicos..."
                }

                Thread.sleep(600)

                // Solicita aumento do pacote BLE
                gatt.requestMtu(100)

                Thread.sleep(300)

                // Agora busca os serviços
                gatt.discoverServices()

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {

                bleConectado = false

                runOnUiThread {
                    txtStatus.text = "Status: BLE desconectado. Codigo: $status"
                    btnBle.text = "Conectar via BLE"
                }

                gatt.close()
                bleGatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {

            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    txtStatus.text = "Erro ao descobrir servicos BLE: $status"
                    btnBle.text = "Conectar via BLE"
                }

                bleConectado = false
                return
            }

            val service = gatt.getService(serviceUuid)

            if (service == null) {
                runOnUiThread {
                    txtStatus.text = "Servico BLE nao encontrado"
                    btnBle.text = "Conectar via BLE"
                }

                bleConectado = false
                return
            }

            val characteristic = service.getCharacteristic(characteristicUuid)

            if (characteristic == null) {
                runOnUiThread {
                    txtStatus.text = "Caracteristica BLE nao encontrada"
                    btnBle.text = "Conectar via BLE"
                }

                bleConectado = false
                return
            }

            gatt.setCharacteristicNotification(characteristic, true)

            val descriptor = characteristic.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )

            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }

            bleConectado = true

            runOnUiThread {
                txtStatus.text = "Status: BLE conectado e recebendo dados..."
                btnBle.text = "Desconectar BLE"
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val texto = characteristic.value.toString(Charsets.UTF_8)

            bufferBle += texto

            if (bufferBle.contains("\n")) {
                val mensagemCompleta = bufferBle.substringBefore("\n")
                bufferBle = bufferBle.substringAfter("\n", "")

                atualizarTela(mensagemCompleta)
            }
        }
    }
    // -------------------------------------------------------------------------
    // BLUETOOTH CLASSICO
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun conectarBluetoothClassico() {
        if (!temPermissao()) {
            pedirPermissoes()
            return
        }

        txtStatus.text = "Status: procurando Bluetooth clássico..."

        val device = bluetoothAdapter
            ?.bondedDevices
            ?.firstOrNull { it.name == classicDeviceName }

        if (device == null) {
            txtStatus.text =
                "Status: pareie primeiro com ESP32_MONITOR_BT nas configurações do Android"
            return
        }

        Thread {
            try {
                classicSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                classicSocket?.connect()

                btClassicoConectado = true

                runOnUiThread {
                    txtStatus.text = "Status: Bluetooth clássico conectado"
                    btnClassic.text = "Desconectar Bluetooth Clássico"
                }

                val reader = BufferedReader(
                    InputStreamReader(classicSocket!!.inputStream)
                )

                while (btClassicoConectado) {
                    val linha = reader.readLine()

                    if (linha != null) {
                        atualizarTela(linha)
                    }
                }

            } catch (e: Exception) {
                btClassicoConectado = false
                classicSocket = null

                runOnUiThread {
                    txtStatus.text = "Bluetooth clássico desconectado"
                    btnClassic.text = "Conectar via Bluetooth Clássico"
                }
            }
        }.start()
    }

    private fun desconectarBluetoothClassico() {
        try {
            btClassicoConectado = false
            classicSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        classicSocket = null

        runOnUiThread {
            txtStatus.text = "Status: Bluetooth clássico desconectado pelo usuário"
            btnClassic.text = "Conectar via Bluetooth Clássico"
        }
    }

    // -------------------------------------------------------------------------
    // ATUALIZAR TELA
    // -------------------------------------------------------------------------

    private fun atualizarTela(jsonTexto: String) {
        runOnUiThread {
            try {
                val json = JSONObject(jsonTexto)

                val temp = json.getDouble("temp")
                val hum = json.getDouble("hum")
                val voltage = json.getDouble("voltage")
                val current = json.getDouble("current")
                val power = json.getDouble("power")

                txtDados.text = """
                    Temperatura: $temp °C
                    Umidade: $hum %
                    Tensão: $voltage V
                    Corrente: $current mA
                    Potência: $power mW
                """.trimIndent()

            } catch (e: Exception) {
                txtDados.text = jsonTexto
            }
        }
    }
}
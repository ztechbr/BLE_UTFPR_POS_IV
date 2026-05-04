package br.edu.utfpr.blepos

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
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
 *    - É bem mais econômico, porem precisa negociar MTU e Descritores.
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
    // Diagnostico recebido do ESP32
    var diagnosticoSensores by mutableStateOf("Diagnóstico: aguardando dados")
        private set
    var statusApi by mutableStateOf("API: aguardando envio")
        private set
    // Controle manual do envio para API
    var apiEnvioAtivo by mutableStateOf(true)
        private set
    var ultimaAtualizacaoApi by mutableStateOf("")
        private set
    // Watchdog da comunicação com ESP32
    var ultimaAtualizacaoEsp32 by mutableStateOf("")
        private set
    var bleConectado by mutableStateOf(false)
        private set
    var btClassicoConectado by mutableStateOf(false)
        private set
    private var ultimoRecebimentoEsp32Ms = 0L
    private val timeoutComunicacaoEsp32Ms = 60_000L

    private val watchdogHandler = Handler(Looper.getMainLooper())

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val agora = System.currentTimeMillis()

            if (ultimoRecebimentoEsp32Ms > 0 &&
                agora - ultimoRecebimentoEsp32Ms > timeoutComunicacaoEsp32Ms
            ) {
                status = "Erro de Comunicação com ESP32 (watchdog)"
                bleConectado = false
                btClassicoConectado = false
                diagnosticoSensores = "Diagnóstico: erro de comunicação"

                temperatura = "---"
                umidade = "---"
                tensao = "---"
                corrente = "---"
                potencia = "---"

                ultimaAtualizacaoEsp32 = "Sem atualização do ESP32 há mais de 60 s"
            }

            watchdogHandler.postDelayed(this, 5000)
        }
    }
    // Inicia watchdog quando o ViewModel é criado
    init {
        watchdogHandler.postDelayed(watchdogRunnable, 5000)
    }



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
    // Configuração da API REST
    private val apiUrl = "https://api-sensores.ztechnologies.io/leituras"
    private val httpClient = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Evita enviar para a API a cada 1 segundo.
    // Para teste, vamos enviar no máximo a cada 10 segundos.
    private var ultimoEnvioApi = 0L
    private val intervaloEnvioApiMs = 10_000L

    @SuppressLint("MissingPermission")
    fun toggleBleConnection() {
        if (bleConectado) desconectarBle() else conectarBle()
    }

    @SuppressLint("MissingPermission")
    fun toggleClassicConnection() {
        if (btClassicoConectado) desconectarBluetoothClassico() else conectarBluetoothClassico()
    }
    // Liga/desliga envio para API
    fun toggleEnvioApi() {
        apiEnvioAtivo = !apiEnvioAtivo

        statusApi = if (apiEnvioAtivo) {
            "API: envio habilitado"
        } else {
            "API: envio desabilitado"
        }
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
                // Aumenta o MTU para permitir JSON maior via BLE
                gatt.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                bleConectado = false
                this@BluetoothViewModel.status = "Status: BLE desconectado"
                gatt.close()
                bleGatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            this@BluetoothViewModel.status = "Status: BLE MTU negociado: $mtu"
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
                        this@BluetoothViewModel.status = "Status: habilitando notificações BLE..."
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val texto = String(value, Charsets.UTF_8)
            processarDadosBle(texto)
        }
        // Compatibilidade BLE para versões antigas do Android
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val texto = characteristic.value?.toString(Charsets.UTF_8) ?: ""
            processarDadosBle(texto)
        }
        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                bleConectado = true
                this@BluetoothViewModel.status = "Status: BLE notificações habilitadas"
            } else {
                bleConectado = false
                this@BluetoothViewModel.status = "Erro: BLE não habilitou notificações"
            }
        }
    }

    private fun processarDadosBle(texto: String) {
        status = "BLE pacote recebido: ${texto.length} bytes"

        bufferBle += texto

        // O ESP32 envia JSON. Normalmente termina com "\n",
        // mas em BLE o pacote pode chegar fragmentado.
        // Então aceitamos também o fechamento "}" como fim de mensagem.
        if (bufferBle.contains("\n") || bufferBle.contains("}")) {

            val mensagem = if (bufferBle.contains("\n")) {
                bufferBle.substringBefore("\n")
            } else {
                bufferBle.substringBefore("}") + "}"
            }

            bufferBle = if (bufferBle.contains("\n")) {
                bufferBle.substringAfter("\n", "")
            } else {
                bufferBle.substringAfter("}", "")
            }

            status = "Status: BLE recebendo dados"
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

            // INICIO ALTERACAO - Lê valores enviados pelo ESP32
            val tempValor = json.optDouble("temp", -9999.0)
            val humValor = json.optDouble("hum", -9999.0)
            val voltageValor = json.optDouble("voltage", -9999.0)
            val currentValor = json.optDouble("current", -9999.0)
            val powerValor = json.optDouble("power", -9999.0)
            // FIM ALTERACAO

            // INICIO ALTERACAO - Lê flags de erro enviadas pelo ESP32
            val tempErro = json.optInt("tempErro", 0) == 1
            val humErro = json.optInt("humErro", 0) == 1
            val inaErro = json.optInt("inaErro", 0) == 1
            val oledErro = json.optInt("oledErro", 0) == 1
            // FIM ALTERACAO

            // INICIO ALTERACAO - Mostra "---" quando o valor não é válido
            temperatura = if (tempErro || tempValor == -9999.0) {
                "---"
            } else {
                "%.1f °C".format(Locale.US, tempValor)
            }

            umidade = if (humErro || humValor == -9999.0) {
                "---"
            } else {
                "%.0f %%".format(Locale.US, humValor)
            }

            if (inaErro || voltageValor == -9999.0 || currentValor == -9999.0 || powerValor == -9999.0) {
                tensao = "---"
                corrente = "---"
                potencia = "---"
            } else {
                tensao = "%.2f V".format(Locale.US, voltageValor)
                corrente = "%.1f mA".format(Locale.US, currentValor)
                potencia = "%.1f mW".format(Locale.US, powerValor)
            }
            // FIM ALTERACAO

            // INICIO ALTERACAO - Mensagem de diagnóstico
            val falhas = mutableListOf<String>()
            if (tempErro) falhas.add("temperatura")
            if (humErro) falhas.add("umidade")
            if (inaErro) falhas.add("INA219")
            if (oledErro) falhas.add("OLED")

            diagnosticoSensores = if (falhas.isEmpty()) {
                "Diagnóstico: sensores OK"
            } else {
                "Diagnóstico: falha em ${falhas.joinToString(", ")}"
            }
            // FIM ALTERACAO

            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            ultimaAtualizacaoEsp32 = "Última atualização com ESP32 às ${sdf.format(Date())}"
            ultimoRecebimentoEsp32Ms = System.currentTimeMillis()

            // Envia leitura para API REST
            enviarLeituraParaApi(
                tempValor = tempValor,
                humValor = humValor,
                voltageValor = voltageValor,
                currentValor = currentValor,
                powerValor = powerValor,
                tempErro = tempErro,
                humErro = humErro,
                inaErro = inaErro
            )

        } catch (e: Exception) {
            diagnosticoSensores = "Diagnóstico: erro ao interpretar JSON"
        }
    }
    // Envia leitura recebida do ESP32 para API REST
    private fun enviarLeituraParaApi(
        tempValor: Double,
        humValor: Double,
        voltageValor: Double,
        currentValor: Double,
        powerValor: Double,
        tempErro: Boolean,
        humErro: Boolean,
        inaErro: Boolean
    ) {
        // Não envia para API se o botão estiver desligado
        if (!apiEnvioAtivo) {
            statusApi = "API: envio desligado"
            return
        }

        val agoraMs = System.currentTimeMillis()

        // Evita gravar uma leitura por segundo no banco durante os testes.
        if (agoraMs - ultimoEnvioApi < intervaloEnvioApiMs) {
            return
        }

        ultimoEnvioApi = agoraMs

        val dataFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val horaFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        val tempApi = if (tempErro) -9999.0 else tempValor
        val humApi = if (humErro) -9999.0 else humValor

        val tensaoApi = if (inaErro) -9999.0 else voltageValor
        val correnteApi = if (inaErro) -9999.0 else currentValor
        val potenciaApi = if (inaErro) -9999.0 else powerValor

        // JSON no mesmo formato testado no PowerShell.
        val jsonApi = JSONObject().apply {
            put("codplantacao", "PLANTDEMO")
            put("codleitura", "LEITDEMO")

            // Latitude/longitude fixas para teste, perto da região de Campo Largo/Curitiba.
            put("lat", -25.459)
            put("lon", -49.530)

            put("dataleit", dataFormat.format(Date()))
            put("horaleit", horaFormat.format(Date()))

            // Conforme orientação do grupo: duplicar temperatura e umidade.
            put("temp_solo", tempApi)
            put("temp_ar", tempApi)
            put("umid_solo", humApi)
            put("umid_ar", humApi)

            // Valores chumbados temporários até chegar sensor de luz, chuva e folha.
            put("luz", 100.0)
            put("chuva", 0.0)
            put("umid_folha", 10.0)

            // Comunicação OK porque o Android recebeu dados do ESP32.
            put("scomunicacao", 1.0)

            // Valores vindos do INA219, ou -9999 se houver erro.
            put("stensao", tensaoApi)
            put("scorrente", correnteApi)
            put("spotencia", potenciaApi)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonApi.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .build()

        statusApi = "API: enviando..."

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    statusApi = "API: erro no envio"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val resposta = it.body?.string() ?: ""

                    mainHandler.post {
                        statusApi = if (it.isSuccessful) {
                            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            ultimaAtualizacaoApi = "Último envio API às ${sdf.format(Date())}"
                            "API: enviado OK (${it.code})"
                        } else {
                            "API: erro HTTP ${it.code}"
                        }
                    }
                }
            }
        })
    }

    override fun onCleared() {
        super.onCleared()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        desconectarBle()
        desconectarBluetoothClassico()
    }
}

package br.edu.utfpr.blepos

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.math.pow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import br.edu.utfpr.blepos.data.api.ApiPayloadMapper
import br.edu.utfpr.blepos.data.esp32.Esp32Packet
import br.edu.utfpr.blepos.data.esp32.Esp32PacketDecoder
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
    var luximetro by mutableStateOf("--")
        private set

    var latitude by mutableStateOf("--")
        private set

    var longitude by mutableStateOf("--")
        private set

    var rssiBle by mutableStateOf("--")
        private set
    var rssiBleCelular by mutableStateOf("--")
        private set
    var distanciaApp by mutableStateOf("--")
        private set
    var bh1750Falha by mutableStateOf(false)
        private set

    var gpsFalha by mutableStateOf(false)
        private set

    var rssiFalha by mutableStateOf(false)
        private set

    // Diagnostico recebido do ESP32
    var diagnosticoSensores by mutableStateOf("...aguardando dados")
        private set
    var statusApi by mutableStateOf("API: aguardando envio")
        private set
    // Controle manual do envio para API
    var apiEnvioAtivo by mutableStateOf(true)
        private set
    var ultimaAtualizacaoApi by mutableStateOf("")
        private set
    var ultimoJsonApi by mutableStateOf("{}")
        private set

    var ultimoCodigoHttpApi by mutableStateOf("--")
        private set
    // Watchdog da comunicação com ESP32
    var ultimaAtualizacaoEsp32 by mutableStateOf("")
        private set
    var bleConectado by mutableStateOf(false)
        private set
    var btClassicoConectado by mutableStateOf(false)
        private set
    var tempFalha by mutableStateOf(false)
        private set

    var humFalha by mutableStateOf(false)
        private set

    var inaFalha by mutableStateOf(false)
        private set

    var oledFalha by mutableStateOf(false)
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
                diagnosticoSensores = "Erro de comunicação"

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
    // RSSI visto pelo aplicativo Android.
    // BLE: atualizado por bleGatt.readRemoteRssi()
    // Clássico: normalmente indisponível em conexão RFCOMM.
    private var ultimoRssiBleAppDbm: Double? = null
    private var ultimoRssiClassicoAppDbm: Double? = null

    // RSSI de referência a 1 metro e fator ambiental.
    // Fórmula: d = 10 ^ ((RSSI_ref - RSSI_medido) / (10 * n))
    var rssiRefLocal by mutableStateOf(-59.0)
        private set
    var fatorNLocal by mutableStateOf(2.0)
        private set

    fun calcularDistancia(rssiApp: Double?, fator: Double, rssiRef: Double = rssiRefLocal): Double? {
        if (rssiApp == null || fator <= 0.0) return null
        return 10.0.pow((rssiRef - rssiApp) / (10.0 * fator))
    }

    fun getUltimoRssiApp(): Double? {
        return when {
            bleConectado -> ultimoRssiBleAppDbm
            btClassicoConectado -> ultimoRssiClassicoAppDbm
            else -> null
        }
    }

    var tagAtual by mutableStateOf("--")
        private set

    fun salvarCalibracao(novoFator: Double, novoRssiRef: Double) {
        if (tagAtual != "--") {
            ApiPayloadMapper.saveCalibracaoToXml(getApplication(), tagAtual, novoFator, novoRssiRef)
            fatorNLocal = novoFator
            rssiRefLocal = novoRssiRef
        }
    }

    private val rssiHandler = Handler(Looper.getMainLooper())

    private val rssiRunnable = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (bleConectado) {
                bleGatt?.readRemoteRssi()
                rssiHandler.postDelayed(this, 5_000)
            }
        }
    }

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

//    @SuppressLint("MissingPermission")
//    private fun conectarBle() {
//        status = "Status: procurando BLE..."
//        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
//
//        scanner.startScan(object : ScanCallback() {
//            override fun onScanResult(callbackType: Int, result: ScanResult) {
//                val device = result.device
//                val nomeEncontrado = device.name ?: result.scanRecord?.deviceName ?: ""
//
//                if (nomeEncontrado.contains("ESP32", ignoreCase = true)) {
//                    scanner.stopScan(this)
//                    status = "Status: BLE encontrado"
//                    conectarGatt(device)
//                }
//            }
//        })
//    }

    @SuppressLint("MissingPermission")
    private fun conectarBle() {
        status = "Status: procurando BLE..."

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            status = "Erro: scanner BLE indisponível"
            return
        }

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val nomeDevice = device.name ?: ""
                val nomeScan = result.scanRecord?.deviceName ?: ""
                val endereco = device.address
                val rssi = result.rssi

                // RSSI visto pelo celular durante o scan BLE.
                // Este valor é inicial; depois da conexão será atualizado por readRemoteRssi().
                ultimoRssiBleAppDbm = rssi.toDouble()


                android.util.Log.d(
                    "BLE_SCAN",
                    "Encontrado nomeDevice=$nomeDevice nomeScan=$nomeScan address=$endereco rssi=$rssi"
                )

                val serviceUuids = result.scanRecord?.serviceUuids

                val encontrouUuid = serviceUuids?.any {
                    it.uuid == serviceUuid
                } == true

                val encontrouNome =
                    nomeDevice.contains("ESP32", ignoreCase = true) ||
                            nomeScan.contains("ESP32", ignoreCase = true) ||
                            nomeScan.contains("MONITOR", ignoreCase = true)

                android.util.Log.d(
                    "BLE_SCAN",
                    "UUIDs encontrados=$serviceUuids encontrouUuid=$encontrouUuid"
                )

                val achouEsp32 = encontrouNome || encontrouUuid

                if (achouEsp32) {
                    scanner.stopScan(this)

                    status = if (encontrouUuid) {
                        "Status: BLE encontrado por UUID"
                    } else {
                        "Status: BLE encontrado: ${nomeScan.ifBlank { nomeDevice }}"
                    }

                    conectarGatt(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                status = "Erro no scan BLE: $errorCode"
                android.util.Log.e("BLE_SCAN", "Scan falhou: $errorCode")
            }
        }

        scanner.startScan(scanCallback)

        mainHandler.postDelayed({
            if (!bleConectado && status.contains("procurando", ignoreCase = true)) {
                scanner.stopScan(scanCallback)
                status = "BLE não encontrado. Verifique advertising do ESP32."
            }
        }, 15_000)
    }





    @SuppressLint("MissingPermission")
    private fun conectarGatt(device: BluetoothDevice) {
        bleGatt?.close()
        status = "Status: conectando GATT..."
        bleGatt = device.connectGatt(getApplication(), false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun desconectarBle() {
        rssiHandler.removeCallbacks(rssiRunnable)

        bleGatt?.disconnect()
        bleGatt?.close()
        bleGatt = null
        bleConectado = false
        ultimoRssiBleAppDbm = null
        status = "Status: BLE desconectado"
        rssiBleCelular = "---"
        distanciaApp = "---"
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                this@BluetoothViewModel.status = "Status: BLE conectado"
                // Aumenta o MTU para acomodar o pacote de 45 bytes + 3 de cabeçalho GATT
                gatt.requestMtu(64)
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                bleConectado = false
                this@BluetoothViewModel.status = "Erro: falha ao descobrir serviços BLE"
                return
            }

            val service = gatt.getService(serviceUuid)

            if (service == null) {
                bleConectado = false
                this@BluetoothViewModel.status = "Erro: SERVICE_UUID não encontrado no ESP32"
                android.util.Log.e("BLE_GATT", "Serviço não encontrado: $serviceUuid")
                return
            }

            val characteristic = service.getCharacteristic(characteristicUuid)

            if (characteristic == null) {
                bleConectado = false
                this@BluetoothViewModel.status = "Erro: CHARACTERISTIC_UUID não encontrada"
                android.util.Log.e("BLE_GATT", "Característica não encontrada: $characteristicUuid")
                return
            }

            val notificacaoAtivada = gatt.setCharacteristicNotification(characteristic, true)

            if (!notificacaoAtivada) {
                bleConectado = false
                this@BluetoothViewModel.status = "Erro: não ativou notificação BLE"
                return
            }

            val descriptor = characteristic.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )

            if (descriptor == null) {
                bleConectado = false
                this@BluetoothViewModel.status = "Erro: descriptor CCCD não encontrado"
                android.util.Log.e("BLE_GATT", "Descriptor CCCD não encontrado")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }

            this@BluetoothViewModel.status = "Status: habilitando notificações BLE..."
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            /*
             * O ESP32 agora envia pacote binário.
             * Não podemos mais converter o ByteArray para String.
             *
             * Antes:
             *   val texto = String(value, Charsets.UTF_8)
             *
             * Agora:
             *   enviamos os bytes diretamente para o decoder.
             */
            processarPacoteBinarioEsp32(value)
        }
        // Compatibilidade BLE para versões antigas do Android
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            /*
             * Versão antiga do callback BLE, usada por algumas versões do Android.
             * Mesmo aqui, o dado recebido também é binário.
             */
            val dados = characteristic.value ?: return

            processarPacoteBinarioEsp32(dados)
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

                // Inicia leitura periódica do RSSI visto pelo celular.
                rssiHandler.removeCallbacks(rssiRunnable)
                rssiHandler.post(rssiRunnable)
            } else {
                bleConectado = false
                this@BluetoothViewModel.status = "Erro: BLE não habilitou notificações"
            }
        }
        override fun onReadRemoteRssi(
            gatt: BluetoothGatt,
            rssi: Int,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // RSSI visto pelo celular Android em relação ao ESP32
                val rssiDouble = rssi.toDouble()
                ultimoRssiBleAppDbm = rssiDouble
                rssiBleCelular = "$rssi dBm"

                // Atualiza distância em tempo real
                if (fatorNLocal > 0.0) {
                    val distancia = 10.0.pow(
                        (rssiRefLocal - rssiDouble) / (10.0 * fatorNLocal)
                    )
                    distanciaApp = "%.2f m".format(Locale.US, distancia)
                }
            }
        }
    }
//    private fun processarPacoteBinarioEsp32(bytes: ByteArray) {
//        /*
//         * Esta função recebe os bytes crus vindos do BLE.
//         *
//         * O pacote esperado tem 45 bytes:
//         * - cabeçalho 0xAA 0x55
//         * - dados dos sensores
//         * - status dos erros
//         * - CRC16 no final
//         */
//        status = "Pacote ESP32 recebido: ${bytes.size} bytes"
//
//        val packet = Esp32PacketDecoder.decode(bytes)
//
//        /*
//         * Se packet == null, o decoder rejeitou o pacote.
//         * Possíveis causas:
//         * - tamanho diferente de 45 bytes
//         * - cabeçalho diferente de 0xAA 0x55
//         * - versão do protocolo diferente de 1
//         */
//        if (packet == null) {
//            diagnosticoSensores = "Pacote ESP32 inválido"
//            return
//        }
//
//        /*
//         * Se o CRC não bater, o pacote chegou corrompido.
//         * Nesse caso não atualizamos a tela e não enviamos para API.
//         */
//        if (!packet.crcOk) {
//            diagnosticoSensores = "CRC inválido - pacote descartado"
//            return
//        }
//
//        atualizarTelaComPacoteEsp32(packet)
//    }

    private fun processarPacoteBinarioEsp32(bytes: ByteArray) {
        val hex = bytes.joinToString(" ") {
            "%02X".format(it)
        }

        status = "Pacote ESP32 recebido: ${bytes.size} bytes"

        android.util.Log.d("ESP32_BIN", "Bytes recebidos: ${bytes.size}")
        android.util.Log.d("ESP32_BIN", "HEX: $hex")

        val packet = Esp32PacketDecoder.decode(bytes)

        if (packet == null) {
            diagnosticoSensores =
                "Pacote inválido: ${bytes.size} bytes"

            android.util.Log.e(
                "ESP32_BIN",
                "Pacote inválido. Tamanho=${bytes.size}, HEX=$hex"
            )
            return
        }

        if (!packet.crcOk) {
            diagnosticoSensores = "CRC inválido - pacote descartado"

            android.util.Log.e(
                "ESP32_BIN",
                "CRC inválido. HEX=$hex"
            )
            return
        }

        atualizarTelaComPacoteEsp32(packet)
    }

    private fun atualizarTelaComPacoteEsp32(packet: Esp32Packet) {
        tagAtual = packet.tag
        // Tenta pegar do XML se houver, senão usa o padrão do ViewModel
        val calibracao = ApiPayloadMapper.getCalibracaoFromXml(getApplication(), packet.tag)
        
        if (calibracao != null) {
            fatorNLocal = calibracao.fatorN
            rssiRefLocal = calibracao.rssiRef
        }

        /*
         * Atualiza as flags usadas na tela de diagnóstico.
         */
        tempFalha = packet.tempErro
        humFalha = packet.humErro
        inaFalha = packet.inaErro
        oledFalha = packet.oledErro
        bh1750Falha = packet.bh1750Erro
        gpsFalha = packet.gpsErro
        rssiFalha = packet.rssiErro

        /*
         * Atualiza temperatura e umidade.
         */
        temperatura = packet.temp?.let {
            "%.1f °C".format(Locale.US, it)
        } ?: "---"

        umidade = packet.hum?.let {
            "%.1f %%".format(Locale.US, it)
        } ?: "---"

        /*
         * Atualiza dados do INA219.
         */
        if (packet.inaErro) {
            tensao = "---"
            corrente = "---"
            potencia = "---"
        } else {
            tensao = packet.voltage?.let {
                "%.2f V".format(Locale.US, it)
            } ?: "---"

            corrente = packet.current?.let {
                "%.1f mA".format(Locale.US, it)
            } ?: "---"

            potencia = packet.power?.let {
                "%.1f mW".format(Locale.US, it)
            } ?: "---"
        }
        /*
        * Luxímetro BH1750
        */
        luximetro = packet.lux?.let {
            "%.1f lux".format(Locale.US, it)
        } ?: "---"

        /*
         * GPS
         */
        latitude = packet.lat?.let {
            "%.6f".format(Locale.US, it)
        } ?: "---"

        longitude = packet.lon?.let {
            "%.6f".format(Locale.US, it)
        } ?: "---"

        /*
         * RSSI BLE
         */
        rssiBle = packet.rssiEsp32Dbm?.let {
            "%.1f dBm".format(Locale.US, it)
        } ?: "---"

        /*
         * Monta diagnóstico textual.
         */
        val falhas = mutableListOf<String>()

        if (packet.tempErro) falhas.add("temperatura")
        if (packet.humErro) falhas.add("umidade")
        if (packet.inaErro) falhas.add("INA219")
        if (packet.oledErro) falhas.add("OLED")
        if (packet.bh1750Erro) falhas.add("BH1750")
        if (packet.gpsErro) falhas.add("GPS")
        if (packet.rssiErro) falhas.add("RSSI")

        diagnosticoSensores = if (falhas.isEmpty()) {
            "Sensores OK - ${packet.tag} seq=${packet.seq}"
        } else {
            "Falha: ${falhas.joinToString(", ")}"
        }

        /*
         * Atualiza watchdog da comunicação.
         */
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        ultimaAtualizacaoEsp32 = "Última atualização com ESP32 às ${sdf.format(Date())}"
        ultimoRecebimentoEsp32Ms = System.currentTimeMillis()

        val tipoComunicacao = when {
            bleConectado -> 1
            btClassicoConectado -> 2
            else -> 0
        }

        enviarPacoteEsp32ParaApi(packet, tipoComunicacao)
    }
    private fun enviarPacoteEsp32ParaApi(packet: Esp32Packet, tipoComunicacao: Int) {

        // -------------------------------------------------------------------------
        // VERIFICA SE O ENVIO PARA API ESTA HABILITADO
        // -------------------------------------------------------------------------

        // Permite desligar envio para API durante testes locais.
        if (!apiEnvioAtivo) {

            // Atualiza status mostrado na interface.
            statusApi = "API: envio desligado"

            return
        }
        // -------------------------------------------------------------------------
        // CONTROLE DE TEMPO ENTRE ENVIOS
        // -------------------------------------------------------------------------

        // Tempo atual do celular em milissegundos.
        val agoraMs = System.currentTimeMillis()

        /*
         * Proteção contra envio excessivo.
         *
         * Mesmo que o ESP32 envie pacotes muito rápido,
         * o aplicativo limita o envio para a API.
         *
         * Exemplo:
         * intervaloEnvioApiMs = 10000
         * → envia no máximo 1 pacote a cada 10 segundos.
         */
        if (agoraMs - ultimoEnvioApi < intervaloEnvioApiMs) {
            return
        }

        // Atualiza instante do último envio realizado.
        ultimoEnvioApi = agoraMs


        // -------------------------------------------------------------------------
        // CONVERSAO DO PACOTE PARA JSON
        // -------------------------------------------------------------------------

        /*
         * Converte o objeto Esp32Packet em JSON.
         *
         * Esta conversão inclui:
         * - temperatura
         * - umidade
         * - tensão
         * - corrente
         * - potência
         * - RSSI
         * - GPS
         * - lux
         * - status dos sensores
         * - CRC
         */
        val appRssiDbm = when (tipoComunicacao) {
            1 -> ultimoRssiBleAppDbm
            2 -> ultimoRssiClassicoAppDbm
            else -> null
        }

        distanciaApp = if (appRssiDbm != null && fatorNLocal > 0.0) {
            val distancia = 10.0.pow(
                (rssiRefLocal - appRssiDbm) / (10.0 * fatorNLocal)
            )
            "%.2f m".format(Locale.US, distancia)
        } else {
            "---"
        }

        val jsonApi = ApiPayloadMapper.fromEsp32Packet(
            context = getApplication(),
            packet = packet,
            tipoComunicacao = tipoComunicacao,
            appRssiDbm = appRssiDbm,
            rssiReferenciaPadrao = rssiRefLocal,
            fatorNPadrao = fatorNLocal
        )


        // -------------------------------------------------------------------------
        // DEBUG / DIAGNOSTICO
        // -------------------------------------------------------------------------

        /*
         * Salva o JSON formatado para mostrar
         * na tela de diagnóstico da API.
         *
         * O parâmetro 4 adiciona indentação.
         */
        ultimoJsonApi = jsonApi.toString(4)

        // Atualiza mensagem de status da API.
        statusApi = "API: enviando pacote ESP32..."


        // -------------------------------------------------------------------------
        // MONTA CORPO HTTP JSON
        // -------------------------------------------------------------------------

        /*
         * Define Content-Type HTTP.
         *
         * application/json:
         * indica que o corpo é JSON.
         *
         * charset=utf-8:
         * codificação dos caracteres.
         */
        val mediaType = "application/json; charset=utf-8".toMediaType()

        /*
         * Converte o JSONObject em corpo HTTP.
         */
        val body = jsonApi.toString().toRequestBody(mediaType)


        // -------------------------------------------------------------------------
        // MONTA REQUISICAO HTTP
        // -------------------------------------------------------------------------

        /*
         * Cria requisição POST para a API.
         *
         * apiUrl:
         * endereço do servidor Flask/REST.
         */
        val request = Request.Builder()

            // URL da API.
            .url(apiUrl)

            // Método POST.
            .post(body)

            // Finaliza construção da requisição.
            .build()


        // -------------------------------------------------------------------------
        // ENVIO ASSINCRONO COM OKHTTP
        // -------------------------------------------------------------------------

        /*
         * enqueue():
         * envia em background sem travar a interface do Android.
         */
        httpClient.newCall(request).enqueue(object : Callback {

            // ---------------------------------------------------------------------
            // FALHA DE COMUNICACAO
            // ---------------------------------------------------------------------

            override fun onFailure(call: Call, e: IOException) {

                /*
                 * mainHandler.post():
                 * atualiza a interface na thread principal do Android.
                 */
                mainHandler.post {

                    // Formata horário atual.
                    val sdf = SimpleDateFormat(
                        "HH:mm:ss",
                        Locale.getDefault()
                    )

                    // Atualiza mensagens da tela.
                    statusApi = "API: erro no envio"

                    ultimaAtualizacaoApi =
                        "Último envio API (FALHA) às ${
                            sdf.format(Date())
                        }"

                    // Código especial para falha sem resposta HTTP.
                    ultimoCodigoHttpApi = "FALHA"
                }
            }


            // ---------------------------------------------------------------------
            // RESPOSTA DA API
            // ---------------------------------------------------------------------

            override fun onResponse(call: Call, response: Response) {

                /*
                 * use():
                 * fecha automaticamente o response depois do uso.
                 */
                response.use { resp ->

                    // Atualiza UI na thread principal.
                    mainHandler.post {

                        // Formata horário atual.
                        val sdf = SimpleDateFormat(
                            "HH:mm:ss",
                            Locale.getDefault()
                        )

                        /*
                         * isSuccessful:
                         * true para HTTP 200-299.
                         */
                        ultimaAtualizacaoApi =
                            if (resp.isSuccessful) {

                                "Último envio API (OK) às ${
                                    sdf.format(Date())
                                }"

                            } else {

                                "Último envio API (ERRO ${resp.code}) às ${
                                    sdf.format(Date())
                                }"
                            }


                        // Guarda código HTTP recebido.
                        // Exemplo:
                        // 200
                        // 201
                        // 400
                        // 500
                        ultimoCodigoHttpApi = resp.code.toString()


                        // Atualiza status mostrado na interface.
                        statusApi =
                            if (resp.isSuccessful) {

                                "API: enviado OK (${resp.code})"

                            } else {

                                "API: erro HTTP ${resp.code}"
                            }
                    }
                }
            }
        })
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

                val input = classicSocket!!.inputStream
                val buffer = ByteArray(45)

                while (btClassicoConectado) {
                    var bytesLidos = 0

                    while (bytesLidos < 45) {
                        val lidos = input.read(buffer, bytesLidos, 45 - bytesLidos)

                        if (lidos == -1) {
                            throw IOException("Bluetooth clássico desconectado")
                        }

                        bytesLidos += lidos
                    }
                    try {
                        ultimoRssiClassicoAppDbm =
                            bluetoothAdapter?.getRemoteDevice(device.address)?.bondState?.toDouble()
                    } catch (_: Exception) {
                    }
                    processarPacoteBinarioEsp32(buffer.copyOf())
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
            tempFalha = tempErro
            humFalha = humErro
            inaFalha = inaErro
            oledFalha = oledErro

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
                "Sensores OK"
            } else {
                "Sensores com Falha"
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
            diagnosticoSensores = "Erro ao interpretar JSON"
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

        // Mesmo com erro de sensor, continua enviando para a API.
        // Apenas substitui o valor inválido por -9999.
        val tempApi = if (tempErro || tempValor == -9999.0) -9999.0 else tempValor
        val humApi = if (humErro || humValor == -9999.0) -9999.0 else humValor

        val tensaoApi = if (inaErro || voltageValor == -9999.0) -9999.0 else voltageValor
        val correnteApi = if (inaErro || currentValor == -9999.0) -9999.0 else currentValor
        val potenciaApi = if (inaErro || powerValor == -9999.0) -9999.0 else powerValor

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
        // Guarda o último JSON enviado para exibir na tela de diagnóstico da API
        ultimoJsonApi = jsonApi.toString(4)

        // Mostra na tela que o envio será feito mesmo com sensor em erro.
        statusApi = if (tempErro || humErro || inaErro) {
            "API: enviando com sensor em falha"
        } else {
            "API: enviando..."
        }
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonApi.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                    statusApi = "API: erro no envio"
                    ultimaAtualizacaoApi = "Último envio API (FALHA) às ${sdf.format(Date())}"
                    ultimoCodigoHttpApi = "FALHA"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val resposta = resp.body?.string() ?: ""

                    mainHandler.post {
                        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                        // SEMPRE atualiza o horário (independente de erro ou sucesso)
                        ultimaAtualizacaoApi = if (resp.isSuccessful) {
                            "Último envio API (OK) às ${sdf.format(Date())}"
                        } else {
                            "Último envio API (ERRO ${resp.code}) às ${sdf.format(Date())}"
                        }

                        ultimoCodigoHttpApi = resp.code.toString()

                        statusApi = if (resp.isSuccessful) {
                            "API: enviado OK (${resp.code})"
                        } else {
                            "API: erro HTTP ${resp.code}"
                        }
                    }
                }
            }
        })
    }

    override fun onCleared() {
        super.onCleared()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        rssiHandler.removeCallbacks(rssiRunnable)
        desconectarBle()
        desconectarBluetoothClassico()
    }
}

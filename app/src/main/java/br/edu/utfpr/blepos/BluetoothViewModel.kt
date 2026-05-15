package br.edu.utfpr.blepos

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import br.edu.utfpr.blepos.data.api.ApiDatasource
import br.edu.utfpr.blepos.data.api.ApiPayloadMapper
import br.edu.utfpr.blepos.data.bluetooth.BleDatasource
import br.edu.utfpr.blepos.data.bluetooth.ClassicBluetoothDatasource
import br.edu.utfpr.blepos.data.esp32.Esp32Packet
import br.edu.utfpr.blepos.data.esp32.Esp32PacketDecoder
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.pow

class BluetoothViewModel(application: Application) : AndroidViewModel(application) {

    // Estados da tela
    var status by mutableStateOf("Status: desconectado")
        private set
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
    var apiEnvioAtivo by mutableStateOf(true)
        private set
    var ultimaAtualizacaoApi by mutableStateOf("")
        private set
    var ultimoJsonApi by mutableStateOf("{}")
        private set
    var ultimoCodigoHttpApi by mutableStateOf("--")
        private set
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
    private val watchdogRunnable: Runnable = object : Runnable {
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

    // Datasources
    private val bleDatasource = BleDatasource(
        context = application,
        serviceUuid = UUID.fromString("12345678-1234-1234-1234-1234567890ab"),
        characteristicUuid = UUID.fromString("abcd1234-1234-1234-1234-abcdef123456"),
        onPacketReceived = { processarPacoteBinarioEsp32(it) },
        onStatusChanged = { status = it },
        onRssiUpdate = { updateRssiBle(it) },
        onConnectionStateChanged = { connected ->
            bleConectado = connected
            if (connected) {
                rssiHandler.removeCallbacks(rssiRunnable)
                rssiHandler.post(rssiRunnable)
            }
        }
    )

    private val classicDatasource = ClassicBluetoothDatasource(
        context = application,
        sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
        deviceName = "ESP32_MONITOR_BT",
        onPacketReceived = { processarPacoteBinarioEsp32(it) },
        onStatusChanged = { status = it },
        onConnectionStateChanged = { btClassicoConectado = it }
    )

    private val apiDatasource = ApiDatasource(
        apiUrl = "https://api-sensores.ztechnologies.io/leituras",
        httpClient = OkHttpClient()
    )

    private val rssiHandler = Handler(Looper.getMainLooper())
    private val rssiRunnable: Runnable = object : Runnable {
        override fun run() {
            if (bleConectado) {
                bleDatasource.readRssi()
                rssiHandler.postDelayed(this, 5_000)
            }
        }
    }

    init {
        watchdogHandler.postDelayed(watchdogRunnable, 5000)
    }

    val estaComunicando: Boolean get() = bleConectado || btClassicoConectado

    private var ultimoRssiBleAppDbm: Double? = null
    private var ultimoRssiClassicoAppDbm: Double? = null

    var rssiRefLocal by mutableStateOf(-59.0)
        private set
    var fatorNLocal by mutableStateOf(2.0)
        private set
    var tagAtual by mutableStateOf("--")
        private set

    fun toggleBleConnection() {
        if (bleConectado) {
            bleDatasource.disconnect()
            rssiHandler.removeCallbacks(rssiRunnable)
            ultimoRssiBleAppDbm = null
            rssiBleCelular = "--"
            distanciaApp = "--"
        } else {
            bleDatasource.startScan()
        }
    }

    fun toggleClassicConnection() {
        if (btClassicoConectado) {
            classicDatasource.disconnect()
            ultimoRssiClassicoAppDbm = null
        } else {
            classicDatasource.connect()
        }
    }

    fun toggleEnvioApi() {
        apiEnvioAtivo = !apiEnvioAtivo
        statusApi = if (apiEnvioAtivo) "API: envio habilitado" else "API: envio desabilitado"
    }

    private fun updateRssiBle(rssi: Int) {
        val rssiDouble = rssi.toDouble()
        ultimoRssiBleAppDbm = rssiDouble
        rssiBleCelular = "$rssi dBm"
        atualizarDistancia(rssiDouble)
    }

    private fun atualizarDistancia(rssiApp: Double) {
        val distancia = calcularDistancia(rssiApp, fatorNLocal, rssiRefLocal)
        distanciaApp = if (distancia != null) "%.2f m".format(Locale.US, distancia) else "---"
    }

    fun getUltimoRssiApp(): Double? = if (bleConectado) ultimoRssiBleAppDbm else ultimoRssiClassicoAppDbm

    fun calcularDistancia(rssi: Double, n: Double, ref: Double): Double? {
        if (n == 0.0) return null
        return 10.0.pow((ref - rssi) / (10.0 * n))
    }

    fun salvarCalibracao(novoFator: Double, novoRssiRef: Double) {
        if (tagAtual != "--") {
            ApiPayloadMapper.saveCalibracaoToXml(getApplication(), tagAtual, novoFator, novoRssiRef)
            fatorNLocal = novoFator
            rssiRefLocal = novoRssiRef
        }
    }

    private var ultimoEnvioApi = 0L
    private val intervaloEnvioApiMs = 10_000L

    private fun processarPacoteBinarioEsp32(bytes: ByteArray) {
        val packet = Esp32PacketDecoder.decode(bytes) ?: return
        if (!packet.crcOk) return
        atualizarTelaComPacoteEsp32(packet)
    }

    private fun atualizarTelaComPacoteEsp32(packet: Esp32Packet) {
        tagAtual = packet.tag
        ApiPayloadMapper.getCalibracaoFromXml(getApplication(), packet.tag)?.let {
            fatorNLocal = it.fatorN
            rssiRefLocal = it.rssiRef
        }

        tempFalha = packet.tempErro
        humFalha = packet.humErro
        inaFalha = packet.inaErro
        oledFalha = packet.oledErro
        bh1750Falha = packet.bh1750Erro
        gpsFalha = packet.gpsErro
        rssiFalha = packet.rssiErro

        temperatura = packet.temp?.let { "%.1f °C".format(Locale.US, it) } ?: "---"
        umidade = packet.hum?.let { "%.1f %%".format(Locale.US, it) } ?: "---"
        
        if (packet.inaErro) {
            tensao = "---"; corrente = "---"; potencia = "---"
        } else {
            tensao = packet.voltage?.let { "%.2f V".format(Locale.US, it) } ?: "---"
            corrente = packet.current?.let { "%.1f mA".format(Locale.US, it) } ?: "---"
            potencia = packet.power?.let { "%.1f mW".format(Locale.US, it) } ?: "---"
        }

        luximetro = packet.lux?.let { "%.1f lux".format(Locale.US, it) } ?: "---"
        latitude = packet.lat?.let { "%.6f".format(Locale.US, it) } ?: "---"
        longitude = packet.lon?.let { "%.6f".format(Locale.US, it) } ?: "---"
        rssiBle = packet.rssiEsp32Dbm?.let { "%.1f dBm".format(Locale.US, it) } ?: "---"

        // Atualiza a distância com o RSSI mais recente do celular
        getUltimoRssiApp()?.let { atualizarDistancia(it) }

        val falhas = mutableListOf<String>().apply {
            if (packet.tempErro) add("temp")
            if (packet.humErro) add("hum")
            if (packet.inaErro) add("INA")
            if (packet.oledErro) add("OLED")
            if (packet.bh1750Erro) add("lux")
            if (packet.gpsErro) add("GPS")
            if (packet.rssiErro) add("RSSI")
        }
        diagnosticoSensores = if (falhas.isEmpty()) "Sensores OK - ${packet.tag}" else "Falha: ${falhas.joinToString(",")}"

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        ultimaAtualizacaoEsp32 = "Atualizado às ${sdf.format(Date())}"
        ultimoRecebimentoEsp32Ms = System.currentTimeMillis()

        enviarParaApiSeNecessario(packet)
    }

    private fun enviarParaApiSeNecessario(packet: Esp32Packet) {
        if (!apiEnvioAtivo) {
            statusApi = "API: envio desligado"
            return
        }

        val agoraMs = System.currentTimeMillis()
        if (agoraMs - ultimoEnvioApi < intervaloEnvioApiMs) return
        ultimoEnvioApi = agoraMs

        val tipoComunicacao = when {
            bleConectado -> 1
            btClassicoConectado -> 2
            else -> 0
        }

        val appRssiDbm = if (bleConectado) ultimoRssiBleAppDbm else ultimoRssiClassicoAppDbm
        
        val jsonApi = ApiPayloadMapper.fromEsp32Packet(
            context = getApplication(),
            packet = packet,
            tipoComunicacao = tipoComunicacao,
            appRssiDbm = appRssiDbm,
            rssiReferenciaPadrao = rssiRefLocal,
            fatorNPadrao = fatorNLocal
        )

        statusApi = "API: enviando..."
        apiDatasource.enviarPayload(jsonApi, object : ApiDatasource.ApiCallback {
            override fun onSuccess(code: Int, message: String, json: String) {
                statusApi = message
                ultimoCodigoHttpApi = code.toString()
                ultimoJsonApi = json
                ultimaAtualizacaoApi = "Sucesso às ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}"
            }

            override fun onFailure(message: String, json: String) {
                statusApi = message
                ultimoCodigoHttpApi = "FALHA"
                ultimoJsonApi = json
                ultimaAtualizacaoApi = "Falha às ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}"
            }
        })
    }

    override fun onCleared() {
        super.onCleared()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        rssiHandler.removeCallbacks(rssiRunnable)
        bleDatasource.disconnect()
        classicDatasource.disconnect()
    }
}

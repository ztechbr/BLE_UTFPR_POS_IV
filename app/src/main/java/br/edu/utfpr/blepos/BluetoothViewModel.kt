package br.edu.utfpr.blepos

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.edu.utfpr.blepos.data.api.ApiDatasource
import br.edu.utfpr.blepos.data.api.ApiPayloadMapper
import br.edu.utfpr.blepos.data.repository.BluetoothRepository
import br.edu.utfpr.blepos.data.esp32.Esp32Packet
import br.edu.utfpr.blepos.data.esp32.Esp32PacketDecoder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

/**
 * ViewModel responsável por gerenciar o estado da UI e a lógica de negócio da comunicação Bluetooth.
 * Segue o padrão MVVM, servindo como intermediário entre o BluetoothRepository (Model) e a UI (View).
 */
class BluetoothViewModel(application: Application) : AndroidViewModel(application) {

    // --- Estados da View (Observados pelo Compose) ---
    
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
    
    // Estados de Falha
    var bh1750Falha by mutableStateOf(false)
        private set
    var gpsFalha by mutableStateOf(false)
        private set
    var rssiFalha by mutableStateOf(false)
        private set
    var tempFalha by mutableStateOf(false)
        private set
    var humFalha by mutableStateOf(false)
        private set
    var inaFalha by mutableStateOf(false)
        private set
    var oledFalha by mutableStateOf(false)
        private set

    // Status da API e Conexão
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

    // --- Lógica de Controle Interno ---

    private var ultimoRecebimentoEsp32Ms = 0L
    private val timeoutComunicacaoEsp32Ms = 60_000L
    
    private var rssiJob: Job? = null
    private var watchdogJob: Job? = null

    // Repository (Model)
    private val repository = BluetoothRepository(
        context = application,
        onPacketReceived = { bytes -> processarPacoteBinarioEsp32(bytes) },
        onStatusChanged = { newStatus -> status = newStatus },
        onBleRssiUpdate = { rssi -> updateRssiBle(rssi) },
        onBleConnectionStateChanged = { connected ->
            bleConectado = connected
            if (connected) startRssiUpdates() else stopRssiUpdates()
        },
        onClassicConnectionStateChanged = { connected -> btClassicoConectado = connected }
    )

    init {
        startWatchdog()
    }

    /**
     * Monitora a conexão com o ESP32. Se não houver dados por mais de 60s, reseta o estado.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                val agora = System.currentTimeMillis()
                if (ultimoRecebimentoEsp32Ms > 0 &&
                    agora - ultimoRecebimentoEsp32Ms > timeoutComunicacaoEsp32Ms
                ) {
                    status = "Erro de Comunicação com ESP32 (watchdog)"
                    limparDadosEsp32()
                }
            }
        }
    }

    private fun limparDadosEsp32() {
        bleConectado = false
        btClassicoConectado = false
        diagnosticoSensores = "Erro de comunicação"
        temperatura = "---"; umidade = "---"; tensao = "---"
        corrente = "---"; potencia = "---"
        ultimaAtualizacaoEsp32 = "Sem atualização do ESP32 há mais de 60 s"
    }

    /**
     * Atualiza o RSSI do BLE periodicamente enquanto conectado.
     */
    private fun startRssiUpdates() {
        rssiJob?.cancel()
        rssiJob = viewModelScope.launch {
            while (bleConectado && isActive) {
                repository.readBleRssi()
                delay(5000)
            }
        }
    }

    private fun stopRssiUpdates() {
        rssiJob?.cancel()
        rssiJob = null
    }

    val estaComunicando: Boolean get() = bleConectado || btClassicoConectado

    private var ultimoRssiBleAppDbm: Double? = null
    private var ultimoRssiClassicAppDbm: Double? = null

    // Configurações de Calibração (Uso de mutableDoubleStateOf para performance)
    var rssiRefLocal by mutableDoubleStateOf(-59.0)
        private set
    var fatorNLocal by mutableDoubleStateOf(2.0)
        private set
    var tagAtual by mutableStateOf("--")
        private set

    // --- Ações do Usuário (Intents) ---

    fun toggleBleConnection() {
        if (bleConectado) {
            repository.disconnectBle()
            stopRssiUpdates()
            limparDadosTela()
        } else {
            // Garante que o Bluetooth Clássico esteja desligado antes de iniciar BLE
            if (btClassicoConectado) {
                repository.disconnectClassic()
            }
            repository.startBleScan()
        }
    }

    fun toggleClassicConnection() {
        if (btClassicoConectado) {
            repository.disconnectClassic()
            ultimoRssiClassicAppDbm = null
            limparDadosTela()
        } else {
            // Garante que o BLE esteja desligado antes de iniciar Clássico
            if (bleConectado) {
                repository.disconnectBle()
                stopRssiUpdates()
            }
            repository.connectClassic()
        }
    }

    fun toggleEnvioApi() {
        apiEnvioAtivo = !apiEnvioAtivo
        statusApi = if (apiEnvioAtivo) "API: envio habilitado" else "API: envio desabilitado"
    }

    fun salvarCalibracao(novoFator: Double, novoRssiRef: Double) {
        if (tagAtual != "--") {
            ApiPayloadMapper.saveCalibracaoToXml(getApplication(), tagAtual, novoFator, novoRssiRef)
            fatorNLocal = novoFator
            rssiRefLocal = novoRssiRef
        }
    }

    // --- Processamento de Dados ---

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

    fun getUltimoRssiApp(): Double? = if (bleConectado) ultimoRssiBleAppDbm else ultimoRssiClassicAppDbm

    fun calcularDistancia(rssi: Double, n: Double, ref: Double): Double? {
        if (n == 0.0) return null
        return 10.0.pow((ref - rssi) / (10.0 * n))
    }

    private fun processarPacoteBinarioEsp32(bytes: ByteArray) {
        if (!estaComunicando) return
        val packet = Esp32PacketDecoder.decode(bytes) ?: return
        if (!packet.crcOk) return
        atualizarTelaComPacoteEsp32(packet)
    }

    private fun limparDadosTela() {
        temperatura = "--"; umidade = "--"; tensao = "--"
        corrente = "--"; potencia = "--"; luximetro = "--"
        latitude = "--"; longitude = "--"
        rssiBle = "--"; rssiBleCelular = "--"; distanciaApp = "--"
        diagnosticoSensores = "Desconectado"
        ultimoRssiBleAppDbm = null
        ultimoRssiClassicAppDbm = null
    }

    private fun atualizarTelaComPacoteEsp32(packet: Esp32Packet) {
        tagAtual = packet.tag
        ApiPayloadMapper.getCalibracaoFromXml(getApplication(), packet.tag)?.let {
            fatorNLocal = it.fatorN
            rssiRefLocal = it.rssiRef
        }

        // Atualização de Estados de Falha e Valores
        tempFalha = packet.tempErro; humFalha = packet.humErro
        inaFalha = packet.inaErro; oledFalha = packet.oledErro
        bh1750Falha = packet.bh1750Erro; gpsFalha = packet.gpsErro
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

        getUltimoRssiApp()?.let { atualizarDistancia(it) }

        // Diagnóstico de Sensores
        val falhas = mutableListOf<String>().apply {
            if (packet.tempErro) add("temp"); if (packet.humErro) add("hum")
            if (packet.inaErro) add("INA"); if (packet.oledErro) add("OLED")
            if (packet.bh1750Erro) add("lux"); if (packet.gpsErro) add("GPS")
            if (packet.rssiErro) add("RSSI")
        }
        diagnosticoSensores = if (falhas.isEmpty()) "Sensores OK - ${packet.tag}" else "Falha: ${falhas.joinToString(",")}"

        ultimaAtualizacaoEsp32 = "Atualizado às ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}"
        ultimoRecebimentoEsp32Ms = System.currentTimeMillis()

        enviarParaApiSeNecessario(packet)
    }

    private var ultimoEnvioApi = 0L
    private val intervaloEnvioApiMs = 10_000L

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

        val appRssiDbm = if (bleConectado) ultimoRssiBleAppDbm else ultimoRssiClassicAppDbm
        
        val jsonApi = ApiPayloadMapper.fromEsp32Packet(
            context = getApplication(),
            packet = packet,
            tipoComunicacao = tipoComunicacao,
            appRssiDbm = appRssiDbm,
            rssiReferenciaPadrao = rssiRefLocal,
            fatorNPadrao = fatorNLocal
        )

        statusApi = "API: enviando..."
        repository.enviarParaApi(jsonApi, object : ApiDatasource.ApiCallback {
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
        stopRssiUpdates()
        watchdogJob?.cancel()
        repository.disconnectBle()
        repository.disconnectClassic()
    }
}

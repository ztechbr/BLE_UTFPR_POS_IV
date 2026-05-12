package br.edu.utfpr.blepos.data.api

import br.edu.utfpr.blepos.data.esp32.Esp32Packet
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Converte o pacote binário já decodificado do ESP32
 * para o JSON esperado pela API REST.
 */
object ApiPayloadMapper {

    fun fromEsp32Packet(packet: Esp32Packet, tipoComunicacao: Int): JSONObject {
        val dataFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val horaFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        return JSONObject().apply {
            put("codplantacao", "PLANTDEMO")
            put("codleitura", packet.tag)

            // GPS:
            // A API não aceita -9999 nem null para latitude/longitude.
            // Se GPS estiver sem fix, usamos coordenada reserva para evitar erro HTTP 500.
            // A falha real continua registrada em status_sensores.
            val latApi = packet.lat ?: -22.907
            val lonApi = packet.lon ?: -43.173

            put("lat", latApi)
            put("lon", lonApi)

            put("dataleit", dataFormat.format(Date()))
            put("horaleit", horaFormat.format(Date()))

            put("temp_solo", packet.temp ?: -9999.0)
            put("temp_ar", packet.temp ?: -9999.0)

            put("umid_solo", packet.hum ?: -9999.0)
            put("umid_ar", packet.hum ?: -9999.0)

            put("luz", packet.lux ?: -9999.0)
            put("chuva", 0.0)
            put("umid_folha", 10.0)

            // 0 = sem comunicação
            // 1 = BLE
            // 2 = Bluetooth Clássico
            put("scomunicacao", tipoComunicacao)

            put("stensao", packet.voltage ?: -9999.0)
            put("scorrente", packet.current ?: -9999.0)
            put("spotencia", packet.power ?: -9999.0)

            put("rec_rssi_dbm", packet.rssiEsp32Dbm ?: -9999.0)
            put("ref_rssi_dbm", -59.0)
            put("seq", packet.seq)
            put("janela_s", packet.windowS)
            put("amostras", packet.samples)
            put("status_sensores", packet.status)
            put("crc_ok", packet.crcOk)
        }
    }
}
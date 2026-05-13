package br.edu.utfpr.blepos.data.api

import android.content.Context
import android.util.Xml
import br.edu.utfpr.blepos.data.esp32.Esp32Packet
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

object ApiPayloadMapper {

    fun fromEsp32Packet(
        context: Context,
        packet: Esp32Packet,
        tipoComunicacao: Int,
        appRssiDbm: Double?,
        rssiReferenciaDbm: Double,
        fatorNPadrao: Double
    ): JSONObject {
        val dataFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val horaFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        // Tenta buscar o fator_n calibrado no XML para este sensor específico
        val fatorNCalibrado = getFatorNFromXml(context, packet.tag)
        val fatorNFinal = fatorNCalibrado ?: fatorNPadrao

        /*
         * Fórmula de distância por RSSI:
         * d = 10 ^ ((RSSI_ref - RSSI_medido) / (10 * n))
         */
        val distanciaCalculada = if (appRssiDbm != null && fatorNFinal > 0.0) {
            10.0.pow((rssiReferenciaDbm - appRssiDbm) / (10.0 * fatorNFinal))
        } else {
            -9999.0
        }

        val latApi = packet.lat ?: -22.907
        val lonApi = packet.lon ?: -43.173

        return JSONObject().apply {
            put("codplantacao", "PLANTDEMO")
            put("codsensor", packet.tag)
            put("codleitura", packet.seq)

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

            put("scomunicacao", tipoComunicacao)

            put("stensao", packet.voltage ?: -9999.0)
            put("scorrente", packet.current ?: -9999.0)
            put("spotencia", packet.power ?: -9999.0)

            put("rec_rssi_dbm", appRssiDbm ?: -9999.0)
            put("ref_rssi_dbm", rssiReferenciaDbm)
            put("fator_n", fatorNFinal)
            put("distcalc_app", distanciaCalculada)

            put("janela_s", packet.windowS)
            put("amostras", packet.samples)
            put("status_sensores", packet.status)
            put("crc_ok", packet.crcOk)
        }
    }

    private fun getFatorNFromXml(context: Context, codsensor: String?): Double? {
        if (codsensor == null) return null
        
        try {
            context.assets.open("calibracao.xml").use { inputStream ->
                val parser = Xml.newPullParser()
                parser.setInput(inputStream, null)

                var eventType = parser.eventType
                var currentTag: String? = null
                var sensorEncontrado = false
                var nEncontrado: Double? = null

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            currentTag = parser.name
                            if (currentTag == "sensor") {
                                sensorEncontrado = false
                                nEncontrado = null
                            }
                        }
                        XmlPullParser.TEXT -> {
                            val text = parser.text.trim()
                            if (text.isNotEmpty()) {
                                if (currentTag == "codsensor" && text == codsensor) {
                                    sensorEncontrado = true
                                } else if (currentTag == "fator_n") {
                                    nEncontrado = text.toDoubleOrNull()
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name == "sensor" && sensorEncontrado && nEncontrado != null) {
                                return nEncontrado
                            }
                            currentTag = null
                        }
                    }
                    eventType = parser.next()
                }
            }
        } catch (e: Exception) {
            // Se o arquivo não existir ou erro no parse, retorna null para usar o padrão
        }
        return null
    }
}

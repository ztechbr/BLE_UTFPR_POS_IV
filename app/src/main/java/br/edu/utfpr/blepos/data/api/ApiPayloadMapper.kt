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

data class CalibracaoData(val fatorN: Double, val rssiRef: Double)

object ApiPayloadMapper {

    fun fromEsp32Packet(
        context: Context,
        packet: Esp32Packet,
        tipoComunicacao: Int,
        appRssiDbm: Double?,
        rssiReferenciaPadrao: Double,
        fatorNPadrao: Double
    ): JSONObject {
        val dataFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val horaFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        // Tenta buscar os dados calibrados no XML para este sensor específico
        val calibracao = getCalibracaoFromXml(context, packet.tag)
        val fatorNFinal = calibracao?.fatorN ?: fatorNPadrao
        val rssiRefFinal = calibracao?.rssiRef ?: rssiReferenciaPadrao

        /*
         * Fórmula de distância por RSSI:
         * d = 10 ^ ((RSSI_ref - RSSI_medido) / (10 * n))
         */
        val distanciaCalculada = if (appRssiDbm != null && fatorNFinal > 0.0) {
            10.0.pow((rssiRefFinal - appRssiDbm) / (10.0 * fatorNFinal))
        } else {
            -9999.0
        }

        val latApi = packet.lat ?: -22.907
        val lonApi = packet.lon ?: -43.173

        return JSONObject().apply {
            put("codplantacao", "PLANTDEMO")
            put("codsensor", packet.tag)
            put("codleitura", packet.seq.toString())

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
            put("umid_folha", 0.0)

            put("scomunicacao", tipoComunicacao)

            put("stensao", packet.voltage ?: -9999.0)
            put("scorrente", packet.current ?: -9999.0)
            put("spotencia", packet.power ?: -9999.0)

            put("rec_rssi_dbm", appRssiDbm ?: -9999.0)
            put("ref_rssi_dbm", rssiRefFinal)
            put("fator_n", fatorNFinal)
            put("distcalc_app", distanciaCalculada)
        }
    }

    fun getCalibracaoFromXml(context: Context, codsensor: String?): CalibracaoData? {
        if (codsensor == null) return null
        
        val file = java.io.File(context.filesDir, "calibracao.xml")
        if (file.exists()) {
            val data = parseXml(file.inputStream(), codsensor)
            if (data != null) return data
        }

        return try {
            context.assets.open("calibracao.xml").use { inputStream ->
                parseXml(inputStream, codsensor)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseXml(inputStream: java.io.InputStream, codsensor: String): CalibracaoData? {
        try {
            val parser = Xml.newPullParser()
            parser.setInput(inputStream, null)

            var eventType = parser.eventType
            var currentTag: String? = null
            var sensorEncontrado = false
            var nEncontrado: Double? = null
            var rssiRefEncontrado: Double? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (currentTag == "sensor") {
                            sensorEncontrado = false
                            nEncontrado = null
                            rssiRefEncontrado = null
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text.trim()
                        if (text.isNotEmpty()) {
                            if (currentTag == "codsensor" && text == codsensor) {
                                sensorEncontrado = true
                            } else if (currentTag == "fator_n") {
                                nEncontrado = text.toDoubleOrNull()
                            } else if (currentTag == "rssi_ref") {
                                rssiRefEncontrado = text.toDoubleOrNull()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "sensor" && sensorEncontrado && nEncontrado != null) {
                            // rssi_ref é opcional no XML, se não houver usa um default (ex: -59.0)
                            return CalibracaoData(nEncontrado, rssiRefEncontrado ?: -59.0)
                        }
                        currentTag = null
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }

    fun saveCalibracaoToXml(context: Context, codsensor: String, novoFator: Double, novoRssiRef: Double) {
        val sensores = mutableMapOf<String, CalibracaoData>()

        // 1. Tentar ler do assets primeiro para ter uma base
        try {
            context.assets.open("calibracao.xml").use { inputStream ->
                lerTodosSensores(inputStream, sensores)
            }
        } catch (e: Exception) {}

        // 2. Tentar ler do arquivo interno para sobrepor (caso já tenha sido editado antes)
        val file = java.io.File(context.filesDir, "calibracao.xml")
        if (file.exists()) {
            try {
                file.inputStream().use { inputStream ->
                    lerTodosSensores(inputStream, sensores)
                }
            } catch (e: Exception) {}
        }

        // 3. Atualizar o valor solicitado
        sensores[codsensor] = CalibracaoData(novoFator, novoRssiRef)

        // 4. Salvar tudo de volta no arquivo interno
        try {
            val xmlContent = StringBuilder()
            xmlContent.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            xmlContent.append("<calibracao>\n")
            for ((id, data) in sensores) {
                xmlContent.append("    <sensor>\n")
                xmlContent.append("        <codsensor>$id</codsensor>\n")
                xmlContent.append("        <fator_n>${data.fatorN}</fator_n>\n")
                xmlContent.append("        <rssi_ref>${data.rssiRef}</rssi_ref>\n")
                xmlContent.append("    </sensor>\n")
            }
            xmlContent.append("</calibracao>")
            
            context.openFileOutput("calibracao.xml", Context.MODE_PRIVATE).use {
                it.write(xmlContent.toString().toByteArray())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun lerTodosSensores(inputStream: java.io.InputStream, map: MutableMap<String, CalibracaoData>) {
        try {
            val parser = Xml.newPullParser()
            parser.setInput(inputStream, null)
            var eventType = parser.eventType
            var currentTag: String? = null
            var currentId: String? = null
            var currentN: Double? = null
            var currentRssiRef: Double? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text.trim()
                        if (text.isNotEmpty()) {
                            if (currentTag == "codsensor") currentId = text
                            else if (currentTag == "fator_n") currentN = text.toDoubleOrNull()
                            else if (currentTag == "rssi_ref") currentRssiRef = text.toDoubleOrNull()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "sensor") {
                            if (currentId != null && currentN != null) {
                                map[currentId] = CalibracaoData(currentN, currentRssiRef ?: -59.0)
                            }
                            currentId = null
                            currentN = null
                            currentRssiRef = null
                        }
                        currentTag = null
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {}
    }
}

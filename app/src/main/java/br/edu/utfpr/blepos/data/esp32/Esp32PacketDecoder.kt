package br.edu.utfpr.blepos.data.esp32

import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Decodificador do pacote binário vindo do ESP32.
 *
 * O ESP32 envia uma struct packed com 45 bytes:
 *
 * header1           1 byte   0xAA
 * header2           1 byte   0x55
 * version           1 byte
 * tag               6 bytes
 * seq               4 bytes
 * window_s          2 bytes
 * samples           2 bytes
 * temp_x10          2 bytes
 * hum_x10           2 bytes
 * voltage_mV        2 bytes
 * current_x10_mA    2 bytes
 * power_x10_mW      2 bytes
 * rssi_x10_dbm      2 bytes
 * lat_x1e7          4 bytes
 * lon_x1e7          4 bytes
 * lux_x100          4 bytes
 * status            2 bytes
 * crc16             2 bytes
 *
 * Total: 45 bytes.
 *
 * O ESP32 trabalha em little-endian.
 * Por isso o ByteBuffer precisa usar ByteOrder.LITTLE_ENDIAN.
 */
object Esp32PacketDecoder {

    const val PACKET_SIZE = 45

    /*
     * Função principal de decodificação.
     *
     * Entrada:
     * - ByteArray recebido via BLE ou Bluetooth Clássico.
     *
     * Saída:
     * - Esp32Packet com valores convertidos.
     * - null se o pacote for inválido.
     */
    fun decode(bytes: ByteArray): Esp32Packet? {

        // Verifica tamanho exato do pacote.
        if (bytes.size != PACKET_SIZE) {
            return null
        }

        // Verifica cabeçalho fixo.
        // O ESP32 sempre inicia o pacote com 0xAA 0x55.
        if (bytes[0] != 0xAA.toByte() || bytes[1] != 0x55.toByte()) {
            return null
        }

        /*
         * Validação CRC16.
         *
         * Os últimos 2 bytes do pacote são o CRC recebido.
         * O Android recalcula o CRC dos primeiros 43 bytes.
         *
         * Se os valores forem diferentes, o pacote foi corrompido.
         */
        val crcRecebido = readUInt16LittleEndian(bytes, 43)
        val crcCalculado = Esp32Crc16.calcular(bytes.copyOfRange(0, 43))
        val crcOk = crcRecebido == crcCalculado

        /*
         * ByteBuffer facilita ler os campos na mesma ordem da struct do ESP32.
         */
        val buffer = ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)

        // Cabeçalho
        buffer.get() // header1
        buffer.get() // header2

        // Versão do protocolo.
        val version = buffer.get().toInt() and 0xFF

        // Por enquanto aceitamos apenas versão 1.
        if (version != 1) {
            return null
        }

        // TAG do equipamento: 6 bytes fixos.
        val tagBytes = ByteArray(6)
        buffer.get(tagBytes)
        val tag = tagBytes.toString(Charsets.US_ASCII)

        // uint32_t no ESP32.
        // Kotlin não tem UInt universal em todo projeto, então convertemos para Long.
        val seq = buffer.int.toLong() and 0xFFFFFFFFL

        // uint16_t
        val windowS = buffer.short.toInt() and 0xFFFF
        val samples = buffer.short.toInt() and 0xFFFF

        /*
         * Valores escalonados.
         *
         * O ESP32 não envia float.
         * Ele envia inteiro multiplicado por fator.
         *
         * Exemplo:
         * temp_x10 = 254 significa 25.4 °C.
         */
        val tempRaw = buffer.short.toInt()
        val humRaw = buffer.short.toInt() and 0xFFFF
        val voltageRaw = buffer.short.toInt() and 0xFFFF
        val currentRaw = buffer.short.toInt()
        val powerRaw = buffer.short.toInt() and 0xFFFF
        val rssiRaw = buffer.short.toInt()

        // GPS
        val latRaw = buffer.int
        val lonRaw = buffer.int

        // uint32_t
        val luxRaw = buffer.int.toLong() and 0xFFFFFFFFL

        // Status dos sensores
        val status = buffer.short.toInt() and 0xFFFF

        // CRC no final. Já foi lido antes, aqui apenas avançamos o buffer.
        buffer.short

        /*
         * Conversão dos valores brutos para unidades reais.
         *
         * Valores inválidos:
         * - INT16_MIN  = -32768
         * - UINT16_MAX = 65535
         * - INT32_MIN  = -2147483648
         * - UINT32_MAX = 4294967295
         */
        val temp = if (tempRaw == Short.MIN_VALUE.toInt()) null else tempRaw / 10.0
        val hum = if (humRaw == 0xFFFF) null else humRaw / 10.0
        val voltage = if (voltageRaw == 0xFFFF) null else voltageRaw / 1000.0
        val current = if (currentRaw == Short.MIN_VALUE.toInt()) null else currentRaw / 10.0
        val power = if (powerRaw == 0xFFFF) null else powerRaw / 10.0
        val rssi = if (rssiRaw == Short.MIN_VALUE.toInt()) null else rssiRaw / 10.0

        val lat = if (latRaw == Int.MIN_VALUE) null else latRaw / 10_000_000.0
        val lon = if (lonRaw == Int.MIN_VALUE) null else lonRaw / 10_000_000.0
        val lux = if (luxRaw == 0xFFFFFFFFL) null else luxRaw / 100.0

        return Esp32Packet(
            tag = tag,
            seq = seq,
            windowS = windowS,
            samples = samples,
            temp = temp,
            hum = hum,
            voltage = voltage,
            current = current,
            power = power,
            rssiEsp32Dbm = rssi,
            lat = lat,
            lon = lon,
            lux = lux,
            status = status,
            crcOk = crcOk
        )
    }

    /*
     * Lê um uint16 little-endian manualmente.
     *
     * Usado para pegar o CRC16 recebido no final do pacote.
     */
    private fun readUInt16LittleEndian(bytes: ByteArray, offset: Int): Int {
        val low = bytes[offset].toInt() and 0xFF
        val high = bytes[offset + 1].toInt() and 0xFF
        return low or (high shl 8)
    }
}
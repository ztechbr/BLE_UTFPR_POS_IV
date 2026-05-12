package br.edu.utfpr.blepos.data.esp32

/*
 * Classe responsável apenas pelo cálculo do CRC16 Modbus.
 *
 * O CRC16 é usado para validar se o pacote binário recebido do ESP32
 * chegou íntegro, sem corrupção de bytes durante a transmissão.
 */
object Esp32Crc16 {

    fun calcular(data: ByteArray): Int {
        var crc = 0xFFFF

        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)

            repeat(8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc shr 1) xor 0xA001
                } else {
                    crc shr 1
                }
            }
        }

        return crc and 0xFFFF
    }
}
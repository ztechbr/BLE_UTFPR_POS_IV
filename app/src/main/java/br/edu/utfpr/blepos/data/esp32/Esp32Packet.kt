package br.edu.utfpr.blepos.data.esp32

/*
 * Modelo de dados decodificado do pacote binário enviado pelo ESP32.
 *
 * O ESP32 NÃO envia JSON.
 * Ele envia bytes em uma estrutura compacta chamada SensorPacket.
 *
 * Este data class representa os valores já convertidos para unidades reais:
 * - temperatura em °C
 * - umidade em %
 * - tensão em V
 * - corrente em mA
 * - potência em mW
 * - RSSI em dBm
 * - latitude/longitude em graus decimais
 * - lux em unidade lux
 */
data class Esp32Packet(
    val tag: String,              // Identificação do equipamento. Exemplo: EQP001
    val seq: Long,                // Número sequencial do pacote
    val windowS: Int,             // Janela de amostragem em segundos
    val samples: Int,             // Quantidade de amostras coletadas na janela

    val temp: Double?,            // Temperatura média em °C
    val hum: Double?,             // Umidade média em %
    val voltage: Double?,         // Tensão média em V
    val current: Double?,         // Corrente média em mA
    val power: Double?,           // Potência média em mW
    val rssiEsp32Dbm: Double?,    // RSSI médio medido pelo ESP32 em dBm
    val lat: Double?,             // Latitude média
    val lon: Double?,             // Longitude média
    val lux: Double?,             // Luminosidade média em lux

    val status: Int,              // Bitmask de erro dos sensores
    val crcOk: Boolean            // Resultado da validação CRC16
) {

    /*
     * Cada bit do campo status representa erro em um sensor.
     *
     * Mesma regra definida no ESP32:
     *
     * STATUS_TEMP_ERRO   0x0001
     * STATUS_HUM_ERRO    0x0002
     * STATUS_INA_ERRO    0x0004
     * STATUS_OLED_ERRO   0x0008
     * STATUS_BH1750_ERRO 0x0010
     * STATUS_GPS_ERRO    0x0020
     * STATUS_RSSI_ERRO   0x0040
     */

    val tempErro: Boolean
        get() = status and 0x0001 != 0

    val humErro: Boolean
        get() = status and 0x0002 != 0

    val inaErro: Boolean
        get() = status and 0x0004 != 0

    val oledErro: Boolean
        get() = status and 0x0008 != 0

    val bh1750Erro: Boolean
        get() = status and 0x0010 != 0

    val gpsErro: Boolean
        get() = status and 0x0020 != 0

    val rssiErro: Boolean
        get() = status and 0x0040 != 0
}
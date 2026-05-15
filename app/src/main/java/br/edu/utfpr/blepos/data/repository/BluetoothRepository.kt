package br.edu.utfpr.blepos.data.repository

import android.content.Context
import br.edu.utfpr.blepos.data.api.ApiDatasource
import br.edu.utfpr.blepos.data.bluetooth.BleDatasource
import br.edu.utfpr.blepos.data.bluetooth.ClassicBluetoothDatasource
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.UUID

class BluetoothRepository(
    context: Context,
    private val onPacketReceived: (ByteArray) -> Unit,
    private val onStatusChanged: (String) -> Unit,
    private val onBleRssiUpdate: (Int) -> Unit,
    private val onBleConnectionStateChanged: (Boolean) -> Unit,
    private val onClassicConnectionStateChanged: (Boolean) -> Unit
) {
    private val bleDatasource = BleDatasource(
        context = context,
        serviceUuid = UUID.fromString("12345678-1234-1234-1234-1234567890ab"),
        characteristicUuid = UUID.fromString("abcd1234-1234-1234-1234-abcdef123456"),
        onPacketReceived = onPacketReceived,
        onStatusChanged = onStatusChanged,
        onRssiUpdate = onBleRssiUpdate,
        onConnectionStateChanged = onBleConnectionStateChanged
    )

    private val classicDatasource = ClassicBluetoothDatasource(
        context = context,
        sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
        deviceName = "ESP32_MONITOR_BT",
        onPacketReceived = onPacketReceived,
        onStatusChanged = onStatusChanged,
        onConnectionStateChanged = onClassicConnectionStateChanged
    )

    private val apiDatasource = ApiDatasource(
        apiUrl = "https://api-sensores.ztechnologies.io/leituras",
        httpClient = OkHttpClient()
    )

    fun startBleScan() = bleDatasource.startScan()
    fun disconnectBle() = bleDatasource.disconnect()
    fun readBleRssi() = bleDatasource.readRssi()

    fun connectClassic() = classicDatasource.connect()
    fun disconnectClassic() = classicDatasource.disconnect()

    fun enviarParaApi(json: JSONObject, callback: ApiDatasource.ApiCallback) {
        apiDatasource.enviarPayload(json, callback)
    }
}

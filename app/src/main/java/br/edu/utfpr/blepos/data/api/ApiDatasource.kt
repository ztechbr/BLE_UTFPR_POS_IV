package br.edu.utfpr.blepos.data.api

import android.os.Handler
import android.os.Looper
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

class ApiDatasource(
    private val apiUrl: String,
    private val httpClient: OkHttpClient
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    interface ApiCallback {
        fun onSuccess(code: Int, message: String, json: String)
        fun onFailure(message: String, json: String)
    }

    fun enviarPayload(json: JSONObject, callback: ApiCallback) {
        val jsonString = json.toString()
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonString.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(apiUrl)
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    callback.onFailure("Erro no envio: ${e.message}", json.toString(4))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val code = resp.code
                    val isSuccessful = resp.isSuccessful
                    mainHandler.post {
                        if (isSuccessful) {
                            callback.onSuccess(code, "Enviado OK ($code)", json.toString(4))
                        } else {
                            callback.onFailure("Erro HTTP $code", json.toString(4))
                        }
                    }
                }
            }
        })
    }
}

package com.wh.island.data

import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {
    val gson = Gson()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    var serverUrl = "http://116.62.19.158"
    var token = ""
    val lastError = mutableStateOf("")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun get(path: String): String? {
        val req = Request.Builder()
            .url("$serverUrl$path")
            .header("X-Token", token)
            .build()
        return exec(req)
    }

    fun post(path: String, body: Any): String? {
        val json = gson.toJson(body)
        val req = Request.Builder()
            .url("$serverUrl$path")
            .header("X-Token", token)
            .post(json.toRequestBody(JSON_TYPE))
            .build()
        return exec(req)
    }

    fun uploadFile(file: File): String? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name,
                file.readBytes().toRequestBody("image/*".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("$serverUrl/api/upload")
            .header("X-Token", token)
            .post(body)
            .build()
        return try {
            val resp = exec(req) ?: return null
            val map: Map<String, String> = gson.fromJson(resp, object : TypeToken<Map<String, String>>() {}.type)
            map["url"]
        } catch (e: Exception) {
            fail(e.message ?: "上传解析失败")
        }
    }

    fun uploadRestore(file: File): Boolean {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("$serverUrl/api/restore")
            .header("X-Token", token)
            .post(body)
            .build()
        return exec(req) != null
    }

    inline fun <reified T> getAs(path: String): T? {
        val raw = get(path) ?: return null
        return try {
            gson.fromJson(raw, object : TypeToken<T>() {}.type)
        } catch (e: Exception) { null }
    }

    fun download(path: String): ByteArray? {
        val req = Request.Builder()
            .url("$serverUrl$path")
            .header("X-Token", token)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes()
                if (!resp.isSuccessful) {
                    fail("导出失败 (${resp.code})")
                    return null
                }
                lastError.value = ""
                bytes
            }
        } catch (e: IOException) {
            fail(e.message ?: "网络错误")
            null
        }
    }

    private fun exec(req: Request): String? {
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful) {
                    fail(parseErr(resp.code, body))
                    return null
                }
                lastError.value = ""
                body
            }
        } catch (e: IOException) {
            fail(e.message ?: "网络错误")
        }
    }

    private fun fail(msg: String): String? {
        lastError.value = msg
        return null
    }

    private fun parseErr(code: Int, body: String?): String {
        if (!body.isNullOrBlank()) {
            try {
                val map: Map<String, String> = gson.fromJson(body, object : TypeToken<Map<String, String>>() {}.type)
                map["error"]?.let { if (it.isNotBlank()) return it }
            } catch (_: Exception) { }
        }
        return when (code) {
            401 -> "登录已失效"
            404 -> "接口不存在"
            else -> "请求失败 ($code)"
        }
    }
}

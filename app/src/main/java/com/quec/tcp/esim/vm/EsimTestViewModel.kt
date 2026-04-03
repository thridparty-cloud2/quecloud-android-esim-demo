package com.quec.tcp.esim.vm

import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.quec.tcp.esim.utils.TCPClientUtils
import com.quectel.basic.queclog.QLog
import com.quectel.sdk.esim.manager.bean.QuecEsimStoreModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EsimTestViewModel : ViewModel(),
    TCPClientUtils.OnReceiveCallbackBlock,
    TCPClientUtils.OnServerConnectedCallbackBlock,
    TCPClientUtils.OnServerDisconnectedCallbackBlock {

    companion object {
        const val TAG = "EsimTestViewModel"
    }

    // UI 状态观察
    private val _isConnected = MutableLiveData<Boolean>()
    val isConnected: LiveData<Boolean> get() = _isConnected

    private val _toastEvent = MutableLiveData<String>()
    val toastEvent: LiveData<String> get() = _toastEvent

    // APDU数据变量
    @Volatile
    private var apduLatch: CountDownLatch? = null
    private var apduResponse: ByteArray? = null

    fun connectTcp(ip: String, port: String) {
        TCPClientUtils.getInstance().connect(ip, port.toInt())
    }

    fun disconnectTcp() {
        TCPClientUtils.getInstance().disconnect()
    }

    // --- TCP 回调逻辑 ---
    override fun connectedCallback() {
        _isConnected.postValue(true)
    }

    override fun disconnectedCallback(e: Exception?) {
        _isConnected.postValue(false)
        _toastEvent.postValue("TCP 已断开")
    }

    override fun receiveCallback(bytes: ByteArray) {
        apduResponse = bytes
        apduLatch?.countDown()
    }

    // --- TransmitCallback (APDU 传输逻辑) ---
    fun handleTransmitApdu(apdu: ByteArray): ByteArray {
        //异常情况返回的错误码     8500000A
        val fallbackApdu = byteArrayOf(0x85.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0A.toByte())

        apduLatch = CountDownLatch(1)
        apduResponse = null

        TCPClientUtils.getInstance().send(apdu)

        return try {
            val success = apduLatch?.await(10, TimeUnit.SECONDS) ?: false
            if (!success) {
                QLog.e(TAG, "APDU 接收超时")
                fallbackApdu
            } else {
                apduResponse ?: fallbackApdu
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            QLog.e(TAG, "APDU 等待被中断: ${e.message}")
            fallbackApdu
        }
    }

    fun getEsimStoreModel(url: String, appkey: String, appsectet: String): QuecEsimStoreModel {
        return QuecEsimStoreModel(
            url = url,
            appkey = appkey,
            appsectet = appsectet
        )
    }
}
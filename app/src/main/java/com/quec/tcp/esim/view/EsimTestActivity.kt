package com.quec.tcp.esim.view

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.commitNow
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.hjq.toast.Toaster
import com.quec.tcp.esim.R
import com.quec.tcp.esim.databinding.ActivityEsimTestBinding
import com.quec.tcp.esim.service.TcpKeepAliveService
import com.quec.tcp.esim.utils.LoadingManager
import com.quec.tcp.esim.utils.TCPClientUtils
import com.quec.tcp.esim.vm.EsimTestViewModel
import com.quectel.basic.queclog.QLog
import com.quectel.sdk.esim.manager.ipa.TransmitCallback
import com.quectel.sdk.esim.manager.view.EsimMallFragment
import com.quectel.sdk.esim.manager.web.EsimManagerCallback
import com.quectel.sdk.esim.manager.web.QuecEsimManagerService
import com.quectel.sdk.pay.wx.QuecWxHandler
import com.yzq.zxinglibrary.android.CaptureActivity
import com.yzq.zxinglibrary.bean.ZxingConfig
import com.yzq.zxinglibrary.common.Constant

class EsimTestActivity : AppCompatActivity(),
    TransmitCallback,
    EsimManagerCallback {

    companion object {
        const val TAG = "EsimTestActivity"
    }

    private lateinit var binding: ActivityEsimTestBinding

    // 引入 ViewModel
    private val viewModel: EsimTestViewModel by viewModels()

    private var managerService: QuecEsimManagerService? = null
    private var mallFragment: EsimMallFragment? = null

    // 扫码业务变量
    private var pendingScanCallback: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEsimTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initView()
        initObservers()
        bindEvent()
    }

    private fun initView() {
        //初始化微信支付key   在这里填写appid
        QuecWxHandler.init(this, "appid")
        // 初始隐藏商城，显示连接界面
        updateLayoutVisibility(false)
    }

    private fun initObservers() {
        //  TCP 连接状态
        viewModel.isConnected.observe(this) { isConnected ->
            LoadingManager.hide()
            if (isConnected) {
                // 启动前台服务
                val serviceIntent = Intent(this, TcpKeepAliveService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                updateLayoutVisibility(true)
                initEsimService()
            } else {
                // 停止前台服务
                val serviceIntent = Intent(this, TcpKeepAliveService::class.java)
                stopService(serviceIntent)
                updateLayoutVisibility(false)
                //释放
                releaseManagerService()
            }
        }

        // Toast 提示
        viewModel.toastEvent.observe(this) { message ->
            Toaster.showShort(message)
        }
    }

    private fun bindEvent() {
        // 将 TCP 回调绑定到 ViewModel
        TCPClientUtils.getInstance().setConnectedCallback(viewModel)
        TCPClientUtils.getInstance().setDisconnectedCallback(viewModel)
        TCPClientUtils.getInstance().setReceivedCallback(viewModel)

        // 连接按钮
        binding.btnConnect.setOnClickListener {
            val ip = binding.etIp.text.toString().trim()
            val port = binding.etPort.text.toString().trim()
            if (ip.isNotEmpty() && port.isNotEmpty()) {
                LoadingManager.show(this@EsimTestActivity)
                viewModel.connectTcp(ip, port)
            }
        }

        // 打开 H5 按钮
        binding.btnOpenInlandH5.setOnClickListener { startEsimWorkflow() }
    }

    private fun startEsimWorkflow() {
        if (viewModel.isConnected.value != true) {
            Toaster.show("未建立连接")
            return
        }
        val url = binding.etUrl.text?.trim()?.toString()
        val appKey = binding.etAppkey.text?.trim()?.toString()
        val appSecret = binding.etAppsectet.text?.trim()?.toString()

        if (url.isNullOrEmpty() || appKey.isNullOrEmpty() || appSecret.isNullOrEmpty()) {
            Toaster.show("请检查输入信息是否完整")
            return
        }
        binding.tcpLayout.visibility = View.GONE
        binding.mallContainer.visibility = View.VISIBLE
        supportFragmentManager.commitNow {
            replace(R.id.mall_container, mallFragment!!)
        }

        val model = viewModel.getEsimStoreModel(url, appKey, appSecret)
        managerService?.openStore(model)
    }

    /**
     * 根据连接状态切换布局
     */
    private fun updateLayoutVisibility(isConnected: Boolean) {
        runOnUiThread {
            if (isConnected) {
                binding.tvStatus.text = "已连接"
                binding.tvStatus.setTextColor(
                    resources.getColor(android.R.color.holo_green_dark)
                )
                binding.ivStatus.setImageResource(android.R.drawable.presence_online)
                binding.btnConnect.text = "已连接"
                binding.btnConnect.isEnabled = false
            } else {
                binding.tvStatus.text = "未连接"
                binding.tvStatus.setTextColor(
                    resources.getColor(android.R.color.holo_red_dark)
                )
                binding.ivStatus.setImageResource(android.R.drawable.presence_offline)
                binding.btnConnect.text = "连接设备并初始化"
                binding.btnConnect.isEnabled = true
                binding.tcpLayout.visibility = View.VISIBLE
                binding.mallContainer.visibility = View.GONE
            }
        }
    }

    private fun initEsimService() {
        // 初始化esim相关
        managerService = QuecEsimManagerService(this)
        managerService?.setTransmitCallback(this)
        managerService?.addListener(this)

        mallFragment = EsimMallFragment()
        managerService?.setEsimMallFragment(mallFragment)
    }

    // --- TransmitCallback 接口实现 (委托给 ViewModel 处理) ---
    override fun onTransmitApdu(apdu: ByteArray): ByteArray {
        return viewModel.handleTransmitApdu(apdu)
    }

    override fun onNeedScanQr(block: (String) -> Unit) {
        pendingScanCallback = block
        requestPermissions()
    }

    override fun onStartDownProfile(block: (Boolean) -> Unit) {
        block(true)
    }

    // --- 扫码和权限相关 ---
    private fun requestPermissions() {
        XXPermissions.with(this)
            .permission(Permission.CAMERA)
            .request(object : OnPermissionCallback {
                override fun onGranted(permissions: MutableList<String?>, allGranted: Boolean) {
                    if (allGranted) startScanner() else pendingScanCallback?.invoke("")
                }

                override fun onDenied(permissions: MutableList<String?>, doNotAskAgain: Boolean) {
                    pendingScanCallback?.invoke("")
                }
            })
    }

    private fun startScanner() {
        val intent = Intent(this, CaptureActivity::class.java)
        val config = ZxingConfig().apply {
            isShake = false
            isShowbottomLayout = false
            isShowFlashLight = false
            isShowAlbum = false
        }
        intent.putExtra(Constant.INTENT_ZXING_CONFIG, config)
        scanLauncher.launch(intent)
    }

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val content = if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra(Constant.CODED_CONTENT).orEmpty()
            } else ""

            if (content.isNotEmpty()) {
                Toaster.show("扫码结果 = $content")
            }

            pendingScanCallback?.invoke(content)
            pendingScanCallback = null
        }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.disconnectTcp()
        releaseManagerService()
    }

    // 处理物理返回键：如果商城打开了，点击返回建议回到连接界面或关闭页面
    override fun onBackPressed() {
        if (binding.mallContainer.isVisible) {
            binding.tcpLayout.visibility = View.VISIBLE
            binding.mallContainer.visibility = View.GONE
//            updateLayoutVisibility(false)
//            viewModel.disconnectTcp()
        } else {
            super.onBackPressed()
        }
    }

    private fun releaseManagerService() {
        try {
            mallFragment?.let {
                if (it.isAdded) {
                    supportFragmentManager.commitNow {
                        remove(it)
                    }
                }
            }
            managerService?.release()
        } catch (e: Exception) {
            QLog.e(TAG, "releaseManagerService error: ${e.message}")
        } finally {
            managerService = null
            mallFragment = null
        }
    }
}
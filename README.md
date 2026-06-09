# eSIM 服务: QuecEsimManagerSdk

## 功能概述

`QuecEsimManagerSdk` 是用于 **eSIM 服务管理与商城接入** 的 Android SDK。  
通过该 SDK，开发者可以在 App 内快速集成 **eSIM 商城及 eSIM Profile 管理能力**，实现从购买到安装的完整流程。

SDK 主要提供以下功能：

- **eSIM 商城接入**
  - 打开 eSIM H5 商城页面
  - 浏览和购买 eSIM 套餐

- **eSIM Profile 管理**
  - Profile 下载与安装
  - Profile 删除
  - Profile 状态查询

- **eSIM 通信能力**
  - APDU 指令交互
  - eSIM 激活流程处理
  - QR Code 扫码激活

- **用户交互流程**
  - 扫码获取 eSIM 激活码
  - 下载 Profile 确认
  - eSIM 下载过程控制

通过 `QuecEsimManagerSdk`，开发者无需自行处理复杂的 **eSIM 通信协议与激活流程**，即可快速完成 eSIM 服务能力接入。

---

## 集成方式

使用 **build.gradle** 集成 SDK：

```gradle
implementation "com.quectel.app.sdk:quec-esim-manager-sdk:x.x.x"
```

该SDK是Fragment需要集成到对应Activity里：
```
需要初始化QuecEsimManagerService, 将EsimMallFragment放入QuecEsimManagerService中
```
**示例代码**
建议建立(TCP/BLE/等等)链接后再进行初始化
```java

private var managerService: QuecEsimManagerService? = null
private var mallFragment: EsimMallFragment? = null

private fun initEsimService() {
    // 初始化esim相关
    managerService = QuecEsimManagerService(this)
    managerService?.setTransmitCallback(this)
    managerService?.addListener(this)
    mallFragment = EsimMallFragment()
    managerService?.setEsimMallFragment(mallFragment)
}
```
可以参考Demo提供的ContainerActivity
### 微信支付初始化
如果需要集成微信支付, 需要添加
````java
QuecWxHandler.init(this, "appkey")
````
### ESim初始化

**接口说明**

打开ESim H5商城页面

```java
fun openStore(model: QuecEsimStoreModel)
```

**参数说明**

| 参数         | 是否必传 | 说明               |
|------------|------|------------------|
| model | 是    |eSim商城所需参数|

**QuecEsimStoreModel属性定义**

| 字段        | 类型                 | 描述      |
|-----------|--------------------|---------|
| appkey  | String            | QBOSS的appkey，用于同步 eSIM 服务 |
| appsectet | String | QBOSS的appsectet，用于同步 eSIM 服务    |
| url | String | 自定义商城 URL，如果为空，SDK 使用默认内部 URL    |


**示例代码**

```java
val model = QuecEsimStoreModel(
    url = url,
    appkey = appkey,
    appsectet = appsectet
)
managerService?.openStore(model)
```
### eSIM 代理监听

>QuecEsimManagerService.addListener实现 EsimManagerCallback 二维码获取以及下载确认等逻辑。.

**EsimManagerCallback**

```kotiln
interface EsimManagerCallback {

    /**
     * 是否允许开始下载 Profile
     * type: 1 国内 / 2 海外
     */
    fun onStartDownProfile(
        block: (enable: Boolean) -> Unit
    )

    /**
     * 需要 App 扫描二维码
     *
     * App 扫描完成后必须调用 block(qrInfo)
     */
    fun onNeedScanQr(
        block: (qrInfo: String) -> Unit
    )

    /**
     * 页面操作事件回调
     */
    fun onInteractionAction(action: QuecEsimAction)
}

```

**示例代码**
```kotiln
private var pendingScanCallback: ((String) -> Unit)? = null

override fun onNeedScanQr(block: (String) -> Unit) {
    pendingScanCallback = block
    requestPermissions()
}
override fun onStartDownProfile(block: (Boolean) -> Unit) {
    block(true)
}

override fun onInteractionAction(action: QuecEsimAction) {
    //QuecEsimAction.QUEC_ESIM_DOWNLOAD_PROFILE, 下载 Profile
    //
    //QuecEsimAction.QUEC_ESIM_DOWNLOAD_ENABLE_PROFILE, 下载并启用 Profile
    //
    //QuecEsimAction.QUEC_ESIM_SWITCH_PROFILE, 切换 Profile
    //
    //QuecEsimAction.QUEC_ESIM_DISABLE_PROFILE, 停用 Profile
    //
    //QuecEsimAction.QUEC_ESIM_DELETE_PROFILE, 删除 Profile
    //
    //QuecEsimAction.QUEC_ESIM_SYNC_PROFILES; 同步 Profiles
}

// 获取相机权限
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
//开始扫码
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
//获取扫码结果
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

```
### APDU 发送与接收机制说明

>实现QuecEsimManagerService.setTransmitCallback的onTransmitApdu模块, 通过 CountDownLatch 实现 APDU 请求的同步发送与响应等待机制，用于将底层异步通信（如 TCP / 蓝牙等）封装为同步调用方式。

发送APDU → 卡住等回包 → 收到回包 → 继续执行

##### 整体流程如下：

1. 调用 onTransmitApdu() 发送 APDU 指令

2. 创建 CountDownLatch 用于等待响应

3. 通过通信通道（TCP / BLE 等）发送 APDU 数据

4. 当前线程进入等待状态（最长等待 10 秒）

5. 当底层通信接收到响应时触发 receiveCallback()

6. 在回调中保存响应数据并调用 countDown()

7. 解除等待并返回 APDU 响应数据

```java
/**
 * APUD收发器
 */
fun interface TransmitCallback {

    /**
     * IPA/JNI 同步调用
     * App 需要在该方法内：
     * 1. 将 APDU 发送给设备
     * 2. 同步等待设备响应
     * 3. 返回设备的 APDU Response
     */
    fun onTransmitApdu(
        data: ByteArray
    ): ByteArray
}

```

**示例代码**
```java

@Volatile
private var apduLatch: CountDownLatch? = null
@Volatile
private var apduResponse: String? = null

override fun onTransmitApdu(
    apdu: ByteArray
): ByteArray {
    val fallbackApdu = byteArrayOf(0x85.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0A.toByte())

    apduLatch = CountDownLatch(1)
    apduResponse = null
    //TODO 将该数据进行发送, 通过TCP/蓝牙等方式传输
    send(apdu)

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

override fun receiveCallback(bytes: ByteArray) {
    apduResponse = bytes
    apduLatch?.countDown()
}
```

### 资源释放

建议当连接断开后进行资源释放, 与上方连接后初始化, 形成对应

##### 示例代码
````java
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
````
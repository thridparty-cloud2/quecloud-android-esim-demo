package com.quec.tcp.esim.app

import android.app.Application
import com.hjq.toast.Toaster
import com.quec.tcp.esim.BuildConfig
import com.quectel.business.log.QuecLogManager

class  QuectelApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 初始化 Toast 框架
        Toaster.init(this)

        QuecLogManager.init(this)
        QuecLogManager.setConsoleLogOpen(BuildConfig.DEBUG)
    }
}

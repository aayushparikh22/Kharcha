package com.spendcalci

import android.app.Application

class KharchaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

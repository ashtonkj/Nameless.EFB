package com.nameless.efb

import android.app.Application
import com.nameless.efb.data.db.EfbDatabase

class EfbApplication : Application() {

    val database: EfbDatabase by lazy {
        EfbDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
    }
}

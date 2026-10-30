package com.pusios.pots

import android.app.Application

object PotsAppContext {
    @Volatile
    var application: Application? = null
}

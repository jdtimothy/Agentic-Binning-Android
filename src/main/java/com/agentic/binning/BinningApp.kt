package com.agentic.binning

import android.app.Application
import android.util.Log
import com.geniex.sdk.GenieXSdk

class BinningApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        GenieXSdk.getInstance().init(
            this,
            object : GenieXSdk.InitCallback {
                override fun onSuccess() {
                    Log.i(TAG, "GenieXSdk init ok")
                }

                override fun onFailure(reason: String) {
                    Log.e(TAG, "GenieXSdk init failed: $reason")
                }
            },
        )
    }

    companion object {
        private const val TAG = "BinningApp"
    }
}

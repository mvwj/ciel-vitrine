package com.cielvitrine.app

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource

/** Lease follows the actual loader's open/close, including asynchronous source replacement. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class LeasedLocalDataSource(private val delegate:DataSource):DataSource by delegate {
    private var lease:AutoCloseable?=null
    override fun open(dataSpec:DataSpec):Long {
        check(lease==null)
        val acquired=ViewerRuntime.source(dataSpec.uri.toString())
        try { return delegate.open(dataSpec).also { lease=acquired } }
        catch(e:Exception){try{delegate.close()}finally{acquired.close()};throw e}
    }
    override fun close(){try{delegate.close()}finally{lease?.close();lease=null}}
    class Factory(private val context:Context):DataSource.Factory {
        override fun createDataSource():DataSource=LeasedLocalDataSource(DefaultDataSource.Factory(context).createDataSource())
    }
}

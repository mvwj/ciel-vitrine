package com.cielvitrine.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveTransfer(val stage:String,val bytes:Long=0,val total:Long=0) {
    val fraction get()=if(total>0)(bytes.toDouble()/total).toFloat().coerceIn(0f,1f) else null
}
/** Transient observations only. A progress indicator never grants verification authority. */
internal object TransferActivity {
    private val mutable=MutableStateFlow<Map<String,ActiveTransfer>>(emptyMap())
    val state=mutable.asStateFlow()
    @Synchronized fun update(id:String,stage:String,bytes:Long=0,total:Long=0){mutable.value=mutable.value+(id to ActiveTransfer(stage,bytes,total))}
    @Synchronized fun clear(id:String){mutable.value=mutable.value-id}
}

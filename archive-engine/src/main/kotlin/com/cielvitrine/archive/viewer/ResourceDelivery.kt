package com.cielvitrine.archive.viewer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Own the result before crossing a cancellable dispatcher boundary. withContext may
 * discard its return value when cancellation races completion of a blocking decoder. */
suspend fun <T : AutoCloseable> deliverViewerResource(load: suspend () -> T, accept: (T) -> Unit) {
    var pending: T? = null
    try {
        withContext(Dispatchers.IO) { pending = load() }
        accept(requireNotNull(pending))
        pending = null
    } finally { pending?.close() }
}

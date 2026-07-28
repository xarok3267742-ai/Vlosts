package io.appmetrica.analytics.appsetid.internal

import android.content.Context
import io.appmetrica.analytics.coreapi.`internal`.identifiers.AppSetIdScope

/**
 * Runtime ABI used by AppMetrica core when its optional App Set ID module is excluded.
 * The concrete retriever is deliberately fail-closed even if another dependency adds
 * Google Play's App Set API in the future.
 */
interface IAppSetIdRetriever {
    fun retrieveAppSetId(context: Context, listener: AppSetIdListener)
}

interface AppSetIdListener {
    fun onAppSetIdRetrieved(id: String, scope: AppSetIdScope)

    fun onFailure(error: Throwable)
}

class AppSetIdRetriever : IAppSetIdRetriever {
    override fun retrieveAppSetId(context: Context, listener: AppSetIdListener) {
        listener.onFailure(IllegalStateException("App Set ID collection is disabled."))
    }
}

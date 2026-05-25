package com.tuneurlradio.app.tuneurl

import android.content.Context
import android.util.Log
import com.dekidea.tuneurl.util.TuneURLManager as SDKTuneURLManager
import com.tuneurlradio.app.data.local.entity.HistoryEngagementEntity
import com.tuneurlradio.app.data.local.entity.SavedEngagementEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper that fires Interest_action reports through the existing SDK
 * pipeline ([SDKTuneURLManager.addRecordOfInterest]).
 *
 * Mirrors the iOS [ReportAction] enum. The wire format is shared between
 * platforms — same endpoint, same lowercase action strings. The SDK already
 * handles `"heard"`, `"interested"`, `"uninterested"` from elsewhere in the
 * app; this helper adds `"acted"` and `"shared"` for the detail-screen flow
 * without disturbing the existing call sites.
 *
 * Fire-and-forget by design: reporting must never block UI, and a failure to
 * report is logged but does not surface to the user.
 */
@Singleton
class EngagementReporter @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val TAG = "EngagementReporter"

    /** Acted = user invoked the engagement (Open Website / View Coupon / Call / Send Message). */
    fun acted(context: Context, engagementId: Int, heardDateIso: String?) {
        post(context, engagementId, ACTION_ACTED, heardDateIso)
    }

    /** Shared = user tapped Share (fires when the share sheet opens). */
    fun shared(context: Context, engagementId: Int, heardDateIso: String?) {
        post(context, engagementId, ACTION_SHARED, heardDateIso)
    }

    /** Convenience overloads for the two entity types. */
    fun acted(context: Context, entity: SavedEngagementEntity) =
        acted(context, entity.engagementId, TimeUtils.formatAsIso(entity.heardAt))

    fun acted(context: Context, entity: HistoryEngagementEntity) =
        acted(context, entity.engagementId, TimeUtils.formatAsIso(entity.heardAt))

    fun shared(context: Context, entity: SavedEngagementEntity) =
        shared(context, entity.engagementId, TimeUtils.formatAsIso(entity.heardAt))

    fun shared(context: Context, entity: HistoryEngagementEntity) =
        shared(context, entity.engagementId, TimeUtils.formatAsIso(entity.heardAt))

    private fun post(context: Context, id: Int, action: String, date: String?) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                SDKTuneURLManager.addRecordOfInterest(appContext, id, action, date ?: "")
                Log.d(TAG, "Reported $action for engagement id=$id")
            } catch (t: Throwable) {
                // Reporting must never bubble up — UX is already past the action.
                Log.w(TAG, "Failed to report $action for engagement id=$id", t)
            }
        }
    }

    companion object {
        const val ACTION_HEARD = "heard"
        const val ACTION_INTERESTED = "interested"
        const val ACTION_UNINTERESTED = "uninterested"
        const val ACTION_ACTED = "acted"
        const val ACTION_SHARED = "shared"
    }
}

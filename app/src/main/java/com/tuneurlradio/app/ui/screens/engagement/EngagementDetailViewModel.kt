package com.tuneurlradio.app.ui.screens.engagement

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.tuneurlradio.app.core.mvi.MviViewModel
import com.tuneurlradio.app.data.repository.EngagementsRepository
import com.tuneurlradio.app.data.repository.toEngagement
import com.tuneurlradio.app.domain.model.Engagement
import com.tuneurlradio.app.navigation.EngagementSource
import com.tuneurlradio.app.navigation.NavArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EngagementDetailState(
    val engagement: Engagement? = null,
    val source: EngagementSource = EngagementSource.SAVED,
    val showDeleteConfirmation: Boolean = false,
    val isLoading: Boolean = true,
    val notFound: Boolean = false
) {
    val canDelete: Boolean get() = source == EngagementSource.SAVED
}

sealed interface EngagementDetailIntent {
    /** Primary action button or content-preview tap. */
    data object PrimaryAction : EngagementDetailIntent
    data object ShareTapped : EngagementDetailIntent
    data object DeleteTapped : EngagementDetailIntent
    data object DeleteConfirmed : EngagementDetailIntent
    data object DeleteCancelled : EngagementDetailIntent
    data object CloseTapped : EngagementDetailIntent
    /** Coupon image tapped (preview only — same as PrimaryAction for coupons). */
    data object CouponPreviewTapped : EngagementDetailIntent
}

/**
 * Side effects the screen surfaces to the host (Activity). Keeping all
 * Intent-launching outside the VM matches the existing pattern in
 * [com.tuneurlradio.app.ui.screens.saved.SavedEngagementsViewModel].
 */
sealed interface EngagementDetailEffect {
    /** Fire `acted` report, then open URL in the system browser. */
    data class OpenWebsite(val url: String) : EngagementDetailEffect
    /** Fire `acted` report, then open ACTION_DIAL with `tel:`. */
    data class PlaceCall(val phoneNumber: String) : EngagementDetailEffect
    /** Fire `acted` report, then open ACTION_VIEW with `sms:`. */
    data class SendSms(val phoneNumber: String) : EngagementDetailEffect
    /** Fire `acted` report, then show in-app full-screen coupon viewer. */
    data class ShowCoupon(val imageUrl: String) : EngagementDetailEffect
    /** Fire `shared` report, then launch the system share sheet. */
    data class Share(val text: String) : EngagementDetailEffect
    /** Pop back to the previous screen. */
    data object Dismiss : EngagementDetailEffect
}

@HiltViewModel
class EngagementDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val engagementsRepository: EngagementsRepository
) : MviViewModel<EngagementDetailState, EngagementDetailIntent, EngagementDetailEffect>(
    EngagementDetailState()
) {

    private val localId: Long = savedStateHandle.get<Long>(NavArgs.LOCAL_ID) ?: -1L
    private val source: EngagementSource =
        savedStateHandle.get<String>(NavArgs.SOURCE)
            ?.let(EngagementSource::fromRoute)
            ?: EngagementSource.SAVED

    init {
        updateState { copy(source = source) }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val entity = when (source) {
                EngagementSource.SAVED -> engagementsRepository.getSavedById(localId)?.toEngagement()
                EngagementSource.HISTORY -> engagementsRepository.getHistoryById(localId)?.toEngagement()
            }
            updateState {
                copy(
                    engagement = entity,
                    isLoading = false,
                    notFound = entity == null
                )
            }
        }
    }

    override fun handleIntent(intent: EngagementDetailIntent) {
        val e = currentState.engagement
        when (intent) {
            EngagementDetailIntent.PrimaryAction,
            EngagementDetailIntent.CouponPreviewTapped -> e?.let(::dispatchPrimary)

            EngagementDetailIntent.ShareTapped -> e?.info
                ?.takeIf { it.isNotBlank() }
                ?.let { sendEffect(EngagementDetailEffect.Share(it)) }

            EngagementDetailIntent.DeleteTapped ->
                updateState { copy(showDeleteConfirmation = true) }

            EngagementDetailIntent.DeleteCancelled ->
                updateState { copy(showDeleteConfirmation = false) }

            EngagementDetailIntent.DeleteConfirmed -> delete()

            EngagementDetailIntent.CloseTapped ->
                sendEffect(EngagementDetailEffect.Dismiss)
        }
    }

    private fun dispatchPrimary(engagement: Engagement) {
        val info = engagement.info?.takeIf { it.isNotBlank() } ?: return
        // The effect contract is "fire `acted` then perform the side effect."
        // Reporting is intentionally done in the effect handler (Activity)
        // rather than here so we have one place that knows the context —
        // same pattern as the existing OpenUrl effect in the list screens.
        val effect: EngagementDetailEffect = when (engagement.type) {
            com.tuneurlradio.app.domain.model.EngagementType.OPEN_PAGE,
            com.tuneurlradio.app.domain.model.EngagementType.SAVE_PAGE ->
                EngagementDetailEffect.OpenWebsite(info)

            com.tuneurlradio.app.domain.model.EngagementType.COUPON ->
                EngagementDetailEffect.ShowCoupon(info)

            com.tuneurlradio.app.domain.model.EngagementType.PHONE ->
                EngagementDetailEffect.PlaceCall(info)

            com.tuneurlradio.app.domain.model.EngagementType.SMS ->
                EngagementDetailEffect.SendSms(info)

            // POLL / API_CALL / UNKNOWN intentionally have no primary action,
            // matching the iOS spec ("placeholder text 'Failed to load content',
            // no primary button").
            com.tuneurlradio.app.domain.model.EngagementType.POLL,
            com.tuneurlradio.app.domain.model.EngagementType.API_CALL,
            com.tuneurlradio.app.domain.model.EngagementType.UNKNOWN -> return
        }
        sendEffect(effect)
    }

    private fun delete() {
        viewModelScope.launch {
            engagementsRepository.getSavedById(localId)?.let {
                engagementsRepository.deleteSavedEngagement(it)
            }
            updateState { copy(showDeleteConfirmation = false) }
            sendEffect(EngagementDetailEffect.Dismiss)
        }
    }
}

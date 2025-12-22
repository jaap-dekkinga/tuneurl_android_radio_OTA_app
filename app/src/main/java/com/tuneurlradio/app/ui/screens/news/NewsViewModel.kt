package com.tuneurlradio.app.ui.screens.news

import androidx.lifecycle.viewModelScope
import com.tuneurlradio.app.core.mvi.MviViewModel
import com.tuneurlradio.app.data.repository.NewsRepository
import com.tuneurlradio.app.domain.model.NewsArticle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NewsState(
    val articlesByCategory: Map<String, List<NewsArticle>> = emptyMap(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

sealed interface NewsIntent {
    data object Load : NewsIntent
    data object Retry : NewsIntent
    data class ArticleClicked(val article: NewsArticle) : NewsIntent
}

sealed interface NewsEffect {
    data class OpenUrl(val url: String) : NewsEffect
}

@HiltViewModel
class NewsViewModel @Inject constructor(
    private val newsRepository: NewsRepository
) : MviViewModel<NewsState, NewsIntent, NewsEffect>(NewsState()) {

    init {
        loadNews()
    }

    override fun handleIntent(intent: NewsIntent) {
        when (intent) {
            NewsIntent.Load -> loadNews()
            NewsIntent.Retry -> loadNews()
            is NewsIntent.ArticleClicked -> {
                intent.article.link?.let { sendEffect(NewsEffect.OpenUrl(it)) }
            }
        }
    }

    private fun loadNews() {
        if (currentState.isLoading) return

        viewModelScope.launch {
            updateState { copy(isLoading = true, errorMessage = null) }

            newsRepository.fetchNews()
                .onSuccess { articles ->
                    updateState { copy(articlesByCategory = articles, isLoading = false) }
                }
                .onFailure { error ->
                    updateState {
                        copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Failed to load news"
                        )
                    }
                }
        }
    }
}

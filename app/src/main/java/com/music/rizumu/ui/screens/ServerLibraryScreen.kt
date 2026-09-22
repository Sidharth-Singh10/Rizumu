package com.music.rizumu.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.rizumu.R
import com.music.rizumu.data.model.HomeShelf
import com.music.rizumu.data.model.ServerLibraryPage
import com.music.rizumu.data.model.ShelfItem
import com.music.rizumu.data.model.UiState
import com.music.rizumu.ui.components.MessageState
import com.music.rizumu.ui.components.PullToRefresh

/**
 * The Library tab when the server is the primary library.
 *
 * The same shape as the YouTube library — a stack of sideways shelves — but
 * built from the listener's own server: playlists, albums, artists and
 * starred items. Rendering is [LibraryGridShelf], so a server shelf looks and
 * behaves like every other shelf, "Show all" included; only where the rows
 * come from is different.
 *
 * Cards carry a `srcb:` browse id, so a tap routes through
 * [com.music.rizumu.ui.MainViewModel.openDetail] to the server's own page,
 * and a long-press raises the same collection menu a YouTube card does —
 * whose queue and download actions load their tracks from the server rather
 * than from YouTube.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerLibraryScreen(
    state: UiState<ServerLibraryPage>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: (ShelfItem) -> Unit,
    onShowAll: (HomeShelf) -> Unit,
    /** A pull-to-refresh in flight — see `serverLibraryRefreshing`. */
    refreshing: Boolean,
    pullState: PullToRefreshState,
    /** Reloads the shelves; also the error state's retry. */
    onRefresh: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        when (state) {
            is UiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
            }

            is UiState.Error -> MessageState(
                message = state.message,
                actionLabel = stringResource(R.string.retry),
                onAction = onRefresh,
            )

            is UiState.Success -> if (state.data.isEmpty) {
                MessageState(message = stringResource(R.string.server_library_empty))
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.data.shelves, key = { it.title }) { shelf ->
                        LibraryGridShelf(
                            shelf = shelf,
                            onItemClick = onItemClick,
                            onItemLongPress = onItemLongPress,
                            onShowAll = { onShowAll(shelf) },
                        )
                    }
                }
            }
        }
    }
}

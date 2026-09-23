package com.music.rizumu.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.rizumu.R
import com.music.rizumu.data.model.HEADER_ART_PX
import com.music.rizumu.data.model.HomeShelf
import com.music.rizumu.data.model.ServerHomePage
import com.music.rizumu.data.model.ShelfItem
import com.music.rizumu.data.model.UiState
import com.music.rizumu.data.model.artworkAt
import com.music.rizumu.ui.components.MessageState
import com.music.rizumu.ui.components.PAGE_GUTTER
import com.music.rizumu.ui.components.PullToRefresh

/**
 * The Play tab when the server is the primary library.
 *
 * A dashboard rather than a listing: the shuffle hero queues a random sample of
 * the whole library, and the shelves below are the server's dynamic rows —
 * recently played, most played, new releases, genres and decades. The static
 * browse rows (playlists, artists, starred) live on the Library tab, so the two
 * tabs do not repeat each other.
 *
 * A row that carries a `videoId` is a recently played track: tapping it plays.
 * Everything else carries a `srcb:` browse id and opens its page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerHomeScreen(
    state: UiState<ServerHomePage>,
    listState: LazyListState,
    /** A pull-to-refresh in flight — see `serverHomeRefreshing`. */
    refreshing: Boolean,
    pullState: PullToRefreshState,
    /** Reloads the dashboard; also the error state's retry. */
    onRefresh: () -> Unit,
    onShuffleAll: () -> Unit,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: (ShelfItem) -> Unit,
    /** Opens a row longer than one screen-width as a full grid. */
    onShowAll: (HomeShelf) -> Unit,
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

            is UiState.Success -> LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "shuffle") {
                    ShuffleAllCard(
                        serverName = state.data.serverName,
                        artwork = state.data.shuffleArtwork,
                        onClick = onShuffleAll,
                    )
                }
                items(state.data.shelves, key = { it.title }) { shelf ->
                    LibraryGridShelf(
                        shelf = shelf,
                        onItemClick = onItemClick,
                        onItemLongPress = onItemLongPress,
                        onShowAll = { onShowAll(shelf) },
                    )
                }
                // LibraryGridShelf draws its own bottom padding; this keeps the
                // last row clear of the floating tab bar.
                item(key = "tail") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/**
 * The one action the tab leads with: play a random sample of the whole library.
 *
 * Dressed in the newest release's cover because a bare button is a worse
 * advertisement for a library than the library itself, with a scrim so the
 * label stays readable over whatever the artwork happens to be.
 */
@Composable
private fun ShuffleAllCard(
    serverName: String,
    artwork: String?,
    onClick: () -> Unit,
) {
    // Over a cover the label has to be white — the scrim guarantees it — and on
    // the bare container it has to be whatever that container's own foreground
    // is, or a light theme would draw white on near-white.
    val foreground = if (artwork != null) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp)
            .height(128.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (artwork != null) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            )
            .clickable(onClick = onClick),
    ) {
        if (artwork != null) {
            AsyncImage(
                model = artwork.artworkAt(HEADER_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Black.copy(alpha = 0.75f), Color.Black.copy(alpha = 0.25f)),
                        ),
                    ),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(42.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.shuffle_all),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.W700,
                    color = foreground,
                )
                if (serverName.isNotBlank()) {
                    Text(
                        text = serverName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = foreground.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

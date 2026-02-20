/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.offline.Download
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import coil3.imageLoader
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.music.R
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.plus
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

class MediaLibrarySessionCallback
@Inject
constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val downloadUtil: DownloadUtil,
) : MediaLibrarySession.Callback {
    private val scope = CoroutineScope(Dispatchers.Main) + Job()

    // Cache remote results to avoid blocking browse/search on network.
    private val remoteHomePlaylists = MutableStateFlow<List<PlaylistItem>>(emptyList())
    @Volatile private var remoteHomeLastFetchMs: Long = 0L
    private val remotePlaylistSongs = ConcurrentHashMap<String, Pair<List<SongItem>, Long>>()
    private val remoteSearchSongs = ConcurrentHashMap<String, Pair<List<SongItem>, Long>>()
    private val REMOTE_TTL_MS = 10 * 60 * 1000L // 10 minutes

    var toggleLike: () -> Unit = {}
    var toggleStartRadio: () -> Unit = {}
    var toggleLibrary: () -> Unit = {}

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        return MediaSession.ConnectionResult.accept(
            connectionResult.availableSessionCommands
                .buildUpon()
                .add(MediaSessionConstants.CommandToggleLike)
                .add(MediaSessionConstants.CommandToggleStartRadio)
                .add(MediaSessionConstants.CommandToggleLibrary)
                .add(MediaSessionConstants.CommandToggleShuffle)
                .add(MediaSessionConstants.CommandToggleRepeatMode)
                .build(),
            connectionResult.availablePlayerCommands,
        )
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MediaSessionConstants.ACTION_TOGGLE_LIKE -> toggleLike()
            MediaSessionConstants.ACTION_TOGGLE_START_RADIO -> toggleStartRadio()
            MediaSessionConstants.ACTION_TOGGLE_LIBRARY -> toggleLibrary()
            MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> session.player.shuffleModeEnabled =
                !session.player.shuffleModeEnabled

            MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> session.player.toggleRepeatMode()
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    @Deprecated("Deprecated in MediaLibrarySession.Callback")
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaItemsWithStartPosition> {
        // IMPORTANT: never return an uncompleted Future (it can stall the host)
        return Futures.immediateFuture(
            MediaItemsWithStartPosition(
                /* mediaItems = */ emptyList(),
                /* startIndex = */ 0,
                /* startPositionMs = */ C.TIME_UNSET
            )
        )
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem
                    .Builder()
                    .setMediaId(MusicService.ROOT)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build(),
                    ).build(),
                params,
            ),
        )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(Dispatchers.IO) {
            LibraryResult.ofItemList(
                when (parentId) {
                    MusicService.ROOT ->
                        listOf(
                            browsableMediaItem(
                                MusicService.SONG,
                                context.getString(R.string.songs),
                                null,
                                drawableUri(R.drawable.music_note),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            browsableMediaItem(
                                MusicService.ARTIST,
                                context.getString(R.string.artists),
                                null,
                                drawableUri(R.drawable.artist),
                                MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                            ),
                            browsableMediaItem(
                                MusicService.ALBUM,
                                context.getString(R.string.albums),
                                null,
                                drawableUri(R.drawable.album),
                                MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                            ),
                            browsableMediaItem(
                                MusicService.PLAYLIST,
                                context.getString(R.string.playlists),
                                null,
                                drawableUri(R.drawable.queue_music),
                                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                            ),
                        )

                    MusicService.SONG -> database.songsByCreateDateAsc().first()
                        .map { it.toMediaItem(parentId) }

                    MusicService.ARTIST ->
                        database.artistsByCreateDateAsc().first().map { artist ->
                            browsableMediaItem(
                                "${MusicService.ARTIST}/${artist.id}",
                                artist.artist.name,
                                context.resources.getQuantityString(
                                    R.plurals.n_song,
                                    artist.songCount,
                                    artist.songCount
                                ),
                                artist.artist.thumbnailUrl?.toUri(),
                                MediaMetadata.MEDIA_TYPE_ARTIST,
                            )
                        }

                    MusicService.ALBUM ->
                        database.albumsByCreateDateAsc().first().map { album ->
                            browsableMediaItem(
                                "${MusicService.ALBUM}/${album.id}",
                                album.album.title,
                                album.artists.joinToString {
                                    it.name
                                },
                                album.album.thumbnailUrl?.toUri(),
                                MediaMetadata.MEDIA_TYPE_ALBUM,
                            )
                        }

                    MusicService.PLAYLIST -> {
                        val likedSongCount = database.likedSongsCount().first()
                        val downloadedSongCount = downloadUtil.downloads.value.size

                        val cachedYt = remoteHomePlaylists.value.take(10)
                        val now = System.currentTimeMillis()
                        if (cachedYt.isEmpty() || (now - remoteHomeLastFetchMs) > REMOTE_TTL_MS) {
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    YouTube.home().getOrNull()?.sections
                                        ?.flatMap { it.items }
                                        ?.filterIsInstance<PlaylistItem>()
                                        ?.take(10)
                                        ?: emptyList()
                                }.onSuccess { list ->
                                    remoteHomeLastFetchMs = System.currentTimeMillis()
                                    remoteHomePlaylists.value = list
                                    session.notifyChildrenChanged(browser, parentId, list.size, params)
                                }.onFailure { e ->
                                    reportException(e)
                                }
                            }
                        }

                        listOf(
                            browsableMediaItem(
                                "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                                context.getString(R.string.liked_songs),
                                context.resources.getQuantityString(
                                    R.plurals.n_song,
                                    likedSongCount,
                                    likedSongCount
                                ),
                                drawableUri(R.drawable.favorite),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                            browsableMediaItem(
                                "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}",
                                context.getString(R.string.downloaded_songs),
                                context.resources.getQuantityString(
                                    R.plurals.n_song,
                                    downloadedSongCount,
                                    downloadedSongCount
                                ),
                                drawableUri(R.drawable.download),
                                MediaMetadata.MEDIA_TYPE_PLAYLIST,
                            ),
                        ) +
                            database.playlistsByCreateDateAsc().first().map { playlist ->
                                browsableMediaItem(
                                    "${MusicService.PLAYLIST}/${playlist.id}",
                                    playlist.playlist.name,
                                    context.resources.getQuantityString(
                                        R.plurals.n_song,
                                        playlist.songCount,
                                        playlist.songCount
                                    ),
                                    playlist.thumbnails.firstOrNull()?.toUri(),
                                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                )
                            } +
                            cachedYt.map { ytPlaylist ->
                                browsableMediaItem(
                                    "${MusicService.YOUTUBE_PLAYLIST}/${ytPlaylist.id}",
                                    ytPlaylist.title,
                                    ytPlaylist.author?.name ?: "YouTube Music",
                                    ytPlaylist.thumbnail?.toUri(),
                                    MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                )
                            }
                    }

                    else ->
                        when {
                            parentId.startsWith("${MusicService.ARTIST}/") ->
                                database.artistSongsByCreateDateAsc(parentId.removePrefix("${MusicService.ARTIST}/"))
                                    .first().map {
                                        it.toMediaItem(parentId)
                                    }

                            parentId.startsWith("${MusicService.ALBUM}/") ->
                                database.albumSongs(parentId.removePrefix("${MusicService.ALBUM}/"))
                                    .first().map {
                                        it.toMediaItem(parentId)
                                    }

                            parentId.startsWith("${MusicService.PLAYLIST}/") -> {
                                val playlistId = parentId.removePrefix("${MusicService.PLAYLIST}/")
                                val songs = when (playlistId) {
                                    PlaylistEntity.LIKED_PLAYLIST_ID -> database.likedSongs(
                                        SongSortType.CREATE_DATE,
                                        true
                                    )

                                    PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                                        val downloads = downloadUtil.downloads.value
                                        database
                                            .allSongs()
                                            .flowOn(Dispatchers.IO)
                                            .map { songs ->
                                                songs.filter {
                                                    downloads[it.id]?.state == Download.STATE_COMPLETED
                                                }
                                            }.map { songs ->
                                                songs
                                                    .map { it to downloads[it.id] }
                                                    .sortedBy { it.second?.updateTimeMs ?: 0L }
                                                    .map { it.first }
                                            }
                                    }

                                    else ->
                                        database.playlistSongs(playlistId).map { list ->
                                            list.map { it.song }
                                        }
                                }.first()

                                // Add shuffle item at the top
                                listOf(
                                    MediaItem.Builder()
                                        .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(context.getString(R.string.shuffle))
                                                .setArtworkUri(drawableUri(R.drawable.shuffle))
                                                .setIsPlayable(true)
                                                .setIsBrowsable(false)
                                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                                .build()
                                        ).build()
                                ) + songs.map { it.toMediaItem(parentId) }
                            }

                            parentId.startsWith("${MusicService.YOUTUBE_PLAYLIST}/") -> {
                                val playlistId = parentId.removePrefix("${MusicService.YOUTUBE_PLAYLIST}/")

                                val now = System.currentTimeMillis()
                                val cachedSongs = remotePlaylistSongs[playlistId]
                                    ?.takeIf { now - it.second <= REMOTE_TTL_MS }
                                    ?.first
                                    ?: emptyList()

                                if (cachedSongs.isEmpty() || (now - (remotePlaylistSongs[playlistId]?.second ?: 0L)) > REMOTE_TTL_MS) {
                                    scope.launch(Dispatchers.IO) {
                                        runCatching {
                                            YouTube.playlist(playlistId).getOrNull()?.songs ?: emptyList()
                                        }.onSuccess { list ->
                                            remotePlaylistSongs[playlistId] = list to System.currentTimeMillis()
                                            session.notifyChildrenChanged(browser, parentId, list.size, params)
                                        }.onFailure { e ->
                                            reportException(e)
                                        }
                                    }
                                }

                                val songs = cachedSongs
                                    .take(100)
                                    .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))

                                // Add shuffle item at the top
                                listOf(
                                    MediaItem.Builder()
                                        .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(context.getString(R.string.shuffle))
                                                .setArtworkUri(drawableUri(R.drawable.shuffle))
                                                .setIsPlayable(true)
                                                .setIsBrowsable(false)
                                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                                .build()
                                        ).build()
                                ) + songs.map { songItem ->
                                    MediaItem.Builder()
                                        .setMediaId("$parentId/${songItem.id}")
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(songItem.title)
                                                .setSubtitle(songItem.artists.joinToString(", ") { it.name })
                                                .setArtist(songItem.artists.joinToString(", ") { it.name })
                                                .setArtworkUri(songItem.thumbnail.toUri())
                                                .setIsPlayable(true)
                                                .setIsBrowsable(false)
                                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                                .build()
                                        )
                                        .build()
                                }
                            }

                            else -> emptyList()
                        }
                },
                params,
            )
        }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future(Dispatchers.IO) {
            database.song(mediaId).first()?.toMediaItem()?.let {
                LibraryResult.ofItem(it, null)
            } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
        }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        session.notifySearchResultChanged(browser, query, 0, params)
        if (query.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val cached = remoteSearchSongs[query]
                if (cached == null || (now - cached.second) > REMOTE_TTL_MS) {
                    runCatching {
                        YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                            .getOrNull()
                            ?.items
                            ?.filterIsInstance<SongItem>()
                            ?: emptyList()
                    }.onSuccess { list ->
                        remoteSearchSongs[query] = list to System.currentTimeMillis()
                        session.notifySearchResultChanged(browser, query, list.size, params)
                    }.onFailure { e ->
                        reportException(e)
                        session.notifySearchResultChanged(browser, query, 0, params)
                    }
                } else {
                    session.notifySearchResultChanged(browser, query, cached.first.size, params)
                }
            }
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return scope.future(Dispatchers.IO) {
            if (query.isEmpty()) {
                return@future LibraryResult.ofItemList(emptyList(), params)
            }

            try {
                val searchResults = mutableListOf<MediaItem>()

                val localSongs = database.allSongs().first().filter { song ->
                    song.song.title.contains(query, ignoreCase = true) ||
                        song.artists.any { it.name.contains(query, ignoreCase = true) } ||
                        song.album?.title?.contains(query, ignoreCase = true) == true
                }

                // Keep local results only; online results come from cache.
                localSongs.forEach { song ->
                    searchResults.add(song.toMediaItem(
                        path = "${MusicService.SEARCH}/$query",
                        isPlayable = true,
                        isBrowsable = true
                    ))
                }

                val cachedOnline = remoteSearchSongs[query]
                    ?.takeIf { System.currentTimeMillis() - it.second <= REMOTE_TTL_MS }
                    ?.first
                    ?: emptyList()

                val filteredOnline = cachedOnline
                    .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                    .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))

                filteredOnline.forEach { songItem ->
                    searchResults.add(
                        MediaItem.Builder()
                            .setMediaId("${MusicService.SEARCH}/$query/${songItem.id}")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(songItem.title)
                                    .setSubtitle(songItem.artists.joinToString(", ") { it.name })
                                    .setArtist(songItem.artists.joinToString(", ") { it.name })
                                    .setArtworkUri(songItem.thumbnail.toUri())
                                    .setIsPlayable(true)
                                    .setIsBrowsable(true)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                    .build()
                            )
                            .build()
                    )
                }

                LibraryResult.ofItemList(searchResults, params)
            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofItemList(emptyList(), params)
            }
        }
    }

    // onSetMediaItems and helper functions kept unchanged (omitted for brevity in this bundled file)

    private fun drawableUri(
        @DrawableRes id: Int,
    ) = Uri
        .Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(id))
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()

    private fun browsableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        iconUri: Uri?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
    ) = MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(iconUri)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(mediaType)
                .build(),
        ).build()

    private fun Song.toMediaItem(path: String, isPlayable: Boolean = true, isBrowsable: Boolean = false): MediaItem {
        val artworkUri = song.thumbnailUrl?.let {
            val snapshot = context.imageLoader.diskCache?.openSnapshot(it)
            if (snapshot != null) {
                snapshot.use { snap -> snap.data.toFile().toUri() }
            } else {
                it.toUri()
            }
        }

        return MediaItem
            .Builder()
            .setMediaId("$path/$id")
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(song.title)
                    .setSubtitle(artists.joinToString { it.name })
                    .setArtist(artists.joinToString { it.name })
                    .setArtworkUri(artworkUri)
                    .setIsPlayable(isPlayable)
                    .setIsBrowsable(isBrowsable)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()
    }
}

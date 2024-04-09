package app.vitune.android.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.LoudnessEnhancer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import app.vitune.android.Database
import app.vitune.android.MainActivity
import app.vitune.android.R
import app.vitune.android.models.Event
import app.vitune.android.models.Format
import app.vitune.android.models.QueuedMediaItem
import app.vitune.android.models.Song
import app.vitune.android.models.SongWithContentLength
import app.vitune.android.preferences.AppearancePreferences
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.preferences.PlayerPreferences
import app.vitune.android.query
import app.vitune.android.transaction
import app.vitune.android.utils.ConditionalCacheDataSourceFactory
import app.vitune.android.utils.GlyphInterface
import app.vitune.android.utils.InvincibleService
import app.vitune.android.utils.TimerJob
import app.vitune.android.utils.YouTubeRadio
import app.vitune.android.utils.activityPendingIntent
import app.vitune.android.utils.broadcastPendingIntent
import app.vitune.android.utils.findNextMediaItemById
import app.vitune.android.utils.forcePlayFromBeginning
import app.vitune.android.utils.forceSeekToNext
import app.vitune.android.utils.forceSeekToPrevious
import app.vitune.android.utils.intent
import app.vitune.android.utils.mediaItems
import app.vitune.android.utils.progress
import app.vitune.android.utils.setPlaybackPitch
import app.vitune.android.utils.shouldBePlaying
import app.vitune.android.utils.thumbnail
import app.vitune.android.utils.timer
import app.vitune.android.utils.toast
import app.vitune.core.data.enums.ExoPlayerDiskCacheSize
import app.vitune.core.data.utils.RingBuffer
import app.vitune.core.ui.utils.isAtLeastAndroid10
import app.vitune.core.ui.utils.isAtLeastAndroid12
import app.vitune.core.ui.utils.isAtLeastAndroid13
import app.vitune.core.ui.utils.isAtLeastAndroid6
import app.vitune.core.ui.utils.isAtLeastAndroid8
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.NavigationEndpoint
import app.vitune.providers.innertube.models.bodies.PlayerBody
import app.vitune.providers.innertube.models.bodies.SearchBody
import app.vitune.providers.innertube.requests.player
import app.vitune.providers.innertube.requests.searchPage
import app.vitune.providers.innertube.utils.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import android.os.Binder as AndroidBinder

const val LOCAL_KEY_PREFIX = "local:"

@get:OptIn(UnstableApi::class)
val DataSpec.isLocal get() = key?.startsWith(LOCAL_KEY_PREFIX) == true

val MediaItem.isLocal get() = mediaId.startsWith(LOCAL_KEY_PREFIX)
val Song.isLocal get() = id.startsWith(LOCAL_KEY_PREFIX)

@kotlin.OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass", "TooManyFunctions") // intended in this class: it is a service
@OptIn(UnstableApi::class)
class PlayerService : InvincibleService(), Player.Listener, PlaybackStatsListener.Callback {
    private lateinit var mediaSession: MediaSession
    private lateinit var cache: SimpleCache
    private lateinit var player: ExoPlayer

    private val defaultActions =
        PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_REWIND or
                PlaybackState.ACTION_PLAY_FROM_SEARCH

    private val stateBuilder
        get() = PlaybackState.Builder().setActions(
            defaultActions.let {
                if (isAtLeastAndroid12) it or PlaybackState.ACTION_SET_PLAYBACK_SPEED else it
            }
        ).addCustomAction(
            /* action = */ "LIKE",
            /* name   = */ "Like",
            /* icon   = */ if (isLikedState.value) R.drawable.heart else R.drawable.heart_outline
        )

    private val playbackStateMutex = Mutex()

    private val metadataBuilder = MediaMetadata.Builder()

    private var notificationManager: NotificationManager? = null
    private var timerJob: TimerJob? = null
    private var radio: YouTubeRadio? = null

    private lateinit var bitmapProvider: BitmapProvider

    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
    private var preferenceUpdaterJob: Job? = null

    private var volumeNormalizationJob: Job? = null

    override var isInvincibilityEnabled = false

    private var audioManager: AudioManager? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var bassBoost: BassBoost? = null

    private val binder = Binder()

    private var isNotificationStarted = false

    override val notificationId get() = NOTIFICATION_ID

    private lateinit var notificationActionReceiver: NotificationActionReceiver

    private val mediaItemState = MutableStateFlow<MediaItem?>(null)
    private val isLikedState = mediaItemState
        .flatMapMerge { item ->
            item?.mediaId?.let {
                Database
                    .likedAt(it)
                    .distinctUntilChanged()
                    .cancellable()
            } ?: flowOf(null)
        }
        .map { it != null }
        .stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    private val glyphInterface by lazy { GlyphInterface(applicationContext) }

    override fun onBind(intent: Intent?): AndroidBinder {
        super.onBind(intent)
        return binder
    }

    @Suppress("CyclomaticComplexMethod")
    override fun onCreate() {
        super.onCreate()

        glyphInterface.tryInit()

        bitmapProvider = BitmapProvider(
            getBitmapSize = {
                (512 * resources.displayMetrics.density)
                    .roundToInt()
                    .coerceAtMost(AppearancePreferences.maxThumbnailSize)
            },
            getColor = { isSystemInDarkMode ->
                if (isSystemInDarkMode) Color.BLACK else Color.WHITE
            }
        )

        createNotificationChannel()

        cache = createCache(this)

        player = ExoPlayer.Builder(this, createRendersFactory(), createMediaSourceFactory())
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setUsePlatformDiagnostics(false)
            .build()

        updateRepeatMode()

        player.skipSilenceEnabled = PlayerPreferences.skipSilence
        player.addListener(this)
        player.addAnalyticsListener(PlaybackStatsListener(false, this))

        maybeRestorePlayerQueue()

        mediaSession = MediaSession(baseContext, "PlayerService")
        mediaSession.setCallback(SessionCallback(player))
        mediaSession.setPlaybackState(stateBuilder.build())
        mediaSession.setSessionActivity(activityPendingIntent<MainActivity>())
        mediaSession.isActive = true

        coroutineScope.launch {
            var first = true
            combine(mediaItemState, isLikedState) { mediaItem, _ ->
                // work around NPE in other processes
                if (first) {
                    first = false
                    return@combine
                }
                if (mediaItem == null) return@combine
                withContext(Dispatchers.Main) {
                    updatePlaybackState()
                    // work around NPE in other processes
                    handler.post {
                        runCatching {
                            applicationContext.getSystemService<NotificationManager>()
                                ?.notify(NOTIFICATION_ID, notification())
                        }
                    }
                }
            }.collect()
        }

        notificationActionReceiver = NotificationActionReceiver()

        val filter = IntentFilter().apply {
            addAction(Action.play.value)
            addAction(Action.pause.value)
            addAction(Action.next.value)
            addAction(Action.previous.value)
            addAction(Action.like.value)
        }

        ContextCompat.registerReceiver(
            /* context  = */ this,
            /* receiver = */ notificationActionReceiver,
            /* filter   = */ filter,
            /* flags    = */ ContextCompat.RECEIVER_NOT_EXPORTED
        )

        maybeResumePlaybackWhenDeviceConnected()

        fun <T> CoroutineScope.subscribe(
            state: StateFlow<T>,
            runner: (() -> Unit) -> Unit = { handler.post(it) },
            callback: suspend (T) -> () -> Unit
        ) = launch {
            state.collectLatest {
                runner(callback(it))
            }
        }

        preferenceUpdaterJob = coroutineScope.launch {
            subscribe(PlayerPreferences.resumePlaybackWhenDeviceConnectedProperty.stateFlow) {
                ::maybeResumePlaybackWhenDeviceConnected
            }
            subscribe(AppearancePreferences.isShowingThumbnailInLockscreenProperty.stateFlow) {
                ::maybeShowSongCoverInLockScreen
            }
            subscribe(PlayerPreferences.trackLoopEnabledProperty.stateFlow) {
                ::updateRepeatMode
            }
            subscribe(PlayerPreferences.queueLoopEnabledProperty.stateFlow) {
                ::updateRepeatMode
            }
            subscribe(PlayerPreferences.volumeNormalizationProperty.stateFlow) {
                ::maybeNormalizeVolume
            }
            subscribe(PlayerPreferences.volumeNormalizationBaseGainProperty.stateFlow) {
                ::maybeNormalizeVolume
            }
            subscribe(PlayerPreferences.bassBoostProperty.stateFlow) {
                ::maybeBassBoost
            }
            subscribe(PlayerPreferences.bassBoostLevelProperty.stateFlow) {
                ::maybeBassBoost
            }
            subscribe(PlayerPreferences.speedProperty.stateFlow) {
                {
                    player.setPlaybackSpeed(it.coerceAtLeast(0.01f))
                }
            }
            subscribe(PlayerPreferences.pitchProperty.stateFlow) {
                {
                    player.setPlaybackPitch(it.coerceAtLeast(0.01f))
                }
            }
            subscribe(PlayerPreferences.isInvincibilityEnabledProperty.stateFlow) {
                {
                    this@PlayerService.isInvincibilityEnabled = it
                }
            }
            subscribe(PlayerPreferences.skipSilenceProperty.stateFlow) {
                {
                    player.skipSilenceEnabled = it
                }
            }
        }
    }

    private fun updateRepeatMode() {
        player.repeatMode = when {
            PlayerPreferences.trackLoopEnabled -> Player.REPEAT_MODE_ONE
            PlayerPreferences.queueLoopEnabled -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.shouldBePlaying || PlayerPreferences.stopWhenClosed)
            broadcastPendingIntent<NotificationDismissReceiver>().send()
        super.onTaskRemoved(rootIntent)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
        maybeSavePlayerQueue()

    override fun onDestroy() {
        runCatching {
            maybeSavePlayerQueue()

            player.removeListener(this)
            player.stop()
            player.release()

            unregisterReceiver(notificationActionReceiver)

            mediaSession.isActive = false
            mediaSession.release()
            cache.release()

            loudnessEnhancer?.release()

            preferenceUpdaterJob?.cancel()

            coroutineScope.cancel()

            glyphInterface.close()
        }

        super.onDestroy()
    }

    override fun shouldBeInvincible() = !player.shouldBePlaying

    override fun onConfigurationChanged(newConfig: Configuration) {
        handler.post {
            runCatching {
                if (bitmapProvider.setDefaultBitmap() && player.currentMediaItem != null)
                    notificationManager?.notify(NOTIFICATION_ID, notification())
            }
        }
        super.onConfigurationChanged(newConfig)
    }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats
    ) {
        val mediaItem =
            eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem

        val totalPlayTimeMs = playbackStats.totalPlayTimeMs

        if (totalPlayTimeMs > 5000 && !DataPreferences.pausePlaytime) query {
            Database.incrementTotalPlayTimeMs(mediaItem.mediaId, totalPlayTimeMs)
        }

        if (totalPlayTimeMs > 30000 && !DataPreferences.pauseHistory) query {
            runCatching {
                Database.insert(
                    Event(
                        songId = mediaItem.mediaId,
                        timestamp = System.currentTimeMillis(),
                        playTime = totalPlayTimeMs
                    )
                )
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        mediaItemState.update { mediaItem }

        maybeRecoverPlaybackError()
        maybeNormalizeVolume()
        maybeProcessRadio()

        with(bitmapProvider) {
            when {
                mediaItem == null -> load(null)
                mediaItem.mediaMetadata.artworkUri == lastUri -> bitmapProvider.load(lastUri)
            }
        }

        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            updateMediaSessionQueue(player.currentTimeline)
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        updateMediaSessionQueue(timeline)
        maybeSavePlayerQueue()
    }

    private fun updateMediaSessionQueue(timeline: Timeline) {
        val builder = MediaDescription.Builder()

        val currentMediaItemIndex = player.currentMediaItemIndex
        val lastIndex = timeline.windowCount - 1
        var startIndex = currentMediaItemIndex - 7
        var endIndex = currentMediaItemIndex + 7

        if (startIndex < 0) endIndex -= startIndex

        if (endIndex > lastIndex) {
            startIndex -= (endIndex - lastIndex)
            endIndex = lastIndex
        }

        startIndex = startIndex.coerceAtLeast(0)

        mediaSession.setQueue(
            List(endIndex - startIndex + 1) { index ->
                val mediaItem = timeline.getWindow(index + startIndex, Timeline.Window()).mediaItem
                MediaSession.QueueItem(
                    builder
                        .setMediaId(mediaItem.mediaId)
                        .setTitle(mediaItem.mediaMetadata.title)
                        .setSubtitle(mediaItem.mediaMetadata.artist)
                        .setIconUri(mediaItem.mediaMetadata.artworkUri)
                        .build(),
                    (index + startIndex).toLong()
                )
            }
        )
    }

    private fun maybeRecoverPlaybackError() {
        if (player.playerError != null) player.prepare()
    }

    private fun maybeProcessRadio() = radio?.let { radio ->
        if (player.mediaItemCount - player.currentMediaItemIndex <= 3)
            coroutineScope.launch(Dispatchers.Main) {
                player.addMediaItems(radio.process())
            }
        Unit
    }

    private fun maybeSavePlayerQueue() {
        if (!PlayerPreferences.persistentQueue) return

        val mediaItems = player.currentTimeline.mediaItems
        val mediaItemIndex = player.currentMediaItemIndex
        val mediaItemPosition = player.currentPosition

        query {
            Database.clearQueue()
            Database.insert(
                mediaItems.mapIndexed { index, mediaItem ->
                    QueuedMediaItem(
                        mediaItem = mediaItem,
                        position = if (index == mediaItemIndex) mediaItemPosition else null
                    )
                }
            )
        }
    }

    private fun maybeRestorePlayerQueue() {
        if (!PlayerPreferences.persistentQueue) return

        query {
            val queuedSong = Database.queue()
            Database.clearQueue()

            if (queuedSong.isEmpty()) return@query

            val index = queuedSong.indexOfFirst { it.position != null }.coerceAtLeast(0)

            runBlocking(Dispatchers.Main) {
                player.setMediaItems(
                    queuedSong.map { mediaItem ->
                        mediaItem.mediaItem.buildUpon()
                            .setUri(mediaItem.mediaItem.mediaId)
                            .setCustomCacheKey(mediaItem.mediaItem.mediaId)
                            .build().apply {
                                mediaMetadata.extras?.putBoolean("isFromPersistentQueue", true)
                            }
                    },
                    index,
                    queuedSong[index].position ?: C.TIME_UNSET
                )
                player.prepare()

                isNotificationStarted = true
                startForegroundService(this@PlayerService, intent<PlayerService>())
                startForeground(NOTIFICATION_ID, notification())
            }
        }
    }

    @Suppress("ReturnCount")
    private fun maybeNormalizeVolume() {
        if (!PlayerPreferences.volumeNormalization) {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            volumeNormalizationJob?.cancel()
            volumeNormalizationJob?.invokeOnCompletion { volumeNormalizationJob = null }
            player.volume = 1f
            return
        }

        runCatching {
            if (loudnessEnhancer == null) loudnessEnhancer = LoudnessEnhancer(player.audioSessionId)
        }.onFailure { return }

        val songId = player.currentMediaItem?.mediaId ?: return
        volumeNormalizationJob?.cancel()
        volumeNormalizationJob = coroutineScope.launch {
            runCatching {
                Database.loudnessDb(songId).cancellable().collectLatest { loudness ->
                    Database.loudnessBoost(songId).cancellable().collectLatest { boost ->
                        withContext(Dispatchers.Main) {
                            loudnessEnhancer?.setTargetGain(
                                PlayerPreferences.volumeNormalizationBaseGainRounded +
                                        ((boost ?: 0f) * 100).toInt() -
                                        ((loudness ?: 0f) * 100).toInt()
                            )
                            loudnessEnhancer?.enabled = true
                        }
                    }
                }
            }
        }
    }

    private fun maybeBassBoost() {
        if (!PlayerPreferences.bassBoost) {
            runCatching {
                bassBoost?.enabled = false
                bassBoost?.release()
            }
            bassBoost = null
            maybeNormalizeVolume()
            return
        }

        runCatching {
            if (bassBoost == null) bassBoost = BassBoost(0, player.audioSessionId)
            bassBoost?.setStrength(PlayerPreferences.bassBoostLevel.toShort())
            bassBoost?.enabled = true
        }.onFailure {
            toast(getString(R.string.error_bassboost_init))
        }
    }

    private fun maybeShowSongCoverInLockScreen() = handler.post {
        val bitmap = if (isAtLeastAndroid13 || AppearancePreferences.isShowingThumbnailInLockscreen)
            bitmapProvider.bitmap else null

        metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
        metadataBuilder.putString(
            MediaMetadata.METADATA_KEY_ART_URI,
            player.mediaMetadata.artworkUri?.toString()?.thumbnail(512)
        )

        if (isAtLeastAndroid13 && player.currentMediaItemIndex == 0) metadataBuilder.putText(
            MediaMetadata.METADATA_KEY_TITLE,
            "${player.mediaMetadata.title} "
        )

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun maybeResumePlaybackWhenDeviceConnected() {
        if (!isAtLeastAndroid6) return

        if (!PlayerPreferences.resumePlaybackWhenDeviceConnected) {
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioDeviceCallback = null
            return
        }
        if (audioManager == null) audioManager = getSystemService<AudioManager>()

        audioDeviceCallback =
            @SuppressLint("NewApi")
            object : AudioDeviceCallback() {
                private fun canPlayMusic(audioDeviceInfo: AudioDeviceInfo) =
                    audioDeviceInfo.isSink && (
                            audioDeviceInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                                    audioDeviceInfo.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                                    audioDeviceInfo.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                            )
                        .let {
                            if (!isAtLeastAndroid8) it else
                                it || audioDeviceInfo.type == AudioDeviceInfo.TYPE_USB_HEADSET
                        }

                override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                    if (!player.isPlaying && addedDevices.any(::canPlayMusic)) player.play()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = Unit
            }

        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, handler)
    }

    private fun sendOpenEqualizerIntent() = sendBroadcast(
        Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        }
    )

    private fun sendCloseEqualizerIntent() = sendBroadcast(
        Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
        }
    )

    private fun updatePlaybackState() = coroutineScope.launch {
        playbackStateMutex.withLock {
            withContext(Dispatchers.Main) {
                mediaSession.setPlaybackState(
                    stateBuilder
                        .setState(player.androidPlaybackState, player.currentPosition, 1f)
                        .setBufferedPosition(player.bufferedPosition)
                        .build()
                )
            }
        }
    }

    private val Player.androidPlaybackState
        get() = when (playbackState) {
            Player.STATE_BUFFERING -> if (playWhenReady) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED
            Player.STATE_READY -> if (playWhenReady) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
            Player.STATE_IDLE -> PlaybackState.STATE_NONE
            else -> PlaybackState.STATE_NONE
        }

    // legacy behavior may cause inconsistencies, but not available on sdk 24 or lower
    @Suppress("DEPRECATION")
    override fun onEvents(player: Player, events: Player.Events) {
        if (player.duration != C.TIME_UNSET) mediaSession.setMetadata(
            metadataBuilder
                .putText(
                    MediaMetadata.METADATA_KEY_TITLE,
                    player.mediaMetadata.title?.toString().orEmpty()
                )
                .putText(
                    MediaMetadata.METADATA_KEY_ARTIST,
                    player.mediaMetadata.artist?.toString().orEmpty()
                )
                .putText(
                    MediaMetadata.METADATA_KEY_ALBUM,
                    player.mediaMetadata.albumTitle?.toString().orEmpty()
                )
                .putLong(MediaMetadata.METADATA_KEY_DURATION, player.duration)
                .build()
        )

        updatePlaybackState()

        if (
            events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY
            )
        ) {
            val notification = notification()

            if (notification == null) {
                isNotificationStarted = false
                makeInvincible(false)
                stopForeground(false)
                sendCloseEqualizerIntent()
                notificationManager?.cancel(NOTIFICATION_ID)
                return
            }

            if (player.shouldBePlaying && !isNotificationStarted) {
                isNotificationStarted = true
                startForegroundService(this@PlayerService, intent<PlayerService>())
                startForeground(NOTIFICATION_ID, notification)
                makeInvincible(false)
                sendOpenEqualizerIntent()
            } else {
                if (!player.shouldBePlaying) {
                    isNotificationStarted = false
                    stopForeground(false)
                    makeInvincible(true)
                    sendCloseEqualizerIntent()
                }
                runCatching {
                    notificationManager?.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    override fun notification(): Notification? {
        if (player.currentMediaItem == null) return null

        val playIntent = Action.play.pendingIntent
        val pauseIntent = Action.pause.pendingIntent
        val nextIntent = Action.next.pendingIntent
        val prevIntent = Action.previous.pendingIntent
        val likeIntent = Action.like.pendingIntent

        val mediaMetadata = player.mediaMetadata

        @Suppress("DEPRECATION") // support for SDK < 26
        val builder = if (isAtLeastAndroid8) {
            Notification.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(applicationContext)
        }
            .setContentTitle(mediaMetadata.title)
            .setContentText(mediaMetadata.artist)
            .setSubText(player.playerError?.message)
            .setLargeIcon(bitmapProvider.bitmap)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSmallIcon(
                player.playerError?.let { R.drawable.alert_circle } ?: R.drawable.app_icon
            )
            .setOngoing(false)
            .setContentIntent(
                activityPendingIntent<MainActivity>(flags = PendingIntent.FLAG_UPDATE_CURRENT) {
                    putExtra("fromNotification", true)
                }
            )
            .setDeleteIntent(broadcastPendingIntent<NotificationDismissReceiver>())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setStyle(
                Notification.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setMediaSession(mediaSession.sessionToken)
            )
            .addAction(R.drawable.play_skip_back, getString(R.string.skip_back), prevIntent)
            .addAction(
                if (player.shouldBePlaying) R.drawable.pause else R.drawable.play,
                if (player.shouldBePlaying) getString(R.string.pause) else getString(R.string.play),
                if (player.shouldBePlaying) pauseIntent else playIntent
            )
            .addAction(R.drawable.play_skip_forward, getString(R.string.skip_forward), nextIntent)
            .addAction(
                if (isLikedState.value) R.drawable.heart else R.drawable.heart_outline,
                getString(R.string.like),
                likeIntent
            )

        bitmapProvider.load(mediaMetadata.artworkUri) { bitmap ->
            maybeShowSongCoverInLockScreen()
            handler.post {
                runCatching {
                    notificationManager?.notify(
                        NOTIFICATION_ID,
                        builder.setLargeIcon(bitmap).build()
                    )
                }
            }
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        notificationManager = getSystemService()
        if (!isAtLeastAndroid8) return

        notificationManager?.createNotificationChannel(
            NotificationChannel(
                /* id = */ NOTIFICATION_CHANNEL_ID,
                /* name = */ getString(R.string.now_playing),
                /* importance = */ NotificationManager.IMPORTANCE_LOW
            )
        )
        notificationManager?.createNotificationChannel(
            NotificationChannel(
                /* id = */ SLEEP_TIMER_NOTIFICATION_CHANNEL_ID,
                /* name = */ getString(R.string.sleep_timer),
                /* importance = */ NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun createMediaSourceFactory() = DefaultMediaSourceFactory(
        /* dataSourceFactory = */ createYouTubeDataSourceResolverFactory(
            findMediaItem = { videoId ->
                runBlocking(Dispatchers.Main) {
                    player.findNextMediaItemById(videoId)
                }
            },
            context = applicationContext,
            cache = cache
        ),
        /* extractorsFactory = */ DefaultExtractorsFactory()
    )

    private fun createRendersFactory(): RenderersFactory {
        val minimumSilenceDuration = PlayerPreferences.minimumSilence.coerceIn(1000L..2_000_000L)
        val audioSink = DefaultAudioSink.Builder(applicationContext)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(false)
            .setAudioOffloadSupportProvider(DefaultAudioOffloadSupportProvider(applicationContext))
            .setAudioProcessorChain(
                DefaultAudioProcessorChain(
                    arrayOf(),
                    SilenceSkippingAudioProcessor(
                        /* minimumSilenceDurationUs = */ minimumSilenceDuration,
                        /* silenceRetentionRatio = */ 0.01f,
                        /* maxSilenceToKeepDurationUs = */ minimumSilenceDuration,
                        /* minVolumeToKeepPercentageWhenMuting = */ 0,
                        /* silenceThresholdLevel = */ 256
                    ),
                    SonicAudioProcessor()
                )
            )
            .build()
            .apply {
                if (isAtLeastAndroid10) setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
            }

        return RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf(
                MediaCodecAudioRenderer(
                    /* context = */ this,
                    /* mediaCodecSelector = */ MediaCodecSelector.DEFAULT,
                    /* eventHandler = */ handler,
                    /* eventListener = */ audioListener,
                    /* audioSink = */ audioSink
                )
            )
        }
    }

    inner class Binder : AndroidBinder() {
        val player: ExoPlayer
            get() = this@PlayerService.player

        val cache: Cache
            get() = this@PlayerService.cache

        val mediaSession
            get() = this@PlayerService.mediaSession

        val sleepTimerMillisLeft: StateFlow<Long?>?
            get() = timerJob?.millisLeft

        private var radioJob: Job? = null

        var isLoadingRadio by mutableStateOf(false)
            private set

        var invincible
            get() = isInvincibilityEnabled
            set(value) {
                isInvincibilityEnabled = value
            }

        fun setBitmapListener(listener: ((Bitmap?) -> Unit)?) = bitmapProvider.setListener(listener)

        @kotlin.OptIn(FlowPreview::class)
        fun startSleepTimer(delayMillis: Long) {
            timerJob?.cancel()

            timerJob = coroutineScope.timer(delayMillis) {
                val notification = NotificationCompat
                    .Builder(this@PlayerService, SLEEP_TIMER_NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(getString(R.string.sleep_timer_ended))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(true)
                    .setSmallIcon(R.drawable.app_icon)
                    .build()

                notificationManager?.notify(SLEEP_TIMER_NOTIFICATION_ID, notification)

                handler.post {
                    player.pause()
                    player.stop()

                    glyphInterface.glyph {
                        turnOff()
                    }
                }
            }.also { job ->
                glyphInterface.progress(
                    job
                        .millisLeft
                        .takeWhile { it != null }
                        .debounce(500)
                        .map { ((it ?: 0L) / delayMillis.toFloat() * 100).toInt() }
                )
            }
        }

        fun cancelSleepTimer() {
            timerJob?.cancel()
            timerJob = null
        }

        fun setupRadio(endpoint: NavigationEndpoint.Endpoint.Watch?) =
            startRadio(endpoint = endpoint, justAdd = true)

        fun playRadio(endpoint: NavigationEndpoint.Endpoint.Watch?) =
            startRadio(endpoint = endpoint, justAdd = false)

        private fun startRadio(endpoint: NavigationEndpoint.Endpoint.Watch?, justAdd: Boolean) {
            radioJob?.cancel()
            radio = null
            YouTubeRadio(
                endpoint?.videoId,
                endpoint?.playlistId,
                endpoint?.playlistSetVideoId,
                endpoint?.params
            ).let { radioData ->
                isLoadingRadio = true
                radioJob = coroutineScope.launch(Dispatchers.Main) {
                    val items = radioData.process().let { Database.filterBlacklistedSongs(it) }
                    if (justAdd) player.addMediaItems(items.drop(1))
                    else player.forcePlayFromBeginning(items)

                    radio = radioData
                    isLoadingRadio = false
                }
            }
        }

        fun stopRadio() {
            isLoadingRadio = false
            radioJob?.cancel()
            radio = null
        }

        /**
         * This method should ONLY be called when the application (sc. activity) is in the foreground!
         */
        fun restartForegroundOrStop() {
            player.pause()
            isInvincibilityEnabled = false
            stopSelf()
        }

        fun isCached(song: SongWithContentLength) =
            song.contentLength?.let { cache.isCached(song.song.id, 0L, it) } ?: false

        fun playFromSearch(query: String) {
            coroutineScope.launch {
                Innertube.searchPage(
                    body = SearchBody(
                        query = query,
                        params = Innertube.SearchFilter.Song.value
                    ),
                    fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
                )
                    ?.getOrNull()
                    ?.items
                    ?.firstOrNull()
                    ?.info
                    ?.endpoint
                    ?.let { playRadio(it) }
            }
        }
    }

    private fun likeAction() = mediaItemState.value?.let { mediaItem ->
        transaction {
            Database.like(
                songId = mediaItem.mediaId,
                likedAt = if (isLikedState.value) null else System.currentTimeMillis()
            )
        }
    }.let { }

    private inner class SessionCallback(private val player: Player) : MediaSession.Callback() {
        override fun onPlay() = player.play()
        override fun onPause() = player.pause()
        override fun onSkipToPrevious() = runCatching(player::forceSeekToPrevious).let { }
        override fun onSkipToNext() = runCatching(player::forceSeekToNext).let { }
        override fun onSeekTo(pos: Long) = player.seekTo(pos)
        override fun onStop() = player.pause()
        override fun onRewind() = player.seekToDefaultPosition()
        override fun onSkipToQueueItem(id: Long) =
            runCatching { player.seekToDefaultPosition(id.toInt()) }.let { }

        override fun onSetPlaybackSpeed(speed: Float) {
            PlayerPreferences.speed = speed.coerceIn(0.01f..2f)
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            if (query.isNullOrBlank()) return
            binder.playFromSearch(query)
        }

        override fun onCustomAction(action: String, extras: Bundle?) {
            super.onCustomAction(action, extras)
            if (action == "LIKE") likeAction()
        }
    }

    inner class NotificationActionReceiver internal constructor() : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Action.pause.value -> player.pause()
                Action.play.value -> player.play()
                Action.next.value -> player.forceSeekToNext()
                Action.previous.value -> player.forceSeekToPrevious()
                Action.like.value -> likeAction()
            }
        }
    }

    class NotificationDismissReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            context.stopService(context.intent<PlayerService>())
        }
    }

    @JvmInline
    private value class Action(val value: String) {
        context(Context)
        val pendingIntent: PendingIntent
            get() = PendingIntent.getBroadcast(
                /* context = */ this@Context,
                /* requestCode = */ 100,
                /* intent = */ Intent(value).setPackage(packageName),
                /* flags = */ PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (isAtLeastAndroid6) PendingIntent.FLAG_IMMUTABLE else 0)
            )

        companion object {
            val pause = Action("app.vitune.android.pause")
            val play = Action("app.vitune.android.play")
            val next = Action("app.vitune.android.next")
            val previous = Action("app.vitune.android.previous")
            val like = Action("app.vitune.android.like")
        }
    }

    companion object {
        fun createDatabaseProvider(context: Context) = StandaloneDatabaseProvider(context)
        fun createCache(context: Context) = with(context) {
            val cacheEvictor = when (val size = DataPreferences.exoPlayerDiskCacheMaxSize) {
                ExoPlayerDiskCacheSize.Unlimited -> NoOpCacheEvictor()
                else -> LeastRecentlyUsedCacheEvictor(size.bytes)
            }

            val directory = cacheDir.resolve("exoplayer").apply {
                if (!exists()) mkdir()
            }

            SimpleCache(directory, cacheEvictor, createDatabaseProvider(context))
        }

        private const val DEFAULT_CHUNK_LENGTH = 512 * 1024L

        // TODO: maybe fix this mess?
        /**
         * Creates a ResolvingDataSource.Factory for YouTube video's
         * Call site MUST either:
         * 1. Verify that the consumer of the factory always saves the MediaItem to the database
         * before trying to resolve the MediaItem
         * 2. Provide a usable MediaItem for the YouTube video with the videoId
         * 3. Make sure the database has a MediaItem for the given videoId and return null when it
         * does
         */
        @Suppress("CyclomaticComplexMethod")
        fun createYouTubeDataSourceResolverFactory(
            findMediaItem: (videoId: String) -> MediaItem?,
            context: Context,
            cache: Cache,
            chunkLength: Long? = DEFAULT_CHUNK_LENGTH
        ): ResolvingDataSource.Factory {
            val ringBuffer = RingBuffer<Pair<String, Uri>?>(2) { null }

            return ResolvingDataSource.Factory(
                ConditionalCacheDataSourceFactory(
                    cacheDataSourceFactory = CacheDataSource.Factory().setCache(cache),
                    upstreamDataSourceFactory = DefaultDataSource.Factory(
                        context,
                        DefaultHttpDataSource.Factory()
                            .setConnectTimeoutMs(16000)
                            .setReadTimeoutMs(8000)
                            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; rv:91.0) Gecko/20100101 Firefox/91.0")
                    )
                ) { !it.isLocal }
            ) { dataSpec ->
                // Thank you Android, for enforcing a Uri in the download request
                val videoId = dataSpec.key?.removePrefix("https://youtube.com/watch?v=")
                    ?: error("A key must be set")

                when {
                    dataSpec.isLocal || cache.isCached(
                        videoId,
                        dataSpec.position,
                        chunkLength ?: DEFAULT_CHUNK_LENGTH
                    ) -> dataSpec

                    videoId == ringBuffer[0]?.first ->
                        dataSpec.withUri(ringBuffer[0]!!.second)

                    videoId == ringBuffer[1]?.first ->
                        dataSpec.withUri(ringBuffer[1]!!.second)

                    else -> {
                        val body = runBlocking(Dispatchers.IO) {
                            Innertube.player(PlayerBody(videoId = videoId))
                        }?.getOrThrow()

                        if (body?.videoDetails?.videoId != videoId) throw VideoIdMismatchException()

                        val format = body.streamingData?.highestQualityFormat
                        val url = when (val status = body.playabilityStatus?.status) {
                            "OK" -> format?.let { _ ->
                                val mediaItem = findMediaItem(videoId)

                                if (mediaItem?.mediaMetadata?.extras?.getString("durationText") == null)
                                    format.approxDurationMs?.div(1000)
                                        ?.let(DateUtils::formatElapsedTime)?.removePrefix("0")
                                        ?.let { durationText ->
                                            mediaItem?.mediaMetadata?.extras?.putString(
                                                "durationText",
                                                durationText
                                            )
                                            Database.updateDurationText(videoId, durationText)
                                        }

                                query {
                                    mediaItem?.let(Database::insert)

                                    Database.insert(
                                        Format(
                                            songId = videoId,
                                            itag = format.itag,
                                            mimeType = format.mimeType,
                                            bitrate = format.bitrate,
                                            loudnessDb = body.playerConfig?.audioConfig?.normalizedLoudnessDb,
                                            contentLength = format.contentLength,
                                            lastModified = format.lastModified
                                        )
                                    )
                                }

                                format.url
                            } ?: throw PlayableFormatNotFoundException()

                            "UNPLAYABLE" -> throw UnplayableException()
                            "LOGIN_REQUIRED" -> throw LoginRequiredException()

                            else -> throw PlaybackException(
                                status,
                                null,
                                PlaybackException.ERROR_CODE_REMOTE_ERROR
                            )
                        }

                        ringBuffer += videoId to url.toUri()
                        dataSpec.buildUpon()
                            .setKey(videoId)
                            .setUri(url.toUri())
                            .build()
                            .let { spec ->
                                (chunkLength ?: format.contentLength)?.let {
                                    spec.subrange(dataSpec.uriPositionOffset, it)
                                } ?: spec
                            }
                    }
                }
            }
        }
    }
}

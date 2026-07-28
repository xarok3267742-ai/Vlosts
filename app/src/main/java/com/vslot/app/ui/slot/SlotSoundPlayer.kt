package com.vslot.app.ui.slot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.vslot.app.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil

internal enum class SlotSoundCue {
    SpinStart,
    ReelSpinLoop,
    ReelStop,
    Payout,
    Win,
    Bonus
}

internal object SlotSoundMix {
    private const val REEL_COUNT = 5
    private const val EDGE_REEL_PAN = 0.55f

    fun stereoVolumes(cue: SlotSoundCue, reelIndex: Int, volume: Float): Pair<Float, Float> {
        if (cue != SlotSoundCue.ReelStop) return volume to volume
        val centerIndex = (REEL_COUNT - 1) / 2f
        val normalizedPosition = (reelIndex.coerceIn(0, REEL_COUNT - 1) - centerIndex) / centerIndex
        val pan = normalizedPosition * EDGE_REEL_PAN
        val left = volume * (1f - pan.coerceAtLeast(0f))
        val right = volume * (1f + pan.coerceAtMost(0f))
        return left to right
    }
}

internal object SlotSoundTiming {
    private const val PLAYBACK_COMPLETION_GRACE_MS = 80L

    fun playbackDurationMs(cue: SlotSoundCue, playbackRate: Float): Long {
        val sourceDurationMs = when (cue) {
            SlotSoundCue.SpinStart -> 580L
            SlotSoundCue.ReelSpinLoop -> 750L
            SlotSoundCue.ReelStop -> 160L
            SlotSoundCue.Payout -> 380L
            SlotSoundCue.Win -> 960L
            SlotSoundCue.Bonus -> 1_380L
        }
        val boundedRate = playbackRate.coerceIn(MIN_PLAYBACK_RATE, MAX_PLAYBACK_RATE)
        return ceil(sourceDurationMs / boundedRate.toDouble()).toLong() +
            PLAYBACK_COMPLETION_GRACE_MS
    }

    private const val MIN_PLAYBACK_RATE = 0.5f
    private const val MAX_PLAYBACK_RATE = 2f
}

internal interface SlotAudioSession {
    fun start()
    fun isPlaybackAllowed(): Boolean
    fun requestAudioFocus(): Boolean
    fun abandonAudioFocus()
    fun release()
}

internal class SlotAudioFocusController(
    private val audioSession: SlotAudioSession
) {
    private var focusRequestActive = false

    @Synchronized
    fun prepareForPlayback(stopPlaybackWhenBlocked: () -> Unit = {}): Boolean {
        if (!audioSession.isPlaybackAllowed()) {
            stopPlaybackWhenBlocked()
            abandonAudioFocus()
            return false
        }
        if (focusRequestActive) return true

        return audioSession.requestAudioFocus().also { granted ->
            focusRequestActive = granted
        }
    }

    @Synchronized
    fun onFocusLost(stopPlayback: () -> Unit) {
        stopPlayback()
        abandonAudioFocus()
    }

    @Synchronized
    fun onPlaybackBecameIdle() {
        abandonAudioFocus()
    }

    @Synchronized
    fun release() {
        abandonAudioFocus()
    }

    private fun abandonAudioFocus() {
        if (!focusRequestActive) return
        focusRequestActive = false
        audioSession.abandonAudioFocus()
    }
}

internal class SlotSoundPlayer(context: Context) {
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(audioAttributes)
        .build()
    private val loadedSoundIds = ConcurrentHashMap.newKeySet<Int>()
    private val activeStreamIds = ConcurrentLinkedQueue<Int>()
    private val transientStreamIds = ConcurrentHashMap.newKeySet<Int>()
    private val fadingLoopStreamIds = ConcurrentHashMap.newKeySet<Int>()
    private val soundIds = ConcurrentHashMap<SlotSoundCue, Int>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioSession: SlotAudioSession = AndroidSlotAudioSession(
        context = context.applicationContext,
        audioAttributes = audioAttributes,
        callbackHandler = mainHandler,
        onAudioFocusLost = ::onAudioFocusLost,
        onPlaybackPolicyChanged = ::onPlaybackPolicyChanged
    )
    private val audioFocusController = SlotAudioFocusController(audioSession)

    @Volatile
    private var desiredLoopCue: SlotSoundCue? = null

    @Volatile
    private var activeLoopStreamId: Int = PLAY_FAILED

    @Volatile
    private var released = false

    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) stopAll()
        }

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (!released && status == LOAD_SUCCESS) {
                loadedSoundIds += sampleId
                desiredLoopCue
                    ?.takeIf { cue -> soundIds[cue] == sampleId }
                    ?.let(::startLoadedLoop)
            }
        }
        soundIds.putAll(mapOf(
            SlotSoundCue.SpinStart to soundPool.load(context, R.raw.slot_spin_start, LOAD_PRIORITY),
            SlotSoundCue.ReelSpinLoop to soundPool.load(context, R.raw.slot_reel_spin_loop, LOAD_PRIORITY),
            SlotSoundCue.ReelStop to soundPool.load(context, R.raw.slot_reel_stop, LOAD_PRIORITY),
            SlotSoundCue.Payout to soundPool.load(context, R.raw.slot_payout, LOAD_PRIORITY),
            SlotSoundCue.Win to soundPool.load(context, R.raw.slot_win, LOAD_PRIORITY),
            SlotSoundCue.Bonus to soundPool.load(context, R.raw.slot_bonus, LOAD_PRIORITY)
        ))
        audioSession.start()
    }

    fun play(cue: SlotSoundCue, reelIndex: Int = 0) {
        if (!enabled || released) return
        val soundId = soundIds[cue] ?: return
        if (soundId !in loadedSoundIds) return
        val volume = when (cue) {
            SlotSoundCue.SpinStart -> 0.48f
            SlotSoundCue.ReelSpinLoop -> 0.24f
            SlotSoundCue.ReelStop -> 0.42f
            SlotSoundCue.Payout -> 0.52f
            SlotSoundCue.Win -> 0.72f
            SlotSoundCue.Bonus -> 0.82f
        }
        val playbackRate = if (cue == SlotSoundCue.ReelStop) {
            (0.94f + reelIndex.coerceIn(0, 4) * 0.025f).coerceAtMost(MAX_PLAYBACK_RATE)
        } else {
            NORMAL_PLAYBACK_RATE
        }
        val (leftVolume, rightVolume) = SlotSoundMix.stereoVolumes(cue, reelIndex, volume)
        if (!audioFocusController.prepareForPlayback(::stopAll)) return
        val streamId = soundPool.play(
            soundId,
            leftVolume,
            rightVolume,
            PLAY_PRIORITY,
            NO_LOOP,
            playbackRate
        )
        if (streamId != PLAY_FAILED) {
            trackTransientStream(
                streamId = streamId,
                playbackDurationMs = SlotSoundTiming.playbackDurationMs(cue, playbackRate)
            )
        } else {
            abandonAudioFocusIfIdle()
        }
    }

    fun startReelSpinLoop() {
        if (!enabled || released) return
        desiredLoopCue = SlotSoundCue.ReelSpinLoop
        val soundId = soundIds[SlotSoundCue.ReelSpinLoop] ?: return
        if (soundId in loadedSoundIds) startLoadedLoop(SlotSoundCue.ReelSpinLoop)
    }

    fun stopReelSpinLoop() {
        stopLoop(fadeOut = true)
    }

    private fun startLoadedLoop(cue: SlotSoundCue) {
        if (!enabled || released || desiredLoopCue != cue || activeLoopStreamId != PLAY_FAILED) return
        val soundId = soundIds[cue] ?: return
        val volume = if (cue == SlotSoundCue.ReelSpinLoop) REEL_LOOP_VOLUME else return
        if (!audioFocusController.prepareForPlayback(::stopAll)) return
        val streamId = soundPool.play(
            soundId,
            volume,
            volume,
            PLAY_PRIORITY,
            LOOP_FOREVER,
            NORMAL_PLAYBACK_RATE
        )
        if (streamId != PLAY_FAILED) {
            activeLoopStreamId = streamId
            trackStream(streamId)
        } else {
            abandonAudioFocusIfIdle()
        }
    }

    private fun stopLoop(fadeOut: Boolean) {
        desiredLoopCue = null
        val streamId = activeLoopStreamId
        activeLoopStreamId = PLAY_FAILED
        if (!released && streamId != PLAY_FAILED) {
            activeStreamIds.remove(streamId)
            if (fadeOut) {
                fadeOutLoop(streamId)
            } else {
                soundPool.stop(streamId)
                abandonAudioFocusIfIdle()
            }
        }
    }

    private fun fadeOutLoop(streamId: Int) {
        fadingLoopStreamIds += streamId
        repeat(REEL_LOOP_FADE_STEPS) { index ->
            val step = index + 1
            mainHandler.postDelayed(
                {
                    if (released || streamId !in fadingLoopStreamIds) return@postDelayed
                    if (step == REEL_LOOP_FADE_STEPS) {
                        soundPool.stop(streamId)
                        fadingLoopStreamIds.remove(streamId)
                        abandonAudioFocusIfIdle()
                    } else {
                        val volume = REEL_LOOP_VOLUME *
                            (REEL_LOOP_FADE_STEPS - step).toFloat() / REEL_LOOP_FADE_STEPS
                        soundPool.setVolume(streamId, volume, volume)
                    }
                },
                step * REEL_LOOP_FADE_STEP_MS
            )
        }
    }

    private fun trackTransientStream(streamId: Int, playbackDurationMs: Long) {
        transientStreamIds += streamId
        trackStream(streamId)
        mainHandler.postDelayed(
            {
                if (transientStreamIds.remove(streamId)) {
                    activeStreamIds.remove(streamId)
                    abandonAudioFocusIfIdle()
                }
            },
            playbackDurationMs
        )
    }

    private fun trackStream(streamId: Int) {
        activeStreamIds += streamId
        while (activeStreamIds.size > MAX_TRACKED_STREAMS) {
            activeStreamIds.poll()
        }
    }

    fun stopAll() {
        if (released) return
        stopLoop(fadeOut = false)
        fadingLoopStreamIds.forEach(soundPool::stop)
        fadingLoopStreamIds.clear()
        while (true) {
            val streamId = activeStreamIds.poll() ?: break
            soundPool.stop(streamId)
        }
        transientStreamIds.clear()
        audioFocusController.onPlaybackBecameIdle()
    }

    fun release() {
        if (released) return
        stopAll()
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        loadedSoundIds.clear()
        soundPool.setOnLoadCompleteListener(null)
        audioFocusController.release()
        audioSession.release()
        soundPool.release()
    }

    private fun onAudioFocusLost() {
        if (!released) {
            audioFocusController.onFocusLost(::stopAll)
        }
    }

    private fun onPlaybackPolicyChanged() {
        if (!released && !audioSession.isPlaybackAllowed()) {
            stopAll()
        }
    }

    private fun abandonAudioFocusIfIdle() {
        if (
            activeLoopStreamId == PLAY_FAILED &&
            activeStreamIds.isEmpty() &&
            transientStreamIds.isEmpty() &&
            fadingLoopStreamIds.isEmpty()
        ) {
            audioFocusController.onPlaybackBecameIdle()
        }
    }

    private companion object {
        const val MAX_STREAMS = 4
        const val LOAD_PRIORITY = 1
        const val LOAD_SUCCESS = 0
        const val PLAY_PRIORITY = 1
        const val PLAY_FAILED = 0
        const val NO_LOOP = 0
        const val LOOP_FOREVER = -1
        const val NORMAL_PLAYBACK_RATE = 1f
        const val MAX_PLAYBACK_RATE = 1.04f
        const val MAX_TRACKED_STREAMS = 16
        const val REEL_LOOP_VOLUME = 0.24f
        const val REEL_LOOP_FADE_STEPS = 4
        const val REEL_LOOP_FADE_STEP_MS = 18L
    }
}

private class AndroidSlotAudioSession(
    context: Context,
    audioAttributes: AudioAttributes,
    callbackHandler: Handler,
    private val onAudioFocusLost: () -> Unit,
    private val onPlaybackPolicyChanged: () -> Unit
) : SlotAudioSession {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    )
        .setAudioAttributes(audioAttributes)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(
            { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        if (!released) onAudioFocusLost()
                    }
                }
            },
            callbackHandler
        )
        .build()
    private val ringerModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!released && intent?.action == AudioManager.RINGER_MODE_CHANGED_ACTION) {
                onPlaybackPolicyChanged()
            }
        }
    }
    @Volatile
    private var released = false

    private var receiverRegistered = false

    override fun start() {
        if (!released && !receiverRegistered) {
            receiverRegistered = registerRingerModeReceiver()
        }
    }

    override fun isPlaybackAllowed(): Boolean {
        if (released) return false
        return runCatching {
            audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL
        }.getOrDefault(false)
    }

    override fun requestAudioFocus(): Boolean {
        if (released) return false
        return runCatching {
            audioManager.requestAudioFocus(audioFocusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.getOrDefault(false)
    }

    override fun abandonAudioFocus() {
        runCatching { audioManager.abandonAudioFocusRequest(audioFocusRequest) }
    }

    override fun release() {
        if (released) return
        released = true
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(ringerModeReceiver) }
        }
    }

    @Suppress("DEPRECATION")
    private fun registerRingerModeReceiver(): Boolean {
        return runCatching {
            val filter = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(
                    ringerModeReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                appContext.registerReceiver(ringerModeReceiver, filter)
            }
        }.isSuccess
    }
}

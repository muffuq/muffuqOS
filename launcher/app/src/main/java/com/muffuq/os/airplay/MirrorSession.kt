package com.muffuq.os.airplay

import java.net.InetAddress

/** Everything one connected sender has set up: timing, the video stream and the audio stream. */
class MirrorSession(
    private val client: String,
    private val key: ByteArray,
    private val iv: ByteArray,
    remote: InetAddress,
    remoteTimingPort: Int,
    private val volume: TvVolume,
) {
    private val ntp = NtpClient(remote, remoteTimingPort)
    val timingPort: Int get() = ntp.localPort

    private var videoReceiver: VideoReceiver? = null
    private var videoPlayer: VideoPlayer? = null
    private var audioReceiver: AudioReceiver? = null
    private var audioPlayer: AudioPlayer? = null
    private var volumeDb = 0f
    private val volumeLink = volume.Link()

    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var paused = false
    @Volatile private var showing = false

    /** No stream left: the sender has stopped mirroring even if its control connection stays open. */
    val isIdle: Boolean
        @Synchronized get() = videoReceiver == null && audioReceiver == null

    @Synchronized
    fun startVideo(streamConnectionId: Long): Int {
        stopVideo()
        MirrorBus.update(MirrorBus.State.Connecting(client))
        val player = VideoPlayer(
            onSize = { w, h ->
                width = w
                height = h
                publish()
            },
            onPause = { p ->
                if (p != paused) {
                    paused = p
                    publish()
                }
            },
            onFirstFrame = {
                showing = true
                publish()
            },
        )
        videoPlayer = player
        MirrorBus.activePlayer = player
        return VideoReceiver(key, streamConnectionId, player).also { videoReceiver = it }.port
    }

    @Synchronized
    fun startAudio(compressionType: Int, samplesPerFrame: Int): Pair<Int, Int> {
        stopAudio()
        val player = AudioPlayer(compressionType, samplesPerFrame).also {
            it.setVolume(volumeDb)
            audioPlayer = it
        }
        val receiver = AudioReceiver(key, iv, player).also { audioReceiver = it }
        return receiver.dataPort to receiver.controlPort
    }

    /** The sender's volume buttons: move the TV's own volume, or scale the stream where the TV's is fixed. */
    @Synchronized
    fun setVolume(db: Float) {
        if (volume.controlsTv) {
            volumeLink.onSenderVolume(db)
        } else {
            volumeDb = db
            audioPlayer?.setVolume(db)
        }
    }

    @Synchronized
    fun stopVideo() {
        videoReceiver?.close()
        videoPlayer?.release()
        if (MirrorBus.activePlayer === videoPlayer) MirrorBus.activePlayer = null
        videoReceiver = null
        videoPlayer = null
    }

    @Synchronized
    fun stopAudio() {
        audioReceiver?.close()
        audioPlayer?.close()
        audioReceiver = null
        audioPlayer = null
    }

    @Synchronized
    fun close() {
        stopVideo()
        stopAudio()
        ntp.close()
        MirrorBus.update(MirrorBus.State.Idle)
    }

    private fun publish() {
        if (width > 0 && height > 0) MirrorBus.update(MirrorBus.State.Mirroring(client, width, height, paused, showing))
    }
}

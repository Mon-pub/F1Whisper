package ch.threema.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import ch.threema.app.ExecutorServices
import ch.threema.app.R
import ch.threema.app.utils.ConfigUtils
import ch.threema.base.utils.getThreemaLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val logger = getThreemaLogger("AudioTrimView")

/**
 * F1Whisper: dual-handle waveform trim widget for the voice recorder.
 *
 * <p>It renders the recorded clip's waveform (decoded directly from the recording [Uri], so no
 * [ch.threema.storage.models.AbstractMessageModel] is needed for an unsent recording) and overlays
 * two draggable handles. Dragging crops the start and end of the clip before sending. It mirrors the
 * dual-handle UX of [ch.threema.app.video.VideoTimelineThumbnailTask]'s video trimmer and reuses the
 * same RMS-per-sample waveform math as [AudioWaveformGeneratorTask].
 *
 * <p>The selected window is reported in milliseconds via [onTrimChanged]; [getStartMs]/[getEndMs]
 * give the current selection. When the handles are at the extremes the whole clip is selected (a
 * no-op pass-through for the trimmer).
 */
class AudioTrimView : View {

    fun interface OnTrimChangedListener {
        fun onTrimChanged(startMs: Long, endMs: Long)
    }

    /**
     * F1Whisper: notified when the user starts/stops dragging a handle. Used by the media-attach
     * preview to suspend the surrounding ViewPager swipe while trimming (mirrors VideoEditView's
     * OnTimelineDragListener).
     */
    interface OnDragStateListener {
        fun onDragStart()

        fun onDragStop()
    }

    private val barPaint = Paint().apply {
        isAntiAlias = true
        color = ConfigUtils.getColorFromAttribute(context, R.attr.colorOnSurfaceVariant)
        alpha = 90
    }
    private val selectedBarPaint = Paint().apply {
        isAntiAlias = true
        color = ConfigUtils.getColorFromAttribute(context, R.attr.colorPrimary)
    }
    private val handlePaint = Paint().apply {
        isAntiAlias = true
        color = ConfigUtils.getColorFromAttribute(context, R.attr.colorPrimary)
    }
    private val dimPaint = Paint().apply {
        isAntiAlias = true
        color = ConfigUtils.getColorFromAttribute(context, android.R.attr.colorBackground)
        alpha = 150
    }

    /**
     * F1Whisper: the moving playback cursor drawn while the clipped preview plays, mirroring
     * [ch.threema.app.camera.VideoEditView]'s progressPaint. Uses [R.attr.colorOnPrimary] so it
     * stays visible against the selected (colorPrimary) waveform bars it overlays.
     */
    private val playheadPaint = Paint().apply {
        isAntiAlias = true
        color = ConfigUtils.getColorFromAttribute(context, R.attr.colorOnPrimary)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.5f
    }

    private val handleWidthPx = resources.displayMetrics.density * 8f
    private val barWidthPx = resources.displayMetrics.density * 3f
    private val barSpacingPx = resources.displayMetrics.density * 2f
    private val cornerRadiusPx = resources.displayMetrics.density * 2f
    private val touchSlopPx = resources.displayMetrics.density * 24f

    private var durationMs: Long = 0L
    private var startFraction = 0f
    private var endFraction = 1f
    private var samples: List<Float> = emptyList()

    private var draggingStart = false
    private var draggingEnd = false

    /**
     * F1Whisper: current preview playback position (absolute, from the start of the clip) in ms, or
     * a negative value when no playhead should be drawn (preview not playing / stopped). Driven by
     * [setPlayheadPosition]/[clearPlayhead] from [ch.threema.app.camera.AudioTrimEditView].
     */
    private var playheadPositionMs: Long = -1L

    private var listener: OnTrimChangedListener? = null
    private var dragStateListener: OnDragStateListener? = null
    private var extractTask: WaveformExtractTask? = null

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    fun setOnTrimChangedListener(listener: OnTrimChangedListener?) {
        this.listener = listener
    }

    fun setOnDragStateListener(listener: OnDragStateListener?) {
        this.dragStateListener = listener
    }

    /**
     * F1Whisper: pre-seed the trim selection (e.g. when re-binding a media item that already has a
     * window) and the known duration, so the handles render at the right spots before the waveform
     * finishes decoding. [knownDurationMs] is used until the container reports its own duration.
     */
    fun setSelection(startMs: Long, endMs: Long, knownDurationMs: Long) {
        if (knownDurationMs > 0L) {
            durationMs = knownDurationMs
        }
        if (durationMs > 0L) {
            startFraction = (startMs.toFloat() / durationMs).coerceIn(0f, 1f)
            endFraction = (endMs.toFloat() / durationMs).coerceIn(startFraction, 1f)
            invalidate()
        }
    }

    /**
     * Load and render the waveform for [uri]. The full playback duration is read from the media
     * container, so the caller does not need to know it in advance. Resets the trim selection to the
     * full clip.
     */
    fun load(uri: Uri) {
        this.durationMs = 1L
        this.startFraction = 0f
        this.endFraction = 1f
        extractTask?.cancel()
        val task = WaveformExtractTask(uri, guessSampleCount(), { extractedDurationMs ->
            post {
                if (extractedDurationMs > 0) {
                    durationMs = extractedDurationMs
                }
            }
        }) { extracted ->
            post {
                samples = extracted
                invalidate()
            }
        }
        extractTask = task
        ExecutorServices.voiceMessageThumbnailExecutorService.execute(Thread(task, "AudioTrimWaveform"))
    }

    fun getStartMs(): Long = (startFraction * durationMs).toLong()

    fun getEndMs(): Long = (endFraction * durationMs).toLong()

    /**
     * True if the user has actually cropped the clip (handles moved off the extremes).
     */
    fun isTrimmed(): Boolean = startFraction > 0f || endFraction < 1f

    private fun guessSampleCount(): Int {
        // Roughly one bar per (barWidth + spacing); clamp so very short/long views stay sensible.
        val available = (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
        return (available / (barWidthPx + barSpacingPx)).roundToInt().coerceIn(16, 256)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) {
            return
        }

        val startX = fractionToX(startFraction)
        val endX = fractionToX(endFraction)
        val midY = height / 2f
        val maxHalfBar = (height / 2f) - handleWidthPx

        // Waveform bars.
        if (samples.isNotEmpty()) {
            val numBars = (width / (barWidthPx + barSpacingPx)).toInt().coerceAtLeast(1)
            val factor = samples.size.toFloat() / numBars
            for (i in 0 until numBars) {
                val sample = samples[(i * factor).roundToInt().coerceIn(0, samples.size - 1)]
                val halfBar = max(maxHalfBar * sample.coerceIn(0f, 1f), resources.displayMetrics.density)
                val barLeft = i * (barWidthPx + barSpacingPx)
                val barRight = barLeft + barWidthPx
                val barCenterX = barLeft + barWidthPx / 2f
                val inSelection = barCenterX in startX..endX
                canvas.drawRoundRect(
                    RectF(barLeft, midY - halfBar, barRight, midY + halfBar),
                    cornerRadiusPx,
                    cornerRadiusPx,
                    if (inSelection) selectedBarPaint else barPaint,
                )
            }
        }

        // Dim the trimmed-away regions.
        if (startX > 0f) {
            canvas.drawRect(0f, 0f, startX, height.toFloat(), dimPaint)
        }
        if (endX < width.toFloat()) {
            canvas.drawRect(endX, 0f, width.toFloat(), height.toFloat(), dimPaint)
        }

        // Handles.
        canvas.drawRoundRect(
            RectF(startX - handleWidthPx / 2f, 0f, startX + handleWidthPx / 2f, height.toFloat()),
            cornerRadiusPx,
            cornerRadiusPx,
            handlePaint,
        )
        canvas.drawRoundRect(
            RectF(endX - handleWidthPx / 2f, 0f, endX + handleWidthPx / 2f, height.toFloat()),
            cornerRadiusPx,
            cornerRadiusPx,
            handlePaint,
        )

        // Playback cursor. Mirrors VideoEditView.dispatchDraw: a vertical line at the current
        // preview position, only while playing, only inside the selected window, and never while a
        // handle is being dragged (so the cursor doesn't fight the user's drag).
        if (playheadPositionMs >= 0L && durationMs > 0L && !draggingStart && !draggingEnd) {
            val playheadFraction = (playheadPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            if (playheadFraction in startFraction..endFraction) {
                val playheadX = fractionToX(playheadFraction)
                canvas.drawLine(playheadX, 0f, playheadX, height.toFloat(), playheadPaint)
            }
        }
    }

    /**
     * F1Whisper: set the absolute preview playback position (ms from the start of the clip) and
     * redraw the moving playback cursor. Called every ~100ms by
     * [ch.threema.app.camera.AudioTrimEditView] while the clipped preview plays.
     */
    fun setPlayheadPosition(positionMs: Long) {
        playheadPositionMs = positionMs
        invalidate()
    }

    /**
     * F1Whisper: hide the playback cursor (preview paused/stopped/ended).
     */
    fun clearPlayhead() {
        if (playheadPositionMs >= 0L) {
            playheadPositionMs = -1L
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val startX = fractionToX(startFraction)
                val endX = fractionToX(endFraction)
                val distToStart = abs(x - startX)
                val distToEnd = abs(x - endX)
                if (distToStart <= touchSlopPx && distToStart <= distToEnd) {
                    draggingStart = true
                } else if (distToEnd <= touchSlopPx) {
                    draggingEnd = true
                } else {
                    return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                dragStateListener?.onDragStart()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingStart) {
                    // Keep a minimum 1-second selection window (clamped so the upper bound never
                    // drops below the lower bound on very short clips).
                    val minGap = minGapFraction()
                    val upper = (endFraction - minGap).coerceAtLeast(0f)
                    startFraction = xToFraction(x).coerceIn(0f, upper)
                    notifyChanged()
                    invalidate()
                    return true
                } else if (draggingEnd) {
                    val minGap = minGapFraction()
                    val lower = (startFraction + minGap).coerceAtMost(1f)
                    endFraction = xToFraction(x).coerceIn(lower, 1f)
                    notifyChanged()
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = draggingStart || draggingEnd
                draggingStart = false
                draggingEnd = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (wasDragging) {
                    dragStateListener?.onDragStop()
                }
            }
        }
        return draggingStart || draggingEnd
    }

    private fun minGapFraction(): Float {
        // At least one second, but never more than the whole clip.
        return min(1000f / durationMs.toFloat(), 1f)
    }

    private fun notifyChanged() {
        listener?.onTrimChanged(getStartMs(), getEndMs())
    }

    private fun fractionToX(fraction: Float): Float {
        val usableLeft = handleWidthPx / 2f
        val usableRight = width - handleWidthPx / 2f
        return usableLeft + fraction * (usableRight - usableLeft)
    }

    private fun xToFraction(x: Float): Float {
        val usableLeft = handleWidthPx / 2f
        val usableRight = width - handleWidthPx / 2f
        if (usableRight <= usableLeft) {
            return 0f
        }
        return ((x - usableLeft) / (usableRight - usableLeft)).coerceIn(0f, 1f)
    }

    override fun onDetachedFromWindow() {
        extractTask?.cancel()
        extractTask = null
        dragStateListener = null
        super.onDetachedFromWindow()
    }

    /**
     * Decodes 16-bit PCM from the recording and produces [requestedSamples] normalized RMS values.
     * Reads the file [Uri] directly (no message model), mirroring [AudioWaveformGeneratorTask].
     */
    private inner class WaveformExtractTask(
        private val uri: Uri,
        private val requestedSamples: Int,
        private val onDuration: (Long) -> Unit,
        private val onReady: (List<Float>) -> Unit,
    ) : Runnable {
        private val canceled = AtomicBoolean(false)

        fun cancel() = canceled.set(true)

        override fun run() {
            if (canceled.get()) {
                return
            }
            var extractor: MediaExtractor? = null
            var decoder: MediaCodec? = null
            val data = ArrayList<Float>()
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(context, uri, null)
                val trackIndex = selectAudioTrack(extractor)
                if (trackIndex < 0) {
                    onReady(emptyList())
                    return
                }
                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
                    onReady(emptyList())
                    return
                }
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    onDuration(format.getLong(MediaFormat.KEY_DURATION) / 1000L)
                }
                val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else {
                    1
                }
                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(format, null, null, 0)
                decoder.start()
                extractRms(extractor, decoder, channels, data)
                if (!canceled.get()) {
                    onReady(data)
                }
            } catch (e: Exception) {
                logger.error("Failed to extract trim waveform", e)
                onReady(emptyList())
            } finally {
                try {
                    decoder?.stop()
                    decoder?.release()
                } catch (e: Exception) {
                    logger.error("Failed to release decoder", e)
                }
                extractor?.release()
            }
        }

        private fun extractRms(
            extractor: MediaExtractor,
            decoder: MediaCodec,
            channels: Int,
            out: ArrayList<Float>,
        ) {
            val info = MediaCodec.BufferInfo()
            var outputDone = false
            var inputDone = false
            val timeoutUs = 10000L
            // Bound the number of RMS points so a long clip does not produce an unbounded list; the
            // view downsamples to its bar count anyway. We collect a few points per requested bar
            // for a smoother render.
            val maxPoints = (requestedSamples * 8).coerceAtLeast(128)
            while (!outputDone && !canceled.get()) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val chunkSize = if (out.size >= maxPoints) -1 else extractor.readSampleData(decoder.getInputBuffer(inIndex)!!, 0)
                        if (chunkSize < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, chunkSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, timeoutUs)
                if (outIndex >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    if (info.size != 0) {
                        decoder.getOutputBuffer(outIndex)?.let { buf ->
                            buf.position(info.offset)
                            var sampleSum = 0.0
                            val frameCount = info.size / if (channels == 2) 4 else 2
                            repeat(frameCount) {
                                val a = buf.get().toInt() and 0xff
                                val b = buf.get().toInt() shl 8
                                val value = ((a or b).toShort()) / 32768f
                                if (channels == 2) {
                                    buf.get()
                                    buf.get()
                                }
                                sampleSum += value.toDouble().pow(2.0)
                            }
                            if (frameCount > 0) {
                                val rms = sqrt(sampleSum / frameCount) * 4
                                out.add(rms.toFloat().coerceIn(0f, 1f))
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                }
            }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }
}

package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

@UnstableApi
class VolumeNormalizationAudioProcessor : BaseAudioProcessor() {
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0
    private var channelCount = 0
    private var rampStepPerFrame = 1.0

    @Volatile
    var enabled = false
        set(value) {
            if (field != value) {
                field = value
                Timber.tag(TAG).d("Normalization processor enabled: $value")
            }
        }

    private data class GainState(val targetGainMb: Int, val linearGain: Double)

    @Volatile
    private var currentGain = GainState(0, 1.0)

    // Gain applied to the last processed frame. It moves toward the target over a few
    // milliseconds so a gain change never produces an audible click or jump.
    private var appliedGain = 1.0

    @Synchronized
    fun setTargetGain(gainMb: Int) {
        if (currentGain.targetGainMb != gainMb) {
            val linearGain = 10.0.pow(gainMb / 2000.0)
            currentGain = GainState(gainMb, linearGain)
            Timber.tag(TAG).d("Target gain set to $gainMb mB (Linear multiplier: $linearGain)")
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        encoding = inputAudioFormat.encoding
        bytesPerSample =
            when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
        channelCount = inputAudioFormat.channelCount
        rampStepPerFrame = 1.0 / (inputAudioFormat.sampleRate * RAMP_SECONDS)

        Timber.tag(TAG).d(
            "Configured: sampleRate=${inputAudioFormat.sampleRate}, channels=${inputAudioFormat.channelCount}, encoding=$encoding",
        )
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val targetGain = if (enabled) currentGain.linearGain else 1.0
        val size = inputBuffer.remaining()
        val output = replaceOutputBuffer(size)

        if (appliedGain == 1.0 && targetGain == 1.0) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)

        val frameCount = size / (bytesPerSample * channelCount)
        var gain = appliedGain
        repeat(frameCount) {
            gain = approach(gain, targetGain)
            repeat(channelCount) { applyGain(inputBuffer, output, gain) }
        }
        appliedGain = gain

        // Buffers are frame aligned, but never drop bytes if one is not.
        while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())

        output.flip()
    }

    private fun approach(
        gain: Double,
        target: Double,
    ): Double =
        when {
            gain < target -> minOf(gain + rampStepPerFrame, target)
            gain > target -> maxOf(gain - rampStepPerFrame, target)
            else -> gain
        }

    private fun applyGain(
        input: ByteBuffer,
        output: ByteBuffer,
        gain: Double,
    ) {
        when (encoding) {
            C.ENCODING_PCM_16BIT ->
                output.putShort(
                    (input.getShort() * gain)
                        .coerceIn(-32768.0, 32767.0)
                        .toInt()
                        .toShort(),
                )

            C.ENCODING_PCM_24BIT -> {
                val b0 = input.get().toInt() and 0xFF
                val b1 = input.get().toInt() and 0xFF
                val b2 = input.get().toInt()
                val sample = (b2 shl 16) or (b1 shl 8) or b0
                val processed = (sample * gain).coerceIn(-8388608.0, 8388607.0).toInt()
                output.put((processed and 0xFF).toByte())
                output.put(((processed shr 8) and 0xFF).toByte())
                output.put(((processed shr 16) and 0xFF).toByte())
            }

            C.ENCODING_PCM_32BIT ->
                output.putInt(
                    (input.getInt() * gain)
                        .coerceIn(-2147483648.0, 2147483647.0)
                        .toLong()
                        .toInt(),
                )

            C.ENCODING_PCM_FLOAT ->
                output.putFloat((input.getFloat() * gain.toFloat()).coerceIn(-1.0f, 1.0f))
        }
    }

    // A flush marks a new stream (seek, new item): start at the target gain, not at the previous track's gain.
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        appliedGain = if (enabled) currentGain.linearGain else 1.0
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        bytesPerSample = 0
        channelCount = 0
        appliedGain = 1.0
    }

    private companion object {
        const val TAG = "VolumeNormalizationProcessor"
        const val RAMP_SECONDS = 0.05
    }
}

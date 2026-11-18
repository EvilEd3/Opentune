package com.opentune.videotv

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * Reads a googlevideo URL in ranges of [chunk] bytes, one request after
 * another, the way YouTube's own players do.
 *
 * googlevideo refuses ranges larger than a client's URLs are minted for (as
 * little as 512 KB for Android VR), and slows or cuts off open-ended ones; a
 * run of bounded ranges is served at full speed. To the player above this
 * looks like one continuous stream. The file's total size comes from
 * [lengths] or the URL's `clen` parameter; with neither, ranges are read
 * until one comes back short.
 */
@OptIn(UnstableApi::class)
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunk: Long = DEFAULT_CHUNK,
    private val lengths: Map<String, Long> = emptyMap(),
) : DataSource {

    private var spec: DataSpec? = null
    private var position = 0L

    /** Where the read stops, exclusive; unset when the file's size is unknown. */
    private var end = C.LENGTH_UNSET.toLong()

    /** Bytes asked of the range now open, and how many of them have arrived. */
    private var asked = 0L
    private var got = 0L
    private var finished = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val total = lengths[dataSpec.uri.toString()]
            ?: runCatching { dataSpec.uri.getQueryParameter("clen")?.toLongOrNull() }.getOrNull()
        spec = dataSpec
        position = dataSpec.position
        finished = false
        val requestedEnd = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else null
        end = when {
            total != null && requestedEnd != null -> minOf(total, requestedEnd)
            total != null -> total
            requestedEnd != null -> requestedEnd
            else -> C.LENGTH_UNSET.toLong()
        }
        if (end != C.LENGTH_UNSET.toLong() && position >= end) {
            finished = true
            return 0
        }
        openChunk()
        return if (end != C.LENGTH_UNSET.toLong()) end - position else C.LENGTH_UNSET.toLong()
    }

    /** @return false when there is nothing past [position]: the end of a file of unknown size. */
    private fun openChunk(): Boolean {
        val base = spec ?: return false
        asked = if (end != C.LENGTH_UNSET.toLong()) minOf(chunk, end - position) else chunk
        got = 0
        return try {
            upstream.open(base.subrange(position - base.position, asked))
            true
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            // Asked for a range starting at or past the end of a file whose size wasn't known.
            if (e.responseCode == 416 && end == C.LENGTH_UNSET.toLong() && position > 0) false else throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (finished) return C.RESULT_END_OF_INPUT
        if (end != C.LENGTH_UNSET.toLong() && position >= end) return C.RESULT_END_OF_INPUT
        var read = upstream.read(buffer, offset, length)
        if (read == C.RESULT_END_OF_INPUT) {
            // This range is used up: on to the next one.
            upstream.close()
            val short = got < asked
            if (end != C.LENGTH_UNSET.toLong() && position >= end) return C.RESULT_END_OF_INPUT
            // A range that came back short, with no size to go by, was the last.
            if (end == C.LENGTH_UNSET.toLong() && short) { finished = true; return C.RESULT_END_OF_INPUT }
            if (!openChunk()) { finished = true; return C.RESULT_END_OF_INPUT }
            read = upstream.read(buffer, offset, length)
            if (read == C.RESULT_END_OF_INPUT) { finished = true; return C.RESULT_END_OF_INPUT }
        }
        position += read
        got += read
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        spec = null
        upstream.close()
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val chunk: Long = DEFAULT_CHUNK,
        private val lengths: Map<String, Long> = emptyMap(),
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource(), chunk, lengths)
    }

    companion object {
        /** Two megabytes: what the audio app reads in, and what most clients' URLs accept. */
        const val DEFAULT_CHUNK = 2L * 1024 * 1024
    }
}

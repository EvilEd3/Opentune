package com.opentune.videotv

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Reads a googlevideo URL in ranges of [chunk] bytes, one request after
 * another, the way YouTube's own players do.
 *
 * A single open-ended request for a whole video file is what googlevideo is
 * quickest to slow to a crawl or cut off part way; a run of bounded ranges is
 * served at full speed. To the player above this looks like one continuous
 * stream. The file's total size comes from the URL's `clen` parameter; a URL
 * without one is read as it is, unchunked.
 */
@OptIn(UnstableApi::class)
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunk: Long = DEFAULT_CHUNK,
) : DataSource {

    private var spec: DataSpec? = null
    private var position = 0L
    private var end = C.LENGTH_UNSET.toLong()
    private var chunked = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        if (total == null) {
            chunked = false
            return upstream.open(dataSpec)
        }
        chunked = true
        spec = dataSpec
        position = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(total, dataSpec.position + dataSpec.length)
        } else {
            total
        }
        if (position >= end) return 0
        openChunk()
        return end - position
    }

    private fun openChunk() {
        val base = spec ?: return
        val length = minOf(chunk, end - position)
        upstream.open(base.subrange(position - base.position, length))
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunked) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (position >= end) return C.RESULT_END_OF_INPUT
        var read = upstream.read(buffer, offset, length)
        if (read == C.RESULT_END_OF_INPUT) {
            // This range is used up: on to the next one.
            upstream.close()
            if (position >= end) return C.RESULT_END_OF_INPUT
            openChunk()
            read = upstream.read(buffer, offset, length)
            if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        }
        position += read
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        spec = null
        upstream.close()
    }

    class Factory(private val upstream: DataSource.Factory, private val chunk: Long = DEFAULT_CHUNK) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource(), chunk)
    }

    companion object {
        /** About ten megabytes, the size YouTube's own web player asks for. */
        const val DEFAULT_CHUNK = 10L * 1024 * 1024
    }
}

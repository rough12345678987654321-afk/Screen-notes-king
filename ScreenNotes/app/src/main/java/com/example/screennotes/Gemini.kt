package com.example.screennotes

/** Kept as a small compatibility facade for older local code that downsizes captured images. */
object Gemini {
    fun shrinkForUpload(jpeg: ByteArray): ByteArray = AiNotesEngine.shrinkForUpload(jpeg)
}

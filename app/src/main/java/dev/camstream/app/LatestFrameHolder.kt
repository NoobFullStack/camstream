package dev.camstream.app

/**
 * Holds only the single most recently published JPEG frame. A slow network consumer
 * therefore never builds an unbounded backlog: it just picks up whatever the camera
 * produced most recently, dropping anything in between.
 */
class LatestFrameHolder {
    private val lock = Object()
    private var frame: ByteArray? = null
    private var version = 0L

    fun publish(jpeg: ByteArray) {
        synchronized(lock) {
            frame = jpeg
            version++
            lock.notifyAll()
        }
    }

    /**
     * Blocks until a frame newer than [sinceVersion] is published, then returns it
     * together with its version (pass that version back in on the next call).
     */
    fun awaitNext(sinceVersion: Long): Pair<ByteArray, Long> {
        synchronized(lock) {
            while (frame == null || version == sinceVersion) {
                lock.wait()
            }
            return frame!! to version
        }
    }
}

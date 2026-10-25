package io.github.evildarkarchon.andysdice.sound;

/**
 * The sink seam (ADR-0012): where rendered blocks go to be heard. It only has to accept a stream, since the
 * mixer does the scheduling, pitch and voices itself. {@link LineSink} is the {@code javax.sound.sampled}
 * one; an OpenAL Soft streaming source is the named fallback behind this seam.
 *
 * <p>A sink is used by the audio thread only.
 */
public interface AudioSink extends AutoCloseable {

    /**
     * Opens a sink on the default device; {@code LineSink::open} in the app. {@link SoundOutput} opens one for
     * each Throw it plays, on the audio thread.
     */
    @FunctionalInterface
    interface Opener {
        /**
         * Opens a sink.
         *
         * @return an open sink, ready to take blocks
         * @throws SinkException if no device can play the mix format
         */
        AudioSink open() throws SinkException;
    }

    /**
     * Returns how much is queued ahead of what is being heard now.
     *
     * @return the frames written but not yet heard
     */
    int queuedFrames();

    /**
     * Queues one block, waiting for room if the queue is full; that wait is what paces the audio thread.
     *
     * @param block {@link MixFormat#BLOCK_FRAMES} frames, interleaved left and right
     * @throws SinkException if the device stopped taking sound (for example, it was unplugged)
     */
    void write(short[] block) throws SinkException;

    /** Stops and releases the device; what is still queued is dropped. Closing twice does nothing. */
    @Override
    void close();
}

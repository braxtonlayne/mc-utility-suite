package com.utilitysuite.world.replay;

/**
 * Playhead of the replay screen: position in fractional frames over a fixed length, play/pause, a speed ladder and
 * scrubbing. Playing past the end stops at the last frame; play from the end restarts at 0. Engine free.
 */
public final class ReplayTimeline {

    public static final double[] SPEEDS = {0.1, 0.25, 0.5, 1.0, 2.0, 4.0};
    private static final int DEFAULT_SPEED = 3;
    public static final double TICKS_PER_SECOND = 20.0;

    private int length;
    private double position;
    private boolean playing;
    private int speedIndex = DEFAULT_SPEED;

    /** Resets to a scene of {@code frames} frames, playhead at the newest frame, paused. */
    public void load(int frames) {
        length = Math.max(0, frames);
        position = Math.max(0, length - 1);
        playing = false;
        speedIndex = DEFAULT_SPEED;
    }

    public int length() {
        return length;
    }

    public double position() {
        return position;
    }

    public boolean playing() {
        return playing;
    }

    public double speed() {
        return SPEEDS[speedIndex];
    }

    private double last() {
        return Math.max(0, length - 1);
    }

    public void togglePlay() {
        if (!playing && position >= last()) {
            position = 0;
        }
        playing = !playing && length > 1;
    }

    public void faster() {
        speedIndex = Math.min(SPEEDS.length - 1, speedIndex + 1);
    }

    public void slower() {
        speedIndex = Math.max(0, speedIndex - 1);
    }

    /** Advances by real {@code seconds} while playing. */
    public void advance(double seconds) {
        if (!playing || seconds <= 0) {
            return;
        }
        position += seconds * TICKS_PER_SECOND * speed();
        if (position >= last()) {
            position = last();
            playing = false;
        }
    }

    /** Jumps to {@code fraction} (0..1) of the scene and pauses. */
    public void scrubTo(double fraction) {
        double f = Math.max(0, Math.min(1, fraction));
        position = f * last();
        playing = false;
    }

    /** Steps whole frames (pauses). */
    public void step(int frames) {
        position = Math.max(0, Math.min(last(), Math.round(position) + frames));
        playing = false;
    }

    /** 0..1 fraction of the playhead. */
    public double fraction() {
        double l = last();
        return l <= 0 ? 0 : position / l;
    }

    /** Seconds before the newest frame (0 at the end of the recording). */
    public double secondsBeforeEnd() {
        return (last() - position) / TICKS_PER_SECOND;
    }
}

package com.ibasco.rpispectrum.audio;

public final class AudioBuffer {
    private volatile float[] samples;

    AudioBuffer(int size) {
        this.samples = new float[size];
    }

    public synchronized float[] get() {
        float[] data = new float[samples.length];
        System.arraycopy(this.samples, 0, data, 0, samples.length);
        return data;
    }

    public synchronized void set(float[] samples) {
        this.samples = samples;
    }

    public synchronized float get(int index) {
        return samples[index];
    }

    public synchronized void mix(float[] left, float[] right) {
        if (left.length == right.length && left.length == this.samples.length && right.length == this.samples.length) {
            for (int i = 0; i < this.samples.length; ++i) {
                this.samples[i] = (left[i] + right[i]) / 2.0F;
            }
        }
    }

    public int size() {
        return samples.length;
    }
}

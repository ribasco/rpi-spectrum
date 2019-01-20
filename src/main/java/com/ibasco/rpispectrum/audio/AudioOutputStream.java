package com.ibasco.rpispectrum.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class AudioOutputStream extends OutputStream {

    private SourceDataLine line;

    private AudioFormat format;

    private ByteArrayOutputStream bos;

    public AudioOutputStream(AudioFormat format) {
        this.format = format;
        bos = new ByteArrayOutputStream(1024);
    }

    @Override
    public void write(int b) throws IOException {
        bos.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        super.write(b, off, len);
    }

    public SourceDataLine getLine() {
        return line;
    }

    @Override
    public void close() throws IOException {
        line.drain();
        line.stop();
        line.close();
        line = null;
    }

    @Override
    public void flush() throws IOException {
        line.flush();
    }
}

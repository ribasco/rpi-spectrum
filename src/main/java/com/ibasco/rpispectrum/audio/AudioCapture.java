package com.ibasco.rpispectrum.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.util.concurrent.CompletableFuture;

public class AudioCapture {
    private static final Logger log = LoggerFactory.getLogger(AudioCapture.class);

    private SourceDataLine sourceDataLine;

    private TargetDataLine targetDataLine;

    private Mixer.Info mixer;

    private void initDataLine() {
        float sampleRate = 44100;
        int sampleSizeInBits = 16;
        int frameSize = 2 * (sampleSizeInBits / 8);
        AudioFormat targetFormat = new AudioFormat(44100, 16, 2, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, targetFormat); // format is an AudioFormat object

        Mixer mixer = AudioSystem.getMixer(getMixer());

        log.debug("Using capture device mixer: {}", mixer);

        if (!mixer.isLineSupported(info)) {
            log.error("Line not supported: {}", info);

            log.debug("Listing available target lines for this mixer");
            for (Line line : mixer.getTargetLines()) {
                log.debug("- Target Line: {}", line.getLineInfo().getLineClass());
            }

            return;
        }
        // Obtain and open the line.
        try {
            if (targetDataLine != null) {
                if (targetDataLine.isOpen()) {
                    log.debug("Line is open. Closing target line");
                    targetDataLine.close();
                }
            }

            targetDataLine = (TargetDataLine) mixer.getLine(info);
            targetDataLine.open(targetFormat, 1024 * 15);
            targetDataLine.start();
            log.debug("Successfully opened capture line: {}, Buffer Size = {}", targetDataLine, targetDataLine.getBufferSize());

            AudioFormat sourecTargetFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16, 2, frameSize, sampleRate / frameSize, false);
            DataLine.Info sourceInfo = new DataLine.Info(SourceDataLine.class, sourecTargetFormat);
            sourceDataLine = (SourceDataLine) AudioSystem.getMixer(null).getLine(sourceInfo);
            sourceDataLine.open(targetFormat, targetDataLine.getBufferSize());
            sourceDataLine.start();

            CompletableFuture.runAsync(() -> {
                while (true) {
                    if (targetDataLine.available() > 0) {
                        byte[] buffer = new byte[targetDataLine.getBufferSize()];
                        int bytesRead = targetDataLine.read(buffer, 0, buffer.length);
                        if (bytesRead == -1) {
                            break;
                        }
                        sourceDataLine.write(buffer, 0, bytesRead);
                    }
                }
            });
        } catch (LineUnavailableException ex) {
            log.error("Line unavailable: {}", ex);
        }
    }

    public Mixer.Info getMixer() {
        return mixer;
    }

    public void setMixer(Mixer.Info mixer) {
        this.mixer = mixer;
    }

    public void init() {
        initDataLine();
    }
}

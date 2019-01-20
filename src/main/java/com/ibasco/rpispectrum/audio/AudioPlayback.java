package com.ibasco.rpispectrum.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tritonus.share.sampled.FloatSampleBuffer;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;

/**
 * Modified version of javazoom's BasicPlayer class
 */
@SuppressWarnings("WeakerAccess")
public class AudioPlayback implements Runnable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AudioPlayback.class);

    public static int SKIP_INACCURACY_SIZE = 1200;

    private static final int DEFAULT_SAMPLE_COUNT = 1024;

    public static int EXTERNAL_BUFFER_SIZE = 1024 * 4;//4000 * 4;

    protected AudioInputStream m_encodedaudioInputStream;

    protected AudioInputStream m_audioInputStream;

    protected AudioFileFormat m_audioFileFormat;

    private int lineBufferSize = EXTERNAL_BUFFER_SIZE;

    protected String m_mixerName = null;

    protected int encodedLength = -1;

    protected FloatControl m_gainControl;

    protected FloatControl m_panControl;

    protected SourceDataLine m_line;

    protected Object m_dataSource;

    protected Thread m_thread = null;

    private AudioBuffer leftChannelBuffer;

    private AudioBuffer rightChannelBuffer;

    private AudioBuffer monoChannelBuffer;

    private AudioBuffer mixedChannelBuffer;

    private FloatSampleBuffer sampleBuffer;

    private int m_SampleCount = DEFAULT_SAMPLE_COUNT;

    private long threadSleep = -1;

    private int m_status = UNKNOWN;

    private final Object mutext = new Object();

    private ArrayList<StatusListener> statusListeners = new ArrayList<>();

    /*
    Sample rate = number of samples / second
    Frame = 1 sample from each channel (PCM)
    Frame Size = Sample size * Channels
    Frame Rate = frames / second.
     */

    @Override
    public void close() {
        log.info("Closing player");
        stopPlayback();
        this.statusListeners.clear();
    }

    @FunctionalInterface
    public interface StatusListener {
        void onStatusChange(PlayerEvent event, int position, double value, Object... params);
    }

    public static final int UNKNOWN = -1;
    public static final int PLAYING = 0;
    public static final int PAUSED = 1;
    public static final int STOPPED = 2;
    public static final int OPENED = 3;
    public static final int SEEKING = 4;

    public enum PlayerEvent {
        UNKNOWN(-1),
        OPENING(0),
        OPENED(1),
        PLAYING(2),
        STOPPED(3),
        PAUSED(4),
        RESUMED(5),
        SEEKING(6),
        SEEKED(7),
        EOM(8),
        PAN(9),
        GAIN(10);

        private int code;

        PlayerEvent(int code) {
            this.code = code;
        }

        public int getCode() {
            return this.code;
        }
    }

    public int getLineBufferSize() {
        return lineBufferSize;
    }

    public void setLineBufferSize(int lineBufferSize) {
        this.lineBufferSize = lineBufferSize;
    }

    public void addListener(StatusListener statusListener) {
        statusListeners.add(statusListener);
    }

    public void removeListener(StatusListener statusListener) {
        statusListeners.remove(statusListener);
    }

    public boolean hasListener(StatusListener statusListener) {
        return statusListeners.contains(statusListener);
    }

    public String getMixerName() {
        return m_mixerName;
    }

    public void setMixerName(String m_mixerName) {
        this.m_mixerName = m_mixerName;
    }

    public void open(File file) throws AudioPlayerException {
        log.info("open(" + file + ")");
        if (file != null) {
            m_dataSource = file;
            initAudioInputStream();
        }
    }

    public void open(URL url) throws AudioPlayerException {
        log.info("open(" + url + ")");
        if (url != null) {
            m_dataSource = url;
            initAudioInputStream();
        }
    }

    public void open(InputStream inputStream) throws AudioPlayerException {
        log.info("open(" + inputStream + ")");
        if (inputStream != null) {
            m_dataSource = inputStream;
            initAudioInputStream();
        }
    }

    public void setPan(double fPan) throws AudioPlayerException {
        if (hasPanControl()) {
            log.debug("Pan : " + fPan);
            m_panControl.setValue((float) fPan);
            notify(PlayerEvent.PAN, getEncodedStreamPosition(), fPan);
        } else throw new AudioPlayerException("Pan control not supported");
    }

    public void setGain(double fGain) throws AudioPlayerException {
        if (hasGainControl()) {
            double minGainDB = getMinimumGain();
            double ampGainDB = ((10.0f / 20.0f) * getMaximumGain()) - getMinimumGain();
            double cste = Math.log(10.0) / 20;
            double valueDB = minGainDB + (1 / cste) * Math.log(1 + (Math.exp(cste * ampGainDB) - 1) * fGain);
            log.debug("Gain : " + valueDB);
            m_gainControl.setValue((float) valueDB);
            notify(PlayerEvent.GAIN, getEncodedStreamPosition(), fGain);
        } else throw new AudioPlayerException("Gain control not supported");
    }

    public boolean hasGainControl() {
        if (m_gainControl == null) {
            // Try to get Gain control again (to support J2SE 1.5)
            if ((m_line != null) && (m_line.isControlSupported(FloatControl.Type.MASTER_GAIN))) m_gainControl = (FloatControl) m_line.getControl(FloatControl.Type.MASTER_GAIN);
        }
        return m_gainControl != null;
    }

    public float getGainValue() {
        if (hasGainControl()) {
            return m_gainControl.getValue();
        } else {
            return 0.0F;
        }
    }

    public float getMaximumGain() {
        if (hasGainControl()) {
            return m_gainControl.getMaximum();
        } else {
            return 0.0F;
        }
    }

    public float getMinimumGain() {
        if (hasGainControl()) {
            return m_gainControl.getMinimum();
        } else {
            return 0.0F;
        }
    }

    private void notify(PlayerEvent event, int position, double value, Object... params) {
        if (statusListeners.isEmpty())
            return;
        statusListeners.forEach(l -> l.onStatusChange(event, position, value, params));
    }

    protected void reset() {
        log.info("reset(): Resetting audio properties");
        m_status = UNKNOWN;
        if (m_audioInputStream != null) {
            synchronized (mutext) {
                closeStream();
            }
        }
        m_audioInputStream = null;
        m_audioFileFormat = null;
        m_encodedaudioInputStream = null;
        encodedLength = -1;
        if (m_line != null) {
            m_line.stop();
            m_line.close();
            m_line = null;
        }
        m_gainControl = null;
        m_panControl = null;

        totalBytes = 0;
        sampleBuffer = null;
        leftChannelBuffer = null;
        rightChannelBuffer = null;
        monoChannelBuffer = null;
        mixedChannelBuffer = null;
    }

    public final AudioFormat getAudioFormat() {
        return m_audioInputStream.getFormat();
    }

    public final AudioBuffer getLeftChannel() {
        return leftChannelBuffer;
    }

    public final AudioBuffer getRightChannel() {
        return rightChannelBuffer;
    }

    public final AudioBuffer getMonoChannel() {
        return monoChannelBuffer;
    }

    public final AudioBuffer getMixedChannel() {
        return mixedChannelBuffer;
    }

    public int getSampleCount() {
        return m_SampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.m_SampleCount = sampleCount;
    }

    public long getThreadSleep() {
        return threadSleep;
    }

    public void setThreadSleep(long threadSleep) {
        this.threadSleep = threadSleep;
    }

    protected void closeStream() {
        // Close stream.
        try {
            if (m_audioInputStream != null) {
                m_audioInputStream.close();
                log.info("Stream closed");
            }
        } catch (IOException e) {
            log.info("Cannot close stream", e);
        }
    }

    protected void initAudioInputStream(File file) throws UnsupportedAudioFileException, IOException {
        m_audioInputStream = AudioSystem.getAudioInputStream(file);
        m_audioFileFormat = AudioSystem.getAudioFileFormat(file);
    }

    protected void initAudioInputStream(URL url) throws UnsupportedAudioFileException, IOException {
        m_audioInputStream = AudioSystem.getAudioInputStream(url);
        m_audioFileFormat = AudioSystem.getAudioFileFormat(url);
    }

    protected void initAudioInputStream(InputStream inputStream) throws UnsupportedAudioFileException, IOException {
        m_audioInputStream = AudioSystem.getAudioInputStream(inputStream);
        m_audioFileFormat = AudioSystem.getAudioFileFormat(inputStream);
    }

    protected void initAudioInputStream() throws AudioPlayerException {
        try {
            reset();
            notify(PlayerEvent.OPENING, getEncodedStreamPosition(), -1, m_dataSource);
            if (m_dataSource instanceof URL) {
                initAudioInputStream((URL) m_dataSource);
            } else if (m_dataSource instanceof File) {
                initAudioInputStream((File) m_dataSource);
            } else if (m_dataSource instanceof InputStream) {
                initAudioInputStream((InputStream) m_dataSource);
            }

            log.info("Initializing sample and channel buffers");
            initSampleBuffer(m_audioInputStream.getFormat(), getSampleCount());
            initAudioBuffers(sampleBuffer.getSampleCount());

            createLine();

            m_status = OPENED;
            notify(PlayerEvent.OPENED, getEncodedStreamPosition(), -1);
        } catch (LineUnavailableException | UnsupportedAudioFileException | IOException e) {
            throw new AudioPlayerException(e);
        }
    }

    protected void createLine() throws LineUnavailableException {
        log.info("Create Line");
        if (m_line == null) {
            AudioFormat sourceFormat = m_audioInputStream.getFormat();
            log.info("Create Line : Source format : " + sourceFormat.toString());
            int nSampleSizeInBits = sourceFormat.getSampleSizeInBits();
            if (nSampleSizeInBits <= 0) nSampleSizeInBits = 16;
            if ((sourceFormat.getEncoding() == AudioFormat.Encoding.ULAW) || (sourceFormat.getEncoding() == AudioFormat.Encoding.ALAW)) nSampleSizeInBits = 16;
            if (nSampleSizeInBits != 8) nSampleSizeInBits = 16;
            AudioFormat targetFormat = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sourceFormat.getSampleRate(), nSampleSizeInBits, sourceFormat.getChannels(), sourceFormat.getChannels() * (nSampleSizeInBits / 8), sourceFormat.getSampleRate(), false);

            log.info("Create Line : Target format: " + targetFormat);
            // Keep a reference on encoded stream to progress notification.
            m_encodedaudioInputStream = m_audioInputStream;
            try {
                // Get total length in bytes of the encoded stream.
                encodedLength = m_encodedaudioInputStream.available();
            } catch (IOException e) {
                log.error("Cannot get m_encodedaudioInputStream.available()", e);
            }
            // Create decoded stream.
            m_audioInputStream = AudioSystem.getAudioInputStream(targetFormat, m_audioInputStream);
            AudioFormat audioFormat = m_audioInputStream.getFormat();
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat, AudioSystem.NOT_SPECIFIED);
            Mixer mixer = getMixer(m_mixerName);
            if (mixer != null) {
                log.info("Mixer : " + mixer.getMixerInfo().toString());
                m_line = (SourceDataLine) mixer.getLine(info);
            } else {
                m_line = (SourceDataLine) AudioSystem.getLine(info);
                m_mixerName = null;
            }
            log.info("Line : " + m_line.toString());
            log.debug("Line Info : " + m_line.getLineInfo().toString());
            log.debug("Line AudioFormat: " + m_line.getFormat().toString());
        }
    }

    protected void openLine() throws LineUnavailableException {
        if (m_line != null) {
            AudioFormat audioFormat = m_audioInputStream.getFormat();
            int buffersize = lineBufferSize;
            if (buffersize <= 0) buffersize = m_line.getBufferSize();
            m_line.open(audioFormat, buffersize);
            log.info("Open Line : BufferSize=" + buffersize);
            /*-- Display supported controls --*/
            Control[] c = m_line.getControls();
            for (Control control : c) {
                log.info("\t- Controls : " + control.toString());
            }
            //gain control
            if (m_line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                m_gainControl = (FloatControl) m_line.getControl(FloatControl.Type.MASTER_GAIN);
                log.info("Master Gain Control : [" + m_gainControl.getMinimum() + "," + m_gainControl.getMaximum() + "] " + m_gainControl.getPrecision());
            }
            //pan control
            if (m_line.isControlSupported(FloatControl.Type.PAN)) {
                m_panControl = (FloatControl) m_line.getControl(FloatControl.Type.PAN);
                log.info("Pan Control : [" + m_panControl.getMinimum() + "," + m_panControl.getMaximum() + "] " + m_panControl.getPrecision());
            }
        }
    }

    protected void initLine() throws LineUnavailableException {
        log.info("initLine()");
        if (m_line == null) createLine();
        if (!m_line.isOpen()) {
            openLine();
        } else {
            AudioFormat lineAudioFormat = m_line.getFormat();
            AudioFormat audioInputStreamFormat = m_audioInputStream == null ? null : m_audioInputStream.getFormat();
            if (!lineAudioFormat.equals(audioInputStreamFormat)) {
                m_line.close();
                openLine();
            }
        }
    }

    public void play() throws AudioPlayerException {
        startPlayback();
    }

    public long seek(long bytes) throws AudioPlayerException {
        return skipBytes(bytes);
    }

    public void stop() {
        stopPlayback();
    }

    public void pause() {
        pausePlayback();
    }

    public void resume() {
        resumePlayback();
    }

    protected long skipBytes(long bytes) throws AudioPlayerException {
        long totalSkipped = 0;
        if (m_dataSource instanceof File) {
            log.info("Bytes to skip : " + bytes);
            int previousStatus = m_status;
            m_status = SEEKING;
            long skipped = 0;
            try {
                synchronized (mutext) {
                    initAudioInputStream();
                    if (m_audioInputStream != null) {
                        // Loop until bytes are really skipped.
                        while (totalSkipped < (bytes - SKIP_INACCURACY_SIZE)) {
                            skipped = m_audioInputStream.skip(bytes - totalSkipped);
                            if (skipped == 0) break;
                            totalSkipped = totalSkipped + skipped;
                            log.info("Skipped : " + totalSkipped + "/" + bytes);
                            if (totalSkipped == -1) throw new AudioPlayerException("Skip not supported");
                        }
                    }
                }
                m_status = OPENED;
                if (previousStatus == PLAYING) startPlayback();
                else if (previousStatus == PAUSED) {
                    startPlayback();
                    pausePlayback();
                }
            } catch (IOException e) {
                throw new AudioPlayerException(e);
            }
        }
        return totalSkipped;
    }

    protected void startPlayback() throws AudioPlayerException {
        if (m_status == STOPPED) initAudioInputStream();
        if (m_status == OPENED) {
            log.info("startPlayback called");
            if (!(m_thread == null || !m_thread.isAlive())) {
                log.info("WARNING: old thread still running!!");
                int cnt = 0;
                while (m_status != OPENED) {
                    try {
                        if (m_thread != null) {
                            log.info("Waiting ... " + cnt);
                            cnt++;
                            Thread.sleep(1000);
                            if (cnt > 2) {
                                m_thread.interrupt();
                            }
                        }
                    } catch (InterruptedException e) {
                        throw new AudioPlayerException("Thread interrupted", e);
                    }
                }
            }
            // Open SourceDataLine.
            try {
                initLine();
            } catch (LineUnavailableException e) {
                throw new AudioPlayerException("Unable to initialize line", e);
            }
            log.info("Creating new thread");
            m_thread = new Thread(this, "CustomPlayer");
            m_thread.start();
            if (m_line != null) {
                m_line.start();
                m_status = PLAYING;
                notify(PlayerEvent.PLAYING, getEncodedStreamPosition(), -1);
            }
        }
    }

    protected void pausePlayback() {
        if (m_line != null) {
            if (m_status == PLAYING) {
                m_line.flush();
                m_line.stop();
                m_status = PAUSED;
                notify(PlayerEvent.PAUSED, getEncodedStreamPosition(), -1);
                log.info("pausePlayback() completed");
            }
        }
    }

    protected void stopPlayback() {
        if ((m_status == PLAYING) || (m_status == PAUSED)) {
            if (m_line != null) {
                m_line.flush();
                m_line.stop();
            }
            m_status = STOPPED;
            notify(PlayerEvent.STOPPED, getEncodedStreamPosition(), -1);
            synchronized (mutext) {
                closeStream();
            }
            log.info("stopPlayback() completed");
        }
    }

    protected void resumePlayback() {
        if (m_line != null) {
            if (m_status == PAUSED) {
                m_line.start();
                m_status = PLAYING;
                notify(PlayerEvent.RESUMED, getEncodedStreamPosition(), -1);
                log.info("resumePlayback() completed");
            }
        }
    }

    public Mixer getMixer(String name) {
        Mixer mixer = null;
        if (name != null) {
            Mixer.Info[] mInfos = AudioSystem.getMixerInfo();
            if (mInfos != null) {
                for (Mixer.Info mInfo : mInfos) {
                    if (mInfo.getName().equals(name)) {
                        mixer = AudioSystem.getMixer(mInfo);
                        break;
                    }
                }
            }
        }
        return mixer;
    }

    public int getStatus() {
        return m_status;
    }

    protected int getEncodedStreamPosition() {
        int nEncodedBytes = -1;
        if (m_dataSource instanceof File) {
            try {
                if (m_encodedaudioInputStream != null) {
                    nEncodedBytes = encodedLength - m_encodedaudioInputStream.available();
                }
            } catch (IOException e) {
                //log.debug("Cannot get m_encodedaudioInputStream.available()", e);
            }
        }
        return nEncodedBytes;
    }

    private void initSampleBuffer(AudioFormat audioFormat, int sampleCount) {
        if (sampleBuffer == null || (sampleBuffer.getSampleCount() < sampleCount)) {
            log.info("Creating float sample buffer with target format: {}, Sample Count: {}", audioFormat, sampleCount);
            sampleBuffer = new FloatSampleBuffer(audioFormat.getChannels(), sampleCount, audioFormat.getSampleRate());
        }
    }

    private void initAudioBuffers(int size) {
        if (leftChannelBuffer == null)
            leftChannelBuffer = new AudioBuffer(size);
        if (rightChannelBuffer == null)
            rightChannelBuffer = new AudioBuffer(size);
        if (monoChannelBuffer == null)
            monoChannelBuffer = new AudioBuffer(size);
        if (mixedChannelBuffer == null)
            mixedChannelBuffer = new AudioBuffer(size);
    }

    private void applySampleBuffer(AudioFormat format, byte[] audioData) {
        int frameCount = sampleBuffer.getByteArrayBufferSize(format) / format.getFrameSize();
        sampleBuffer.setSamplesFromBytes(audioData, 0, format, 0, frameCount);
    }

    private void applyChannelBuffers(FloatSampleBuffer sampleBuffer) {
        if (sampleBuffer == null)
            throw new IllegalStateException("Sample buffer cannot be null");

        //Extract audio samples
        float[] left = sampleBuffer.getChannel(0);
        float[] right = sampleBuffer.getChannel(1);
        float[] mono = new float[sampleBuffer.getSampleCount()];
        float[] mixed = new float[sampleBuffer.getSampleCount()];

        for (int i = 0; i < mixed.length; i++) {
            mixed[i] = left[i] + right[i];
        }
        for (int i = 0; i < mono.length; i++) {
            if (sampleBuffer.getChannelCount() == 2)
                mono[i] = (left[i] + right[i]) / 2.0f;
            else
                mono[i] = left[i];
        }

        //Store left, right and mixed to audio buffer
        leftChannelBuffer.set(left);
        rightChannelBuffer.set(right);
        monoChannelBuffer.set(mono);
        mixedChannelBuffer.set(mixed);

    }

    private long totalBytes = 0;

    private volatile double elapsed;

    public double getElapsed() {
        return elapsed;
    }

    public void run() {
        log.info("Thread Running");
        int nBytesRead = 1;
        byte[] abData = new byte[EXTERNAL_BUFFER_SIZE];
        int avgSize = 30;

        // Lock stream while playing.
        synchronized (mutext) {
            // Main play/pause loop.
            while ((nBytesRead != -1) && (m_status != STOPPED) && (m_status != SEEKING) && (m_status != UNKNOWN)) {
                if (m_status == PLAYING) {
                    // Play.
                    try {
                        nBytesRead = m_audioInputStream.read(abData, 0, abData.length);

                        if (nBytesRead >= 0) {
                            //byte[] pcmData = new byte[nBytesRead];
                            //System.arraycopy(abData, 0, pcmData, 0, nBytesRead);
                            totalBytes += nBytesRead;

                            AudioFormat format = m_audioInputStream.getFormat();

                            //Store raw audio data to FloatSampleBuffer
                            applySampleBuffer(format, abData);

                            //Extract audio samples from the available channels and apply FFT
                            applyChannelBuffers(sampleBuffer);

                            if (m_line.available() > m_line.getBufferSize())
                                log.debug("Buffer underrun : " + m_line.available() + "/" + m_line.getBufferSize());

                            long framesRead = totalBytes / format.getFrameSize();
                            long totalFrames = m_audioFileFormat.getFrameLength();

                            double totalSeconds = (double) totalFrames / format.getSampleRate();
                            this.elapsed = ((double) framesRead / (double) totalFrames) * totalSeconds;

                            //log.debug("total seconds: {}, elapsed: {}, frames read: {}, frame size: {}, frame length: {}", totalSeconds, elapsedSeconds, framesRead, format.getFrameSize(), totalFrames);
                            m_line.write(abData, 0, nBytesRead);
                        }
                    } catch (IOException e) {
                        log.error("Thread cannot run()", e);
                        m_status = STOPPED;
                        notify(PlayerEvent.STOPPED, getEncodedStreamPosition(), -1);
                    }
                    // Nice CPU usage.
                    if (threadSleep > 0) {
                        try {
                            Thread.sleep(threadSleep);
                        } catch (InterruptedException e) {
                            log.error("Thread cannot sleep(" + threadSleep + ")", e);
                        }
                    }
                } else {
                    // Pause
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        log.error("Thread cannot sleep(1000)", e);
                    }
                }
            }
            // Free audio resources.
            if (m_line != null) {
                m_line.drain();
                m_line.stop();
                m_line.close();
                m_line = null;
            }

            // Notification of "End Of Media"
            if (nBytesRead == -1) {
                notify(PlayerEvent.EOM, getEncodedStreamPosition(), -1);
            }
            // Close stream.
            closeStream();
        }
        m_status = STOPPED;
        notify(PlayerEvent.STOPPED, getEncodedStreamPosition(), -1);
        log.info("Thread completed");
    }

    public boolean hasPanControl() {
        if (m_panControl == null) {
            // Try to get Pan control again (to support J2SE 1.5)
            if ((m_line != null) && (m_line.isControlSupported(FloatControl.Type.PAN))) m_panControl = (FloatControl) m_line.getControl(FloatControl.Type.PAN);
        }
        return m_panControl != null;
    }

    public float getPrecision() {
        if (hasPanControl()) {
            return m_panControl.getPrecision();
        } else {
            return 0.0F;
        }
    }

    public float getPan() {
        if (hasPanControl()) {
            return m_panControl.getValue();
        } else {
            return 0.0F;
        }
    }
}

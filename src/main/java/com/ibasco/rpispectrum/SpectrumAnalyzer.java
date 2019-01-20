package com.ibasco.rpispectrum;

import com.ibasco.glcdemulator.client.GlcdRemoteClient;
import com.ibasco.glcdemulator.client.net.GeneralOptions;
import com.ibasco.glcdemulator.client.net.TcpTransporOptions;
import com.ibasco.glcdemulator.client.net.TcpTransport;
import com.ibasco.glcdemulator.client.net.Transport;
import com.ibasco.rpispectrum.audio.AudioPlayback;
import com.ibasco.ucgdisplay.drivers.glcd.*;
import com.ibasco.ucgdisplay.drivers.glcd.enums.GlcdBusInterface;
import com.ibasco.ucgdisplay.drivers.glcd.enums.GlcdFont;
import com.ibasco.ucgdisplay.drivers.glcd.enums.GlcdPin;
import com.ibasco.ucgdisplay.drivers.glcd.enums.GlcdRotation;
import ddf.minim.analysis.FFT;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("SpellCheckingInspection")
public class SpectrumAnalyzer {

    private boolean simulation;

    private static final Logger log = LoggerFactory.getLogger(SpectrumAnalyzer.class);

    private AudioPlayback audioPlayer = new AudioPlayback();

    private AtomicBoolean shutdown = new AtomicBoolean(false);

    private CommandLineParser parser = new DefaultParser();

    private HelpFormatter formatter = new HelpFormatter();

    private String ipAddress;

    private String transportDevice = "/dev/spidev0.0";

    private String gpioDevice = "/dev/gpiochip0";

    private int portNumber = 3580;

    private InputStream audioResource = getClass().getResourceAsStream("/audio/shep.mp3");

    public static void main(String[] args) throws Exception {
        new SpectrumAnalyzer().run(args);
    }

    private void ensureSimulationMode(CommandLine line) throws ParseException {
        if (!line.hasOption("simulation"))
            throw new ParseException("Simulation parameter required");
    }

    private void run(String[] args) throws Exception {
        // create Options object
        Options options = new Options();

        populateOptions(options);

        try {
            CommandLine line = parser.parse(options, args);
            if (line.hasOption("simulation")) {
                simulation = true;
            } else {
                simulation = false;
            }
            if (line.hasOption("ip")) {
                ensureSimulationMode(line);
                ipAddress = line.getOptionValue("ip");
            }
            if (line.hasOption("port")) {
                ensureSimulationMode(line);
                portNumber = Integer.valueOf(line.getOptionValue("port"));
            }
            if (line.hasOption("transport")) {
                transportDevice = line.getOptionValue("transport");
            }
            if (line.hasOption("gpio")) {
                gpioDevice = line.getOptionValue("gpio");
            }
            if (line.hasOption("audio")) {
                File audioFile = Paths.get(line.getOptionValue("audio")).toFile();
                if (!audioFile.exists() || !audioFile.canRead()) {
                    String msg = "Could not find audio file resource: " + audioFile;
                    System.err.println(msg);
                    throw new ParseException(msg);
                }
                audioResource = new FileInputStream(audioFile);
            }
        } catch (ParseException e) {
            formatter.printHelp("java " + System.getProperty("sun.java.command"), options, true);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down");
            shutdown.set(true);
        }));

        //Configure GLCD
        GlcdConfig config = new GlcdConfig();
        config.setDisplay(Glcd.ST7920.D_128x64); //Glcd.RA8835.D_320x240
        config.setBusInterface(GlcdBusInterface.SPI_HW_4WIRE_ST7920);


        GlcdBaseDriver driver = createDriver(config);

        audioPlayer.setLineBufferSize(1024 * 4);
        audioPlayer.open(audioResource);
        audioPlayer.play();

        FFT fft = new FFT(audioPlayer.getSampleCount(), audioPlayer.getAudioFormat().getSampleRate());
        fft.linAverages(30);

        driver.setFont(GlcdFont.FONT_6X13_MR);

        ForkJoinPool.commonPool().execute(() -> {
            try {
                while (!shutdown.get()) {
                    float[] samples = audioPlayer.getMixedChannel().get();
                    fft.forward(samples);

                    int width = driver.getWidth();
                    int height = driver.getHeight();
                    int total = fft.avgSize();
                    int spacing = 2;
                    int barWidth = width / total;
                    int scaleFactor = 1;

                    driver.clearBuffer();
                    driver.drawString(width / 2, 20, formatDuration(Duration.ofSeconds((long) audioPlayer.getElapsed())));
                    for (int i = 0; i < total; i++) {
                        float value = fft.getAvg(i);
                        int y = (int) (height - (value * scaleFactor));
                        int barHeight = height - y;
                        int x = i * barWidth;
                        driver.drawBox(x, y, barWidth - spacing, barHeight);
                    }
                    driver.sendBuffer();

                    Thread.sleep(5);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
    }

    private void populateOptions(Options options) {
        options.addOption(Option.builder("s").longOpt("simulation").desc("Set to simulation mode (use with the glcd-emulator program)").build());
        options.addOption(Option.builder("i").hasArg().argName("ip address").longOpt("ip").desc("Hostname/IP Address").build());
        options.addOption(Option.builder("p").hasArg().argName("port number").longOpt("port").desc("Port number (Default: 3580)").build());
        options.addOption(Option.builder("a").hasArg().argName("mp3 audio").longOpt("audio").desc("MP3 audio path").build());
        options.addOption(Option.builder("t").hasArg().argName("device path").longOpt("transport").desc("Transport device path (Default: /dev/spidev0.0)").build());
        options.addOption(Option.builder("g").hasArg().argName("device path").longOpt("gpio").desc("GPIO device path (Default: /dev/gpiochip0)").build());
    }

    private static String formatDuration(Duration duration) {
        long seconds = duration.getSeconds();
        long absSeconds = Math.abs(seconds);
        String positive = String.format(
                "%d:%02d:%02d",
                absSeconds / 3600,
                (absSeconds % 3600) / 60,
                absSeconds % 60);
        return seconds < 0 ? "-" + positive : positive;
    }

    private GlcdBaseDriver createDriver(GlcdConfig config) {
        if (simulation) {
            Transport dataTransport = new TcpTransport();
            dataTransport.setOption(TcpTransporOptions.IP_ADDRESS, ipAddress);
            dataTransport.setOption(TcpTransporOptions.PORT_NUMBER, portNumber); //3580
            dataTransport.setOption(GeneralOptions.DEBUG_OUTPUT, false);
            config.setRotation(GlcdRotation.ROTATION_NONE);
            return new GlcdRemoteClient(config, dataTransport);
        } else {
            GlcdPinMapConfig pinMap = new GlcdPinMapConfig()
                    .map(GlcdPin.SPI_CLOCK, 14)
                    .map(GlcdPin.SPI_MOSI, 12)
                    .map(GlcdPin.CS, 10);
            config.setRotation(GlcdRotation.ROTATION_180);
            config.setPinMapConfig(pinMap);
            config.setTransportDevice(transportDevice);
            config.setGpioDevice(gpioDevice);
            return new GlcdDriver(config);
        }
    }
}

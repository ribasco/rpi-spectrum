package com.ibasco.rpispectrum.audio;

import javax.sound.sampled.*;
import java.util.HashMap;

public class AudioUtils {
    public static HashMap<Mixer.Info, Line.Info> getRecordingDataLines() {
        HashMap<Mixer.Info, Line.Info> out = new HashMap<>();
        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        for (Mixer.Info mixerInfo : mixerInfos) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            Line.Info[] lineInfos = mixer.getTargetLineInfo();
            if (lineInfos.length >= 1 && lineInfos[0].getLineClass().equals(TargetDataLine.class)) {
                out.put(mixerInfo, lineInfos[0]);
            }
        }
        return out;
    }

    public static HashMap<Mixer.Info, Line.Info> getPlaybackDataLines() {
        HashMap<Mixer.Info, Line.Info> out = new HashMap<>();
        Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
        for (Mixer.Info mixerInfo : mixerInfos) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            Line.Info[] lineInfos = mixer.getSourceLineInfo();
            if (lineInfos.length >= 1 && lineInfos[0].getLineClass().equals(SourceDataLine.class)) {
                out.put(mixerInfo, lineInfos[0]);
            }
        }
        return out;
    }

    public static Mixer getDefaultPlaybackMixer() {
        return AudioSystem.getMixer(null);
    }
}

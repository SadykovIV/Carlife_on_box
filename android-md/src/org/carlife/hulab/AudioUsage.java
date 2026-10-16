package org.carlife.hulab;
/** Disjoint native Android playback roles; no amplitude-based classification. */
final class AudioUsage {
    static boolean isMedia(int usage){return usage==0||usage==1||usage==14;}
    static boolean isSpeech(int usage){return usage==12;}
    static boolean isAssistant(int usage){return usage==16;}
    static int[] forLane(CarLifeAudio.Lane lane){
        switch(lane){case MEDIA:return new int[]{0,1,14};case TTS:return new int[]{12};case VR:return new int[]{16};default:throw new IllegalArgumentException("unknown audio lane");}
    }
}

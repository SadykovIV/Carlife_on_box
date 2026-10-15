package org.carlife.hulab;
/** Disjoint native Android playback roles; no amplitude-based classification. */
final class AudioUsage {
    static boolean isMedia(int usage){return usage==0||usage==1||usage==14;}
    static boolean isSpeech(int usage){return usage==12||usage==16;}
    static int[] forLane(CarLifeAudio.Lane lane){return lane==CarLifeAudio.Lane.MEDIA?new int[]{0,1,14}:new int[]{12,16};}
}

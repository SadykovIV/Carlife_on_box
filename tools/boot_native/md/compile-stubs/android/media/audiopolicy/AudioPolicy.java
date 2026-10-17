package android.media.audiopolicy;
import android.media.AudioFocusInfo;
/** Compile-only AOSP API-35 listener signatures; never packaged in the probe. */
public final class AudioPolicy {
    public static abstract class AudioPolicyFocusListener {
        public void onAudioFocusGrant(AudioFocusInfo info,int result) {}
        public void onAudioFocusLoss(AudioFocusInfo info,boolean wasNotified) {}
        public void onAudioFocusRequest(AudioFocusInfo info,int result) {}
        public void onAudioFocusAbandon(AudioFocusInfo info) {}
    }
}

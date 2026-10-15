package org.carlife.hulab;

import android.media.AudioFormat;
import android.media.AudioRecord;
import java.io.IOException;

/** Captures Android output, not the microphone. Uses the process's existing permissions. */
final class RemoteSubmixSource implements PcmAudioSession.Source {
    static final int REMOTE_SUBMIX=8; // MediaRecorder.AudioSource hidden constant, Android platform API.
    private AudioRecord record;
    public void start()throws IOException{
        int minimum=AudioRecord.getMinBufferSize(CarLifeAudio.RATE,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT);
        if(minimum<=0)throw new IOException("remote submix format unsupported: "+minimum);
        int buffer=Math.max(minimum,CarLifeAudio.CHUNK_BYTES*4);
        record=new AudioRecord.Builder().setAudioSource(REMOTE_SUBMIX)
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(CarLifeAudio.RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(buffer).build();
        if(record.getState()!=AudioRecord.STATE_INITIALIZED||record.getSampleRate()!=CarLifeAudio.RATE||record.getChannelCount()!=CarLifeAudio.CHANNELS||record.getAudioFormat()!=AudioFormat.ENCODING_PCM_16BIT)
            throw new IOException("remote submix actual format/state mismatch");
        record.startRecording();
        if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException("remote submix did not start");
    }
    public int read(byte[] data,int offset,int length)throws IOException{
        int n=record.read(data,offset,length,AudioRecord.READ_NON_BLOCKING);
        if(n<0)throw new IOException("AudioRecord read error "+n);return n;
    }
    public void close(){AudioRecord r=record;record=null;if(r!=null)r.release();}
}

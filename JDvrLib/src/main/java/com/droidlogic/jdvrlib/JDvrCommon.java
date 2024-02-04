package com.droidlogic.jdvrlib;

import android.media.MediaFormat;
import android.media.tv.tuner.filter.AvSettings;
import android.util.Log;

public class JDvrCommon {
    final static private String TAG = JDvrFile.class.getSimpleName();
    private static int mNextSessionNumber = 0;
    // Enums
    public static class JDvrStreamType {
        public final static int STREAM_TYPE_VIDEO = 0;
        public final static int STREAM_TYPE_AUDIO = 1;
        public final static int STREAM_TYPE_AD = 2;
        public final static int STREAM_TYPE_SUBTITLE = 3;
        public final static int STREAM_TYPE_TELETEXT = 4;
        public final static int STREAM_TYPE_ECM = 5;
        public final static int STREAM_TYPE_EMM = 6;
        public final static int STREAM_TYPE_OTHER = 7;
    }
    public static class JDvrVideoFormat {
        public final static int VIDEO_FORMAT_UNDEFINED = AvSettings.VIDEO_STREAM_TYPE_UNDEFINED;
        public final static int VIDEO_FORMAT_MPEG1 = AvSettings.VIDEO_STREAM_TYPE_MPEG1;
        public final static int VIDEO_FORMAT_MPEG2 = AvSettings.VIDEO_STREAM_TYPE_MPEG2;
        public final static int VIDEO_FORMAT_H264 = AvSettings.VIDEO_STREAM_TYPE_AVC;
        public final static int VIDEO_FORMAT_HEVC = AvSettings.VIDEO_STREAM_TYPE_HEVC;
        public final static int VIDEO_FORMAT_VP9 = AvSettings.VIDEO_STREAM_TYPE_VP9;
    }
    public static class JDvrAudioFormat {
        public final static int AUDIO_FORMAT_UNDEFINED = AvSettings.AUDIO_STREAM_TYPE_UNDEFINED;
        public final static int AUDIO_FORMAT_MPEG = AvSettings.AUDIO_STREAM_TYPE_MPEG1;
        public final static int AUDIO_FORMAT_MPEG2 = AvSettings.AUDIO_STREAM_TYPE_MPEG2;
        public final static int AUDIO_FORMAT_AC3 = AvSettings.AUDIO_STREAM_TYPE_AC3;
        public final static int AUDIO_FORMAT_EAC3 = AvSettings.AUDIO_STREAM_TYPE_EAC3;
        public final static int AUDIO_FORMAT_DTS = AvSettings.AUDIO_STREAM_TYPE_DTS;
        public final static int AUDIO_FORMAT_AAC = AvSettings.AUDIO_STREAM_TYPE_AAC;
        public final static int AUDIO_FORMAT_HEAAC = AvSettings.AUDIO_STREAM_TYPE_AAC_HE_ADTS;
        public final static int AUDIO_FORMAT_LATM = AvSettings.AUDIO_STREAM_TYPE_AAC_LATM;
        public final static int AUDIO_FORMAT_PCM = AvSettings.AUDIO_STREAM_TYPE_PCM;
        public final static int AUDIO_FORMAT_AC4 = AvSettings.AUDIO_STREAM_TYPE_AC4;
    }

    // Functions
    public static int generateSessionNumber() {
        return mNextSessionNumber++;
    }
    public static String getCallerInfo(int level) {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        StackTraceElement caller = stackTraceElements[level];
        return caller.getMethodName() +":"+caller.getLineNumber();
    }
    public static String JDvrAudioFormatToMimeType(int format) {
        if (format == JDvrAudioFormat.AUDIO_FORMAT_MPEG) {
            return MediaFormat.MIMETYPE_AUDIO_MPEG;
        } else if (format == JDvrAudioFormat.AUDIO_FORMAT_MPEG2) {
            return MediaFormat.MIMETYPE_AUDIO_MPEG;
        } else if (format == JDvrAudioFormat.AUDIO_FORMAT_AAC) {
            return MediaFormat.MIMETYPE_AUDIO_AAC;
        } else if (format == JDvrAudioFormat.AUDIO_FORMAT_EAC3) {
            return MediaFormat.MIMETYPE_AUDIO_EAC3;
        } else if (format == JDvrAudioFormat.AUDIO_FORMAT_AC3) {
            return MediaFormat.MIMETYPE_AUDIO_AC3;
        } else if (format == JDvrAudioFormat.AUDIO_FORMAT_AC4) {
            return MediaFormat.MIMETYPE_AUDIO_AC4;
        } else {
            Log.e(TAG,"Unrecognized format:"+format);
            return null;
        }
    }
}

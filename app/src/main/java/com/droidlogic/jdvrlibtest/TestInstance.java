package com.droidlogic.jdvrlibtest;

import static android.media.tv.TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK;
import static android.media.tv.TvInputService.PRIORITY_HINT_USE_CASE_TYPE_RECORD;

import android.media.tv.tuner.Tuner;
import android.media.tv.tuner.dvr.DvrSettings;
import android.media.tv.tuner.filter.AvSettings;
import android.media.tv.tuner.filter.Filter;
import android.media.tv.tuner.filter.FilterCallback;
import android.media.tv.tuner.filter.FilterConfiguration;
import android.media.tv.tuner.filter.FilterEvent;
import android.media.tv.tuner.filter.Settings;
import android.media.tv.tuner.filter.TsFilterConfiguration;
import android.media.tv.tuner.frontend.Atsc3PlpInfo;
import android.media.tv.tuner.frontend.DvbcFrontendSettings;
import android.media.tv.tuner.frontend.FrontendSettings;
import android.media.tv.tuner.frontend.OnTuneEventListener;
import android.media.tv.tuner.frontend.ScanCallback;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import com.amlogic.asplayer.api.ASPlayer;
import com.amlogic.asplayer.api.AudioParams;
import com.amlogic.asplayer.api.EventMask;
import com.amlogic.asplayer.api.InitParams;
import com.amlogic.asplayer.api.InputSourceType;
import com.amlogic.asplayer.api.VideoParams;
import com.droidlogic.jdvrlib.JDvrFile;
import com.droidlogic.jdvrlib.JDvrPlayer;
import com.droidlogic.jdvrlib.JDvrPlayer.JDvrPlaybackProgress;
import com.droidlogic.jdvrlib.JDvrPlayerSettings;
import com.droidlogic.jdvrlib.JDvrCommon.*;
import com.droidlogic.jdvrlib.JDvrRecorder;
import com.droidlogic.jdvrlib.JDvrRecorder.JDvrRecordingProgress;
import com.droidlogic.jdvrlib.JDvrRecorderSettings;
import com.droidlogic.jdvrlib.OnJDvrPlayerEventListener;
import com.droidlogic.jdvrlib.OnJDvrRecorderEventListener;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executor;

public class TestInstance implements OnTuneEventListener,
        ScanCallback {
    private String TAG = TestInstance.class.getSimpleName();
    private Tuner mTunerForRecording;
    private Tuner mTunerForPlayback;
    private MainActivity mActivity;
    final private TunerExecutor mExecutor;
    private Handler mTaskHandler = null;
    private HandlerThread mHandlerThread = null;
    final private Handler mUiHandler;
    private JDvrRecorder mJDvrRecorder;
    JDvrRecorderSettings mJDvrRecorderSettings = JDvrRecorderSettings
            .builder()
            .setDataFormat(DvrSettings.DATA_FORMAT_TS)
                .setLowThreshold(100)
                .setHighThreshold(900)
                .setPacketSize(188)
                .setRecorderBufferSize(10*1024*1024)
                .setFilterBufferSize(1024*1024)
                .setSegmentSize(30*1024*1024)
                .build();
    JDvrPlayerSettings mJDvrPlayerSettings = JDvrPlayerSettings.builder().build();
    private boolean mLocked = false;
    private final String mFolder = "/storage/emulated/0/Recordings";
    //private final String mFolder = "/storage/C632-AAA0/Recordings";
    private JDvrPlayer mJDvrPlayer = null;
    private ASPlayer mASPlayer = null;
    private JDvrPlaybackProgress mProgress = null;
    int mPlayingFileHandle = 0;
    private final static int TIMESHIFT_MAGIC_CODE = 8888;

    private final OnJDvrRecorderEventListener mJDvrRecorderEventListener = new OnJDvrRecorderEventListener() {
        @Override
        public void onJDvrRecorderEvent(Message msg) {
            //Log.d(TAG, "TestInstance receives message, what:" + msg.what);
            switch (msg.what) {
                case JDvrRecorderEvent.NOTIFY_DEBUG_MSG:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    msg.obj.toString()));
                    break;
                case JDvrRecorderEvent.NOTIFY_INITIAL_STATE:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received Recorder NOTIFY_INITIAL_STATE"));
                    break;
                case JDvrRecorderEvent.NOTIFY_STARTING_STATE:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received Recorder NOTIFY_STARTING_STATE"));
                    break;
                case JDvrRecorderEvent.NOTIFY_STARTED_STATE:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received Recorder NOTIFY_STARTED_STATE"));
                    break;
                case JDvrRecorderEvent.NOTIFY_STOPPING_STATE:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received Recorder NOTIFY_STOPPING_STATE"));
                    break;
                case JDvrRecorderEvent.NOTIFY_PAUSED_STATE:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received Recorder NOTIFY_PAUSED_STATE"));
                    break;
                case JDvrRecorderEvent.NOTIFY_NO_DATA_ERROR:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received Recorder NOTIFY_NO_DATA_ERROR"));
                    break;
                case JDvrRecorderEvent.NOTIFY_IO_ERROR:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received Recorder NOTIFY_IO_ERROR"));
                    break;
                case JDvrRecorderEvent.NOTIFY_DISK_FULL_ERROR:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received Recorder NOTIFY_DISK_FULL_ERROR"));
                    break;
                case JDvrRecorderEvent.NOTIFY_PROGRESS:
                    JDvrRecordingProgress progress = (JDvrRecordingProgress) msg.obj;
                    Log.d(TAG,"recording: "+progress.toString());
                    break;
                default:
                    break;
            }
        }
    };
    private final OnJDvrPlayerEventListener mJDvrPlayerEventListener = new OnJDvrPlayerEventListener() {
        private int mLastEvent = 0;
        @Override
        public void onJDvrPlayerEvent(Message msg) {
            switch (msg.what) {
                case JDvrPlayerEvent.NOTIFY_DEBUG_MSG:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS, msg.obj.toString()));
                    break;
                case JDvrPlayerEvent.NOTIFY_PROGRESS:
                    JDvrPlaybackProgress progress = (JDvrPlaybackProgress) msg.obj;
                    Log.d(TAG,"playback: "+progress.toString());
                    Message msg2 = new Message();
                    msg2.what = MainActivity.UI_MSG_PROGRESS;
                    msg2.obj = progress;
                    mUiHandler.sendMessage(msg2);
                    mProgress = progress;
                    break;
                case JDvrPlayerEvent.NOTIFY_EOS:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received Player NOTIFY_EOS"));
                    break;
                case JDvrPlayerEvent.NOTIFY_EDGE_LEAVING:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received Player NOTIFY_EDGE_LEAVING pos:"+msg.arg1));
                    break;
                case JDvrPlayerEvent.NOTIFY_INITIAL_STATE:
                    if (mLastEvent == JDvrPlayerEvent.NOTIFY_EOS
                            || mLastEvent == JDvrPlayerEvent.NOTIFY_STOPPING_STATE) {
                        mJDvrPlayer = null;
                        mASPlayer.release();
                        mASPlayer = null;
                    }
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received Player NOTIFY_INITIAL_STATE"));
                    break;
                case JDvrPlayerEvent.NOTIFY_STARTING_STATE:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received Player NOTIFY_STARTING_STATE"));
                    break;
                case JDvrPlayerEvent.NOTIFY_SMOOTH_PLAYING_STATE:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received Player NOTIFY_SMOOTH_PLAYING_STATE"));
                    break;
                case JDvrPlayerEvent.NOTIFY_SKIPPING_PLAYING_STATE:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received Player NOTIFY_SKIPPING_PLAYING_STATE"));
                    break;
                case JDvrPlayerEvent.NOTIFY_PAUSED_STATE:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received Player NOTIFY_PAUSED_STATE"));
                    break;
                case JDvrPlayerEvent.NOTIFY_STOPPING_STATE:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received Player NOTIFY_STOPPING_STATE"));
                    break;
            }
            mLastEvent = msg.what;
        }
    };

    public TestInstance(int instance, MainActivity activity) {
        TAG += "["+instance+"]";
        mActivity = activity;
        mUiHandler = mActivity.getUiHandler();
        System.loadLibrary("jdvrlib-jni");
        System.loadLibrary("jdvrlib-ref-native-client");
        initHandler();
        mExecutor = new TunerExecutor();
        mTunerForRecording = new Tuner(mActivity.getApplicationContext(),
                null,PRIORITY_HINT_USE_CASE_TYPE_RECORD);
        Log.d(TAG, "Tuner is created:" + mTunerForRecording);
        mTunerForRecording.setOnTuneEventListener(mExecutor, this);
        mTunerForPlayback = new Tuner(mActivity.getApplicationContext(),
                null,PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        Log.d(TAG, "Tuner2 is created:" + mTunerForPlayback);
        mTunerForPlayback.setOnTuneEventListener(mExecutor, this);
        native_passTuners(mTunerForRecording,mTunerForPlayback);
        native_passRecorderSettings(mJDvrRecorderSettings);
        native_passPlayerSettings(mJDvrPlayerSettings);
    }

    public void onTuneEvent(int tuneEvent) {
        Log.d(TAG, "onTuneEvent event: " + tuneEvent);
        if (tuneEvent == OnTuneEventListener.SIGNAL_LOCKED) {
            mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS, "SIGNAL_LOCKED"));
            mLocked = true;
            Log.i(TAG, "SIGNAL_LOCKED");
        } else if (tuneEvent == OnTuneEventListener.SIGNAL_NO_SIGNAL) {
            mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS, "SIGNAL_NO_SIGNAL"));
            Log.w(TAG, "SIGNAL_NO_SIGNAL");
        } else if (tuneEvent == OnTuneEventListener.SIGNAL_LOST_LOCK) {
            mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS, "SIGNAL_LOST_LOCK"));
            mLocked = false;
            Log.w(TAG, "SIGNAL_LOST_LOCK");
        }
    }

    private void tuneStart() {
        FrontendSettings feSettings;
        int freqMhz = 666;
        int symbol = 6875;
        feSettings = DvbcFrontendSettings
                .builder()
                .setFrequencyLong(freqMhz * 1000000)
                .setAnnex(DvbcFrontendSettings.ANNEX_A)
                .setSymbolRate(symbol * 1000)
                .build();
        if (feSettings != null) {
            if (mTunerForRecording != null) {
                mTunerForRecording.tune(feSettings);
            }
        }
    }
    private void tuneStop() {
        if (mTunerForRecording != null) {
            mTunerForRecording.cancelTuning();
        }
    }
    private void recordingStart() {
        if (!mLocked) {
            mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                    "Should not start recording, as tuner is not locked"));
            return;
        }
        if (mJDvrRecorder == null) {
            Log.e(TAG, "recordingStart, JDvrRecorder is invalid");
            return;
        }
        mJDvrRecorder.start();
    }
    private void recordingStop() {
        if (mJDvrRecorder == null) {
            Log.e(TAG, "recordingStop, JDvrRecorder is invalid");
            return;
        }
        mJDvrRecorder.stop();
    }
    private void addStreams1() {
        if (mJDvrRecorder == null) {
            Log.e(TAG, "addStream1, JDvrRecorder is invalid");
            return;
        }
        // BBC MUX UH.ts
        mJDvrRecorder.addStream(0, JDvrStreamType.STREAM_TYPE_OTHER, 0);
        mJDvrRecorder.addStream(600, JDvrStreamType.STREAM_TYPE_VIDEO,JDvrVideoFormat.VIDEO_FORMAT_MPEG2);
        mJDvrRecorder.addStream(601, JDvrStreamType.STREAM_TYPE_AUDIO, JDvrAudioFormat.AUDIO_FORMAT_MPEG);
        mJDvrRecorder.addStream(602, JDvrStreamType.STREAM_TYPE_AUDIO, JDvrAudioFormat.AUDIO_FORMAT_MPEG);
        mJDvrRecorder.addStream(4161, JDvrStreamType.STREAM_TYPE_OTHER,0);
    }
    private void addStreams2() {
        if (mJDvrRecorder == null) {
            Log.e(TAG, "addStream2, JDvrRecorder is invalid");
            return;
        }
        // BBC MUX UH.ts
        mJDvrRecorder.addStream(0, JDvrStreamType.STREAM_TYPE_OTHER, 0);
        mJDvrRecorder.addStream(610, JDvrStreamType.STREAM_TYPE_VIDEO,JDvrVideoFormat.VIDEO_FORMAT_MPEG2);
        mJDvrRecorder.addStream(611, JDvrStreamType.STREAM_TYPE_AUDIO, JDvrAudioFormat.AUDIO_FORMAT_MPEG);
        mJDvrRecorder.addStream(612, JDvrStreamType.STREAM_TYPE_AUDIO, JDvrAudioFormat.AUDIO_FORMAT_MPEG);
        mJDvrRecorder.addStream(4225, JDvrStreamType.STREAM_TYPE_OTHER,0);
    }
    private void addStreams3() {
        if (mJDvrRecorder == null) {
            Log.e(TAG, "addStream3, JDvrRecorder is invalid");
            return;
        }
        // TRT 4K_2019102211412302.ts
        mJDvrRecorder.addStream(0, JDvrStreamType.STREAM_TYPE_OTHER, 0);
        mJDvrRecorder.addStream(1, JDvrStreamType.STREAM_TYPE_OTHER, 0);
        mJDvrRecorder.addStream(17, JDvrStreamType.STREAM_TYPE_OTHER, 0);
        mJDvrRecorder.addStream(4755, JDvrStreamType.STREAM_TYPE_OTHER,0);
        mJDvrRecorder.addStream(4855, JDvrStreamType.STREAM_TYPE_VIDEO,JDvrVideoFormat.VIDEO_FORMAT_HEVC);
        mJDvrRecorder.addStream(4955, JDvrStreamType.STREAM_TYPE_AUDIO, JDvrAudioFormat.AUDIO_FORMAT_EAC3);
        mJDvrRecorder.addStream(5055, JDvrStreamType.STREAM_TYPE_AUDIO, JDvrAudioFormat.AUDIO_FORMAT_AAC);
    }
    private void addStreams4() {
        if (mJDvrRecorder == null) {
            Log.e(TAG, "addStream4, JDvrRecorder is invalid");
            return;
        }
        // gr1.ts
        mJDvrRecorder.addStream(0, JDvrStreamType.STREAM_TYPE_OTHER, 0);
        mJDvrRecorder.addStream(273, JDvrStreamType.STREAM_TYPE_OTHER, 0);
        mJDvrRecorder.addStream(220, JDvrStreamType.STREAM_TYPE_VIDEO,JDvrVideoFormat.VIDEO_FORMAT_H264);
        mJDvrRecorder.addStream(230, JDvrStreamType.STREAM_TYPE_AUDIO, JDvrAudioFormat.AUDIO_FORMAT_MPEG);
        mJDvrRecorder.addStream(231, JDvrStreamType.STREAM_TYPE_AUDIO, JDvrAudioFormat.AUDIO_FORMAT_MPEG);
        mJDvrRecorder.addStream(232, JDvrStreamType.STREAM_TYPE_AUDIO, JDvrAudioFormat.AUDIO_FORMAT_MPEG);
        mJDvrRecorder.addStream(240, JDvrStreamType.STREAM_TYPE_SUBTITLE, 0);
        mJDvrRecorder.addStream(242, JDvrStreamType.STREAM_TYPE_SUBTITLE, 0);
    }
    private void addStreams5() {
        if (mJDvrRecorder == null) {
            Log.e(TAG, "addStream5, JDvrRecorder is invalid");
            return;
        }
        // bbc_TV&Radio.ts
        mJDvrRecorder.addStream(0, JDvrStreamType.STREAM_TYPE_OTHER, 0);
        mJDvrRecorder.addStream(4543, JDvrStreamType.STREAM_TYPE_OTHER, 0);
        mJDvrRecorder.addStream(660, JDvrStreamType.STREAM_TYPE_AUDIO, JDvrAudioFormat.AUDIO_FORMAT_MPEG);
    }
    private void removeStreams1() {
        if (mJDvrRecorder == null) {
            Log.e(TAG, "removeStreams1, JDvrRecorder is invalid");
            return;
        }
        // BBC MUX UH.ts
        mJDvrRecorder.removeStream(0);
        mJDvrRecorder.removeStream(600);
        mJDvrRecorder.removeStream(601);
        mJDvrRecorder.removeStream(602);
        mJDvrRecorder.removeStream(4161);
    }
    private void removeStreams2() {
        if (mJDvrRecorder == null) {
            Log.e(TAG, "removeStreams2, JDvrRecorder is invalid");
            return;
        }
        // BBC MUX UH.ts
        mJDvrRecorder.removeStream(0);
        mJDvrRecorder.removeStream(610);
        mJDvrRecorder.removeStream(611);
        mJDvrRecorder.removeStream(612);
        mJDvrRecorder.removeStream(4225);
    }
    private void removeStreams3() {
        if (mJDvrRecorder == null) {
            Log.e(TAG, "removeStreams3, JDvrRecorder is invalid");
            return;
        }
        // TRT 4K_2019102211412302.ts
        mJDvrRecorder.removeStream(0);
        mJDvrRecorder.removeStream(1);
        mJDvrRecorder.removeStream(17);
        mJDvrRecorder.removeStream(4755);
        mJDvrRecorder.removeStream(4855);
        mJDvrRecorder.removeStream(4955);
        mJDvrRecorder.removeStream(5055);
    }
    private void removeStreams4() {
        if (mJDvrRecorder == null) {
            Log.e(TAG, "removeStreams4, JDvrRecorder is invalid");
            return;
        }
        // gr1.ts
        mJDvrRecorder.removeStream(0);
        mJDvrRecorder.removeStream(273);
        mJDvrRecorder.removeStream(220);
        mJDvrRecorder.removeStream(230);
        mJDvrRecorder.removeStream(231);
        mJDvrRecorder.removeStream(232);
        mJDvrRecorder.removeStream(240);
        mJDvrRecorder.removeStream(242);
    }
    private void removeStreams5() {
        if (mJDvrRecorder == null) {
            Log.e(TAG, "removeStreams5, JDvrRecorder is invalid");
            return;
        }
        // bbc_TV&Radio.ts
        mJDvrRecorder.removeStream(0);
        mJDvrRecorder.removeStream(4543);
        mJDvrRecorder.removeStream(660);
    }
    private void prepareRecorder() {
        int index = determineRecordingIndex(mFolder);
        if (index == -1) {
            mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                    "Failed to generate a recording prefix"));
            return;
        }
        final String prefix = String.format(Locale.US,mFolder +"/%08d",index);
        mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                "Recording prefix: "+prefix));
        JDvrFile recFile;
        try {
            recFile = new JDvrFile(prefix,true);
        } catch (RuntimeException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
            mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                    "Exception: " + e));
            return;
        }
        mJDvrRecorder = new JDvrRecorder(mTunerForRecording, recFile, mJDvrRecorderSettings,
                mExecutor, mJDvrRecorderEventListener);
    }
    private void prepareTimeshiftRecorder() {
        final String prefix = String.format(Locale.US,mFolder +"/timeshift");
        mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                "Recording prefix: "+prefix));
        JDvrFile recFile;
        try {
            recFile = new JDvrFile(prefix,300*1024*1024,360,true);
        } catch (RuntimeException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
            mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                    "Exception: " + e));
            return;
        }
        mJDvrRecorder = new JDvrRecorder(mTunerForRecording, recFile, mJDvrRecorderSettings,
                mExecutor, mJDvrRecorderEventListener);
    }
    private void pauseRecording() {
        if (mJDvrRecorder == null) {
            Log.e(TAG, "pauseRecording, JDvrRecorder is invalid");
            return;
        }
        mJDvrRecorder.pause();
    }
    private boolean createNativeJDvrPlayer(final int rec_id, Surface surface) {
        if (mASPlayer != null) {
            mASPlayer.release();
            mASPlayer = null;
        }
        if (surface == null) {
            Log.e(TAG, "Invalid surface");
            return false;
        }
        final String recPrefix = String.format(Locale.US,"%s/%08d",mFolder,rec_id);
        mPlayingFileHandle = native_prepareFileForPlayback(recPrefix);
        if (mPlayingFileHandle == -1) {
            Log.e(TAG, "Invalid playback file handle -1");
            return false;
        }
        final int videoPid = native_getVideoPID(mPlayingFileHandle);
        final int videoFormat = native_getVideoFormat(mPlayingFileHandle);
        if (videoPid == 0x1fff || videoFormat == JDvrVideoFormat.VIDEO_FORMAT_UNDEFINED) {
            Log.e(TAG, "Invalid video PID or video format");
            return false;
        }
        final int audioPid = native_getAudioPID(mPlayingFileHandle);
        final int audioFormat = native_getAudioFormat(mPlayingFileHandle);
        if (audioPid == 0x1fff || audioFormat == JDvrAudioFormat.AUDIO_FORMAT_UNDEFINED) {
            Log.e(TAG, "Invalid audio PID or audio format");
            return false;
        }
        InitParams initParams = new InitParams.Builder()
                .setPlaybackMode(InitParams.PLAYBACK_MODE_PASSTHROUGH)
                .setInputSourceType(InputSourceType.TS_MEMORY)
                .setEventMask(EventMask.EVENT_TYPE_PTS_MASK)
                .build();
        mASPlayer = new ASPlayer(initParams, mTunerForPlayback, null);
        mASPlayer.prepare();

        Filter videoFilter = openVideoFilter(videoPid,videoFormat);
        if (videoFilter == null) {
            Log.e(TAG,"Failed to create video filter");
            return false;
        }
        videoFilter.start();
        final int width = 1920;
        final int height = 1080;
        final int avSyncHwId = mTunerForPlayback.getAvSyncHwId(videoFilter);
        if (avSyncHwId == -1) {
            Log.e(TAG,"AvSyncHwId is invalid");
            return false;
        }
        VideoParams videoParams = new VideoParams.Builder(native_getVideoMIMEType(mPlayingFileHandle), width, height)
                .setPid(videoPid)
                .setTrackFilterId((int)videoFilter.getIdLong())
                .setAvSyncHwId(avSyncHwId)
                .build();
        mASPlayer.setVideoParams(videoParams);

        Filter audioFilter = openAudioFilter(audioPid,audioFormat);
        if (audioFilter == null) {
            Log.e(TAG,"Failed to create audio filter");
            return false;
        }
        audioFilter.start();
        AudioParams audioParams = new AudioParams.Builder(native_getAudioMIMEType(mPlayingFileHandle), 48000, 2)
                .setPid(audioPid)
                .setTrackFilterId((int)audioFilter.getIdLong())
                .setAvSyncHwId(avSyncHwId)
                .build();
        mASPlayer.setAudioParams(audioParams);
        mASPlayer.setSurface(surface);
        native_prepareJDvrPlayer(mASPlayer, mPlayingFileHandle);
        return true;
    }
    private boolean createPlayer(final int rec_id, Surface surface) {
        if (mJDvrPlayer != null) {
            Log.e(TAG,"JDvrPlayer is not null");
            return false;
        }
        if (mASPlayer != null) {
            Log.e(TAG,"ASPlayer is not null");
            return false;
        }
        if (surface == null) {
            Log.e(TAG, "Invalid surface");
            return false;
        }
        final String recPrefix;
        if (rec_id == TIMESHIFT_MAGIC_CODE) {
            recPrefix = String.format(Locale.US,"%s/timeshift",mFolder);
        } else {
            recPrefix = String.format(Locale.US,"%s/%08d",mFolder,rec_id);
        }
        JDvrFile file;
        try {
            file = new JDvrFile(recPrefix);
        } catch (RuntimeException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
            mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                    "Exception: " + e));
            return false;
        }
        final int videoPid = file.getVideoPID();
        final int videoFormat = file.getVideoFormat();
        if (videoPid == 0x1fff || videoFormat == JDvrVideoFormat.VIDEO_FORMAT_UNDEFINED) {
            Log.e(TAG, "Invalid video PID or video format");
            return false;
        }
        final int audioPid = file.getAudioPID();
        final int audioFormat = file.getAudioFormat();
        if (audioPid == 0x1fff || audioFormat == JDvrAudioFormat.AUDIO_FORMAT_UNDEFINED) {
            Log.e(TAG, "Invalid audio PID or audio format");
            return false;
        }
        InitParams initParams = new InitParams.Builder()
                .setPlaybackMode(InitParams.PLAYBACK_MODE_PASSTHROUGH)
                .setInputSourceType(InputSourceType.TS_MEMORY)
                .setEventMask(EventMask.EVENT_TYPE_PTS_MASK)
                .build();
        mASPlayer = new ASPlayer(initParams, mTunerForPlayback, null);
        mASPlayer.prepare();

        Filter videoFilter = openVideoFilter(videoPid,videoFormat);
        if (videoFilter == null) {
            Log.e(TAG,"Failed to create video filter");
            return false;
        }
        videoFilter.start();
        final int width = 1920;
        final int height = 1080;
        final int avSyncHwId = mTunerForPlayback.getAvSyncHwId(videoFilter);
        if (avSyncHwId == -1) {
            Log.e(TAG,"AvSyncHwId is invalid");
            return false;
        }
        VideoParams videoParams = new VideoParams.Builder(file.getVideoMIMEType(), width, height)
                .setPid(videoPid)
                .setTrackFilterId((int)videoFilter.getIdLong())
                .setAvSyncHwId(avSyncHwId)
                .build();
        mASPlayer.setVideoParams(videoParams);

        Filter audioFilter = openAudioFilter(audioPid,audioFormat);
        if (audioFilter == null) {
            Log.e(TAG,"Failed to create audio filter");
            return false;
        }
        audioFilter.start();
        AudioParams audioParams = new AudioParams.Builder(file.getAudioMIMEType(), 48000, 2)
                .setPid(audioPid)
                .setTrackFilterId((int)audioFilter.getIdLong())
                .setAvSyncHwId(avSyncHwId)
                .build();
        mASPlayer.setAudioParams(audioParams);
        mASPlayer.setSurface(surface);
        mJDvrPlayer = new JDvrPlayer(mASPlayer, file, mJDvrPlayerSettings, mExecutor, mJDvrPlayerEventListener);
        return true;
    }
    private void startPlayback() {
        if (mJDvrPlayer == null) {
            Log.e(TAG, "startPlayback, JDvrPlayer is invalid");
            return;
        }
        mJDvrPlayer.play();
    }
    private void stopPlayback() {
        if (mJDvrPlayer == null) {
            Log.e(TAG, "stopPlayback, JDvrPlayer is invalid");
            return;
        }
        mProgress = null;
        mJDvrPlayer.stop();
    }
    private void setSpeed(double speed) {
        if (mJDvrPlayer == null) {
            Log.e(TAG, "changeSpeed, JDvrPlayer is invalid");
            return;
        }
        mJDvrPlayer.setSpeed(speed);
    }
    private void pausePlayback() {
        if (mJDvrPlayer == null) {
            Log.e(TAG, "pausePlayback, JDvrPlayer is invalid");
            return;
        }
        mJDvrPlayer.pause();
    }
    private void seekFromCurPos(int offset_in_sec) {
        if (mJDvrPlayer == null) {
            Log.e(TAG, "seekFromCurPos, JDvrPlayer is invalid");
            return;
        }
        int seconds = 0;
        if (mProgress != null) {
            seconds = (int)mProgress.currTime/1000 + offset_in_sec;
            seconds = Math.max((int)mProgress.startTime/1000,Math.min((int)mProgress.endTime/1000,seconds));
        } else {
            seconds = Math.max(offset_in_sec,0);
        }
        mJDvrPlayer.seek(seconds);
    }
    private void deleteFile(final int rec_id) {
        final String recPrefix;
        if (rec_id == TIMESHIFT_MAGIC_CODE) {
            recPrefix = String.format(Locale.US,"%s/timeshift",mFolder);
        } else {
            recPrefix = String.format(Locale.US,"%s/%08d",mFolder,rec_id);
        }
        boolean ret = JDvrFile.delete2(recPrefix);
        if (ret) {
            mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                    "Delete "+recPrefix+" successfully"));
        } else {
            mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                    "Fail to delete "+recPrefix));
        }
    }
    private int determineRecordingIndex(String folder) {
        File data;
        for (int i=0; i<1000; i++) {
            String path = String.format(Locale.US,"%s/%08d.stat",folder,i);
            data = new File(path);
            if (!data.exists()) {
                Log.d(TAG,"determineRecordingIndex returns: "+i);
                return i;
            }
        }
        Log.d(TAG,"determineRecordingIndex returns: -1");
        return -1;
    }
    private void initHandler() {
        Log.d(TAG, "Create mTaskHandler with mHandlerThread and TaskHandlerCallback");
        mHandlerThread = new HandlerThread("task");
        mHandlerThread.start();
        mTaskHandler = new TaskHandler(mHandlerThread.getLooper(), new TaskHandlerCallback());
    }
    public Handler getTaskHandler() {
        return mTaskHandler;
    }

    // Overrides
    @Override
    public void onLocked() {
        Log.d(TAG, "onLocked");
    }
    @Override
    public void onScanStopped() {
        Log.d(TAG, "onScanStopped");
    }
    @Override
    public void onSignalTypeReported(int signalType) {
        Log.d(TAG, "onSignalTypeReported");
    }
    @Override
    public void onHierarchyReported(int hierarchy) {
        Log.d(TAG, "onHierarchyReported");
    }
    public void onAtsc3PlpInfosReported(Atsc3PlpInfo[] atsc3PlpInfos) {
        Log.d(TAG, "onAtsc3PlpInfosReported");
    }
    @Override
    public void onAnalogSifStandardReported(int sif) {
        Log.d(TAG, "onAnalogSifStandardReported");
    }
    @Override
    public void onDvbsStandardReported(int dvbsStandard) {
        Log.d(TAG, "onDvbsStandardReported");
    }
    @Override
    public void onDvbtStandardReported(int dvbtStandard) {
        Log.d(TAG, "onDvbtStandardReported");
    }
    @Override
    public void onInputStreamIdsReported(int[] inputStreamIds) {
        Log.d(TAG, "onInputStreamIdsReported");
    }
    @Override
    public void onGroupIdsReported(int[] groupIds) {
        Log.d(TAG, "onGroupIdsReported");
    }
    @Override
    public void onPlpIdsReported(int[] plpIds) {
        Log.d(TAG, "onPlpIdsReported");
    }
    @Override
    public void onSymbolRatesReported(int[] rate) {
        Log.d(TAG, "onSymbolRatesReported:" + Arrays.toString(rate));
    }
    @Override
    public void onFrequenciesReported(int[] frequency) {
        Log.d(TAG, "onFrequenciesReported:"+Arrays.toString(frequency));
    }
    @Override
    public void onProgress(int percent) {
        Log.d(TAG, "onProgress percent:" + percent);
    }
    public void destroy() {
        Log.d(TAG, "destroy");
        releaseInstance();
    }
    private void releaseInstance() {
        if (mTunerForRecording != null) {
            mTunerForRecording.close();
            mTunerForRecording = null;
        }
        if (mTunerForPlayback != null) {
            mTunerForPlayback.close();
            mTunerForPlayback = null;
        }
        if (mTaskHandler != null) {
            mTaskHandler.removeCallbacksAndMessages(null);
            mTaskHandler = null;
        }
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            mHandlerThread = null;
        }
        mActivity = null;
    }
    private Filter openVideoFilter(int pid, int format) {
        long bufferSize = 1024 * 1024 * 4;
        Filter filter = mTunerForPlayback.openFilter(Filter.TYPE_TS, Filter.SUBTYPE_VIDEO, bufferSize, mExecutor, new FilterCallback() {
            @Override
            public void onFilterEvent(Filter filter, FilterEvent[] events) {
            }
            @Override
            public void onFilterStatusChanged(Filter filter, int status) {
            }
        });
        if (filter == null) {
            return null;
        }
        AvSettings settings = AvSettings.builder(Filter.TYPE_TS, false)
                .setPassthrough(true)
                .setVideoStreamType(format)
                .build();
        FilterConfiguration filterConfiguration = TsFilterConfiguration.builder()
                .setTpid(pid)
                .setSettings(settings)
                .build();
        filter.configure(filterConfiguration);
        return filter;
    }
    private Filter openAudioFilter(int pid, int format) {
        long bufferSize = 1024 * 1024 * 2;
        Filter filter = mTunerForPlayback.openFilter(Filter.TYPE_TS, Filter.SUBTYPE_AUDIO, bufferSize, mExecutor, new FilterCallback() {
            @Override
            public void onFilterEvent(Filter filter, FilterEvent[] events) {
            }
            @Override
            public void onFilterStatusChanged(Filter filter, int status) {
            }
        });
        if (filter == null) {
            return null;
        }
        Settings settings = AvSettings.builder(Filter.TYPE_TS, true)
                .setPassthrough(true)
                .setAudioStreamType(format)
                .build();
        FilterConfiguration filterConfiguration = TsFilterConfiguration.builder()
                .setTpid(pid)
                .setSettings(settings)
                .build();
        filter.configure(filterConfiguration);
        return filter;
    }

    // Classes
    public class TunerExecutor implements Executor {
        public void execute(Runnable r) {
            //Log.d(TAG, "Execute run() of r in ui thread");
            if (!mTaskHandler.post(r)) {
                Log.d(TAG, "Execute mTaskHandler is shutting down");
            }
        }
    }
    static private class TaskHandler extends Handler {
        public TaskHandler(Looper looper, Callback callback) {
            super(looper, callback);
        }
    }
    private class TaskHandlerCallback implements Handler.Callback {
        @Override
        public boolean handleMessage(Message message) {
            boolean result = true;
            switch (message.what) {
                case TaskMsg.TASK_MSG_START_TUNING:
                    tuneStart();
                    break;
                case TaskMsg.TASK_MSG_STOP_TUNING:
                    tuneStop();
                    break;
                case TaskMsg.TASK_MSG_START_RECORDING:
                    recordingStart();
                    break;
                case TaskMsg.TASK_MSG_STOP_RECORDING:
                    recordingStop();
                    break;
                case TaskMsg.TASK_MSG_ADD_STREAMS1:
                    addStreams1();
                    break;
                case TaskMsg.TASK_MSG_ADD_STREAMS2:
                    addStreams2();
                    break;
                case TaskMsg.TASK_MSG_REMOVE_STREAM1:
                    removeStreams1();
                    break;
                case TaskMsg.TASK_MSG_REMOVE_STREAM2:
                    removeStreams2();
                    break;
                case TaskMsg.TASK_MSG_PREPARE_RECORDER:
                    prepareRecorder();
                    break;
                case TaskMsg.TASK_MSG_PAUSE_RECORDING:
                    pauseRecording();
                    break;
                case TaskMsg.TASK_MSG_PREPARE_TIMESHIFT_RECORDER:
                    prepareTimeshiftRecorder();
                    break;
                case TaskMsg.TASK_MSG_CREATE_PLAYER:
                    createPlayer(message.arg1,(Surface)message.obj);
                    break;
                case TaskMsg.TASK_MSG_START_PLAYBACK:
                    startPlayback();
                    break;
                case TaskMsg.TASK_MSG_STOP_PLAYBACK:
                    stopPlayback();
                    break;
                case TaskMsg.TASK_MSG_SET_SPEED:
                    setSpeed((Double) message.obj);
                    break;
                case TaskMsg.TASK_MSG_PAUSE_PLAYBACK:
                    pausePlayback();
                    break;
                case TaskMsg.TASK_MSG_SEEK:
                    seekFromCurPos(message.arg1);
                    break;
                case TaskMsg.TASK_MSG_NATIVE_PREPARE_RECORDER:
                    int index = determineRecordingIndex(mFolder);
                    if (index == -1) {
                        mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                "Failed to generate a recording prefix"));
                        break;
                    }
                    native_prepareRecorder(index);
                    break;
                case TaskMsg.TASK_MSG_NATIVE_PREPARE_TSH_RECORDER:
                    native_prepareTimeshiftRecorder();
                    break;
                case TaskMsg.TASK_MSG_NATIVE_ADD_STREAM:
                    native_addStream();
                    break;
                case TaskMsg.TASK_MSG_NATIVE_REMOVE_STREAM:
                    native_removeStream();
                    break;
                case TaskMsg.TASK_MSG_NATIVE_START_RECORDING:
                    native_startRecording();
                    break;
                case TaskMsg.TASK_MSG_NATIVE_PAUSE_RECORDING:
                    native_pauseRecording();
                    break;
                case TaskMsg.TASK_MSG_NATIVE_STOP_RECORDING:
                    native_stopRecording();
                    break;
                case TaskMsg.TASK_MSG_NATIVE_CREATE_PLAYER:
                    createNativeJDvrPlayer(message.arg1,(Surface)message.obj);
                    break;
                case TaskMsg.TASK_MSG_NATIVE_PLAY:
                    native_play();
                    break;
                case TaskMsg.TASK_MSG_NATIVE_PAUSE_PLAYBACK:
                    native_pausePlayback();
                    break;
                case TaskMsg.TASK_MSG_NATIVE_STOP_PLAYBACK:
                    native_stopPlayback();
                    mProgress = null;
                    mPlayingFileHandle = 0;
                    break;
                case TaskMsg.TASK_MSG_NATIVE_SEEK:
                    int seconds = 0;
                    if (mProgress != null) {
                        seconds = (int) mProgress.currTime / 1000 + message.arg1;
                        seconds = Math.max((int) mProgress.startTime / 1000, Math.min((int) mProgress.endTime / 1000, seconds));
                    } else {
                        seconds = Math.max(message.arg1,0);
                    }
                    native_seek(seconds);
                    break;
                case TaskMsg.TASK_MSG_NATIVE_SET_SPEED:
                    native_setSpeed((Double) message.obj);
                    break;
                case TaskMsg.TASK_MSG_NATIVE_GET_PROGRESS:
                    JDvrPlaybackProgress progress = native_getPlayingProgress();
                    //Log.d(TAG,progress.toString());
                    Message msg2 = new Message();
                    msg2.what = MainActivity.UI_MSG_PROGRESS;
                    msg2.obj = progress;
                    mUiHandler.sendMessage(msg2);
                    mProgress = progress;
                    break;
                case TaskMsg.TASK_MSG_DELETE:
                    deleteFile(message.arg1);
                    break;
                case TaskMsg.TASK_MSG_JNI_DELETE:
                    final String pathPrefix;
                    if (message.arg1 == TIMESHIFT_MAGIC_CODE) {
                        pathPrefix = String.format(Locale.US,"%s/timeshift",mFolder);
                    } else {
                        pathPrefix = String.format(Locale.US,"%s/%08d",mFolder,message.arg1);
                    }
                    boolean ret = native_delete(pathPrefix);
                    if (ret) {
                        mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                "Delete recording "+pathPrefix+" successfully"));
                    } else {
                        mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                "Fail to delete recording "+pathPrefix));
                    }
                    break;
                default:
                    result = false;
                    break;
            }
            return result;
        }
    }
    private native void native_passTuners(Tuner tunerForRecording, Tuner tunerForPlayback);
    private native void native_passRecorderSettings(JDvrRecorderSettings settings);
    private native void native_passPlayerSettings(JDvrPlayerSettings settings);
    private native int native_prepareRecorder(int recId);
    private native int native_prepareTimeshiftRecorder();
    private native void native_addStream();
    private native void native_removeStream();
    private native void native_startRecording();
    private native void native_pauseRecording();
    private native void native_stopRecording();
    private native int native_prepareFileForPlayback(String pathPrefix);
    private native int native_prepareJDvrPlayer(ASPlayer asplayer, int fileHandle);
    private native int native_getVideoPID(int fileHandle);
    private native int native_getVideoFormat(int fileHandle);
    private native String native_getVideoMIMEType(int fileHandle);
    private native int native_getAudioPID(int fileHandle);
    private native int native_getAudioFormat(int fileHandle);
    private native String native_getAudioMIMEType(int fileHandle);
    private native boolean native_play();
    private native boolean native_pausePlayback();
    private native boolean native_stopPlayback();
    private native boolean native_seek(int offsetInSec);
    private native boolean native_setSpeed(double speed);
    private native JDvrPlaybackProgress native_getPlayingProgress();
    private native boolean native_delete(String pathPrefix);
    static public class TaskMsg {
        public final static int TASK_MSG_START_TUNING = 1;
        public final static int TASK_MSG_STOP_TUNING = 2;
        public final static int TASK_MSG_START_RECORDING = 3;
        public final static int TASK_MSG_STOP_RECORDING = 4;
        public final static int TASK_MSG_ADD_STREAMS1 = 5;
        public final static int TASK_MSG_ADD_STREAMS2 = 6;
        public final static int TASK_MSG_REMOVE_STREAM1 = 7;
        public final static int TASK_MSG_REMOVE_STREAM2 = 8;
        public final static int TASK_MSG_PREPARE_RECORDER = 9;
        public final static int TASK_MSG_PAUSE_RECORDING = 10;
        public final static int TASK_MSG_PREPARE_TIMESHIFT_RECORDER = 11;
        public final static int TASK_MSG_CREATE_PLAYER = 12;
        public final static int TASK_MSG_START_PLAYBACK = 13;
        public final static int TASK_MSG_STOP_PLAYBACK = 14;
        public final static int TASK_MSG_SET_SPEED = 15;
        public final static int TASK_MSG_PAUSE_PLAYBACK = 16;
        public final static int TASK_MSG_SEEK = 17;
        public final static int TASK_MSG_NATIVE_PREPARE_RECORDER = 18;
        public final static int TASK_MSG_NATIVE_ADD_STREAM = 19;
        public final static int TASK_MSG_NATIVE_START_RECORDING = 20;
        public final static int TASK_MSG_NATIVE_PAUSE_RECORDING = 21;
        public final static int TASK_MSG_NATIVE_STOP_RECORDING = 22;
        public final static int TASK_MSG_NATIVE_REMOVE_STREAM = 23;
        public final static int TASK_MSG_NATIVE_PREPARE_TSH_RECORDER = 26;
        public final static int TASK_MSG_NATIVE_CREATE_PLAYER = 27;
        public final static int TASK_MSG_NATIVE_PLAY = 28;
        public final static int TASK_MSG_NATIVE_PAUSE_PLAYBACK = 29;
        public final static int TASK_MSG_NATIVE_STOP_PLAYBACK = 30;
        public final static int TASK_MSG_NATIVE_SEEK = 31;
        public final static int TASK_MSG_NATIVE_SET_SPEED = 32;
        public final static int TASK_MSG_NATIVE_GET_PROGRESS = 33;
        public final static int TASK_MSG_DELETE = 34;
        public final static int TASK_MSG_JNI_DELETE = 35;
    }
}

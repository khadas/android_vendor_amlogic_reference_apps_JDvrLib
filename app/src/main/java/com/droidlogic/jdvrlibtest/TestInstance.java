package com.droidlogic.jdvrlibtest;

import android.media.tv.tuner.Tuner;
import android.media.tv.tuner.dvr.DvrSettings;
import android.media.tv.tuner.frontend.DvbcFrontendSettings;
import android.media.tv.tuner.frontend.FrontendSettings;
import android.media.tv.tuner.frontend.OnTuneEventListener;
import android.media.tv.tuner.frontend.ScanCallback;
import android.media.tv.tuner.frontend.Atsc3PlpInfo;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executor;

import com.droidlogic.jdvrlib.JDvrRecorder;
import com.droidlogic.jdvrlib.JDvrRecorderSettings;
import com.droidlogic.jdvrlib.JDvrFile;
import com.droidlogic.jdvrlib.OnJDvrRecorderEventListener;

public class TestInstance implements OnTuneEventListener,
        ScanCallback {
    private String TAG = TestInstance.class.getSimpleName();
    private Tuner mTuner;
    private MainActivity mActivity;
    final private TunerExecutor mExecutor;
    private Handler mTaskHandler = null;
    private HandlerThread mHandlerThread = null;
    final private Handler mUiHandler;
    private JDvrRecorder mJDvrRecorder;
    JDvrRecorderSettings mJDvrRecorderSettings = null;
    private boolean mLocked = false;
    private final String mFolder = "/storage/emulated/0/Recordings";
    //private final String mFolder = "/storage/C632-AAA0/Recordings";

    private OnJDvrRecorderEventListener mJDvrRecorderEventListener = new OnJDvrRecorderEventListener() {
        @Override
        public void onJDvrRecorderEvent(Message msg) {
            //Log.d(TAG, "TestInstance receives message, what:" + msg.what);
            switch (msg.what) {
                case JDvrRecorderEvent.NOTIFY_DEBUG_MSG:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,msg.obj.toString()));
                    break;
                case JDvrRecorderEvent.NOTIFY_INITIAL_STATE:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received NOTIFY_INITIAL_STATE"));
                    break;
                case JDvrRecorderEvent.NOTIFY_STARTING_STATE:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received NOTIFY_STARTING_STATE"));
                    break;
                case JDvrRecorderEvent.NOTIFY_STARTED_STATE:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received NOTIFY_STARTED_STATE"));
                    break;
                case JDvrRecorderEvent.NOTIFY_STOPPING_STATE:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received NOTIFY_STOPPING_STATE"));
                    break;
                case JDvrRecorderEvent.NOTIFY_PAUSED_STATE:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received NOTIFY_PAUSED_STATE"));
                    break;
                case JDvrRecorderEvent.NOTIFY_NO_DATA_ERROR:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received NOTIFY_NO_DATA_ERROR"));
                    break;
                case JDvrRecorderEvent.NOTIFY_IO_ERROR:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received NOTIFY_IO_ERROR"));
                    break;
                case JDvrRecorderEvent.NOTIFY_DISK_FULL_ERROR:
                    mUiHandler.sendMessage(
                            mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                                    "Received NOTIFY_DISK_FULL_ERROR"));
                case JDvrRecorderEvent.NOTIFY_PROGRESS:
                    JDvrRecorder.JDvrRecordingProgress progress = (JDvrRecorder.JDvrRecordingProgress) msg.obj;
                    Log.d(TAG,progress.toString());
                    break;
                default:
                    break;
            }
        }
    };

    public TestInstance(int instance, MainActivity activity) {
        TAG += "["+instance+"]";
        mActivity = activity;
        mUiHandler = mActivity.getUiHandler();
        initHandler();
        Log.d(TAG, "New tuner executor");
        mExecutor = new TunerExecutor();
        mTuner = new Tuner(mActivity.getApplicationContext(),
                null/*tvInputSessionId*/,
                200/*PRIORITY_HINT_USE_CASE_TYPE_SCAN*/);
        Log.d(TAG, "mTuner created:" + mTuner);
        mTuner.setOnTuneEventListener(mExecutor, this);
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
        Log.d(TAG, "tuneStart enter");
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
            if (mTuner != null) {
                mTuner.tune(feSettings);
            }
        }
    }
    private void tuneStop() {
        Log.d(TAG, "tuneStop enter");
        if (mTuner != null) {
            mTuner.cancelTuning();
        }
    }
    private void recordingStart() {
        Log.d(TAG, "recordingStart enter");
        if (!mLocked) {
            mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                    "Should not start recording, as tuner is not locked"));
        }
        if (mJDvrRecorder == null) {
            Log.e(TAG, "recordingStart, JDvrRecorder is invalid");
            return;
        }
        mJDvrRecorder.start();
    }
    private void recordingStop() {
        Log.d(TAG, "recordingStop enter");
        if (mJDvrRecorder == null) {
            Log.e(TAG, "recordingStop, JDvrRecorder is invalid");
            return;
        }
        mJDvrRecorder.stop();
    }
    private void addStreams1() {
        Log.d(TAG, "addStreams1 enter");
        if (mJDvrRecorder == null) {
            Log.e(TAG, "addStream1, JDvrRecorder is invalid");
            return;
        }
        mJDvrRecorder.addStream(0, JDvrRecorder.JDvrStreamType.STREAM_TYPE_OTHER, 0);
        mJDvrRecorder.addStream(600, JDvrRecorder.JDvrStreamType.STREAM_TYPE_VIDEO,JDvrRecorder.JDvrVideoFormat.VIDEO_FORMAT_MPEG2);
        mJDvrRecorder.addStream(601, JDvrRecorder.JDvrStreamType.STREAM_TYPE_AUDIO, JDvrRecorder.JDvrAudioFormat.AUDIO_FORMAT_MPEG);
        mJDvrRecorder.addStream(602, JDvrRecorder.JDvrStreamType.STREAM_TYPE_AUDIO, JDvrRecorder.JDvrAudioFormat.AUDIO_FORMAT_MPEG);
        mJDvrRecorder.addStream(4161, JDvrRecorder.JDvrStreamType.STREAM_TYPE_OTHER,0);
    }
    private void addStreams2() {
        Log.d(TAG, "addStreams2 enter");
        if (mJDvrRecorder == null) {
            Log.e(TAG, "addStream2, JDvrRecorder is invalid");
            return;
        }
        mJDvrRecorder.addStream(0, JDvrRecorder.JDvrStreamType.STREAM_TYPE_OTHER, 0);
        mJDvrRecorder.addStream(610, JDvrRecorder.JDvrStreamType.STREAM_TYPE_VIDEO,JDvrRecorder.JDvrVideoFormat.VIDEO_FORMAT_MPEG2);
        mJDvrRecorder.addStream(611, JDvrRecorder.JDvrStreamType.STREAM_TYPE_AUDIO, JDvrRecorder.JDvrAudioFormat.AUDIO_FORMAT_MPEG);
        mJDvrRecorder.addStream(612, JDvrRecorder.JDvrStreamType.STREAM_TYPE_AUDIO, JDvrRecorder.JDvrAudioFormat.AUDIO_FORMAT_MPEG);
        mJDvrRecorder.addStream(4225, JDvrRecorder.JDvrStreamType.STREAM_TYPE_OTHER,0);
    }
    private void removeStreams1() {
        Log.d(TAG, "removeStreams1 enter");
        if (mJDvrRecorder == null) {
            Log.e(TAG, "removeStreams1, JDvrRecorder is invalid");
            return;
        }
        mJDvrRecorder.removeStream(0);
        mJDvrRecorder.removeStream(600);
        mJDvrRecorder.removeStream(601);
        mJDvrRecorder.removeStream(602);
        mJDvrRecorder.removeStream(4161);
    }
    private void removeStreams2() {
        Log.d(TAG, "removeStreams2 enter");
        if (mJDvrRecorder == null) {
            Log.e(TAG, "removeStreams2, JDvrRecorder is invalid");
            return;
        }
        mJDvrRecorder.removeStream(0);
        mJDvrRecorder.removeStream(610);
        mJDvrRecorder.removeStream(611);
        mJDvrRecorder.removeStream(612);
        mJDvrRecorder.removeStream(4225);
    }
    private void prepareRecorder() {
        Log.d(TAG, "prepareRecorder enter");
        mJDvrRecorderSettings = JDvrRecorderSettings
                .builder()
                .setDataFormat(DvrSettings.DATA_FORMAT_TS)
                .setLowThreshold(100)
                .setHighThreshold(900)
                .setPacketSize(188)
                .setRecorderBufferSize(10*1024*1024)
                .setFilterBufferSize(1024*1024)
                .setSegmentSize(30*1024*1024)
                .build();
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
            recFile = new JDvrFile(prefix, false);
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e);
            mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                    "Exception: " + e));
            return;
        }
        mJDvrRecorder = new JDvrRecorder(mTuner,recFile, mJDvrRecorderSettings,
                mExecutor,mJDvrRecorderEventListener);
    }
    private void prepareTimeshiftRecorder() {
        Log.d(TAG, "prepareRecorder enter");
        mJDvrRecorderSettings = JDvrRecorderSettings
                .builder()
                .setDataFormat(DvrSettings.DATA_FORMAT_TS)
                .setLowThreshold(100)
                .setHighThreshold(900)
                .setPacketSize(188)
                .setRecorderBufferSize(10*1024*1024)
                .setFilterBufferSize(1024*1024)
                .setSegmentSize(30*1024*1024)
                .build();
        final String prefix = String.format(Locale.US,mFolder +"/timeshift");
        mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                "Recording prefix: "+prefix));
        JDvrFile recFile;
        try {
            recFile = new JDvrFile(prefix, true, 300*1024*1024, 360);
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e);
            mUiHandler.sendMessage(mUiHandler.obtainMessage(MainActivity.UI_MSG_STATUS,
                    "Exception: " + e));
            return;
        }
        mJDvrRecorder = new JDvrRecorder(mTuner,recFile, mJDvrRecorderSettings,
                mExecutor,mJDvrRecorderEventListener);
    }
    private void pauseRecording() {
        Log.d(TAG, "pauseRecording enter");
        if (mJDvrRecorder == null) {
            Log.e(TAG, "pauseRecording, JDvrRecorder is invalid");
            return;
        }
        mJDvrRecorder.pause();
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
        if (mTuner != null) {
            mTuner.close();
            mTuner = null;
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
            Log.d(TAG, "TaskHandlerCallback handleMessage:" + message.what);
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
                default:
                    result = false;
                    break;
            }
            return result;
        }
    }
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
    }
}

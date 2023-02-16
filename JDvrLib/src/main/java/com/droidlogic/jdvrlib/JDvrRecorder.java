package com.droidlogic.jdvrlib;

import android.media.tv.tuner.Tuner;
import android.media.tv.tuner.dvr.DvrRecorder;
import android.media.tv.tuner.dvr.OnRecordStatusChangedListener;
import android.media.tv.tuner.filter.FilterCallback;
import android.media.tv.tuner.filter.Filter;
import android.media.tv.tuner.filter.FilterConfiguration;
import android.media.tv.tuner.filter.FilterEvent;
import android.media.tv.tuner.filter.RecordSettings;
import android.media.tv.tuner.filter.Settings;
import android.media.tv.tuner.filter.TsFilterConfiguration;
import android.media.tv.tuner.filter.TsRecordEvent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import com.droidlogic.jdvrlib.OnJDvrRecorderEventListener.JDvrRecorderEvent;

public class JDvrRecorder {
    private static class JDvrRecordingSession {
        public final static int START_STATE    = 0;
        public final static int INITIAL_STATE  = 1;
        public final static int STARTING_STATE = 2;
        public final static int STARTED_STATE  = 3;
        public final static int PAUSED_STATE   = 4;
        public final static int STOPPING_STATE = 5;

        // Recorder State
        private int mState = START_STATE;

        // Statuses
        // true: some filtered data are received lately,
        // false: no data receiving for a period of time.
        private boolean mStreamOn = false;
        // Timestamp of last data reception
        private long mTimestampOfLastDataReception = 0;
        private long mTimestampOfLastNoDataNotify = 0;
        private long mTimestampOfLastIOErrorNotify = 0;
        private long mTimestampOfLastDiskFullNotify = 0;
        private long mTimestampOfLastProgressNotify = 0;

        // true: if DvrRecorder.start() has just been called,
        // false: otherwise.
        private boolean mIsStarting = false;
        // true: if DvrRecorder.stop() has just been called,
        // false: otherwise.
        private boolean mIsStopping = false;
        // true: if there is not an ongoing recording,
        // false: if there is an ongoing recording.
        private boolean mHaveStopped = true;
        // ture: if controller requests to start recording,
        // false: JDvrRecorder has handled the request or has no 'TO_START' request to handle
        private boolean mControllerToStart = false;
        // ture: if controller requests to stop recording,
        // false: JDvrRecorder has handled the request or has no 'TO_EXIT' request to handle
        private boolean mControllerToExit = false;
        // ture: if controller requests to pause recording and the paused recording hasn't been
        //       resumed by controller,
        // false: otherwise.
        private boolean mControllerToPause = false;
        // true: if there are PID changes and filters haven't be updated to reflect the changes.
        // false: if there is not any PID change or those changes have already taken effect.
        private boolean mPidChanged = false;
        private boolean mIOError = false;
        private boolean mHaveSentIOErrorNotify = false;
        private boolean mDiskFull = false;
        private boolean mHaveSentDiskFullNotify = false;
        private final ArrayList<JDvrStreamInfo> mStreams = new ArrayList<>();
        private final ArrayList<JDvrStreamInfo> mStreamsToBeAdded = new ArrayList<>();
        private final ArrayList<JDvrStreamInfo> mStreamsToBeRemoved = new ArrayList<>();
        private final ArrayList<TsRecordEvent> mTsDataToProcess = new ArrayList<>();
    }
    private static class JDvrRecordingStatus {
        public final static int STREAM_STATUS_PID_CHANGED   = 1;
        public final static int CONTROLLER_STATUS_TO_START  = 2;
        public final static int CONTROLLER_STATUS_TO_EXIT   = 3;
        public final static int CONTROLLER_STATUS_TO_PAUSE  = 4;
    }
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
        public final static int VIDEO_FORMAT_MPEG1 = 0;
        public final static int VIDEO_FORMAT_MPEG2 = 1;
        public final static int VIDEO_FORMAT_H264 = 2;
        public final static int VIDEO_FORMAT_HEVC = 3;
        public final static int VIDEO_FORMAT_VP9 = 4;
    }
    public static class JDvrAudioFormat {
        public final static int AUDIO_FORMAT_MPEG = 0;
        public final static int AUDIO_FORMAT_AC3 = 1;
        public final static int AUDIO_FORMAT_EAC3 = 2;
        public final static int AUDIO_FORMAT_DTS = 3;
        public final static int AUDIO_FORMAT_AAC = 4;
        public final static int AUDIO_FORMAT_HEAAC = 5;
        public final static int AUDIO_FORMAT_LATM = 6;
        public final static int AUDIO_FORMAT_PCM = 7;
        public final static int AUDIO_FORMAT_AC4 = 8;
    }
    public static class JDvrStreamInfo {
        public final int pid;
        public final int type;
        public final int format;

        public JDvrStreamInfo(int _pid, int _type, int _format) {
            pid = _pid;
            type = _type;
            format = _format;
        }

        @Override
        public String toString() {
            return "{" + "\"pid\":" + pid + ", \"type\":" + type + ", \"format\":" + format + '}';
        }
    }
    public static class JDvrRecordingProgress {
        public long duration;   // in ms
        public long startTime;  // in ms
        public long endTime;    // in ms
        public int numberOfSegments;
        public int firstSegmentId;
        public int lastSegmentId;
        public long size;

        @Override
        public String toString() {
            return "{\"duration\":" + duration +
                    ", \"startTime\":" + startTime +
                    ", \"endTime\":" + endTime +
                    ", \"numberOfSegments\":" + numberOfSegments +
                    ", \"firstSegmentId\":" + firstSegmentId +
                    ", \"lastSegmentId\":" + lastSegmentId +
                    ", \"size\":" + size + '}';
        }
    }

    // Stream status will be OFF when not receiving any data for this period of time.
    final static private int timeout1 = 5000;   // in ms
    // NO_DATA_ERROR message will be notified when staying in PAUSED state and not receiving any
    // data for this period of time.
    final static private int timeout2 = 10000;   // in ms
    // The minimum time interval for NO_DATA_ERROR messages
    final static private int interval1 = 10000;   // in ms
    // The minimum time interval for IO_ERROR/DISK_FULL_ERROR messages
    final static private int interval2 = 10000;   // in ms
    // The minimum time interval for PROGRESS messages
    final static private int interval3 = 1000;   // in ms

    // Member Variables
    private final JDvrRecordingSession mSession = new JDvrRecordingSession();
    final private String TAG = JDvrRecorder.class.getSimpleName();
    private final Tuner mTuner;
    private DvrRecorder mDvrRecorder = null;
    private final JDvrRecorderSettings mSettings;
    private final HashMap<Integer,Filter> mFilters = new HashMap<>();
    private final HandlerThread mRecordingThread = new HandlerThread("JDvrRecorder task");
    private final Executor mListenerExecutor;
    private final OnJDvrRecorderEventListener mListener;
    private final Object mOnJDvrRecorderEventLock = new Object();
    private final Handler mRecordingHandler;
    private final JDvrFile mDvrFile;

    // Callbacks
    private final Handler.Callback mRecordingCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message message) {
            Log.d(TAG, "JDvrRecorder receives message, what:" + message.what);
            // Rules:
            // 1. Update status
            // 2. Do *NOT* update state
            // 3. Do *NOT* call Tuner APIs
            if (message.what == JDvrRecordingStatus.STREAM_STATUS_PID_CHANGED) {
                if (message.arg1 >= 0) {
                    JDvrStreamInfo stream = (JDvrStreamInfo) message.obj;
                    // Adding a PID
                    final int pid_to_add = message.arg1;
                    if (mFilters.containsKey(pid_to_add)) {
                        Log.w(TAG,"PID to add already exists. pid: "+pid_to_add);
                        return true;
                    }
                    mSession.mStreamsToBeAdded.add(stream);
                    mSession.mPidChanged = true;
                    // Take immediate action on PID changing
                    mRecordingHandler.postAtFrontOfQueue(mStateMachineRunnable);
                } else if (message.arg2>=0) {
                    // Removing a PID
                    final int pid_to_remove = message.arg2;
                    JDvrStreamInfo stream = new JDvrStreamInfo(pid_to_remove,0,0);
                    if (!mFilters.containsKey(pid_to_remove)) {
                        Log.w(TAG,"PID to remove does not exist. pid: "+pid_to_remove);
                        return true;
                    }
                    mSession.mStreamsToBeRemoved.add(stream);
                    mSession.mPidChanged = true;
                    // Take immediate action on PID changing
                    mRecordingHandler.postAtFrontOfQueue(mStateMachineRunnable);
                }
            } else if (message.what == JDvrRecordingStatus.CONTROLLER_STATUS_TO_START) {
                if (isCmdInProgress()) {
                    Log.i(TAG, "Just ignore this command as another command is in progress");
                    return true;
                }
                mSession.mControllerToStart = true;
            } else if (message.what == JDvrRecordingStatus.CONTROLLER_STATUS_TO_EXIT) {
                if (isCmdInProgress()) {
                    Log.i(TAG, "Just ignore this command as another command is in progress");
                    return true;
                }
                mSession.mControllerToExit = true;
            } else if (message.what == JDvrRecordingStatus.CONTROLLER_STATUS_TO_PAUSE) {
                if (isCmdInProgress()) {
                    Log.i(TAG, "Just ignore this command as another command is in progress");
                    return true;
                }
                mSession.mControllerToPause = true;
            }
            return false;
        }
    };
    private final Executor mRecorderExecutor = new Executor() {
        public void execute(Runnable r) {
            if (!mRecordingHandler.post(r)) {
                Log.w(TAG, "Recorder Handler is shutting down");
            }
        }
    };
    private final FilterCallback mFilterCallback = new FilterCallback() {
        @Override
        public void onFilterEvent(Filter filter, FilterEvent[] filterEvents) {
            for (FilterEvent event : filterEvents) {
                if (event instanceof TsRecordEvent) {
                    TsRecordEvent recordEvent = (TsRecordEvent) event;
                    final int dataLength = (int)recordEvent.getDataLength();
                    if (dataLength>0) {
                        mSession.mTimestampOfLastDataReception = SystemClock.elapsedRealtime();
                        mSession.mTsDataToProcess.add(recordEvent);
                    }
                }
            }
        }

        @Override
        public void onFilterStatusChanged(Filter filter, int i) {
            //Log.d(TAG, "onFilterStatusChanged i:"+i);
        }
    };
    private final OnRecordStatusChangedListener mRecordStatusChangedListener = (status) -> {
        if (status == Filter.STATUS_DATA_READY) {
            Log.d(TAG, "onRecordStatusChanged status: STATUS_DATA_READY");
        }
    };
    private void updateState() {
        // Rules:
        // 1. Check status but do *NOT* update status
        // 2. Update state
        // 3. Do *NOT* call Tuner APIs
        int prevState = mSession.mState;
        if (mSession.mState == JDvrRecordingSession.START_STATE) {
            final boolean cond1 = (mDvrRecorder != null);
            if (cond1) {
                mSession.mState = JDvrRecordingSession.INITIAL_STATE;
                Log.i(TAG,"State transition: START => INITIAL");
            }
        } else if (mSession.mState == JDvrRecordingSession.INITIAL_STATE) {
            final boolean cond1 = (mFilters.size()>0);
            final boolean cond2 = mSession.mIsStarting;
            if (cond1 && cond2) {
                mSession.mState = JDvrRecordingSession.STARTING_STATE;
                Log.i(TAG,"State transition: INITIAL => STARTING");
            }
        } else if (mSession.mState == JDvrRecordingSession.STARTING_STATE) {
            if (mSession.mStreamOn) {
                mSession.mState = JDvrRecordingSession.STARTED_STATE;
                Log.i(TAG, "State transition: STARTING => STARTED");
            } else {
                mSession.mState = JDvrRecordingSession.PAUSED_STATE;
                Log.i(TAG, "State transition: STARTING => PAUSED");
            }
        } else if (mSession.mState == JDvrRecordingSession.STARTED_STATE) {
            final boolean cond1 = mSession.mIsStopping;
            final boolean cond2 = !mSession.mStreamOn;
            final boolean cond3 = mSession.mControllerToPause;
            final boolean cond4 = mSession.mIOError;
            final boolean cond5 = mSession.mDiskFull;
            if (cond1) {
                Log.i(TAG,"State transition: STARTED => STOPPING");
                mSession.mState = JDvrRecordingSession.STOPPING_STATE;
            } else if (cond2 || cond3 || cond4 || cond5) {
                Log.i(TAG,"State transition: STARTED => PAUSED");
                mSession.mState = JDvrRecordingSession.PAUSED_STATE;
            }
        } else if (mSession.mState == JDvrRecordingSession.STOPPING_STATE) {
            final boolean cond1 = mSession.mHaveStopped;
            final boolean cond2 = (mFilters.size() == 0);
            if (cond1 && cond2) {
                Log.i(TAG,"State transition: STOPPING => INITIAL");
                mSession.mState = JDvrRecordingSession.INITIAL_STATE;
            }
        } else if (mSession.mState == JDvrRecordingSession.PAUSED_STATE) {
            final boolean cond1 = mSession.mStreamOn;
            final boolean cond2 = !mSession.mControllerToPause;
            final boolean cond3 = !mSession.mIOError;
            final boolean cond4 = mSession.mControllerToExit;
            final boolean cond5 = !mSession.mDiskFull;
            if (cond1 && cond2 && cond3 && cond5) {
                Log.i(TAG,"State transition: PAUSED => STARTED");
                mSession.mState = JDvrRecordingSession.STARTED_STATE;
            } else if (cond4) {
                Log.i(TAG,"State transition: PAUSED => STOPPING");
                mSession.mState = JDvrRecordingSession.STOPPING_STATE;
            }
        }
        // Notify state changing to listener
        if (prevState != mSession.mState) {
            Message msg = new Message();
            if (mSession.mState == JDvrRecordingSession.INITIAL_STATE) {
                msg.what = JDvrRecorderEvent.NOTIFY_INITIAL_STATE;
            } else if (mSession.mState == JDvrRecordingSession.STARTING_STATE) {
                msg.what = JDvrRecorderEvent.NOTIFY_STARTING_STATE;
            } else if (mSession.mState == JDvrRecordingSession.STARTED_STATE) {
                msg.what = JDvrRecorderEvent.NOTIFY_STARTED_STATE;
            } else if (mSession.mState == JDvrRecordingSession.STOPPING_STATE) {
                msg.what = JDvrRecorderEvent.NOTIFY_STOPPING_STATE;
            } else if (mSession.mState == JDvrRecordingSession.PAUSED_STATE) {
                msg.what = JDvrRecorderEvent.NOTIFY_PAUSED_STATE;
            } else {
                Log.e(TAG,"Invalid state value: " + mSession.mState);
                return;
            }
            onJDvrRecorderEvent(msg);
        }
    }
    private void handlingStartState() {
        JDvrSegment.setMaxSegmentSize(mSettings.mSegmentSize);
        if (mTuner == null) {
            Log.e(TAG, "Tuner is invalid");
            return;
        }
        if (mDvrRecorder == null) {
            mDvrRecorder = mTuner.openDvrRecorder(
                    mSettings.mRecorderBufferSize,
                    mRecorderExecutor,
                    mRecordStatusChangedListener);
        }
        if (mDvrRecorder == null) {
            Log.e(TAG, "Failed to openDvrRecorder");
            return;
        }
        mDvrRecorder.configure(mSettings.getDvrSettings());
    }
    private void handlingInitialState() {
        if (mSession.mPidChanged) {
            handlingPidChanges();
        }
        if (mSession.mControllerToStart) {
            int result = mDvrRecorder.start();
            if (result == Tuner.RESULT_SUCCESS) {
                mSession.mIsStarting = true;
                mSession.mHaveStopped = false;
            } else {
                Log.e(TAG, "DvrRecorder.start() fails. return value: "+result);
            }
        }
        if (mSession.mControllerToExit) {
            mRecordingThread.quit();
            mSession.mControllerToExit = false;
        }
    }
    private void handlingStartingState() {
        if (mSession.mControllerToStart) {
            mFilters.forEach((pid,filter) -> {
                int result = filter.start();
                if (result != Tuner.RESULT_SUCCESS) {
                    Log.e(TAG, "Filter.start() on PID " + pid + " fails. return value: "+result);
                }
            });
            mSession.mControllerToStart = false;
        }
    }
    private void handlingStartedState() {
        mSession.mTsDataToProcess.forEach(event -> {
            final int dataLength = (int)event.getDataLength();
            byte[] buffer = new byte [dataLength];
            final long n = mDvrRecorder.write(buffer,0,dataLength);
            long curTs = SystemClock.elapsedRealtime();
            if (n > 0 && !mSession.mIOError) {
                try {
                    mDvrFile.write(buffer, 0, (int) n);
                } catch (Exception e) {
                    Log.e(TAG, "Exception: " + e);
                    e.printStackTrace();
                    mSession.mIOError = true;
                    mSession.mTimestampOfLastIOErrorNotify = curTs;
                    mSession.mHaveSentIOErrorNotify = false;
                    return;
                }
                if (mSession.mTimestampOfLastProgressNotify == 0
                        || curTs >= mSession.mTimestampOfLastProgressNotify + interval3) {
                    JDvrRecordingProgress progress = new JDvrRecordingProgress();
                    progress.duration = mDvrFile.duration();
                    progress.startTime = mDvrFile.getStartTime();
                    progress.endTime = progress.startTime + progress.duration;
                    progress.numberOfSegments = mDvrFile.getNumberOfSegments();
                    progress.firstSegmentId = mDvrFile.getFirstSegmentID();
                    progress.lastSegmentId = mDvrFile.getLastSegmentID();
                    progress.size = mDvrFile.size();
                    Message msg = new Message();
                    msg.what = JDvrRecorderEvent.NOTIFY_PROGRESS;
                    msg.obj = progress;
                    onJDvrRecorderEvent(msg);
                    mSession.mTimestampOfLastProgressNotify = curTs;
                }
            }
        });
        //Log.d(TAG,"Processed " + mSession.mTsDataToProcess.size() + " events");
        mSession.mTsDataToProcess.clear();
        if (mSession.mPidChanged) {
            handlingPidChanges();
        }
        if (mSession.mControllerToExit) {
            mFilters.forEach((pid,filter) -> {
                try {
                    if (filter.stop() != Tuner.RESULT_SUCCESS) {
                        Log.e(TAG, "Filter.stop() on PID " + pid + " fails.");
                    }
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Exception: " + e);
                    e.printStackTrace();
                }
            });
            int result = mDvrRecorder.stop();
            if (result != Tuner.RESULT_SUCCESS) {
                Log.e(TAG, "DvrRecorder.stop() fails. return value: " + result);
            }
            mSession.mIsStopping = true;
        }
    }
    private void handlingStoppingState() {
        if (mSession.mControllerToExit) {
            for (Map.Entry<Integer, Filter> entry : mFilters.entrySet()) {
                entry.getValue().close();
            }
            mFilters.clear();
            try {
                mDvrRecorder.close();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Exception: " + e);
                e.printStackTrace();
            }
            mDvrFile.close();
            mSession.mHaveStopped = true;
            // Here it resets the timestamp in order to speed up STREAM_OFF
            mSession.mTimestampOfLastDataReception = 0;
        }
    }
    private void handlingPausedState() {
        mSession.mTsDataToProcess.forEach(event -> {
            final int dataLength = (int) event.getDataLength();
            byte[] buffer = new byte[dataLength];
            mDvrRecorder.write(buffer, 0, dataLength);
            // In Paused state, it just discards coming data, so there is no further handling of the data
        });
        mSession.mTsDataToProcess.clear();
        if (mSession.mControllerToStart) {
            mSession.mControllerToPause = false;
            mSession.mControllerToStart = false;
        }
        final long curTs = SystemClock.elapsedRealtime();
        final boolean cond1 = (curTs - mSession.mTimestampOfLastDataReception > timeout1 + timeout2);
        final boolean cond2 = (curTs - mSession.mTimestampOfLastNoDataNotify > interval1);
        final boolean cond3 = mSession.mIOError;
        final boolean cond4 = (curTs - mSession.mTimestampOfLastIOErrorNotify > interval2);
        final boolean cond5 = mSession.mDiskFull;
        final boolean cond6 = (curTs - mSession.mTimestampOfLastDiskFullNotify > interval2);
        if (cond1 && cond2) {
            Message msg = new Message();
            msg.what = JDvrRecorderEvent.NOTIFY_NO_DATA_ERROR;
            onJDvrRecorderEvent(msg);
            mSession.mTimestampOfLastNoDataNotify = curTs;
        }
        if (cond3) {
            if (!mSession.mHaveSentIOErrorNotify) {
                Message msg = new Message();
                msg.what = JDvrRecorderEvent.NOTIFY_IO_ERROR;
                onJDvrRecorderEvent(msg);
                mSession.mHaveSentIOErrorNotify = true;
            }
            if (cond4) {
                mSession.mIOError = false;
            }
        }
        if (cond5) {
            if (!mSession.mHaveSentDiskFullNotify) {
                Message msg = new Message();
                msg.what = JDvrRecorderEvent.NOTIFY_DISK_FULL_ERROR;
                onJDvrRecorderEvent(msg);
                mSession.mTimestampOfLastDiskFullNotify = curTs;
                mSession.mHaveSentDiskFullNotify = true;
            } else if (cond6) {
                mSession.mHaveSentDiskFullNotify = false;
            }
        }
    }
    private void handlingPidChanges() {
        if (mSession.mPidChanged) {
            mSession.mStreamsToBeRemoved.forEach(stream -> {
                Filter f = mFilters.remove(stream.pid);
                if (f == null) {
                    Log.e(TAG, "The filter to remove is invalid");
                    return;
                }
                mDvrRecorder.detachFilter(f);
                try {
                    int result = f.stop();
                    if (result != Tuner.RESULT_SUCCESS) {
                        Log.e(TAG, "Filter.stop() fails. return value: "+result);
                    }
                    f.close();
                } catch (Exception e) {
                    Log.e(TAG, "Exception: " + e);
                    e.printStackTrace();
                }
                mSession.mStreams.removeIf(s -> s.pid == stream.pid);
            });
            mSession.mStreamsToBeAdded.forEach(stream -> {
                Filter f = mTuner.openFilter(
                        Filter.TYPE_TS,
                        Filter.SUBTYPE_RECORD,
                        mSettings.mFilterBufferSize,
                        mRecorderExecutor,
                        mFilterCallback);
                if (f == null) {
                    Log.e(TAG, "Failed to openFilter");
                    return;
                }
                Settings recordSettings = RecordSettings
                        .builder(Filter.TYPE_TS)
                        .setTsIndexMask(RecordSettings.TS_INDEX_FIRST_PACKET)
                        .build();
                FilterConfiguration filterConfig = TsFilterConfiguration
                        .builder()
                        .setTpid(stream.pid)
                        .setSettings(recordSettings)
                        .build();
                f.configure(filterConfig);
                mDvrRecorder.attachFilter(f);
                mFilters.put(stream.pid,f);
                mSession.mStreams.add(stream);
                if (mSession.mState == JDvrRecordingSession.STARTED_STATE) {
                    f.start();
                }
            });
            mSession.mPidChanged = false;
            mSession.mStreamsToBeAdded.clear();
            mSession.mStreamsToBeRemoved.clear();
            mDvrFile.updateRecordingStreams(mSession.mStreams);
        } else {
            Log.w(TAG,"Trying to handle PID changes but status variable mPidChanged indicates there is no change");
        }
    }
    private final Runnable mStateMachineRunnable = new Runnable() {
        @Override
        public void run() {
            // Before post, remove any state machine runnable in the queue.
            mRecordingHandler.removeCallbacks(this);
            // Schedule it to run 20 ms later which will result in a loop execution of state machine runnable.
            try {
                mRecordingHandler.postDelayed(this, 20L);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Exception: " + e);
                e.printStackTrace();
                return;
            }

            updateState();

            // Rules for this function and sub handling*State functions:
            // 1. Call Tuner APIs
            // 2. Update status
            // 3. Do *NOT* change state
            { // Update "STREAM_ON" status
                final long curTs = SystemClock.elapsedRealtime();
                final boolean cond1 = (mSession.mTimestampOfLastDataReception != 0);
                final boolean cond2 = (curTs-mSession.mTimestampOfLastDataReception < timeout1);
                if ((cond1 && cond2) && !mSession.mStreamOn) {
                    Message msg = new Message();
                    msg.what = JDvrRecorderEvent.NOTIFY_DEBUG_MSG;
                    msg.obj = "STREAM_ON";
                    onJDvrRecorderEvent(msg);
                    Log.i(TAG,"STREAM_ON");
                } else if (!(cond1 && cond2) && mSession.mStreamOn) {
                    Message msg = new Message();
                    msg.what = JDvrRecorderEvent.NOTIFY_DEBUG_MSG;
                    msg.obj = "STREAM_OFF";
                    onJDvrRecorderEvent(msg);
                    Log.i(TAG,"STREAM_OFF");
                }
                mSession.mStreamOn = (cond1 && cond2);
            }
            if (mSession.mState == JDvrRecordingSession.START_STATE) {
                handlingStartState();
            } else if (mSession.mState == JDvrRecordingSession.INITIAL_STATE) {
                handlingInitialState();
            } else if (mSession.mState == JDvrRecordingSession.STARTING_STATE) {
                handlingStartingState();
            } else if (mSession.mState == JDvrRecordingSession.STARTED_STATE) {
                handlingStartedState();
            } else if (mSession.mState == JDvrRecordingSession.PAUSED_STATE) {
                handlingPausedState();
            } else if (mSession.mState == JDvrRecordingSession.STOPPING_STATE) {
                handlingStoppingState();
            }
        }
    };
    private final Runnable mDiskSpaceCheckerRunnable = new Runnable() {
        final static long checkingInterval = 30000L;  // in ms
        @Override
        public void run() {
            mRecordingHandler.removeCallbacks(this);
            try {
                mRecordingHandler.postDelayed(this, checkingInterval);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Exception: " + e);
                e.printStackTrace();
                return;
            }

            final String pathPrefix = mDvrFile.getPathPrefix();
            final String dirname = pathPrefix.substring(0,pathPrefix.lastIndexOf('/'));
            StatFs stat = new StatFs(dirname);
            final long diskAvailable = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
            Log.d(TAG,"Free disk space: " + (diskAvailable>>20) + " MB");
            mSession.mDiskFull = diskAvailable <= 0;
        }
    };
    private final Runnable mFilesUpdaterRunnable = new Runnable() {
        final static long updateInterval = 5000L;  // in ms
        public void run() {
            mRecordingHandler.removeCallbacks(this);
            try {
                mRecordingHandler.postDelayed(this, updateInterval);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Exception: " + e);
                e.printStackTrace();
                return;
            }
            mDvrFile.updateListFile();
        }
    };

    // Public APIs
    /**
     * Constructs a JDvrRecorder instance.
     * It results in the running of recording thread and corresponding state machine.
     *
     * @param tuner A Tuner instance.
     * @param file A JDvrFile instance.
     * @param settings A JDvrSettings instance.
     * @param executor An Executor instance that executes submitted Runnable tasks.
     * @param listener An OnJDvrRecorderEventListener instance for receiving JDvrRecorder notifications.
     */
    public JDvrRecorder(Tuner tuner, JDvrFile file, JDvrRecorderSettings settings,
                        Executor executor, OnJDvrRecorderEventListener listener) {
        mTuner = tuner;
        mDvrFile = file;
        mSettings = settings;
        synchronized (mOnJDvrRecorderEventLock) {
            mListenerExecutor = executor;
            mListener = listener;
        }
        mRecordingThread.start();
        mRecordingHandler = new Handler(mRecordingThread.getLooper(), mRecordingCallback);
        mRecordingHandler.post(mStateMachineRunnable);
        mRecordingHandler.post(mDiskSpaceCheckerRunnable);
        mRecordingHandler.post(mFilesUpdaterRunnable);
    }
    /**
     * Add a stream to be recorded.
     * The design supports adding a stream to recording on the fly.
     *
     * @param pid the packet id of the stream
     * @param stream_type the JDvrLib stream type(i.e. JDvrStreamType) of the stream.
     * @param format the JDvrLib stream format(i.e. JDvrVideoFormat/JDvrAudioFormat) of the stream.
     * @return true if operation is successful, or false if there is any problem.
     */
    public boolean addStream (int pid, int stream_type, int format) {
        if (mDvrRecorder == null) {
            Log.e(TAG, "addStream: DvrRecorder is invalid");
            return false;
        }
        Log.d(TAG, "JDvrRecorder.addStream pid:"+pid+", type:"+stream_type+", format:"+format);
        Message msg = new Message();
        msg.what = JDvrRecordingStatus.STREAM_STATUS_PID_CHANGED;
        msg.arg1 = pid;
        msg.arg2 = -1;
        msg.obj = new JDvrStreamInfo(pid,stream_type,format);
        mRecordingHandler.sendMessage(msg);
        return true;
    }
    /**
     * Remove a stream being recorded.
     * The design supports removing a stream from recording on the fly.
     *
     * @param pid the packet id of the stream.
     * @return true if operation is successful, or false if there is any problem.
     */
    public boolean removeStream(int pid) {
        if (mDvrRecorder == null) {
            Log.e(TAG, "removeStream: DvrRecorder is invalid");
            return false;
        }
        Log.d(TAG, "JDvrRecorder.removeStream pid:"+pid);
        Message msg = new Message();
        msg.what = JDvrRecordingStatus.STREAM_STATUS_PID_CHANGED;
        msg.arg1 = -1;
        msg.arg2 = pid;
        mRecordingHandler.sendMessage(msg);
        return true;
    }
    /**
     * Start a recording or resume a paused recording.
     *
     * @return true if operation is successful, or false if there is any problem.
     */
    public boolean start () {
        if (mDvrRecorder == null) {
            Log.e(TAG, "start: DvrRecorder is invalid");
            return false;
        }
        Log.d(TAG, "JDvrRecorder.start");
        mRecordingHandler.sendMessage(
                mRecordingHandler.obtainMessage(JDvrRecordingStatus.CONTROLLER_STATUS_TO_START,null));
        return true;
    }
    /**
     * Stop the recording.
     *
     * @return true if operation is successful, or false if there is any problem.
     */
    public boolean stop () {
        if (mDvrRecorder == null) {
            Log.e(TAG, "stop: DvrRecorder is invalid");
            return false;
        }
        Log.d(TAG, "JDvrRecorder.stop");
        mRecordingHandler.sendMessage(
                mRecordingHandler.obtainMessage(JDvrRecordingStatus.CONTROLLER_STATUS_TO_EXIT,null));
        return true;
    }
    /**
     * Pause the recording.
     *
     * @return true if operation is successful, or false if there is any problem.
     */
    public boolean pause () {
        if (mDvrRecorder == null) {
            Log.e(TAG, "pause: DvrRecorder is invalid");
            return false;
        }
        Log.d(TAG, "JDvrRecorder.pause");
        mRecordingHandler.sendMessage(
                mRecordingHandler.obtainMessage(JDvrRecordingStatus.CONTROLLER_STATUS_TO_PAUSE,null));
        return true;
    }

    // Private functions
    private void onJDvrRecorderEvent(final Message msg) {
        if (mListenerExecutor != null && mListener != null) {
            mListenerExecutor.execute(() -> {
                synchronized (mOnJDvrRecorderEventLock) {
                    if (mOnJDvrRecorderEventLock != null) {
                        mListener.onJDvrRecorderEvent(msg);
                    }
                }
            });
        }
    }
    private boolean isCmdInProgress() {
        return mSession.mControllerToStart || mSession.mControllerToExit;
    }
}

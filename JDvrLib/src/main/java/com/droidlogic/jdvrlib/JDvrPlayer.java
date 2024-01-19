package com.droidlogic.jdvrlib;

import static com.droidlogic.jdvrlib.JDvrCommon.JDvrStreamType.STREAM_TYPE_OTHER;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import com.amlogic.asplayer.api.ASPlayer;
import com.amlogic.asplayer.api.InputBuffer;
import com.amlogic.asplayer.api.TsPlaybackListener;
import com.amlogic.asplayer.api.VideoTrickMode;
import com.droidlogic.jdvrlib.OnJDvrPlayerEventListener.JDvrPlayerEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;

public class JDvrPlayer {
    private static class JDvrPlaybackSession {
        public final static int START_STATE             = 0;
        public final static int INITIAL_STATE           = 1;
        public final static int STARTING_STATE          = 2;
        public final static int SMOOTH_PLAYING_STATE    = 3;
        public final static int SKIPPING_PLAYING_STATE  = 4;
        public final static int PAUSED_STATE            = 5;
        public final static int STOPPING_STATE          = 6;

        // Player State
        private int mState = START_STATE;
        // Statuses
        private boolean mControllerToStart = false;
        private boolean mControllerToPause = false;
        private boolean mControllerToExit = false;
        private final int mSessionNumber;
        private boolean mIsStarting = false;
        private boolean mIsStopping = false;
        private boolean mHaveStopped = true;
        private boolean mIsEOS = false;
        private double mCurrentSpeed = 0.0d;
        private double mTargetSpeed = 1.0d;
        private Integer mTargetSeekPos = 0;      // in seconds
        private boolean mFirstVideoFrameReceived = false;
        private boolean mFirstAudioFrameReceived = false;
        private long mTimestampOfLastProgressNotify = 0;
        private boolean mRecordingIsUpdatedLately = true;
        private boolean mTrickModeBySeekIsOn = false;
        private boolean mHasPausedDecoding = false;
        private boolean mVideoDecoderInitReceived = false;
        private boolean mAudioFormatChangeReceived = false;
        private boolean mStartingPhaseSeek1Done = false;
        private boolean mStartingPhaseSeek2Done = false;

        public JDvrPlaybackSession() {
            mSessionNumber = JDvrCommon.generateSessionNumber();
        }
        public int getSessionNumber() {
            return mSessionNumber;
        }
    }
    private static class JDvrPlaybackStatus {
        public final static int CONTROLLER_STATUS_TO_START  = 1;
        public final static int CONTROLLER_STATUS_TO_PAUSE  = 2;
        public final static int CONTROLLER_STATUS_TO_EXIT   = 3;
        public final static int CONTROLLER_STATUS_TO_SET_SPEED  = 4;
        public final static int CONTROLLER_STATUS_TO_SEEK   = 5;
    }
    public static class JDvrPlaybackProgress {
        public int sessionNumber;
        public int state;
        public double speed;
        public long currTime;    // in ms, from origin
        public long startTime;   // in ms, from origin
        public long endTime;     // in ms, from origin
        public long duration;    // in ms
        public int currSegmentId;
        public int firstSegmentId;
        public int lastSegmentId;
        public int numberOfSegments;
        @Override
        public String toString() {
            return "{" +
                    "\"sessionNumber\":" + sessionNumber +
                    ", \"state\":" + state +
                    ", \"speed\":" + speed +
                    ", \"currTime\":" + currTime +
                    ", \"startTime\":" + startTime +
                    ", \"endTime\":" + endTime +
                    ", \"duration\":" + duration +
                    ", \"currSegmentId\":" + currSegmentId +
                    ", \"firstSegmentId\":" + firstSegmentId +
                    ", \"lastSegmentId\":" + lastSegmentId +
                    ", \"numberOfSegments\":" + numberOfSegments +
                    '}';
        }
    }

    private final static int READ_LEN = 188*1024;  // in bytes
    private final static int interval1 = 1000;   // in ms
    private final JDvrPlaybackSession mSession = new JDvrPlaybackSession();
    final private String TAG = getLogTAG();
    private final ASPlayer mASPlayer;
    private JDvrFile mJDvrFile;
    private final JDvrPlayerSettings mSettings;
    private final Executor mListenerExecutor;
    private final OnJDvrPlayerEventListener mListener;
    private final HandlerThread mPlaybackThread = new HandlerThread("JDvrPlayer task");
    private final Handler mPlaybackHandler;
    private final Object mOnJDvrPlayerEventLock = new Object();
    private InputBuffer mPendingInputBuffer;
    private long mLastTrickModeTimestamp = 0L;
    private long mLastTrickModeTimeOffset = 0L;
    private final ArrayList<Pair<Long,Long>> mLastModifiedRecords = new ArrayList<>();
    private long mLastPts = 0L;     // Original PTS in 90KHz
    private long mEndTime = 0L;
    private long mPlayingTime = 0L;

    // Callbacks
    private final Handler.Callback mPlaybackCallback = message -> {
        // Rules:
        // 1. Update status
        // 2. Do *NOT* update state
        // 3. Do *NOT* call ASPlayer APIs
        if (message.what == JDvrPlaybackStatus.CONTROLLER_STATUS_TO_START) {
            mSession.mControllerToStart = true;
            mSession.mTargetSpeed = 1.0d;
        } else if (message.what == JDvrPlaybackStatus.CONTROLLER_STATUS_TO_PAUSE) {
            mSession.mControllerToPause = true;
            mSession.mTargetSpeed = 0.0d;
        } else if (message.what == JDvrPlaybackStatus.CONTROLLER_STATUS_TO_EXIT) {
            mSession.mControllerToExit = true;
        } else if (message.what == JDvrPlaybackStatus.CONTROLLER_STATUS_TO_SET_SPEED) {
            mSession.mTargetSpeed = (Double) message.obj;
            if (mSession.mTargetSpeed == 0.0d) {
                mSession.mControllerToPause = true;
            }
        } else if (message.what == JDvrPlaybackStatus.CONTROLLER_STATUS_TO_SEEK) {
            mSession.mTargetSeekPos = (Integer) message.obj;
        }
        return false;
    };
    private final Runnable mStateMachineRunnable = new Runnable() {
        @Override
        public void run() {
            // Before post, remove any state machine runnable in the queue.
            mPlaybackHandler.removeCallbacks(this);
            // Schedule it to run 20 ms later which will result in a loop execution of state machine runnable.
            try {
                mPlaybackHandler.postDelayed(this, 20L);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
                e.printStackTrace();
                return;
            }
            updateState();
            if (mSession.mState == JDvrPlaybackSession.START_STATE) {
                handlingStartState();
            } else if (mSession.mState == JDvrPlaybackSession.INITIAL_STATE) {
                handlingInitialState();
            } else if (mSession.mState == JDvrPlaybackSession.STARTING_STATE) {
                handlingStartingState();
            } else if (mSession.mState == JDvrPlaybackSession.SMOOTH_PLAYING_STATE) {
                handlingSmoothPlayingState();
            } else if (mSession.mState == JDvrPlaybackSession.SKIPPING_PLAYING_STATE) {
                handlingSkippingPlayingState();
            } else if (mSession.mState == JDvrPlaybackSession.PAUSED_STATE) {
                handlingPausedState();
            } else if (mSession.mState == JDvrPlaybackSession.STOPPING_STATE) {
                handlingStoppingState();
            }
        }
    };
    private final Runnable mSegmentsMonitor = new Runnable() {
        @Override
        public void run() {
            mPlaybackHandler.removeCallbacks(this);
            try {
                mPlaybackHandler.postDelayed(this, 1000L);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
                e.printStackTrace();
                return;
            }
            if (mSession.mState == JDvrPlaybackSession.INITIAL_STATE || mJDvrFile == null) {
                return;
            }
            final String pathPrefix = mJDvrFile.getPathPrefix();
            // Segment adding based on actual segment files
            final int idNew = mJDvrFile.getLastSegmentId()+1;
            final String pathComing = String.format(Locale.US,"%s-%04d.idx",pathPrefix,idNew);
            File fileComing = new File(pathComing);
            if (fileComing.exists()) {
                mJDvrFile.addSegment();
            }
            if (mJDvrFile.isTimeshift()) {
                // Segment removing based on actual segment files
                final int idCurr = mJDvrFile.getSegmentIdBeingRead();
                final int idFirst = mJDvrFile.getFirstSegmentId();
                final String pathGoing = String.format(Locale.US, "%s-%04d.idx", pathPrefix, idFirst);
                File fileGoing = new File(pathGoing);
                if (!fileGoing.exists()) {
                    mJDvrFile.removeSegment(idFirst);
                }
                // Edge condition handling
                final int startTime = (int)mJDvrFile.getStartTime()/1000;   // in sec
                final int count = mJDvrFile.getNumberOfSegments();
                final long limitSize = mJDvrFile.getLimitSize();            // in bytes
                final int limitDuration = mJDvrFile.getLimitDuration();     // in sec
                final long currSize = mJDvrFile.size();                     // in bytes
                final int currDuration = (int)(mJDvrFile.duration()/1000);  // in sec
                final int rate = (currDuration > 0) ? (int)(currSize/currDuration) : 0; // in bytes/sec
                final boolean cond1 = JDvrFile.isEffectiveLimitSize(limitSize) || JDvrFile.isEffectiveLimitDuration(limitDuration);
                final boolean cond2 = (idCurr == idFirst);
                final boolean cond3 = (limitDuration - currDuration <= 5);
                final boolean cond4 = (limitSize - currSize <= 5L * rate);
                if (cond1 && cond2 && (cond3 || cond4)) {
                    final int delta = currDuration/(count-1);
                    final int newTime = (startTime+delta); // in sec
                    innerSeek(newTime);
                    Message msg = new Message();
                    msg.what = JDvrPlayerEvent.NOTIFY_EDGE_LEAVING;
                    msg.arg1 = newTime;
                    onJDvrPlayerEvent(msg);
                }
            }
        }
    };

    private final Executor mPlayerExecutor = new Executor() {
        public void execute(Runnable r) {
            if (!mPlaybackHandler.post(r)) {
                Log.w(TAG, "Playback Handler is shutting down");
            }
        }
    };
    private void onJDvrPlayerEvent(final Message msg) {
        if (mListenerExecutor != null && mListener != null) {
            mListenerExecutor.execute(() -> {
                synchronized (mOnJDvrPlayerEventLock) {
                    mListener.onJDvrPlayerEvent(msg);
                }
            });
        }
    }
    private final TsPlaybackListener mTsPlaybackListener = new TsPlaybackListener() {
        @Override
        public void onPlaybackEvent(PlaybackEvent playbackEvent) {
            //Log.d(TAG,"onPlaybackEvent "+playbackEvent.getClass().getSimpleName());
            if (playbackEvent instanceof TsPlaybackListener.VideoFirstFrameEvent) {
                mPlaybackHandler.postAtFrontOfQueue(() -> mSession.mFirstVideoFrameReceived = true);
            } else if (playbackEvent instanceof TsPlaybackListener.AudioFirstFrameEvent) {
                mPlaybackHandler.postAtFrontOfQueue(() -> mSession.mFirstAudioFrameReceived = true);
            } else if (playbackEvent instanceof TsPlaybackListener.VideoDecoderInitCompletedEvent) {
                mPlaybackHandler.postAtFrontOfQueue(() -> mSession.mVideoDecoderInitReceived = true);
            } else if (playbackEvent instanceof TsPlaybackListener.AudioFormatChangeEvent) {
                mPlaybackHandler.postAtFrontOfQueue(() -> mSession.mAudioFormatChangeReceived = true);
            } else if (playbackEvent instanceof TsPlaybackListener.PtsEvent) {
                if (((PtsEvent) playbackEvent).mPts > 0) { // Here the mPts from ASPlayer is in microsecond (us)
                    mLastPts = ((PtsEvent) playbackEvent).mPts * 90 / 1000;     // Convert it to original MPEG PTS in 90KHz and store it in mLastPts
                    if (!mPlaybackHandler.hasCallbacks(mPtsRunnable)) {
                        mPlaybackHandler.post(mPtsRunnable);
                    }
                }
            }
        }
    };
    private final Runnable mPtsRunnable = new Runnable() {
        @Override
        public void run() {
            if (mSession.mState == JDvrPlaybackSession.INITIAL_STATE || mJDvrFile == null) {
                return;
            }
            //Log.d(TAG,"pts:"+mLastPts);
            mJDvrFile.updateLastPts(mLastPts);
        }
    };

    // Public APIs
    /**
     * Constructs a JDvrPlayer instance.
     * It results in the running of playback thread and corresponding state machine.
     *
     * @param asplayer An ASPlayer instance.
     * @param file A JDvrFile instance.
     * @param settings A JDvrPlayerSettings instance.
     * @param executor An Executor instance that executes submitted Runnable tasks.
     * @param listener An OnJDvrPlayerEventListener instance for receiving JDvrPlayer notifications.
     */
    public JDvrPlayer(ASPlayer asplayer, JDvrFile file, JDvrPlayerSettings settings,
                      Executor executor, OnJDvrPlayerEventListener listener) {
        Log.d(TAG,"JDvrLibAPI JDvrPlayer.ctor "+file.getPathPrefix());
        mASPlayer = asplayer;
        mJDvrFile = file;
        mSettings = (settings == null) ? JDvrPlayerSettings.builder().build() : settings;
        mListenerExecutor = ((executor != null) ? executor : mPlayerExecutor);
        mListener = ((listener != null) ? listener : new JNIJDvrPlayerListener(this));
        Log.d(TAG,"calling ASPlayer.addPlaybackListener at "+JDvrCommon.getCallerInfo(3));
        mASPlayer.addPlaybackListener(mTsPlaybackListener);
        Log.d(TAG,"calling ASPlayer.flushDvr at "+JDvrCommon.getCallerInfo(3));
        mASPlayer.flushDvr();
        mPlaybackThread.start();
        mPlaybackHandler = new Handler(mPlaybackThread.getLooper(),mPlaybackCallback);
        mPlaybackHandler.post(mStateMachineRunnable);
        mPlaybackHandler.post(mSegmentsMonitor);
    }
    /**
     * Start playing a recording or resume a paused playback.
     *
     * @return true if operation is successful, or false if there is any problem.
     */
    public boolean play() {
        Log.d(TAG,"JDvrLibAPI JDvrPlayer.play");
        mPlaybackHandler.sendMessage(
                mPlaybackHandler.obtainMessage(JDvrPlaybackStatus.CONTROLLER_STATUS_TO_START,null));
        return true;
    }
    /**
     * Stop a playback.
     *
     * @return true if operation is successful, or false if there is any problem.
     */
    public boolean stop() {
        Log.d(TAG,"JDvrLibAPI JDvrPlayer.stop");
        final long ts1 = SystemClock.elapsedRealtime();
        mPlaybackHandler.sendMessage(
                mPlaybackHandler.obtainMessage(JDvrPlaybackStatus.CONTROLLER_STATUS_TO_EXIT, null));
        try {
            mPlaybackThread.join(1000);
        } catch (InterruptedException e) {
            Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
        }
        final long ts2 = SystemClock.elapsedRealtime();
        final long diff = ts2 - ts1;
        if (diff >= 1000) {
            Log.w(TAG, "JDvrPlayer.stop took too long time " + diff + "ms");
        } else {
            Log.d(TAG, "JDvrPlayer.stop took " + diff + "ms");
        }
        return true;
    }
    /**
     * Pause a playback. It can be resumed later by calling play()
     *
     * @return true if operation is successful, or false if there is any problem.
     */
    public boolean pause() {
        Log.d(TAG,"JDvrLibAPI JDvrPlayer.pause");
        mPlaybackHandler.sendMessage(
                mPlaybackHandler.obtainMessage(JDvrPlaybackStatus.CONTROLLER_STATUS_TO_PAUSE,null));
        return true;
    }
    /**
     * Set playback speed.
     *
     * @param speed The playback speed.
     *              (2.0, 16.0]:    fast forward skipping playing at high speed.
     *              (1.0, 2.0]:     fast forward smooth playing.
     *              == 1.0:         playing at original speed.
     *              (0.0, 1.0):     slow forward smooth playing.
     *              == 0.0:         pause playing.
     *              [-16.0, 0.0):   backward skipping playing.
     * @return true if operation is successful, or false if there is any problem.
     */
    public boolean setSpeed(double speed) {
        Log.d(TAG,"JDvrLibAPI JDvrPlayer.setSpeed "+speed);
        mPlaybackHandler.sendMessage(
                mPlaybackHandler.obtainMessage(JDvrPlaybackStatus.CONTROLLER_STATUS_TO_SET_SPEED,speed));
        return true;
    }
    /**
     * Seek to a specific time position.
     *
     * @param seconds The target seeking time position in seconds.
     *                It refers to the original time (say it 'origin') when the recording was initially made.
     *                The seconds should always refer to origin even if earliest segments are removed
     *                under timeshift condition.
     * @return true if operation is successful, or false if there is any problem.
     */
    public boolean seek(int seconds) {
        Log.d(TAG,"JDvrLibAPI JDvrPlayer.seek to "+seconds+"s");
        return innerSeek(seconds);
    }

    // Private APIs

    // Private functions
    private void updateState() {
        // Rules:
        // 1. Check status but do *NOT* update status
        // 2. Update state
        // 3. Do *NOT* call ASPlayer APIs
        int prevState = mSession.mState;
        if (mSession.mState == JDvrPlaybackSession.START_STATE) {
            final boolean cond1 = (mASPlayer != null);
            final boolean cond2 = (mJDvrFile != null);
            if (cond1 && cond2) {
                mSession.mState = JDvrPlaybackSession.INITIAL_STATE;
                Log.i(TAG, "State transition: START => INITIAL");
            }
        } else if (mSession.mState == JDvrPlaybackSession.INITIAL_STATE) {
            final boolean cond1 = mSession.mIsStarting;
            if (cond1) {
                mSession.mState = JDvrPlaybackSession.STARTING_STATE;
                Log.i(TAG, "State transition: INITIAL => STARTING");
            }
        } else if (mSession.mState == JDvrPlaybackSession.STARTING_STATE) {
            final boolean cond1 = mSession.mFirstVideoFrameReceived;
            final boolean cond2 = isSmoothPlaySpeed(mSession.mTargetSpeed);
            final boolean cond3 = isSkippingPlaySpeed(mSession.mTargetSpeed);
            final boolean cond4 = (mSession.mTargetSpeed == 0.0d);
            final boolean cond5 = mSession.mFirstAudioFrameReceived;
            final boolean cond6 = mSession.mIsStopping;
            if (cond1 || cond5) {
                if (cond2) {
                    mSession.mState = JDvrPlaybackSession.SMOOTH_PLAYING_STATE;
                    Log.i(TAG, "State transition: STARTING => SMOOTH_PLAYING");
                } else if (cond3) {
                    mSession.mState = JDvrPlaybackSession.SKIPPING_PLAYING_STATE;
                    Log.i(TAG, "State transition: STARTING => SKIPPING_PLAYING");
                } else if (cond4) {
                    mSession.mState = JDvrPlaybackSession.PAUSED_STATE;
                    Log.i(TAG, "State transition: STARTING => PAUSED");
                }
            }
            if (cond6) {
                mSession.mState = JDvrPlaybackSession.STOPPING_STATE;
                Log.i(TAG, "State transition: STARTING => STOPPING");
            }
        } else if (mSession.mState == JDvrPlaybackSession.SMOOTH_PLAYING_STATE) {
            final boolean cond1 = mSession.mIsStopping;
            final boolean cond2 = mSession.mIsEOS;
            final boolean cond3 = (mSession.mTargetSpeed == 0.0d);
            final boolean cond4 = isSkippingPlaySpeed(mSession.mTargetSpeed);
            if (cond1 || cond2) {
                mSession.mState = JDvrPlaybackSession.STOPPING_STATE;
                Log.i(TAG, "State transition: SMOOTH_PLAYING => STOPPING");
            } else if (cond3) {
                mSession.mState = JDvrPlaybackSession.PAUSED_STATE;
                Log.i(TAG, "State transition: SMOOTH_PLAYING => PAUSED");
            } else if (cond4) {
                mSession.mState = JDvrPlaybackSession.SKIPPING_PLAYING_STATE;
                Log.i(TAG, "State transition: SMOOTH_PLAYING => SKIPPING_PLAYING");
            }
        } else if (mSession.mState == JDvrPlaybackSession.SKIPPING_PLAYING_STATE) {
            final boolean cond1 = mSession.mIsStopping;
            final boolean cond2 = mSession.mIsEOS;
            final boolean cond3 = (mSession.mTargetSpeed == 0.0d);
            final boolean cond4 = isSmoothPlaySpeed(mSession.mTargetSpeed);
            if (cond1 || cond2) {
                mSession.mState = JDvrPlaybackSession.STOPPING_STATE;
                Log.i(TAG, "State transition: SKIPPING_PLAYING => STOPPING");
            } else if (cond3) {
                mSession.mState = JDvrPlaybackSession.PAUSED_STATE;
                Log.i(TAG, "State transition: SKIPPING_PLAYING => PAUSED");
            } else if (cond4) {
                mSession.mState = JDvrPlaybackSession.SMOOTH_PLAYING_STATE;
                Log.i(TAG, "State transition: SKIPPING_PLAYING => SMOOTH_PLAYING");
            }
        } else if (mSession.mState == JDvrPlaybackSession.PAUSED_STATE) {
            final boolean cond1 = isSmoothPlaySpeed(mSession.mTargetSpeed) ;
            final boolean cond2 = isSkippingPlaySpeed(mSession.mTargetSpeed);
            final boolean cond3 = mSession.mIsStopping;
            if (cond3) {
                mSession.mState = JDvrPlaybackSession.STOPPING_STATE;
                Log.i(TAG, "State transition: PAUSED => STOPPING");
            } else if (cond1) {
                mSession.mState = JDvrPlaybackSession.SMOOTH_PLAYING_STATE;
                Log.i(TAG, "State transition: PAUSED => SMOOTH_PLAYING");
            } else if (cond2) {
                mSession.mState = JDvrPlaybackSession.SKIPPING_PLAYING_STATE;
                Log.i(TAG, "State transition: PAUSED => SKIPPING_PLAYING");
            }
        } else if (mSession.mState == JDvrPlaybackSession.STOPPING_STATE) {
            final boolean cond1 = mSession.mHaveStopped;
            if (cond1) {
                mSession.mState = JDvrPlaybackSession.INITIAL_STATE;
                Log.i(TAG,"State transition: STOPPING => INITIAL");
            }
        }
        if (prevState != mSession.mState) {
            Message msg = new Message();
            if (mSession.mState == JDvrPlaybackSession.INITIAL_STATE) {
                msg.what = JDvrPlayerEvent.NOTIFY_INITIAL_STATE;
            } else if (mSession.mState == JDvrPlaybackSession.STARTING_STATE) {
                msg.what = JDvrPlayerEvent.NOTIFY_STARTING_STATE;
            } else if (mSession.mState == JDvrPlaybackSession.SMOOTH_PLAYING_STATE) {
                msg.what = JDvrPlayerEvent.NOTIFY_SMOOTH_PLAYING_STATE;
            } else if (mSession.mState == JDvrPlaybackSession.SKIPPING_PLAYING_STATE) {
                msg.what = JDvrPlayerEvent.NOTIFY_SKIPPING_PLAYING_STATE;
            } else if (mSession.mState == JDvrPlaybackSession.STOPPING_STATE) {
                msg.what = JDvrPlayerEvent.NOTIFY_STOPPING_STATE;
            } else if (mSession.mState == JDvrPlaybackSession.PAUSED_STATE) {
                msg.what = JDvrPlayerEvent.NOTIFY_PAUSED_STATE;
            } else {
                Log.e(TAG,"Invalid state value: " + mSession.mState);
                return;
            }
            onJDvrPlayerEvent(msg);
        }
    }
    private String getLogTAG() {
        return JDvrPlayer.class.getSimpleName()+"#"+mSession.getSessionNumber();
    }
    private void handlingStartState() {
    }
    private void handlingInitialState() {
        if (mSession.mControllerToStart || mSession.mControllerToPause) {
            Log.d(TAG,"calling ASPlayer.flushDvr at "+JDvrCommon.getCallerInfo(3));
            try { // Consider ASPlayer may have already been released at DTVKit side
                mASPlayer.flushDvr();
            } catch (NullPointerException e) {
                Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
                mSession.mControllerToStart = false;
                mSession.mControllerToPause = false;
                return;
            }
            Log.d(TAG,"calling ASPlayer.startVideoDecoding at "+JDvrCommon.getCallerInfo(3));
            if (mASPlayer.startVideoDecoding() < 0) {
                Log.e(TAG, "ASPlayer.startVideoDecoding fails");
                return;
            }
            mSession.mHasPausedDecoding = false;
            if (mASPlayer.startAudioDecoding() < 0) {
                Log.e(TAG, "ASPlayer.startAudioDecoding fails");
                return;
            }
            if (mSession.mControllerToPause) {
                Log.d(TAG,"calling ASPlayer.setTrickMode(BY_SEEK) at "+JDvrCommon.getCallerInfo(3));
                mASPlayer.setTrickMode(VideoTrickMode.TRICK_MODE_BY_SEEK);
                // Here speed 1.0f is given to ensure AudioFirstFrameEvent reception.
                Log.d(TAG,"calling ASPlayer.startFast(1.0) at "+JDvrCommon.getCallerInfo(3));
                mASPlayer.startFast(1.0f);
                mSession.mTrickModeBySeekIsOn = true;
            }
            mSession.mIsStarting = true;
            mSession.mHaveStopped = false;
        } else if (mSession.mControllerToExit || mSession.mIsEOS) {
            try { // Consider ASPlayer may have already been released at DTVKit side
                Log.d(TAG,"calling ASPlayer.removePlaybackListener at "+JDvrCommon.getCallerInfo(3));
                mASPlayer.removePlaybackListener(mTsPlaybackListener);
            } catch (NullPointerException e) {
                Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
            }
            mPlaybackThread.quitSafely();
            mJDvrFile.close();
            mJDvrFile = null;
            mSession.mControllerToExit = false;
            mSession.mIsEOS = false;
        }
    }
    private void handlingStartingState() {
        final boolean cond1 = !mSession.mStartingPhaseSeek1Done;
        final boolean cond2 = !mSession.mStartingPhaseSeek2Done;
        final boolean cond3 = mSession.mVideoDecoderInitReceived;
        final boolean cond4 = mSession.mAudioFormatChangeReceived;
        final boolean cond5 = !mSession.mRecordingIsUpdatedLately;
        final boolean cond6 = (mSession.mTargetSeekPos != null);
        final boolean cond7 = mSession.mControllerToExit;
        if (cond6 && (cond1 || (cond2 && (cond3 || cond4)))) {
            Log.d(TAG, "calling ASPlayer.flushDvr at " + JDvrCommon.getCallerInfo(3));
            mASPlayer.flushDvr();
            mJDvrFile.seek(mSession.mTargetSeekPos * 1000);
            mPendingInputBuffer = null;
            if (cond1) {
                Log.d(TAG,"First seek to "+mSession.mTargetSeekPos+"s in starting phase");
                mSession.mStartingPhaseSeek1Done = true;
            } else {
                Log.d(TAG,"Second seek to "+mSession.mTargetSeekPos+"s in starting phase");
                mSession.mStartingPhaseSeek2Done = true;
                mSession.mTargetSeekPos = null;
            }
            mPlaybackHandler.removeCallbacks(mPtsRunnable);
        }
        if (cond7) {
            mSession.mIsStopping = true;
        }
        final boolean condA = (-1 == injectData());
        if (cond5 && condA) {
            mSession.mIsEOS = true;
        }
    }
    private void handlingSmoothPlayingState() {
        final long curTs = SystemClock.elapsedRealtime();
        injectData();
        final boolean cond1 = (mEndTime != 0 && mEndTime-mPlayingTime<300);
        final boolean cond2 = mSession.mControllerToExit;
        final boolean cond3 = mSession.mCurrentSpeed != mSession.mTargetSpeed;
        final boolean cond4 = mSession.mControllerToPause;
        final boolean cond5 = (mSession.mTargetSeekPos != null);
        final boolean cond6 = isSkippingPlaySpeed(mSession.mTargetSpeed);
        final boolean cond7 = isSmoothPlaySpeed(mSession.mTargetSpeed);
        final boolean cond8 = mSession.mCurrentSpeed == 0.0;
        final boolean cond9 = !mSession.mRecordingIsUpdatedLately;
        mSession.mIsStarting = false;
        mSession.mControllerToStart = false;
        if (cond1 && cond9) {
            mSession.mIsEOS = true;
            mSession.mIsStopping = true;
        }
        if (cond2) {
            mSession.mIsStopping = true;
        }
        if (cond3) {
            if (cond8) {
                Log.d(TAG,"calling ASPlayer.resumeVideoDecoding at "+JDvrCommon.getCallerInfo(3));
                mASPlayer.resumeVideoDecoding();
                mASPlayer.resumeAudioDecoding();
            }
            if (cond7) {
                Log.d(TAG,"calling ASPlayer.setTrickMode(SMOOTH) at "+JDvrCommon.getCallerInfo(3));
                mASPlayer.setTrickMode(VideoTrickMode.TRICK_MODE_SMOOTH);
                mSession.mTrickModeBySeekIsOn = false;
                Log.d(TAG,"calling ASPlayer.startFast("+mSession.mTargetSpeed+") at "+JDvrCommon.getCallerInfo(3));
                final int ret = mASPlayer.startFast((float)mSession.mTargetSpeed);
                if (ret == 0) {
                    speedTransition();
                } else {
                    Log.e(TAG,"ASPlayer.startFast returns "+ret);
                }
            }
            if (cond4) {
                mSession.mTargetSpeed = 0.0d;
                Log.d(TAG,"calling ASPlayer.stopFast at "+JDvrCommon.getCallerInfo(3));
                mASPlayer.stopFast();
                Log.d(TAG,"calling ASPlayer.pauseVideoDecoding at "+JDvrCommon.getCallerInfo(3));
                final int ret = mASPlayer.pauseVideoDecoding();
                mASPlayer.pauseAudioDecoding();
                mSession.mHasPausedDecoding = true;
                if (ret == 0) {
                    speedTransition();
                } else {
                    Log.e(TAG,"ASPlayer.pauseVideoDecoding returns "+ret);
                }
            }
        }
        if (cond5) {
            Log.d(TAG,"calling ASPlayer.flushDvr/flush at "+JDvrCommon.getCallerInfo(3));
            mASPlayer.flushDvr();
            mASPlayer.flush();
            mJDvrFile.seek(mSession.mTargetSeekPos*1000);
            mPendingInputBuffer = null;
            mSession.mTargetSeekPos = null;
            mPlaybackHandler.removeCallbacks(mPtsRunnable);
        }
        if (cond6) {
            speedTransition();
            Log.d(TAG,"calling ASPlayer.stopFast at "+JDvrCommon.getCallerInfo(3));
            mASPlayer.stopFast();
        }
        if (mSession.mTimestampOfLastProgressNotify == 0
                || curTs >= mSession.mTimestampOfLastProgressNotify + interval1) {
            notifyProgress();
            mSession.mTimestampOfLastProgressNotify = curTs;
            checkLastModifiedTime();
        }
    }
    private void handlingSkippingPlayingState() {
        final long curTs = SystemClock.elapsedRealtime();
        injectData();
        final boolean cond1 = (mEndTime != 0 && mEndTime-mPlayingTime<300);
        final boolean cond2 = mSession.mControllerToExit;
        final boolean cond3 = mSession.mControllerToPause;
        final boolean cond4 = (mSession.mTargetSeekPos != null);
        final boolean cond5 = isSmoothPlaySpeed(mSession.mCurrentSpeed);
        final boolean cond6 = (curTs >= mLastTrickModeTimestamp + 1000);
        final boolean cond7 = !mSession.mRecordingIsUpdatedLately;
        mSession.mIsStarting = false;
        mSession.mControllerToStart = false;
        if (cond1 && cond7) {
            mSession.mIsEOS = true;
            mSession.mIsStopping = true;
        }
        if (cond2) {
            mSession.mIsStopping = true;
        }
        if (cond3) {
            Log.d(TAG,"calling ASPlayer.pauseVideoDecoding at "+JDvrCommon.getCallerInfo(3));
            mASPlayer.pauseVideoDecoding();
            mASPlayer.pauseAudioDecoding();
            mSession.mHasPausedDecoding = true;
            speedTransition();
        }
        if (cond4) {
            Log.d(TAG,"calling ASPlayer.flushDvr/flush at "+JDvrCommon.getCallerInfo(3));
            mASPlayer.flushDvr();
            mASPlayer.flush();
            mJDvrFile.seek(mSession.mTargetSeekPos*1000);
            mPendingInputBuffer = null;
            mLastTrickModeTimestamp = curTs;
            mLastTrickModeTimeOffset = mSession.mTargetSeekPos*1000;
            mSession.mTargetSeekPos = null;
            mPlaybackHandler.removeCallbacks(mPtsRunnable);
        }
        if (cond5) {
            speedTransition();
            mLastTrickModeTimestamp = 0;
            mLastTrickModeTimeOffset = 0;
        }
        if (cond6) {
            if (mLastTrickModeTimestamp == 0) {
                final long playingTime = mJDvrFile.getPlayingTime();
                if (playingTime >= 0) {
                    mLastTrickModeTimeOffset = playingTime;
                }
            }
            mLastTrickModeTimestamp = curTs;
            long newOffset = (int) (mLastTrickModeTimeOffset + mSession.mTargetSpeed * 1000);
            newOffset = Math.min(newOffset,mJDvrFile.getStartTime()+mJDvrFile.duration());
            newOffset = Math.max(newOffset,mJDvrFile.getStartTime());
            Log.d(TAG,"calling ASPlayer.flushDvr/flush at "+JDvrCommon.getCallerInfo(3));
            mASPlayer.flushDvr();
            mASPlayer.flush();
            mJDvrFile.seek((int)newOffset);
            mPendingInputBuffer = null;
            mPlaybackHandler.removeCallbacks(mPtsRunnable);
            if (!mSession.mTrickModeBySeekIsOn) {
                Log.d(TAG, "calling ASPlayer.setTrickMode(BY_SEEK) at " + JDvrCommon.getCallerInfo(3));
                mASPlayer.setTrickMode(VideoTrickMode.TRICK_MODE_BY_SEEK);
                mSession.mTrickModeBySeekIsOn = true;
                Log.d(TAG,"calling ASPlayer.startFast("+mSession.mTargetSpeed+") at "+JDvrCommon.getCallerInfo(3));
                mASPlayer.startFast((float) mSession.mTargetSpeed);
            }
            mLastTrickModeTimeOffset = newOffset;
        }
        if (mSession.mTimestampOfLastProgressNotify == 0
                || curTs >= mSession.mTimestampOfLastProgressNotify + interval1) {
            notifyProgress();
            mSession.mTimestampOfLastProgressNotify = curTs;
            checkLastModifiedTime();
        }
    }
    private void handlingPausedState() {
        final long curTs = SystemClock.elapsedRealtime();
        mSession.mControllerToPause = false;
        final boolean cond1 = (mEndTime != 0 && mEndTime-mPlayingTime<300);
        final boolean cond2 = isSmoothPlaySpeed(mSession.mCurrentSpeed);
        final boolean cond3 = isSkippingPlaySpeed(mSession.mCurrentSpeed);
        final boolean cond4 = mSession.mControllerToExit;
        final boolean cond5 = (mSession.mTargetSeekPos != null);
        final boolean cond6 = mSession.mFirstVideoFrameReceived;
        final boolean cond7 = !mSession.mRecordingIsUpdatedLately;
        final boolean cond8 = !mSession.mHasPausedDecoding;
        final boolean cond9 = mSession.mFirstAudioFrameReceived;
        if (!(cond6 || cond9)) {
            final int len = injectData();
            Log.d(TAG,"injected "+len+" bytes in PAUSED state");
        }
        if (cond1 && cond7) {
            mSession.mIsEOS = true;
            mSession.mIsStopping = true;
        }
        if (cond4) {
            mSession.mIsStopping = true;
        } else if (cond2) {
            Log.d(TAG,"calling ASPlayer.stopFast at "+JDvrCommon.getCallerInfo(3));
            mASPlayer.stopFast();
            Log.d(TAG,"calling ASPlayer.pauseVideoDecoding at "+JDvrCommon.getCallerInfo(3));
            final int ret = mASPlayer.pauseVideoDecoding();
            mASPlayer.pauseAudioDecoding();
            mSession.mHasPausedDecoding = true;
            if (ret == 0) {
                speedTransition();
                mSession.mFirstVideoFrameReceived = false;
                mSession.mFirstAudioFrameReceived = false;
            } else {
                Log.e(TAG,"ASPlayer.pauseVideoDecoding returns "+ret);
            }
        } else if (cond3) {
            speedTransition();
            mSession.mFirstVideoFrameReceived = false;
            mSession.mFirstAudioFrameReceived = false;
        } else if (cond5) {
            Log.d(TAG,"calling ASPlayer.flushDvr/flush at "+JDvrCommon.getCallerInfo(3));
            mASPlayer.flushDvr();
            mASPlayer.flush();
            mJDvrFile.seek(mSession.mTargetSeekPos*1000);
            mPendingInputBuffer = null;
            mSession.mFirstVideoFrameReceived = false;
            mSession.mFirstAudioFrameReceived = false;
            mSession.mTargetSeekPos = null;
            mPlaybackHandler.removeCallbacks(mPtsRunnable);
        } else if ((cond6 || cond9) && cond8) {
            Log.d(TAG,"calling ASPlayer.pauseVideoDecoding at "+JDvrCommon.getCallerInfo(3));
            mASPlayer.pauseVideoDecoding();
            mASPlayer.pauseAudioDecoding();
            mSession.mHasPausedDecoding = true;
        }
        if (mSession.mTimestampOfLastProgressNotify == 0
                || curTs >= mSession.mTimestampOfLastProgressNotify + interval1) {
            notifyProgress();
            mSession.mTimestampOfLastProgressNotify = curTs;
            checkLastModifiedTime();
        }
    }
    private void handlingStoppingState() {
        mSession.mIsStarting = false;
        mSession.mControllerToStart = false;
        if (mSession.mIsStopping) {
            try { // Consider ASPlayer may have already been released at DTVKit side
                Log.d(TAG, "calling ASPlayer.stopVideoDecoding at " + JDvrCommon.getCallerInfo(3));
                mASPlayer.stopVideoDecoding();
                mASPlayer.stopAudioDecoding();
                Log.d(TAG, "calling ASPlayer.flushDvr at " + JDvrCommon.getCallerInfo(3));
                mASPlayer.flushDvr();
            } catch (NullPointerException e) {
                Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
            }
            mSession.mHaveStopped = true;
            if (mSession.mIsEOS) {
                Message msg = new Message();
                msg.what = JDvrPlayerEvent.NOTIFY_EOS;
                onJDvrPlayerEvent(msg);
            }
        }
    }
    private int injectData() {
        byte[] buffer = new byte [READ_LEN];
        if (mPendingInputBuffer == null) {
            // Only allow to read new data when there is no pending data.
            final int len = mJDvrFile.read(buffer,0,READ_LEN);
            //Log.d(TAG,"injectData, JDvrFile.read returns: "+len);
            if (len == 0 || len == -1) {
                return len;
            }
            if (!mSession.mVideoDecoderInitReceived) {
                ArrayList<JDvrRecorder.JDvrStreamInfo> streams = new ArrayList<>(mJDvrFile.getStreamsInfoAt(0));
                streams.removeIf(s->s.type==STREAM_TYPE_OTHER);
                IntStream.range(0,len/188).filter(i -> {
                    final boolean cond1 = (buffer[i*188] == 0x47);
                    final int pid = ((buffer[i*188+1]&0x1F)<<8)+buffer[i*188+2];
                    final boolean cond2 = streams.stream().anyMatch(s -> s.pid == pid);
                    return cond1 && cond2;
                }).forEach(i -> { buffer[i*188+1]=(byte)0x1f; buffer[i*188+2]=(byte)0xff;});
            }
            mPendingInputBuffer = new InputBuffer(buffer, 0, len);
        }
        if (mPendingInputBuffer.mBufferSize <= 0) {
            mPendingInputBuffer = null;
            return 0;
        }
        int len2;
        try {
            len2 = mASPlayer.writeData(mPendingInputBuffer,0);
            //Log.d(TAG,"injectData, ASPlayer.writeData returns: "+len2);
        } catch (NullPointerException e) {
            //Log.w(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
            return 0;
        }
        if (len2 == mPendingInputBuffer.mBufferSize) {
            mPendingInputBuffer = null;
        } else if (len2 > 0 && len2 < mPendingInputBuffer.mBufferSize) {
            mPendingInputBuffer.mOffset += len2;
            mPendingInputBuffer.mBufferSize -= len2;
        } else if (len2 < 0) {
            len2 = 0;
        }
        //Log.d(TAG,"injectData, injected "+len2+" bytes, remains:"+(mPendingInputBuffer!=null?mPendingInputBuffer.mBufferSize:0)+" bytes");
        return len2;
    }
    private boolean isSmoothPlaySpeed(double speed) {
        return (speed > 0.0d && speed <= 2.0d);
    }
    private boolean isSkippingPlaySpeed(double speed) {
        return (speed < 0.0d || speed > 2.0d);
    }
    private void speedTransition() {
        Log.d(TAG,"Speed transition: "+mSession.mCurrentSpeed+" => "+mSession.mTargetSpeed
                +" (at "+JDvrCommon.getCallerInfo(4)+")");
        mSession.mCurrentSpeed = mSession.mTargetSpeed;
    }
    private boolean innerSeek(int seconds) {
        Log.d(TAG,"JDvrPlayer.innerSeek to "+seconds+"s");
        mPlaybackHandler.sendMessage(
                mPlaybackHandler.obtainMessage(JDvrPlaybackStatus.CONTROLLER_STATUS_TO_SEEK,seconds));
        return true;
    }
    private void notifyProgress() {
        //Log.d(TAG,"JDvrPlayer.notifyProgress");
        final long playingTime = mJDvrFile.getPlayingTime();
        if (playingTime == -1) {
            Log.w(TAG,"Failed to get playing time, so skip notifying progress this time");
            return;
        }
        JDvrPlaybackProgress progress = new JDvrPlaybackProgress();
        progress.sessionNumber = mSession.mSessionNumber;
        progress.state = mSession.mState;
        progress.speed = mSession.mCurrentSpeed;
        progress.duration = mJDvrFile.duration();
        progress.startTime = mJDvrFile.getStartTime();
        progress.endTime = progress.startTime + progress.duration;
        progress.currTime = playingTime;
        progress.numberOfSegments = mJDvrFile.getNumberOfSegments();
        progress.firstSegmentId = mJDvrFile.getFirstSegmentId();
        progress.lastSegmentId = mJDvrFile.getLastSegmentId();
        progress.currSegmentId = mJDvrFile.getSegmentIdBeingRead();
        mPlayingTime = progress.currTime;
        mEndTime = progress.endTime;
        Message msg = new Message();
        msg.what = JDvrPlayerEvent.NOTIFY_PROGRESS;
        msg.obj = progress;
        onJDvrPlayerEvent(msg);
    }
    private void checkLastModifiedTime() {
        String statPath = mJDvrFile.getPathPrefix()+".stat";
        File statFile = new File(statPath);
        final long lastModified = statFile.lastModified();
        final long curTs = SystemClock.elapsedRealtime();
        mLastModifiedRecords.add(new Pair<>(curTs, lastModified));
        mLastModifiedRecords.removeIf(p -> p.first + 3*1000 <= curTs);
        if (mLastModifiedRecords.size() == 1) {
            return;
        }
        mSession.mRecordingIsUpdatedLately = !mLastModifiedRecords.stream().allMatch(p -> p.second == lastModified);
    }
}

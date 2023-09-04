package com.droidlogic.jdvrlib;

import static com.amlogic.asplayer.api.ASPlayer.INFO_BUSY;

import android.media.tv.tuner.filter.Filter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import com.amlogic.asplayer.api.ASPlayer;
import com.amlogic.asplayer.api.AudioParams;
import com.amlogic.asplayer.api.InputBuffer;
import com.amlogic.asplayer.api.TsPlaybackListener;
import com.amlogic.asplayer.api.VideoTrickMode;
import com.droidlogic.jdvrlib.JDvrCommon.*;
import com.droidlogic.jdvrlib.OnJDvrPlayerEventListener.JDvrPlayerEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Executor;

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
        private Integer mTargetSeekPos = null;      // in seconds
        private boolean mFirstVideoFrameReceived = false;
        private boolean mFirstAudioFrameReceived = false;
        private long mTimestampOfLastProgressNotify = 0;
        private boolean mRecordingIsUpdatedLately = true;
        private boolean mTrickModeBySeekIsOn = false;

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
        public long currTime;    // in ms
        public long startTime;   // in ms
        public long endTime;     // in ms
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
    public static class JDvrAudioTrack extends JDvrAudioTriple {
        Filter filter = null;
        AudioParams params = null;
        public JDvrAudioTrack(int pid, int format) {
            super(pid,format);
        }
        public JDvrAudioTriple toAudioTriple() {
            return new JDvrAudioTriple(pid,format);
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
    private int mAvHwSyncId = -1;
    private ArrayList<JDvrAudioTrack> mAudioTracks = new ArrayList<>();
    private int mActiveAudioTrackIndex = -1;

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
                Log.e(TAG, "Exception: " + e);
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
                Log.e(TAG, "Exception: " + e);
                e.printStackTrace();
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
            } else if (playbackEvent instanceof TsPlaybackListener.PtsEvent) {
                if (count++%8 == 0) { // Ignore 7/8 PTS events
                    mLastPts = ((PtsEvent) playbackEvent).mPts * 90 / 1000;     // Original PTS in 90KHz
                    if (mLastPts > 0) {
                        if (mPlaybackHandler.hasCallbacks(mPtsRunnable)) {
                            mPlaybackHandler.removeCallbacks(mPtsRunnable);
                        }
                        mPlaybackHandler.post(mPtsRunnable);
                    }
                }
            }
        }
        private long count = 0;
    };
    private final Runnable mPtsRunnable = new Runnable() {
        @Override
        public void run() {
            // Here the mPts from ASPlayer is in microsecond (us)
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
        mPlaybackHandler.sendMessage(
                mPlaybackHandler.obtainMessage(JDvrPlaybackStatus.CONTROLLER_STATUS_TO_EXIT, null));
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
            if (cond1) {
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
            mASPlayer.flushDvr();
            Log.d(TAG,"calling ASPlayer.startVideoDecoding at "+JDvrCommon.getCallerInfo(3));
            if (mASPlayer.startVideoDecoding() < 0) {
                Log.e(TAG, "ASPlayer.startVideoDecoding fails");
                return;
            }
            if (mASPlayer.startAudioDecoding() < 0) {
                Log.e(TAG, "ASPlayer.startAudioDecoding fails");
                return;
            }
            if (mSession.mControllerToPause) {
                Log.d(TAG,"calling ASPlayer.setTrickMode(BY_SEEK) at "+JDvrCommon.getCallerInfo(3));
                mASPlayer.setTrickMode(VideoTrickMode.TRICK_MODE_BY_SEEK);
                Log.d(TAG,"calling ASPlayer.startFast("+mSession.mTargetSpeed+") at "+JDvrCommon.getCallerInfo(3));
                mASPlayer.startFast((float)mSession.mTargetSpeed);
                mSession.mTrickModeBySeekIsOn = true;
            }
            mSession.mIsStarting = true;
            mSession.mHaveStopped = false;
        } else if (mSession.mControllerToExit || mSession.mIsEOS) {
            mJDvrFile.close();
            mJDvrFile = null;
            mASPlayer.removePlaybackListener(mTsPlaybackListener);
            mPlaybackThread.quit();
            mSession.mControllerToExit = false;
            mSession.mIsEOS = false;
        }
    }
    private void handlingStartingState() {
        final boolean cond1 = (-1 == injectData());
        final boolean cond2 = !mSession.mRecordingIsUpdatedLately;
        if (cond1 && cond2) {
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
            mSession.mTargetSeekPos = null;
            // Clear outdated PTS events cached in the queue prior to seek.
            if (mPlaybackHandler.hasCallbacks(mPtsRunnable)) {
                mPlaybackHandler.removeCallbacks(mPtsRunnable);
            }
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
        //final boolean cond8 = mSession.mFirstVideoFrameReceived;
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
            speedTransition();
        }
        if (cond4) {
            Log.d(TAG,"calling ASPlayer.flushDvr/flush at "+JDvrCommon.getCallerInfo(3));
            mASPlayer.flushDvr();
            mASPlayer.flush();
            mJDvrFile.seek(mSession.mTargetSeekPos*1000);
            mLastTrickModeTimestamp = curTs;
            mLastTrickModeTimeOffset = mSession.mTargetSeekPos*1000;
            mSession.mTargetSeekPos = null;
            // Clear outdated PTS events cached in the queue prior to seek.
            if (mPlaybackHandler.hasCallbacks(mPtsRunnable)) {
                mPlaybackHandler.removeCallbacks(mPtsRunnable);
            }
        }
        if (cond5) {
            speedTransition();
            Log.d(TAG,"calling ASPlayer.setTrickMode(SMOOTH) at "+JDvrCommon.getCallerInfo(3));
            mASPlayer.setTrickMode(VideoTrickMode.TRICK_MODE_SMOOTH);
            mSession.mTrickModeBySeekIsOn = false;
            Log.d(TAG,"calling ASPlayer.stopFast at "+JDvrCommon.getCallerInfo(3));
            mASPlayer.stopFast();
            mLastTrickModeTimestamp = 0;
            mLastTrickModeTimeOffset = 0;
        }
        if (cond6) {
            if (mLastTrickModeTimestamp == 0) {
                mLastTrickModeTimeOffset = mJDvrFile.getPlayingTime();
            }
            mLastTrickModeTimestamp = curTs;
            long newOffset = (int) (mLastTrickModeTimeOffset + mSession.mTargetSpeed * 1000);
            newOffset = Math.min(newOffset,mJDvrFile.getStartTime()+mJDvrFile.duration());
            newOffset = Math.max(newOffset,mJDvrFile.getStartTime());
            Log.d(TAG,"calling ASPlayer.flushDvr/flush at "+JDvrCommon.getCallerInfo(3));
            mASPlayer.flushDvr();
            mASPlayer.flush();
            mJDvrFile.seek((int)newOffset);
            // Clear outdated PTS events cached in the queue prior to seek.
            if (mPlaybackHandler.hasCallbacks(mPtsRunnable)) {
                mPlaybackHandler.removeCallbacks(mPtsRunnable);
            }
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
        injectData();
        final boolean cond1 = (mEndTime != 0 && mEndTime-mPlayingTime<300);
        final boolean cond2 = isSmoothPlaySpeed(mSession.mCurrentSpeed);
        final boolean cond3 = isSkippingPlaySpeed(mSession.mCurrentSpeed);
        final boolean cond4 = mSession.mControllerToExit;
        final boolean cond5 = (mSession.mTargetSeekPos != null);
        final boolean cond6 = mSession.mFirstVideoFrameReceived;
        final boolean cond7 = !mSession.mRecordingIsUpdatedLately;
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
            if (ret == 0) {
                speedTransition();
            } else {
                Log.e(TAG,"ASPlayer.pauseVideoDecoding returns "+ret);
            }
        } else if (cond3) {
            speedTransition();
        } else if (cond5) {
            Log.d(TAG,"calling ASPlayer.flushDvr/flush at "+JDvrCommon.getCallerInfo(3));
            mASPlayer.flushDvr();
            mASPlayer.flush();
            mJDvrFile.seek(mSession.mTargetSeekPos*1000);
            mSession.mFirstVideoFrameReceived = false;
            mSession.mTargetSeekPos = null;
            // Clear outdated PTS events cached in the queue prior to seek.
            if (mPlaybackHandler.hasCallbacks(mPtsRunnable)) {
                mPlaybackHandler.removeCallbacks(mPtsRunnable);
            }
        } else if (cond6) {
            Log.d(TAG,"calling ASPlayer.pauseVideoDecoding at "+JDvrCommon.getCallerInfo(3));
            mASPlayer.pauseVideoDecoding();
            mASPlayer.pauseAudioDecoding();
            mSession.mFirstVideoFrameReceived = false;
        }
        if (mSession.mTimestampOfLastProgressNotify == 0
                || curTs >= mSession.mTimestampOfLastProgressNotify + interval1) {
            notifyProgress();
            mSession.mTimestampOfLastProgressNotify = curTs;
            checkLastModifiedTime();
        }
    }
    private void handlingStoppingState() {
        if (mSession.mIsStopping) {
            Log.d(TAG,"calling ASPlayer.stopVideoDecoding at "+JDvrCommon.getCallerInfo(3));
            mASPlayer.stopVideoDecoding();
            mASPlayer.stopAudioDecoding();
            Log.d(TAG,"calling ASPlayer.flushDvr at "+JDvrCommon.getCallerInfo(3));
            mASPlayer.flushDvr();
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
        InputBuffer inputBuffer;
        if (mPendingInputBuffer == null) {
            final int len = mJDvrFile.read(buffer,0,READ_LEN);
            if (len == 0 || len == -1) {
                return len;
            }
            inputBuffer = new InputBuffer(buffer,0,len);
        } else {
            inputBuffer = mPendingInputBuffer;
        }
        int len2 = 0;
        try {
            len2 = mASPlayer.writeData(inputBuffer,0);
        } catch (NullPointerException e) {
            mPendingInputBuffer = inputBuffer;
            //Log.w(TAG, "Exception: " + e);
            return 0;
        }
        if (len2 == inputBuffer.mBufferSize) {
            mPendingInputBuffer = null;
        } else if (len2 > 0 && len2 < inputBuffer.mBufferSize) {
            inputBuffer.mOffset += len2;
            inputBuffer.mBufferSize -= len2;
            mPendingInputBuffer = inputBuffer;
        } else if (len2 < 0) {
            if (len2 == INFO_BUSY) {
                mPendingInputBuffer = inputBuffer;
            }
            len2 = 0;
        }
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
        JDvrPlaybackProgress progress = new JDvrPlaybackProgress();
        progress.sessionNumber = mSession.mSessionNumber;
        progress.state = mSession.mState;
        progress.speed = mSession.mCurrentSpeed;
        progress.duration = mJDvrFile.duration();
        progress.startTime = mJDvrFile.getStartTime();
        progress.endTime = progress.startTime + progress.duration;
        progress.currTime = mJDvrFile.getPlayingTime();
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

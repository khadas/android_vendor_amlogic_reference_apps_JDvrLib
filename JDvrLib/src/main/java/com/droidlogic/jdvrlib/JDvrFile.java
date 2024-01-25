package com.droidlogic.jdvrlib;

import android.os.SystemClock;
import android.util.JsonReader;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.droidlogic.jdvrlib.JDvrCommon.*;
import com.droidlogic.jdvrlib.JDvrRecorder.JDvrStreamInfo;

public class JDvrFile {
    final static private String TAG = JDvrFile.class.getSimpleName();
    final private String mPathPrefix;
    final private int mType;  // 0: for normal recording, 1: for timeshift recording, 2: for playback
    private long mLimitSize = Long.MAX_VALUE;
    private int mLimitSeconds = Integer.MAX_VALUE;
    private final ArrayList<JDvrSegment> mSegments = new ArrayList<>();
    final private String mStatPath;
    final private String mListPath;
    final private String mLockPath;
    FileChannel mLockChannel;
    FileLock mLock;
    private long mTimestampOfLastIndexWrite = 0;
    private long mTimestampOfOrigin = 0;
    private long mTotalObsoletePausedTime = 0;   // in ms
    final public static int mMinIndexInterval = 300;  // in ms
    final public static int mPtsMargin = mMinIndexInterval * 90 * 2;  // in 90KHz
    private ArrayList<JDvrStreamInfo> mCurrentRecordingStreams = new ArrayList<>();
    private boolean mPidHasChanged = false;
    private final Comparator<JDvrSegment> mStartTimeCmp = Comparator.comparingLong(JDvrSegment::getStartTime);
    private int mSegmentIdBeingRead = 0;
    private int mLastLoadedSegmentId = -1;
    private long mPlayingTime = 0L;     // in ms
    private long mLastPts = 0L;

    // Public APIs
    /**
     * Constructs a JDvrFile instance for normal recording.
     * A JDvrFile object represents a PVR recording which consists of a series of associated files
     * like ts, index, status and list files.
     *
     * @param path_prefix The path prefix of a recording. All the associated files use the prefix
     *                    as part of their file path.
     * @param trunc whether to clean the recording if it already exists.
     */
    public JDvrFile(String path_prefix, boolean trunc) {
        mType = 0;
        final String dirname = path_prefix.substring(0,path_prefix.lastIndexOf('/'));
        File dir = new File(dirname);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("Unable to create " + dir.getAbsolutePath());
        }
        mPathPrefix = path_prefix;
        mStatPath = mPathPrefix + ".stat";
        mListPath = mPathPrefix + ".list";
        mLockPath = mPathPrefix + ".lock";
        commonProcedure(trunc);
    }
    /**
     * Constructs a JDvrFile instance for timeshift recording.
     * A JDvrFile object represents a PVR recording which consists of a series of associated files
     * like ts, index, status and list files.
     *
     * @param path_prefix The path prefix of a recording. All the associated files use the prefix
     *                    as part of their file path.
     * @param limit_size Limited recording size in bytes. If recording reaches the limitation,
     *                   the earliest segment of the recording will be removed to make some room.
     * @param limit_seconds Limited recording duration in seconds. If recording reaches the limitation,
     *                   the earliest segment of the recording will be removed to make some room.
     * @param trunc whether to clean the recording if it already exists.
     */
    public JDvrFile(String path_prefix, long limit_size, int limit_seconds, boolean trunc) {
        mType = 1;
        final String dirname = path_prefix.substring(0,path_prefix.lastIndexOf('/'));
        File dir = new File(dirname);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("Unable to create " + dir.getAbsolutePath());
        }
        mLimitSize = (limit_size == 0 ? Long.MAX_VALUE : Math.abs(limit_size));
        mLimitSeconds = (limit_seconds == 0 ? Integer.MAX_VALUE : Math.abs(limit_seconds));
        mPathPrefix = path_prefix;
        mStatPath = mPathPrefix + ".stat";
        mListPath = mPathPrefix + ".list";
        mLockPath = mPathPrefix + ".lock";
        commonProcedure(trunc);
    }
    /**
     * Constructs a JDvrFile instance for playback.
     * A JDvrFile object represents a PVR recording which consists of a series of associated files
     * like ts, index, status and list files.
     *
     * @param path_prefix The path prefix of a recording. All the associated files use the prefix
     *                    as part of their file path.
     */
    public JDvrFile(String path_prefix) {
        mType = 2;
        final String dirname = path_prefix.substring(0,path_prefix.lastIndexOf('/'));
        File dir = new File(dirname);
        if (!dir.exists()) {
            throw new RuntimeException("Unable to visit " + dir.getAbsolutePath());
        }
        mPathPrefix = path_prefix;
        mStatPath = mPathPrefix + ".stat";
        mListPath = mPathPrefix + ".list";
        mLockPath = mPathPrefix + ".lock";
        if (!createLockIfNotExist(mLockPath)) {
            throw new RuntimeException("Cannot create .lock file");
        }
        try {
            mLockChannel = new RandomAccessFile(mLockPath, "rw").getChannel();
            Log.d(TAG,"lock(100-200) for playback");
            mLock = mLockChannel.tryLock(100, 200, false);
            if (mLock == null) {
                throw new RuntimeException("Cannot acquire lock for playback");
            }
            File statFile = new File(mStatPath);
            if (!statFile.exists() || statFile.length() == 0) {
                repairFiles(path_prefix);
            }
            if (!(load() || load() || load())) {
                Log.d(TAG,"unlock(100-200) for playback");
                mLock.release();
                throw new RuntimeException("Fails to load recording files");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    public static void loadDvrJni() {
        Log.d(TAG, "loadDvrJni()");
        try {
            System.loadLibrary("jdvrlib-jni");
        } catch (UnsatisfiedLinkError e) {
            Log.d(TAG, "tuner JNI library not found!");
        }
    }
    private boolean load() {
        if (mSegments.size() > 0) {
            return true;
        }
        Log.i(TAG,"loading recording " + mPathPrefix);
        File listFile = new File(mListPath);
        if (listFile.exists()) {
            RandomAccessFile listStream;
            try {
                listStream = new RandomAccessFile(listFile, "r");
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
            try {
                for (String line = listStream.readLine();
                     line != null;
                     line = listStream.readLine()) {
                    String[] tokens = line.split(",");
                    final int segment_id = Integer.parseInt(tokens[0]);
                    final long start_time = Long.parseLong(tokens[1]);
                    final long duration = Long.parseLong(tokens[2]);
                    JDvrSegment segment = new JDvrSegment(mPathPrefix, segment_id, (mType == 2 ? 1 : 0), 0);
                    segment.setStartTime(start_time);
                    segment.setDuration(duration);
                    mSegments.add(segment);
                    mLastLoadedSegmentId = segment.id();
                }
            } catch (IOException | NumberFormatException e) {
                Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
                e.printStackTrace();
                return false;
            }
            if (mSegments.size() == 0) {
                Log.e(TAG,"Fails to load any segment");
                return false;
            }
            mSegmentIdBeingRead = mSegments.get(0).id();
        }
        if (mType == 2) {
            try {
                final String[] lines = Files.readAllLines(Paths.get(mStatPath)).toArray(new String[0]);
                for (String line : lines) {
                    JsonReader reader = new JsonReader(new StringReader(line));
                    long limitSize = Long.MAX_VALUE;
                    int limitDuration = Integer.MAX_VALUE;
                    boolean hit = false;
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String name = reader.nextName();
                        if (name.equals("limit_size")) {
                            limitSize = reader.nextLong();
                            hit = true;
                        } else if (name.equals("limit_duration")) {
                            limitDuration = reader.nextInt();
                            hit = true;
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endObject();
                    if (hit) {
                        mLimitSize = (limitSize > 0 ? limitSize : Long.MAX_VALUE);
                        mLimitSeconds = (limitDuration > 0 ? limitDuration : Integer.MAX_VALUE);
                        break;
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
                e.printStackTrace();
                return false;
            }
        }
        return true;
    }
    /**
     * Check if current recording file is for a timeshift recording/playback.
     *
     * @return true if it is for a timeshift recording/playback, or false if it is for a normal recording/playback.
     */
    public boolean isTimeshift() {
        final boolean cond1 = isEffectiveLimitSize(mLimitSize);
        final boolean cond2 = isEffectiveLimitDuration(mLimitSeconds);
        final boolean cond3 = (mType != 0);
        return (cond1 || cond2) && cond3;
    }
    /**
     * Get the full ts file path of a specific segment.
     * @param segment_id segment ID
     * @return The full path of ts file, or null if segment does not exist.
     */
    public String getTsFilename(int segment_id) {
        JDvrSegment seg = mSegments.stream().filter(s -> (s.id() == segment_id)).findFirst().orElse(null);
        return (seg != null) ? seg.getTsPath() : null;
    }
    /**
     * Get the full index file path of a specific segment.
     * @param segment_id segment ID
     * @return The full path of index file, or null if segment does not exist.
     */
    public String getIndexFilename(int segment_id) {
        JDvrSegment seg = mSegments.stream().filter(s -> (s.id() == segment_id)).findFirst().orElse(null);
        return (seg != null) ? seg.getIndexPath() : null;
    }
    /**
     * Get the path prefix in use.
     * @return the path prefix.
     */
    public String getPathPrefix() {
        return mPathPrefix;
    }
    /**
     * Add a new segment to the recording. It will be the last one at end of segments list.
     * @return new segment id
     */
    public int addSegment() {
        final int newID = getLastSegmentId() + 1;
        JDvrSegment segment = new JDvrSegment(mPathPrefix, newID, (mType < 2) ? 0 : 1, 0);
        segment.setLastSegment(true);
        mSegments.add(segment);
        if (mSegments.size()>0) {
            mSegments.get(mSegments.size()-1).setLastSegment(false);
        }
        Log.i(TAG,"addSegment, id:"+segment.id());
        return newID;
    }
    /**
     * Remove a specific segment from the recording.
     * @param segment_id the segment id to be removed.
     * @return true if operation is successful, or false if the segment does not exist.
     */
    public boolean removeSegment(int segment_id) {
        if (mType == 0) { throw new RuntimeException("Cannot do this under Normal Recording situation"); }
        JDvrSegment seg = mSegments.stream().filter(s -> (s.id() == segment_id)).findFirst().orElse(null);
        if (seg == null) {
            return false;
        }
        seg.close();
        if (mType == 1) {
            seg.delete();
        }
        mSegments.remove(seg);
        return true;
    }
    /**
     * Get first segment id of the recording.
     * @return first segment's segment id, or -1 if there is no segment at all.
     */
    public int getFirstSegmentId() {
        return (mSegments.size() > 0) ? mSegments.get(0).id() : -1;
    }
    /**
     * Get last segment id of the recording.
     * @return last segment's segment id, or -1 if there is no segment at all.
     */
    public int getLastSegmentId() {
        return (mSegments.size() > 0) ? mSegments.get(mSegments.size()-1).id() : -1;
    }
    /**
     * Get number of segments of the recording.
     * @return number of segments.
     */
    public int getNumberOfSegments() {
        return mSegments.size();
    }
    /**
     * Get the follower's id of a specific segment.
     * @param segment_id the referring segment id.
     * @return the segment id of its following segment, or -1 if the given segment does not exist.
     */
    public int getNextSegmentId(int segment_id) {
        final JDvrSegment seg = mSegments.stream().filter(s -> (s.id() == segment_id)).findFirst().orElse(null);
        return (seg != null) ? seg.id() + 1 : -1;
    }
    /**
     * Get the start time of a recording.
     * Start time is relative to origin at which the recording was initially made. In line with
     * this, start time of a normal recording shall always be 0. Under timeshift situations, as
     * some of its oldest segments have been removed due to size/duration limitation, its start
     * time calculation need to consider all removed segments.
     *
     * @return the start time of a recording in ms.
     */
    public long getStartTime() {
        JDvrSegment segment = getFirstSegment();
        if (segment == null) {
            return -1;
        }
        return segment.getStartTime();
    }
    /**
     * Delete all associated files of a recording including ts/index/status/list.
     * A recording that is being recorded or played cannot be deleted.
     *
     * @return true if operation is successful, or false otherwise.
     */
    public boolean delete() {
        if (mLockChannel != null || mLock != null) {
            Log.e(TAG,"Cannot delete as JDvrFile seems still in use. Need to call JDvrFile.close.");
            return false;
        }
        return delete2(mPathPrefix);
    }
    /**
     * Delete all associated files of a recording including ts/index/status/list.
     * A recording that is being recorded or played cannot be deleted.
     * This is the static version of delete() that does not require to be used with associated
     * JDvrFile object for convenience.
     *
     * @param pathPrefix the path prefix of the recording to be removed.
     * @return true if operation is successful, or false otherwise.
     */
    public static boolean delete2(String pathPrefix) {
        final String lockPath = pathPrefix + ".lock";
        if (!createLockIfNotExist(lockPath)) {
            Log.e(TAG,"Cannot create .lock file");
            return false;
        }
        try {
            FileChannel lockChannel = new RandomAccessFile(lockPath, "rw").getChannel();
            Log.d(TAG,"lock(0-200) for delete");
            FileLock lock = lockChannel.tryLock(0,200,false);
            if (lock == null) {
                return false;
            }
            removeAssociatedFiles(pathPrefix);
            Log.d(TAG,"unlock(0-200) for delete");
            lock.release();
            lockChannel.close();
        } catch (IOException e) {
            Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
            e.printStackTrace();
            return false;
        } catch (OverlappingFileLockException e) {
            Log.w(TAG,"trying to acquire a lock but it has already been held by other");
            return false;
        }
        File lockFile = new File(lockPath);
        lockFile.delete();
        return true;
    }
    /**
     * Get recording size in bytes.
     * @return recording size in bytes.
     */
    public long size()
    {
        if (mSegments.size() == 0) {
            return 0L;
        }
        return mSegments.stream().mapToLong(JDvrSegment::size).sum();
    }
    public static long size2(String pathPrefix) {
        long ret = 0L;
        final String statPath = pathPrefix + ".stat";
        try {
            final String[] lines = Files.readAllLines(Paths.get(statPath)).toArray(new String[0]);
            for (String line : lines) {
                JsonReader reader = new JsonReader(new StringReader(line));
                boolean hit = false;
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (name.equals("size")) {
                        ret = reader.nextLong();
                        hit = true;
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
                if (hit) {
                    break;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
            e.printStackTrace();
            return 0L;
        }
        return ret;
    }
    /**
     * Get recording duration in ms.
     * @return recording duration in ms.
     */
    public long duration()
    {
        return mSegments.stream().mapToLong(JDvrSegment::duration).sum();
    }
    public static long duration2(String pathPrefix) {
        long ret = 0L;
        final String statPath = pathPrefix + ".stat";
        File statFile = new File(statPath);
        if (!statFile.exists() || statFile.length() == 0) {
            repairFiles(pathPrefix);
        }
        try {
            final String[] lines = Files.readAllLines(Paths.get(statPath)).toArray(new String[0]);
            for (String line : lines) {
                JsonReader reader = new JsonReader(new StringReader(line));
                boolean hit = false;
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (name.equals("duration")) {
                        ret = reader.nextLong();
                        hit = true;
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
                if (hit) {
                    break;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
            e.printStackTrace();
            return 0L;
        }
        return ret;
    }
    /**
     * Close all segment files including ts/index.
     */
    public void close() {
        if (mSegments.size() > 0) {
            mSegments.forEach(JDvrSegment::close);
            if (mType<2) {
                try {
                    updateListFile();
                } catch (IOException e) {
                    Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
                }
            }
        }
        try {
            if (mLock.isValid()) {
                if (mType == 0) {
                    Log.d(TAG,"unlock for recording");
                } else if (mType == 1) {
                    Log.d(TAG, "unlock for timeshift recording");
                } else if (mType == 2) {
                    Log.d(TAG, "unlock for playback");
                }
                mLock.release();
                mLock = null;
            }
            if (mLockChannel != null) {
                mLockChannel.close();
                mLockChannel = null;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    public long getLimitSize() {
        return mLimitSize;
    }
    public int getLimitDuration() {
        return mLimitSeconds;
    }
    /**
     * Get video PID of the recording.
     *
     * @return the PID of video if successful or 0x1fff if failed.
     */
    public int getVideoPID() {
        if (mType < 2) { throw new RuntimeException("Cannot do this under Recording situation"); }
        JDvrSegment seg = mSegments.stream().filter(s -> (s.id() == mSegmentIdBeingRead)).findFirst().orElse(null);
        return (seg != null) ? seg.getVideoPid() : 0x1fff;
    }
    /**
     * Get video MIME type of the recording.
     *
     * @return the MIME type string of video if successful or null if failed.
     */
    public String getVideoMIMEType() {
        if (mType < 2) { throw new RuntimeException("Cannot do this under Recording situation"); }
        JDvrSegment seg = mSegments.stream().filter(s -> (s.id() == mSegmentIdBeingRead)).findFirst().orElse(null);
        return (seg != null) ? seg.getVideoMIMEType() : null;
    }
    /**
     * Get video format of the recording.
     *
     * @return the format of video if successful or VIDEO_FORMAT_UNDEFINED if failed.
     */
    public int getVideoFormat() {
        if (mType < 2) { throw new RuntimeException("Cannot do this under Recording situation"); }
        JDvrSegment seg = mSegments.stream().filter(s -> (s.id() == mSegmentIdBeingRead)).findFirst().orElse(null);
        return (seg != null) ? seg.getVideoFormat() : JDvrVideoFormat.VIDEO_FORMAT_UNDEFINED;
    }
    /**
     * Get primary audio PID of the recording.
     *
     * @return the PID of audio if successful or 0x1fff if failed.
     */
    public int getAudioPID() {
        if (mType < 2) { throw new RuntimeException("Cannot do this under Recording situation"); }
        JDvrSegment seg = mSegments.stream().filter(s -> (s.id() == mSegmentIdBeingRead)).findFirst().orElse(null);
        return (seg != null) ? seg.getAudioPID() : 0x1fff;
    }
    /**
     * Get primary audio MIME type of the recording.
     *
     * @return the MIME type string of audio if successful or null if failed.
     */
    public String getAudioMIMEType() {
        if (mType < 2) { throw new RuntimeException("Cannot do this under Recording situation"); }
        JDvrSegment seg = mSegments.stream().filter(s -> (s.id() == mSegmentIdBeingRead)).findFirst().orElse(null);
        return (seg != null) ? seg.getAudioMIMEType() : null;
    }
    /**
     * Get audio format of the recording.
     *
     * @return the format of audio if successful or AUDIO_FORMAT_UNDEFINED if failed.
     */
    public int getAudioFormat() {
        if (mType < 2) { throw new RuntimeException("Cannot do this under Recording situation"); }
        JDvrSegment seg = mSegments.stream().filter(s -> (s.id() == mSegmentIdBeingRead)).findFirst().orElse(null);
        return (seg != null) ? seg.getAudioFormat() : JDvrAudioFormat.AUDIO_FORMAT_UNDEFINED;
    }

    // Private APIs
    public int write (byte[] buffer, int offset, int size, long pts) throws IOException {
        if (mType == 2) { throw new RuntimeException("Cannot do this under Playback situation"); }
        final long curTs = SystemClock.elapsedRealtime();
        JDvrSegment lastSegment = null;
        // 1. Add a segment if necessary
        {
            final boolean cond1 = (mSegments.size() == 0);
            boolean cond2 = false;
            boolean cond3 = false;
            if (!cond1) {
                lastSegment = getLastSegment();
                cond2 = (lastSegment.size() + size > JDvrSegment.getMaxSegmentSize());
                cond3 = (lastSegment.id() <= mLastLoadedSegmentId); // in case of appending recording
            }
            if (cond1 || cond2 || cond3) {
                if (lastSegment != null) {
                    // write last index
                    final long timeElapsed = cond1 ? 0 : curTs - mTimestampOfLastIndexWrite;
                    final String line = String.format(Locale.US,"{\"time\":%d, \"offset\":%d, \"pts\":%d}\n",
                            lastSegment.duration()+timeElapsed,lastSegment.size(),pts);
                    lastSegment.writeIndex(line.getBytes(), line.length());
                    updateStatFile();
                    updateListFile();
                }
                addSegment();
                mTimestampOfLastIndexWrite = curTs;
                if (cond1) {
                    mTimestampOfOrigin = curTs;
                    Log.d(TAG,"Origin timestamp is " + mTimestampOfOrigin);
                }
            }
        }
        JDvrSegment currSegment = getLastSegment();
        // 2. Remove a segment if necessary
        {
            final boolean cond1 = (size()+size > mLimitSize);
            final boolean cond2 = ((duration()+curTs-mTimestampOfLastIndexWrite)/1000 >= mLimitSeconds);
            final boolean cond3 = isTimeshift();
            final boolean cond4 = (mSegments.size()>1);
            if ((cond1 || cond2) && cond3 && cond4) {
                mTotalObsoletePausedTime += mSegments.get(0).getPausedTime();
                removeSegment(getFirstSegmentId());
            }
        }
        // 3. Update index file if necessary
        final long newSize = size() + size;
        {
            final boolean cond1 = (currSegment.size() == 0);
            final long timeElapsed = cond1 ? 0 : curTs - mTimestampOfLastIndexWrite;
            final long totalTimeSpent = curTs - mTimestampOfOrigin;
            final long totalTimePaused = mTotalObsoletePausedTime + mSegments.stream().mapToLong(JDvrSegment::getPausedTime).sum();
            final long timeOffsetFromOrigin = totalTimeSpent - totalTimePaused;
            final long timeOffsetOfSegment = Math.min(currSegment.duration()+((timeElapsed<2*mMinIndexInterval)?timeElapsed:mMinIndexInterval),timeOffsetFromOrigin);
            final boolean cond2 = mPidHasChanged;
            if (cond1 || cond2) {
                final String streamStr = mCurrentRecordingStreams.stream().map(Object::toString).collect(Collectors.joining(","));
                final String line = String.format(Locale.US,
                        "{\"time\":%d, \"time_offset_from_origin\":%d, \"id\":%d, \"nb_pids\":%d, \"pids\":[%s]}\n",
                        timeOffsetOfSegment,timeOffsetFromOrigin,currSegment.id(),mCurrentRecordingStreams.size(),streamStr);
                currSegment.writeIndex(line.getBytes(),line.length());
                mPidHasChanged = false;
            }
            final boolean cond3 = (newSize <= mLimitSize);
            final boolean cond4 = (curTs - mTimestampOfLastIndexWrite >= mMinIndexInterval);
            if (cond1 || (cond3 && cond4)) {
                final String line = String.format(Locale.US,"{\"time\":%d, \"offset\":%d, \"pts\":%d}\n",
                        timeOffsetOfSegment,currSegment.size(),pts);
                currSegment.writeIndex(line.getBytes(), line.length());
                mTimestampOfLastIndexWrite = curTs;
                updateStatFile();
                updateListFile();
            }
        }
        // 4. Write data to TS file
        int ret = 0;
        {
            final boolean cond1 = (newSize <= mLimitSize);
            if (cond1) {
                ret = currSegment.write(buffer, offset, size);
            }
        }
        return ret;
    }
    public int read(byte[] buffer, int offset, int size) {
        if (mType < 2) { throw new RuntimeException("Cannot do this under Recording situation"); }
        JDvrSegment seg = mSegments.stream().filter(s -> (s.id() == mSegmentIdBeingRead)).findFirst().orElse(null);
        if (seg == null) {
            return 0;
        }
        int n;
        try {
            n = seg.read(buffer,offset,size);
        } catch (IOException e) {
            Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
            e.printStackTrace();
            return 0;
        }
        if (n == -1) { // In case current segment has reached end
            final int nextSegmentId = mSegmentIdBeingRead + 1;
            seg = mSegments.stream().filter(s -> (s.id() == nextSegmentId)).findFirst().orElse(null);
            if (seg != null) {
                mSegmentIdBeingRead += 1;
                String line =  String.format(Locale.US,"reading segment transition in playback: %04d => %04d",
                        mSegmentIdBeingRead-1, mSegmentIdBeingRead);
                Log.d(TAG,line);
                seg.seek(0L);
                try {
                    n = seg.read(buffer,offset,size);
                } catch (IOException e) {
                    Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
                    e.printStackTrace();
                    return 0;
                }
            }
        }
        return n;
    }
    public boolean seek(int ms) {
        if (mType < 2) { throw new RuntimeException("Cannot do this under Recording situation"); }
        JDvrSegment ref = new JDvrSegment("",-1, 2, 0);
        ref.setStartTime(ms);
        int i = Collections.binarySearch(mSegments,ref,mStartTimeCmp);
        if (i >= mSegments.size()) {
            Log.w(TAG,"seek: input ms "+ms+" is invalid");
            return false;
        } else if (i < 0) {
            i = -(i+2);
            if (i == -1) {
                i=0;
            }
        }
        JDvrSegment segment = mSegments.get(i);
        final long timeOffset = ms - segment.getStartTime();
        if (timeOffset < 0) {
            Log.w(TAG,"seek: timeOffset "+timeOffset+" is invalid");
            return false;
        }
        final long offset = segment.getOffsetOf(timeOffset);
        mSegments.forEach(seg -> seg.seek(0));
        segment.seek(offset);
        mSegmentIdBeingRead = segment.id();
        mPlayingTime = ms;
        final Long pts = segment.getPtsOf(timeOffset);
        if (pts != null) {
            updateLastPts(pts);
        }
        Log.i(TAG,"JDvrFile.seek to ms:"+ms+" (seg#"+i+" + "+timeOffset+"ms)");
        return true;
    }
    public JDvrSegment getFirstSegment() {
        return (mSegments.size() > 0) ? mSegments.get(0) : null;
    }
    public JDvrSegment getLastSegment() {
        return (mSegments.size() > 0) ? mSegments.get(mSegments.size()-1) : null;
    }
    public int getSegmentIdBeingRead() {
        if (mType < 2) { throw new RuntimeException("Cannot do this under Recording situation"); }
        return mSegmentIdBeingRead;
    }
    /**
     * Get current playing time in ms from origin
     *
     * @return  playing time if operation is successful, or -1 if there is any problem.
     */
    public long getPlayingTime() {
        if (mType < 2) { throw new RuntimeException("Cannot do this under Recording situation"); }
        final int segIdx = segmentsIndexOf(mPlayingTime);
        if (segIdx == -1) {
            Log.e(TAG,"Cannot get segment for time "+mPlayingTime);
            return -1L;
        }
        final int nextIdx = segIdx+1;
        final JDvrSegment currSeg = mSegments.get(segIdx);
        final long segmentTimeOffset = mPlayingTime - currSeg.getStartTime();
        // Search pts in current index file.
        Long newSegmentPlayingTime = currSeg.findPtsFrom(mLastPts,segmentTimeOffset);
        if (newSegmentPlayingTime != null) {
            mPlayingTime = currSeg.getStartTime() + newSegmentPlayingTime;
            //Log.d(TAG,"Finding PTS1 "+mLastPts+" from seg#"+currSeg.id()+"+off:"+segmentTimeOffset+"ms returns segmentPlayingTime:"+newSegmentPlayingTime+"ms PlayingTime:"+mPlayingTime+"ms");
        } else if (nextIdx<mSegments.size()) {
            final JDvrSegment nextSeg = mSegments.get(nextIdx);
            // Continue to search pts in next index file.
            newSegmentPlayingTime = nextSeg.findPtsFrom(mLastPts,0L);
            if (newSegmentPlayingTime != null) {
                mPlayingTime = nextSeg.getStartTime() + newSegmentPlayingTime;
                //Log.d(TAG,"Finding PTS2 "+mLastPts+" from seg#"+nextSeg.id()+"+off:"+segmentTimeOffset+"ms returns segmentPlayingTime:"+newSegmentPlayingTime+"ms PlayingTime:"+mPlayingTime+"ms");
            }
        }
        if (newSegmentPlayingTime == null) {
            Log.w(TAG,"Cannot find out matching index for pts "+mLastPts+" in seg#"
                    +currSeg.id()+" starting from offset:"+segmentTimeOffset+"ms (and seg#"+(currSeg.id()+1)+" if any)");
        }
        return (newSegmentPlayingTime != null) ? mPlayingTime : -1L;
    }

    /**
     * The purpose of this function is for JDvrRecorder to notify any stream change to JDvrFile, so
     * that JDvrFile can reflect those changes in associated index file.
     * @param newStreamArray A list of streams that are being recorded.
     * @return true if it is aware of some differences and updates its own stream array based on
     * the input one, or false if it fails to find out any stream change.
     */
    public boolean updateRecordingStreams(ArrayList<JDvrStreamInfo> newStreamArray) {
        if (mType == 2) { throw new RuntimeException("Cannot do this under Playback situation"); }
        Comparator<JDvrStreamInfo> streamInfoCmp = Comparator.comparingInt(info -> info.pid);
        mCurrentRecordingStreams.sort(streamInfoCmp);
        newStreamArray.sort(streamInfoCmp);
        if (mCurrentRecordingStreams.equals(newStreamArray)) {
            return false;
        }
        mCurrentRecordingStreams = newStreamArray.stream().distinct().collect(Collectors.toCollection(ArrayList::new));
        mPidHasChanged = true;
        return true;
    }
    private boolean updateStatFile() throws IOException {
        if (mType == 2) { throw new RuntimeException("Cannot do this under Playback situation"); }
        final long total_size = size();
        final String statContent = String.format(Locale.US,
                "{\"size\":%d, \"duration\":%d, \"packets\":%d, \"first_segment_id\":%d, \"last_segment_id\":%d, \"limit_size\":%d, \"limit_duration\":%d}\n",
                total_size,duration(),total_size/188, getFirstSegmentId(), getLastSegmentId(),
                (mLimitSize == Long.MAX_VALUE ? 0 : Math.abs(mLimitSize)),
                (mLimitSeconds == Integer.MAX_VALUE ? 0 : Math.abs(mLimitSeconds)));
        try {
            FileOutputStream statStream = new FileOutputStream(mStatPath, false);
            statStream.write(statContent.getBytes(),0,statContent.length());
            statStream.close();
        } catch (IOException e) {
            Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
            throw e;
        }
        return true;
    }
    public boolean updateListFile() throws IOException {
        if (mType == 2) { throw new RuntimeException("Cannot do this under Playback situation"); }
        try {
            FileOutputStream listStream = new FileOutputStream(mListPath, false);
            mSegments.forEach(seg -> {
                final String line = seg.toString();
                try {
                    listStream.write(line.getBytes(), 0, line.length());
                } catch (IOException e) {
                    Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
                    e.printStackTrace();
                }
            });
            listStream.close();
        } catch (IOException e) {
            Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
            throw e;
        }
        return true;
    }
    public static boolean isEffectiveLimitSize(long size) {
        return (size > 0L && size != Long.MAX_VALUE);
    }
    public static boolean isEffectiveLimitDuration(long duration) {
        return (duration > 0L && duration != Integer.MAX_VALUE);
    }
    public void updateLastPts(long pts) {
        mLastPts = pts;
    }
    public ArrayList<JDvrStreamInfo> getStreamsInfoAt(long time) {
        if (mType < 2) { throw new RuntimeException("Cannot do this under Recording situation"); }
        final int segIdx = segmentsIndexOf(time);
        if (segIdx == -1) {
            Log.e(TAG,"Cannot get segment for time "+time);
            return null;
        }
        final JDvrSegment currSeg = mSegments.get(segIdx);
        final long segmentTimeOffset = time - currSeg.getStartTime();
        return currSeg.findMatchingStreamsInfo(segmentTimeOffset);
    }

    // Private functions
    private static int removeAssociatedFiles(String pathPrefix) {
        final String dirName = pathPrefix.substring(0,pathPrefix.lastIndexOf('/'));
        File dir = new File(dirName);
        if (!dir.exists()) {
            return 0;
        }
        final File[] files = dir.listFiles((file, s) -> {
            final String path = file.getAbsolutePath() + "/" + s;
            return path.matches(pathPrefix+"(\\.(stat|list)|-\\d+\\.(idx|ts))");
        });
        if (files == null) {
            return 0;
        }
        int ret = files.length;
        for (final File file: files) {
            if (!file.delete()) {
                Log.e(TAG,"Fails to remove " + file.getAbsolutePath());
                ret--;
            } else {
                Log.d(TAG,"Removed " + file.getAbsolutePath());
            }
        }
        return ret;
    }
    private static boolean createLockIfNotExist(String path) {
        File lockFile = new File(path);
        if (!lockFile.exists()) {
            try {
                lockFile.createNewFile();
            } catch (IOException e) {
                Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
                e.printStackTrace();
                return false;
            }
        }
        return true;
    }
    private void commonProcedure(boolean trunc) {
        if (!createLockIfNotExist(mLockPath)) {
            throw new RuntimeException("Cannot create .lock file");
        }
        try {
            mLockChannel = new RandomAccessFile(mLockPath, "rw").getChannel();
            if (trunc) {
                Log.d(TAG,"lock(0-200) for trunc");
                mLock = mLockChannel.tryLock(0,200,false);
                if (mLock != null) {
                    removeAssociatedFiles(mPathPrefix);
                    Log.d(TAG, "unlock(0-200) for trunc");
                    mLock.release();
                } else {
                    throw new RuntimeException("Cannot acquire lock for trunc");
                }
            }
            Log.d(TAG,"lock(0-100) for recording");
            mLock = mLockChannel.tryLock(0, 100, false);
            if (mLock == null) {
                throw new RuntimeException("Cannot acquire lock for recording");
            }
            if (!(load() || load() || load())) {
                Log.d(TAG,"unlock(0-100) for recording");
                mLock.release();
                throw new RuntimeException("Fails to load recording files");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    private int segmentsIndexOf(long timeOffset) {
        if (timeOffset < 0) {
            return -1;
        }
        return IntStream.range(0,mSegments.size()).filter(i->mSegments.get(i).getStartTime()+mSegments.get(i).duration()>=timeOffset).findFirst().orElse(-1);
    }
    private static boolean repairFiles(String pathPrefix) {
        Log.d(TAG,"Repairing "+pathPrefix);
        final String dirName = pathPrefix.substring(0,pathPrefix.lastIndexOf('/'));
        File dir = new File(dirName);
        if (!dir.exists()) {
            Log.w(TAG,"Cannot repair recording, for "+dir.getAbsolutePath()+" doesn't exist.");
            return false;
        }
        final File[] files = dir.listFiles((file, s) -> (file.getAbsolutePath()+"/"+s).matches(pathPrefix+"-\\d+\\.idx"));
        if (files == null) {
            Log.w(TAG,"Cannot repair recording "+pathPrefix+", for there is not any associated .idx files");
            return false;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        Integer[] segIds = Arrays.stream(files).map(File::toPath).map(Path::toString)
                .map(s -> Integer.parseInt(s.substring(s.lastIndexOf('-')+1,s.lastIndexOf('.'))))
                .toArray(Integer[]::new);
        ArrayList<JDvrSegment> segments = new ArrayList<>();
        for (Integer id : segIds) {
            JDvrSegment seg = new JDvrSegment(pathPrefix, id,1,2);
            segments.add(seg);
        }
        final long size = segments.stream().mapToLong(JDvrSegment::size).sum();
        final long duration = segments.stream().mapToLong(JDvrSegment::duration).sum();
        final int first_seg_id = segments.get(0).id();
        final int last_seg_id = segments.get(segments.size()-1).id();
        final String statContent = String.format(Locale.US,"{\"size\":%d, \"duration\":%d, "
                +"\"packets\":%d, \"first_segment_id\":%d, \"last_segment_id\":%d, "
                +"\"limit_size\":0, \"limit_duration\":0}",
                size,duration,size/188,first_seg_id,last_seg_id);
        //Log.d(TAG,"Repaired statContent:"+statContent);
        final String listContent = segments.stream().map(JDvrSegment::toString).collect(Collectors.joining());
        //Log.d(TAG,"Repaired listContent:"+listContent);
        try {
            FileOutputStream statStream = new FileOutputStream(pathPrefix+".stat", false);
            statStream.write(statContent.getBytes(),0,statContent.length());
            statStream.close();
            FileOutputStream listStream = new FileOutputStream(pathPrefix+".list", false);
            listStream.write(listContent.getBytes(),0,listContent.length());
            listStream.close();
        } catch (IOException e) {
            Log.e(TAG, "Exception at "+JDvrCommon.getCallerInfo(3)+": " + e);
            return false;
        }
        Log.d(TAG,"Repaired "+pathPrefix);
        return true;
    }
}

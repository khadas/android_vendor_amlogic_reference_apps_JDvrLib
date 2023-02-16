package com.droidlogic.jdvrlib;

import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Collectors;

public class JDvrFile {
    final private String TAG = JDvrRecorder.class.getSimpleName();
    final private String mPathPrefix;
    final private boolean mIsTimeshift;
    final private long mLimitSize;
    final private int mLimitSeconds;
    private final ArrayList<JDvrSegment> mSegments = new ArrayList<>();
    final private String mStatPath;
    final private String mListPath;
    private long mTimestampOfLastIndexWrite = 0;
    private long mTimestampOfOrigin = 0;
    private long mTotalObsoletePausedTime = 0;   // in ms
    final private static int mMinIndexInterval = 300;  // in ms
    private ArrayList<JDvrRecorder.JDvrStreamInfo> mStreamArray = new ArrayList<>();
    private boolean mPidHasChanged = false;

    // Public APIs
    /**
     * Constructs a JDvrFile instance.
     * A JDvrFile object represents a PVR recording which consists of a series of associated files
     * like ts, index, status and list files.
     * This constructor is preferred to be used to create a timeshift recording, so is_timeshift
     * should be true and valid values should be supplied to limit_size or limit_seconds.
     *
     * @param path_prefix The path prefix of a recording. All the associated files use the prefix
     *                    as part of their file path.
     * @param is_timeshift Indicate whether the recording is a timeshift recording. Normally,
     *                     timeshift recordings have a restricted recording size or duration.
     * @param limit_size The timeshift recording file size limitation in bytes. 0 means no
     *                   limitation.
     * @param limit_seconds The timeshift recording duration limitation in seconds. 0 means no
     *                      limitation.
     * @throws IOException if the folder part of path_prefix for storing recording files is inaccessible.
     */
    public JDvrFile(String path_prefix, boolean is_timeshift, long limit_size, int limit_seconds) throws IOException {
        final String dirname = path_prefix.substring(0,path_prefix.lastIndexOf('/'));
        File dir = new File(dirname);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create " + dir.getAbsolutePath());
        }
        mPathPrefix = path_prefix;
        mIsTimeshift = is_timeshift;
        mLimitSize = (limit_size <= 0) ? Long.MAX_VALUE : limit_size;
        mLimitSeconds = (limit_seconds <= 0) ? Integer.MAX_VALUE : limit_seconds;

        mStatPath = mPathPrefix + ".stat";
        mListPath = mPathPrefix + ".list";
        removeFilesWithPrefix(path_prefix);
    }
    /**
     * Constructs a JDvrFile instance.
     * A JDvrFile object represents a PVR recording which consists of a series of associated files
     * like ts, index, status and list files.
     * This constructor is preferred to be used to create a normal recording, so is_timeshift
     * should be false.
     *
     * @param path_prefix The path prefix of a recording. All the associated files use the prefix
     *                    as part of their file path.
     * @param is_timeshift Indicate whether the recording is a timeshift recording. Normally,
     *                     timeshift recordings have a restricted recording size or duration.
     * @throws IOException if the folder part of path_prefix for storing recording files is inaccessible.
     */
    public JDvrFile(String path_prefix, boolean is_timeshift) throws IOException {
        this(path_prefix,is_timeshift,0,0);
    }
    /**
     * Check if current recording is a timeshift recording.
     * @return true if it is a timeshift recording, or false if it is a normal recording.
     */
    public boolean isTimeshift() {
        return mIsTimeshift;
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
     * @throws IOException if it fails to create any segment file.
     */
    public int addSegment() throws IOException {
        final int newID = getLastSegmentID() + 1;
        JDvrSegment segment = new JDvrSegment(mPathPrefix, newID);
        mSegments.add(segment);
        return newID;
    }
    /**
     * Remove a specific segment from the recording.
     * @param segment_id the segment id to be removed.
     * @return true if operation is successful, or false if the segment does not exist.
     */
    public boolean removeSegment(int segment_id) {
        JDvrSegment seg = mSegments.stream().filter(s -> (s.id() == segment_id)).findFirst().orElse(null);
        if (seg == null) {
            return false;
        }
        seg.close();
        seg.delete();
        mSegments.remove(seg);
        return true;
    }
    /**
     * Get first segment id of the recording.
     * @return first segment's segment id, or -1 if there is no segment at all.
     */
    public int getFirstSegmentID() {
        return (mSegments.size() > 0) ? mSegments.get(0).id() : -1;
    }
    /**
     * Get last segment id of the recording.
     * @return last segment's segment id, or -1 if there is no segment at all.
     */
    public int getLastSegmentID() {
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
    public int getNextSegmentID(int segment_id) {
        final JDvrSegment seg = mSegments.stream().filter(s -> (s.id() == segment_id)).findFirst().orElse(null);
        return (seg != null) ? seg.id() + 1 : -1;
    }
    /**
     * Get the start time of a recording.
     * Start time is relative to origin at which the recording was initially made. In line with
     * this, start time of a normal recording shall always be 0. Under timeshift situations, as
     * some of its oldest segments have been removed due to size/duration limitation, its start
     * time calculation need to consider all removed segments.
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
     * @return true if operation is successful, or false otherwise.
     */
    public boolean delete() {
        mSegments.forEach(seg -> {
            seg.close();
            seg.delete();
        });
        File statFile = new File(mStatPath);
        statFile.delete();
        File listFile = new File(mListPath);
        listFile.delete();
        mSegments.clear();
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
        mSegments.get(mSegments.size()-1).flush();
        return mSegments.stream().mapToLong(JDvrSegment::size).sum();
    }
    /**
     * Get recording duration in ms.
     * @return recording duration in ms.
     */
    public long duration()
    {
        return mSegments.stream().mapToLong(JDvrSegment::duration).sum();
    }
    /**
     * Close all segment files including ts/index.
     */
    public void close() {
        mSegments.forEach(JDvrSegment::close);
    }

    // Private APIs
    public int write (byte[] buffer, int offset, int size) throws IOException {
        final long curTs = SystemClock.elapsedRealtime();
        JDvrSegment lastSegment;
        // 1. Add a segment if necessary
        {
            final boolean cond1 = (mSegments.size() == 0);
            boolean cond2 = false;
            if (!cond1) {
                lastSegment = getLastSegment();
                cond2 = (lastSegment.size() + size > JDvrSegment.getMaxSegmentSize());
            }
            if (cond1 || cond2) {
                addSegment();
                mTimestampOfLastIndexWrite = curTs;
                if (cond1) {
                    mTimestampOfOrigin = curTs;
                    Log.d(TAG,"Origin timestamp is " + mTimestampOfOrigin);
                }
            }
        }
        lastSegment = getLastSegment();
        // 2. Remove a segment if necessary
        {
            final boolean cond1 = (size()+size > mLimitSize);
            final boolean cond2 = ((duration()+curTs-mTimestampOfLastIndexWrite)/1000 >= mLimitSeconds);
            final boolean cond3 = isTimeshift();
            final boolean cond4 = (mSegments.size()>1);
            if ((cond1 || cond2) && cond3 && cond4) {
                mTotalObsoletePausedTime += mSegments.get(0).getPausedTime();
                removeSegment(getFirstSegmentID());
            }
        }
        // 3. Update index file if necessary
        final long newSize = size() + size;
        {
            final long timeElapsed = curTs - mTimestampOfLastIndexWrite;
            final long timeOffsetOfSegment = lastSegment.duration()
                    + ((timeElapsed < 2 * mMinIndexInterval) ? timeElapsed : mMinIndexInterval);
            final long totalTimeSpent = curTs - mTimestampOfOrigin;
            final long totalTimePaused = mTotalObsoletePausedTime + mSegments.stream().mapToLong(JDvrSegment::getPausedTime).sum();
            final long timeOffsetFromOrigin = totalTimeSpent - totalTimePaused;

            final boolean cond1 = (lastSegment.size() == 0);
            final boolean cond2 = mPidHasChanged;
            if (cond1 || cond2) {
                final String streamStr = mStreamArray.stream().map(Object::toString).collect(Collectors.joining(","));
                final String line = String.format(Locale.US,
                        "{\"time\":%d, \"time_offset_from_origin\":%d, \"id\":%d, \"nb_pids\":%d, \"pids\":[%s]}\n",
                        timeOffsetOfSegment,timeOffsetFromOrigin,lastSegment.id(), mStreamArray.size(),streamStr);
                lastSegment.writeIndex(line.getBytes(),line.length());
                mPidHasChanged = false;
            }
            final boolean cond3 = (newSize <= mLimitSize);
            final boolean cond4 = (curTs - mTimestampOfLastIndexWrite >= mMinIndexInterval);
            if (cond3 && cond4) {
                final long newFileOffset = lastSegment.size();
                final String line = String.format(Locale.US,"{\"time\":%d, \"offset\":%d}\n", timeOffsetOfSegment, newFileOffset);
                lastSegment.writeIndex(line.getBytes(), line.length());
                mTimestampOfLastIndexWrite = curTs;
                updateStatFile();
            }
        }
        // 4. Write data to TS file
        int ret = 0;
        {
            final boolean cond1 = (newSize <= mLimitSize);
            if (cond1) {
                ret = lastSegment.write(buffer, offset, size);
            }
        }
        return ret;
    }
    public JDvrSegment getFirstSegment() {
        return (mSegments.size() > 0) ? mSegments.get(0) : null;
    }
    public JDvrSegment getLastSegment() {
        return (mSegments.size() > 0) ? mSegments.get(mSegments.size()-1) : null;
    }

    /**
     * The purpose of this function is for JDvrRecorder to notify any stream change to JDvrFile, so
     * that JDvrFile can reflect those changes in associated index file.
     * @param newStreamArray A list of streams that are being recorded.
     * @return true if it is aware of some differences and updates its own stream array based on
     * the input one, or false if it fails to find out any stream change.
     */
    public boolean updateRecordingStreams(ArrayList<JDvrRecorder.JDvrStreamInfo> newStreamArray) {
        Comparator<JDvrRecorder.JDvrStreamInfo> streamInfoCmp = Comparator.comparingInt(info -> info.pid);
        mStreamArray.sort(streamInfoCmp);
        newStreamArray.sort(streamInfoCmp);
        if (mStreamArray.equals(newStreamArray)) {
            return false;
        }
        mStreamArray = newStreamArray.stream().distinct().collect(Collectors.toCollection(ArrayList::new));
        mPidHasChanged = true;
        return true;
    }
    private boolean updateStatFile() {
        final long total_size = size();
        final String statContent = String.format(Locale.US,
                "{\"size\":%d, \"duration\":%d, \"packets\":%d, \"first_segment_id\":%d, \"last_segment_id\":%d}\n",
                total_size,duration(),total_size/188, getFirstSegmentID(), getLastSegmentID());
        try {
            FileOutputStream statStream = new FileOutputStream(mStatPath, false);
            statStream.write(statContent.getBytes(),0,statContent.length());
            statStream.close();
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
            return false;
        }
        return true;
    }
    public boolean updateListFile() {
        try {
            FileOutputStream listStream = new FileOutputStream(mListPath, false);
            mSegments.forEach(seg -> {
                final String line = seg.toString();
                try {
                    listStream.write(line.getBytes(), 0, line.length());
                } catch (IOException e) {
                    Log.e(TAG, "Exception: " + e);
                    e.printStackTrace();
                }
            });
            listStream.close();
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
            return false;
        }
        return true;
    }

    // Private functions
    private int removeFilesWithPrefix(final String pathPrefix) {
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
}

package com.droidlogic.jdvrlib;

import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.JsonReader;
import android.util.Log;

import com.droidlogic.jdvrlib.JDvrRecorder.JDvrAudioFormat;
import com.droidlogic.jdvrlib.JDvrRecorder.JDvrStreamInfo;
import com.droidlogic.jdvrlib.JDvrRecorder.JDvrStreamType;
import com.droidlogic.jdvrlib.JDvrRecorder.JDvrVideoFormat;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.IntStream;

class JDvrSegment {
    final private String TAG = JDvrSegment.class.getSimpleName();
    final private String mPathPrefix;
    final private int mMode;  // 0: for recording, 1: for playback, 2: for comparator
    final private int mSegmentID;
    final private String mTsPath;
    private File mTsFile = null;
    private RandomAccessFile mTsStream = null;
    final private String mIndexPath;
    private File mIndexFile = null;
    private RandomAccessFile mIndexStream = null;
    private long mLastReadOffset = 0L;  // for playback only
    private static int mMaxSegmentSize = 100*1024*1024;
    private long mFirstWriteTimestamp = 0;  // for recording only
    private long mLastWriteTimestamp = 0;   // for recording only
    private long mDuration = 0L;    // in ms
    private long mStartTime = 0L;   // in ms
    private int mLoadLevel = 0;     // mainly for playback
    final private static String regex1 = ".*\"offset\":.*";
    final private static String regex2 = ".*nb_pids.*";
    private int mProcessedLines = 0;
    private final ArrayList<JDvrSegmentTimeOffsetIndex> mTimeOffsetIndexArray = new ArrayList<>();
    private final Comparator<JDvrSegmentTimeOffsetIndex> mIndexTimeCmp = Comparator.comparingLong(idx -> idx.time);
    private final Comparator<JDvrSegmentTimeOffsetIndex> mIndexOffsetCmp = Comparator.comparingLong(idx -> idx.offset);
    private final ArrayList<JDvrSegmentTimeStreamIndex> mTimeStreamIndexArray = new ArrayList<>();

    private static class JDvrSegmentTimeOffsetIndex {
        long time;
        long offset;
        long pts;

        public JDvrSegmentTimeOffsetIndex(long time, long offset, long pts) {
            this.time = time;
            this.offset = offset;
            this.pts = pts;
        }
    }
    private static class JDvrSegmentTimeStreamIndex {
        long time;
        long timeOffsetFromOrigin;
        int id;
        ArrayList<JDvrStreamInfo> pids;

        public JDvrSegmentTimeStreamIndex(long time, long timeOffsetFromOrigin, int id, ArrayList<JDvrStreamInfo> pids) {
            this.time = time;
            this.timeOffsetFromOrigin = timeOffsetFromOrigin;
            this.id = id;
            this.pids = pids;
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "%d,%d,%d\n", mSegmentID, mStartTime, mDuration);
    }

    public JDvrSegment(String path_prefix, int segment_id, int mode) {
        mSegmentID = segment_id;
        mMode = mode;
        if (mMode == 2) {
            mPathPrefix = "";
            mTsPath = "";
            mIndexPath = "";
        } else {
            mPathPrefix = String.format(Locale.US, "%s-%04d", path_prefix, mSegmentID);
            mTsPath = String.format(Locale.US, "%s.ts", mPathPrefix);
            mIndexPath = String.format(Locale.US, "%s.idx", mPathPrefix);
            load(0);
        }
    }

    public int id() {
        return mSegmentID;
    }
    public int getVideoPid() {
        if (mLoadLevel < 2) {
            load(2);
        }
        if (mTimeStreamIndexArray.size() == 0) {
            return 0x1fff;
        }
        JDvrStreamInfo info = mTimeStreamIndexArray.get(0).pids.stream()
                .filter(i -> (i.type == JDvrStreamType.STREAM_TYPE_VIDEO))
                .findFirst().orElse(null);
        return (info!=null) ? info.pid : 0x1fff;
    }
    public String getVideoMIMEType() {
        if (mLoadLevel < 2) {
            load(2);
        }
        if (mTimeStreamIndexArray.size() == 0) {
            return null;
        }
        JDvrStreamInfo info = mTimeStreamIndexArray.get(0).pids.stream()
                .filter(i -> (i.type == JDvrStreamType.STREAM_TYPE_VIDEO))
                .findFirst().orElse(null);
        if (info == null) {
            return null;
        } else if (info.format == JDvrVideoFormat.VIDEO_FORMAT_MPEG2) {
            return MediaFormat.MIMETYPE_VIDEO_MPEG2;
        } else if (info.format == JDvrVideoFormat.VIDEO_FORMAT_H264) {
            return MediaFormat.MIMETYPE_VIDEO_AVC;
        } else if (info.format == JDvrVideoFormat.VIDEO_FORMAT_HEVC) {
            return MediaFormat.MIMETYPE_VIDEO_HEVC;
        }
        return null;
    }
    public int getVideoFormat() {
        if (mLoadLevel < 2) {
            load(2);
        }
        if (mTimeStreamIndexArray.size() == 0) {
            return JDvrVideoFormat.VIDEO_FORMAT_UNDEFINED;
        }
        JDvrStreamInfo info = mTimeStreamIndexArray.get(0).pids.stream()
                .filter(i -> (i.type == JDvrStreamType.STREAM_TYPE_VIDEO))
                .findFirst().orElse(null);
        if (info == null) {
            return JDvrVideoFormat.VIDEO_FORMAT_UNDEFINED;
        } else if (info.format == JDvrVideoFormat.VIDEO_FORMAT_MPEG2) {
            return JDvrVideoFormat.VIDEO_FORMAT_MPEG2;
        } else if (info.format == JDvrVideoFormat.VIDEO_FORMAT_H264) {
            return JDvrVideoFormat.VIDEO_FORMAT_H264;
        } else if (info.format == JDvrVideoFormat.VIDEO_FORMAT_HEVC) {
            return JDvrVideoFormat.VIDEO_FORMAT_HEVC;
        }
        return JDvrVideoFormat.VIDEO_FORMAT_UNDEFINED;
    }
    public int getAudioPID() {
        if (mLoadLevel < 2) {
            load(2);
        }
        if (mTimeStreamIndexArray.size() == 0) {
            return 0x1fff;
        }
        JDvrStreamInfo info = mTimeStreamIndexArray.get(0).pids.stream()
                .filter(i -> (i.type == JDvrStreamType.STREAM_TYPE_AUDIO))
                .findFirst().orElse(null);
        return (info!=null) ? info.pid : 0x1fff;
    }
    public String getAudioMIMEType() {
        if (mLoadLevel < 2) {
            load(2);
        }
        if (mTimeStreamIndexArray.size() == 0) {
            return null;
        }
        JDvrStreamInfo info = mTimeStreamIndexArray.get(0).pids.stream()
                .filter(i -> (i.type == JDvrStreamType.STREAM_TYPE_AUDIO))
                .findFirst().orElse(null);
        if (info == null) {
            return null;
        } else if (info.format == JDvrAudioFormat.AUDIO_FORMAT_MPEG) {
            return MediaFormat.MIMETYPE_AUDIO_MPEG;
        } else if (info.format == JDvrAudioFormat.AUDIO_FORMAT_AAC) {
            return MediaFormat.MIMETYPE_AUDIO_AAC;
        } else if (info.format == JDvrAudioFormat.AUDIO_FORMAT_EAC3) {
            return MediaFormat.MIMETYPE_AUDIO_EAC3;
        }
        return null;
    }
    public int getAudioFormat() {
        if (mLoadLevel < 2) {
            load(2);
        }
        if (mTimeStreamIndexArray.size() == 0) {
            return JDvrAudioFormat.AUDIO_FORMAT_UNDEFINED;
        }
        JDvrStreamInfo info = mTimeStreamIndexArray.get(0).pids.stream()
                .filter(i -> (i.type == JDvrStreamType.STREAM_TYPE_AUDIO))
                .findFirst().orElse(null);
        if (info == null) {
            return JDvrAudioFormat.AUDIO_FORMAT_UNDEFINED;
        } else if (info.format == JDvrAudioFormat.AUDIO_FORMAT_MPEG) {
            return JDvrAudioFormat.AUDIO_FORMAT_MPEG;
        } else if (info.format == JDvrAudioFormat.AUDIO_FORMAT_MPEG2) {
            return JDvrAudioFormat.AUDIO_FORMAT_MPEG2;
        } else if (info.format == JDvrAudioFormat.AUDIO_FORMAT_AAC) {
            return JDvrAudioFormat.AUDIO_FORMAT_AAC;
        } else if (info.format == JDvrAudioFormat.AUDIO_FORMAT_EAC3) {
            return JDvrAudioFormat.AUDIO_FORMAT_EAC3;
        }
        return JDvrAudioFormat.AUDIO_FORMAT_UNDEFINED;
    }
    private int load(int level) {
        //final long ts1 = SystemClock.elapsedRealtime();
        try {
            if (mTsFile == null) {
                mTsFile = new File(mTsPath);
            }
            if (mTsStream == null) {
                if (mMode == 1) {
                    mTsStream = new RandomAccessFile(mTsFile, "r");
                } else {
                    mTsStream = new RandomAccessFile(mTsFile, "rws");
                }
            }
            if (mIndexFile == null) {
                mIndexFile = new File(mIndexPath);
            }
            if (mIndexStream == null) {
                if (mMode == 1) {
                    mIndexStream = new RandomAccessFile(mIndexFile, "r");
                } else {
                    mIndexStream = new RandomAccessFile(mIndexFile, "rws");
                }
            }
            if (!mTsFile.exists() || !mIndexFile.exists()) {
                Log.w(TAG,"Trying to load segment "+mPathPrefix+", but files don't exist");
                return 0;
            }
            if (level == 1 && mLoadLevel <= 1) {
                if (mDuration > 0) {
                    mLoadLevel = 1;
                } else if (mDuration == 0 && mStartTime == 0) {
                    level = 2;
                }
            }
            Path path = Paths.get(mIndexPath);
            if (level == 2) {
                final String[] lines = Files.readAllLines(path).toArray(new String[0]);
                if (lines.length > 0) {
                    boolean cond1 = false;
                    boolean cond2 = false;
                    String indexInfo = IntStream.rangeClosed(1, lines.length)
                            .mapToObj(i -> lines[lines.length - i])
                            .filter(s -> s.matches(regex1)).findFirst().orElse(null);
                    if (indexInfo != null) {
                        JDvrSegmentTimeOffsetIndex idx1 = parseTimeOffsetIndex(indexInfo);
                        if (idx1 != null) {
                            mDuration = idx1.time;
                            cond1 = true;
                        }
                    }
                    String streamInfo = Arrays.stream(lines).filter(s -> s.matches(regex2))
                            .findFirst().orElse(null);
                    if (streamInfo != null) {
                        JDvrSegmentTimeStreamIndex idx2 = parseTimeStreamIndex(streamInfo);
                        if (idx2 != null) {
                            mTimeStreamIndexArray.clear();
                            mTimeStreamIndexArray.add(idx2);
                            mStartTime = idx2.timeOffsetFromOrigin - idx2.time;
                            cond2 = true;
                        }
                    }
                    if (cond1 && cond2 && mLoadLevel < 2) {
                        mLoadLevel = 2;
                    }
                }
            } else if (level == 3 && mLoadLevel <= 3) {
                final String[] lines = Files.readAllLines(path).toArray(new String[0]);
                if (lines.length > 0) {
                    Arrays.stream(lines).skip(mProcessedLines).filter(s -> s.matches(regex1))
                            .forEach(line -> mTimeOffsetIndexArray.add(parseTimeOffsetIndex(line)));
                    mTimeOffsetIndexArray.removeIf(Objects::isNull);
                    if (mProcessedLines == 0) {
                       mTimeStreamIndexArray.clear();
                    }
                    Arrays.stream(lines).skip(mProcessedLines).filter(s -> s.matches(regex2))
                            .forEach(line -> mTimeStreamIndexArray.add(parseTimeStreamIndex(line)));
                    mTimeStreamIndexArray.removeIf(Objects::isNull);
                    JDvrSegmentTimeOffsetIndex idx1 = mTimeOffsetIndexArray.get(mTimeOffsetIndexArray.size() - 1);
                    if (idx1 != null) {
                        mDuration = idx1.time;
                    }
                    JDvrSegmentTimeStreamIndex idx2 = mTimeStreamIndexArray.get(mTimeStreamIndexArray.size() - 1);
                    if (idx2 != null) {
                        mStartTime = idx2.timeOffsetFromOrigin - idx2.time;
                    }
                    if (idx1 != null && idx2 != null && mLoadLevel < 3) {
                        mLoadLevel = 3;
                    }
                }
                mProcessedLines = lines.length;
            }
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
        //} finally {
            //final long ts2 = SystemClock.elapsedRealtime();
            //Log.i(TAG, String.format("load(%d) segment %s, time spent: %dms", level, mPathPrefix, ts2 - ts1));
        }
        return mLoadLevel;
    }
    public int write(byte[] buffer, int offset, int size) {
        if (mMode == 1) { throw new RuntimeException("Cannot do this under Playback situation"); }
        try {
            mTsStream.seek(mTsStream.length());
            mTsStream.write(buffer, offset, size);
            if (mFirstWriteTimestamp == 0) {
                mFirstWriteTimestamp = SystemClock.elapsedRealtime();
            }
            mLastWriteTimestamp = SystemClock.elapsedRealtime();
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
            return 0;
        }
        return size;
    }
    public int writeIndex(byte[] buffer, int size) {
        if (mMode == 1) { throw new RuntimeException("Cannot do this under Playback situation"); }
        JsonReader reader = new JsonReader(new StringReader(new String(buffer)));
        long timeOffset = -1;
        long timeOffsetFromOrigin = -1;
        long offset = -1;
        try {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                switch (name) {
                    case "time":
                        timeOffset = reader.nextLong();
                        break;
                    case "time_offset_from_origin":
                        timeOffsetFromOrigin = reader.nextLong();
                        break;
                    case "offset":
                        offset = reader.nextLong();
                        break;
                    default:
                        reader.skipValue();
                        break;
                }
            }
            reader.endObject();
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
        }
        if (timeOffset >= 0) {
            mDuration = timeOffset;
            if (offset >= 0) {
                mTimeOffsetIndexArray.add(new JDvrSegmentTimeOffsetIndex(timeOffset,offset,0L));
            }
        }
        if (timeOffsetFromOrigin > 0 && mStartTime == 0) {
            mStartTime = timeOffsetFromOrigin - timeOffset;
        }
        try {
            mIndexStream.seek(mIndexStream.length());
            mIndexStream.write(buffer, 0, size);
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
            return 0;
        }
        return 0;
    }
    public int read(byte[] buffer, int offset, int size) throws IOException {
        if (mMode == 0) { throw new RuntimeException("Cannot do this under Recording situation"); }
        int ret;
        try {
            mTsStream.seek(mLastReadOffset);
            ret = mTsStream.read(buffer,offset,size);
            mLastReadOffset = (ret == -1) ? mTsStream.length() : mLastReadOffset + ret;
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
            return 0;
        }
        return ret;
    }
    public void seek(long pos) {
        if (mMode == 0) { throw new RuntimeException("Cannot do this under Recording situation"); }
        if (pos < 0) {
            return;
        }
        try {
            pos = Math.min(pos,mTsStream.length());
            mTsStream.seek(pos);
            mLastReadOffset = pos;
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
        }
    }
    public void close() {
        try {
            mTsStream.close();
            mTsStream = null;
            mIndexStream.close();
            mIndexStream = null;
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
        }
    }
    public void delete() {
        Log.i(TAG,"Deleting segment: " + mTsPath);
        mTsFile.delete();
        mIndexFile.delete();
        mTimeOffsetIndexArray.clear();
    }
    public int size() {
        return (int)mTsFile.length();
    }
    static public void setMaxSegmentSize(int size) {
        mMaxSegmentSize = size;
    }
    static public int getMaxSegmentSize() {
        return mMaxSegmentSize;
    }
    public long duration() {
        if (mMode == 1) { // only in case of playback we need to load() if necessary
            load(2);
        }
        return mDuration;
    }
    public void setDuration(long duration) {
        mDuration = duration;
    }
    public long getStartTime() {
        if (mMode == 2) {
            return mStartTime;
        }
        if (mLoadLevel < 1) {
            load(1);
        }
        return mStartTime;
    }
    public void setStartTime(long time) {
        mStartTime = time;
    }
    public long getPausedTime() {
        if (mMode == 1) { throw new RuntimeException("Cannot do this under Playback situation"); }
        final long timeSpent = mLastWriteTimestamp - mFirstWriteTimestamp;
        final long pausedTime = timeSpent - mDuration;
        final boolean cond1 = mFirstWriteTimestamp > 0;
        final boolean cond2 = pausedTime >= 0;
        return (cond1 && cond2) ? pausedTime : 0L;
    }
    public String getTsPath() {
        return mTsPath;
    }
    public String getIndexPath() {
        return mIndexPath;
    }
    public boolean isClosed() {
        return (mTsFile == null);
    }
    public int getLoadLevel() {
        return mLoadLevel;
    }
    public Long getOffsetOf(final long time) {
        if (mMode == 0) { throw new RuntimeException("Cannot do this under Recording situation"); }
        Integer i = findMatchingIndexByTimeOffset(time);
        return (i != null) ? mTimeOffsetIndexArray.get(i).offset : null;
    }
    public Long getPtsOf(final long time) {
        if (mMode == 0) { throw new RuntimeException("Cannot do this under Recording situation"); }
        Integer i = findMatchingIndexByTimeOffset(time);
        return (i != null) ? mTimeOffsetIndexArray.get(i).pts : null;
    }
    public Long getTimeOffsetOf(final long offset) {
        if (mMode == 0) { throw new RuntimeException("Cannot do this under Recording situation"); }
        Integer i = findMatchingIndexByOffset(offset);
        return (i != null) ? mTimeOffsetIndexArray.get(i).time : null;
    }
    // Private functions
    private JDvrSegmentTimeOffsetIndex parseTimeOffsetIndex(final String line) {
        try {
            JsonReader reader = new JsonReader(new StringReader(line));
            long timeOffset = -1;
            long offset = -1;
            long pts = 0;
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                switch (name) {
                    case "time":
                        timeOffset = reader.nextLong();
                        break;
                    case "offset":
                        offset = reader.nextLong();
                        break;
                    case "pts":
                        pts = reader.nextLong();
                        break;
                    default:
                        reader.skipValue();
                        break;
                }
            }
            reader.endObject();
            if (timeOffset >= 0 && offset >= 0) {
                return new JDvrSegmentTimeOffsetIndex(timeOffset,offset,pts);
            }
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
        }
        return null;
    }
    private JDvrSegmentTimeStreamIndex parseTimeStreamIndex(final String line) {
        JsonReader reader = new JsonReader(new StringReader(line));
        long timeOffset = -1;
        long timeOffsetFromOrigin = -1;
        int id = -1;
        ArrayList<JDvrStreamInfo> pids = new ArrayList<>();
        try {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                switch (name) {
                    case "time":
                        timeOffset = reader.nextLong();
                        break;
                    case "time_offset_from_origin":
                        timeOffsetFromOrigin = reader.nextLong();
                        break;
                    case "id":
                        id = reader.nextInt();
                        break;
                    case "pids":
                        reader.beginArray();
                        while (reader.hasNext()) {
                            int pid = -1;
                            int type = -1;
                            int format = -1;
                            reader.beginObject();
                            while (reader.hasNext()) {
                                String name2 = reader.nextName();
                                switch (name2) {
                                    case "pid":
                                        pid = reader.nextInt();
                                        break;
                                    case "type":
                                        type = reader.nextInt();
                                        break;
                                    case "format":
                                        format = reader.nextInt();
                                        break;
                                    default:
                                        reader.skipValue();
                                        break;
                                }
                            }
                            reader.endObject();
                            if (pid != -1 && type != -1 && format != -1) {
                                pids.add(new JDvrStreamInfo(pid, type, format));
                            }
                        }
                        reader.endArray();
                        break;
                    default:
                        reader.skipValue();
                        break;
                }
            }
            reader.endObject();
            if (timeOffset >= 0 && timeOffsetFromOrigin >= 0 && pids.size() > 0) {
                return new JDvrSegmentTimeStreamIndex(timeOffset,timeOffsetFromOrigin,id,pids);
            }
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
        }
        return null;
    }
    Long findPtsFrom(long pts, long timeOffsetFrom) {
        if (mMode == 0) { throw new RuntimeException("Cannot do this under Recording situation"); }
        final int len = mTimeOffsetIndexArray.size();
        final Integer i = findMatchingIndexByTimeOffset(timeOffsetFrom);
        if (i == null) {
            return null;
        }
        for (int j=i; j<mTimeOffsetIndexArray.size()-1; j++) {
            final JDvrSegmentTimeOffsetIndex idx0 = mTimeOffsetIndexArray.get(j);
            final JDvrSegmentTimeOffsetIndex idx1 = mTimeOffsetIndexArray.get(j+1);
            if ( j > 0 && idx0.pts <= pts && idx1.pts >= pts) {
                return idx0.time;
            } else if (j == 0 && idx1.pts > pts && idx1.pts - pts <= JDvrFile.mMinIndexInterval * 90 * 2) {
                return idx0.time;
            } else if (j+2 == len && pts > idx1.pts && pts - idx1.pts <= JDvrFile.mMinIndexInterval * 90 * 2) {
                return idx1.time;
            }
        }
        return null;
    }
    private Integer findMatchingIndexByOffset(long offset) {
        if (mMode == 0) { throw new RuntimeException("Cannot do this under Recording situation"); }
        final int len = mTimeOffsetIndexArray.size();
        final boolean cond1 = (len == 0);
        final boolean cond2 = (!cond1 && mTimeOffsetIndexArray.get(len - 1).offset < offset);
        final boolean cond3 = (mLoadLevel < 3);
        final boolean cond4 = (mLoadLevel == 3);
        if (cond1 || cond3 || (cond2 && cond4)) {
            load(3);
        }
        final JDvrSegmentTimeOffsetIndex ref = new JDvrSegmentTimeOffsetIndex(0L,offset,0L);
        int i = Collections.binarySearch(mTimeOffsetIndexArray,ref,mIndexOffsetCmp);
        if (i >= len) {
            return null;
        } else if (i < 0) {
            i = -(i+2);
            if (i == -1) {
                i=0;
            }
        }
        return i;
    }
    private Integer findMatchingIndexByTimeOffset(long time) {
        if (mMode == 0) { throw new RuntimeException("Cannot do this under Recording situation"); }
        final int len = mTimeOffsetIndexArray.size();
        final boolean cond1 = (len == 0);
        final boolean cond2 = (!cond1 && mTimeOffsetIndexArray.get(len - 1).time < time);
        final boolean cond3 = (mLoadLevel < 3);
        final boolean cond4 = (mLoadLevel == 3);
        if (cond1 || cond3 || (cond2 && cond4)) {
            load(3);
        }
        final JDvrSegmentTimeOffsetIndex ref = new JDvrSegmentTimeOffsetIndex(time,0L,0L);
        int i = Collections.binarySearch(mTimeOffsetIndexArray,ref,mIndexTimeCmp);
        if (i >= len) {
            return null;
        } else if (i < 0) {
            i = -(i+2);
            if (i == -1) {
                i=0;
            }
        }
        return i;
    }
}
package com.droidlogic.jdvrlib;

import android.os.SystemClock;
import android.util.JsonReader;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.Locale;

public class JDvrSegment {
    final private String TAG = JDvrRecorder.class.getSimpleName();
    final private String mPathPrefix;
    final private int mSegmentID;
    final private String mTsPath;
    final private File mTsFile;
    private FileOutputStream mTsStream;
    final private String mIndexPath;
    final private File mIndexFile;
    private FileOutputStream mIndexStream;
    private int mSegmentSize = 0;
    private static int mMaxSegmentSize = 100*1024*1024;
    private long mFirstWriteTimestamp = 0;
    private long mLastWriteTimestamp = 0;
    private long mDuration = 0L;    // in ms
    private long mStartTime = 0L;    // in ms

    @Override
    public String toString() {
        return String.format(Locale.US, "%d,%d,%d\n", mSegmentID, mStartTime, mDuration);
    }

    public JDvrSegment(String path_prefix, int segment_id) throws IOException {
        mPathPrefix = path_prefix;
        mSegmentID = segment_id;
        mTsPath = String.format(Locale.US,"%s-%04d.ts",mPathPrefix, mSegmentID);
        mTsFile = new File(mTsPath);
        mTsStream = new FileOutputStream(mTsFile);
        mIndexPath = String.format(Locale.US,"%s-%04d.idx",mPathPrefix, mSegmentID);
        mIndexFile = new File(mIndexPath);
        mIndexStream = new FileOutputStream(mIndexFile);
        final String buf = "{\"time\":0, \"offset\":0}\n";
        mIndexStream.write(buf.getBytes(), 0, buf.length());
        Log.i(TAG,"New segment: "+mTsPath);
    }

    public int id() {
        return mSegmentID;
    }
    public int write(byte[] buffer, int offset, int size) {
        if (isClosed()) {
            Log.w(TAG,"Trying to write but segment file has been closed already.");
            return 0;
        }
        try {
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
        if (isClosed()) {
            Log.w(TAG,"Trying to write but index file has been closed already.");
            return 0;
        }
        JsonReader reader = new JsonReader(new StringReader(new String(buffer)));
        long timeOffset = -1;
        long timeOffsetFromOrigin = -1;
        try {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (name.equals("time")) {
                    timeOffset = reader.nextLong();
                } else if (name.equals("time_offset_from_origin")) {
                    timeOffsetFromOrigin = reader.nextLong();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
        }
        if (timeOffset >= 0) {
            mDuration = timeOffset;
        }
        if (timeOffsetFromOrigin > 0 && mStartTime == 0) {
            mStartTime = timeOffsetFromOrigin - timeOffset;
        }
        try {
            mIndexStream.write(buffer, 0, size);
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
            return 0;
        }
        return 0;
    }
    public void close() {
        try {
            mTsStream.flush();
            mIndexStream.flush();
        } catch (IOException e) {
            Log.e(TAG, "Exception: " + e);
            e.printStackTrace();
        }
        mSegmentSize = (int)mTsFile.length();
        final long nbPackets = mSegmentSize/188;
        String line = String.format(Locale.US,
                "{\"time\":%d, \"id\":%d, \"duration\":%d, \"size\":%d, \"nb_packets\":%d}\n",
                mDuration, mSegmentID, mDuration, mSegmentSize, nbPackets);
        writeIndex(line.getBytes(),line.length());
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
    }
    public int size() {
        return mSegmentSize;
    }
    public boolean flush() {
        if (mTsStream != null) {
            try {
                mTsStream.flush();
            } catch (IOException e) {
                Log.e(TAG, "Exception: " + e);
                e.printStackTrace();
                return false;
            }
        }
        mSegmentSize = (int)mTsFile.length();
        return true;
    }
    static public void setMaxSegmentSize(int size) {
        mMaxSegmentSize = size;
    }
    static public int getMaxSegmentSize() {
        return mMaxSegmentSize;
    }
    public long duration() {
        return mDuration;
    }
    public long getStartTime() {
        return mStartTime;
    }
    public long getPausedTime() {
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
        return (mTsFile == null || mIndexFile == null);
    }
}

package com.droidlogic.jdvrlib;

import android.media.tv.tuner.dvr.DvrSettings;

public class JDvrRecorderSettings {
    private final int mStatusMask;
    private final long mLowThreshold;
    private final long mHighThreshold;
    private final long mPacketSize;
    private final int mDataFormat;

    public int mRecorderBufferSize;
    public int mFilterBufferSize;
    public int mSegmentSize;

    private JDvrRecorderSettings(int statusMask, long lowThreshold,
                                 long highThreshold, long packetSize,
                                 int dataFormat,
                                 int recorderBufferSize, int filterBufferSize,
                                 int segmentSize
    ) {
        mStatusMask = statusMask;
        mLowThreshold = lowThreshold;
        mHighThreshold = highThreshold;
        mPacketSize = packetSize;
        mDataFormat = dataFormat;
        mRecorderBufferSize = recorderBufferSize;
        mFilterBufferSize = filterBufferSize;
        mSegmentSize = segmentSize;
    }
    public static Builder builder() {
        return new Builder();
    }
    public static final class Builder {
        private int mStatusMask;
        private long mLowThreshold = 100L;
        private long mHighThreshold = 900L;
        private long mPacketSize = 188L;
        private int mDataFormat = DvrSettings.DATA_FORMAT_TS;
        private int mRecorderBufferSize = 188 * 10 * 1000;
        private int mFilterBufferSize = 188 * 1000;
        private int mSegmentSize = 100 * 1024 * 1024;

        public Builder setStatusMask(int statusMask) {
            this.mStatusMask = statusMask;
            return this;
        }
        public Builder setLowThreshold(long lowThreshold) {
            this.mLowThreshold = lowThreshold;
            return this;
        }
        public Builder setHighThreshold(long highThreshold) {
            this.mHighThreshold = highThreshold;
            return this;
        }
        public Builder setPacketSize(long packetSize) {
            this.mPacketSize = packetSize;
            return this;
        }
        public Builder setDataFormat(int dataFormat) {
            this.mDataFormat = dataFormat;
            return this;
        }
        public Builder setRecorderBufferSize(int size) {
            this.mRecorderBufferSize = size;
            return this;
        }
        public Builder setSegmentSize(int size) {
            this.mSegmentSize = size;
            return this;
        }
        public Builder setFilterBufferSize(int size) {
            this.mFilterBufferSize = size;
            return this;
        }

        public JDvrRecorderSettings build() {
            return new JDvrRecorderSettings(mStatusMask,mLowThreshold,mHighThreshold,mPacketSize,
                    mDataFormat,mRecorderBufferSize,mFilterBufferSize,mSegmentSize);
        }
    }
    public DvrSettings getDvrSettings() {
        return DvrSettings
                .builder()
                .setStatusMask(mStatusMask)
                .setDataFormat(mDataFormat)
                .setLowThreshold(mLowThreshold)
                .setHighThreshold(mHighThreshold)
                .setPacketSize(mPacketSize)
                .build();
    }
}

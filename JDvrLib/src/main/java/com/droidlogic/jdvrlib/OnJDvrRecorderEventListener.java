package com.droidlogic.jdvrlib;

import android.os.Message;

public interface OnJDvrRecorderEventListener {
    // Enum
    static public class JDvrRecorderEvent {
        public final static int NOTIFY_PROGRESS = 2001;
        public final static int NOTIFY_INITIAL_STATE = 3001;
        public final static int NOTIFY_STARTING_STATE = 3002;
        public final static int NOTIFY_STARTED_STATE = 3003;
        public final static int NOTIFY_PAUSED_STATE = 3004;
        public final static int NOTIFY_STOPPING_STATE = 3005;
        public final static int NOTIFY_NO_DATA_ERROR = 4001;
        public final static int NOTIFY_IO_ERROR = 4002;
        public final static int NOTIFY_DISK_FULL_ERROR = 4003;
        public final static int NOTIFY_DEBUG_MSG = 5001;
    }

    void onJDvrRecorderEvent(Message msg);
}

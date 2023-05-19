package com.droidlogic.jdvrlib;

import android.os.Message;

public interface OnJDvrPlayerEventListener {
    static public class JDvrPlayerEvent {
        public final static int NOTIFY_PROGRESS = 2001;
        public final static int NOTIFY_EOS = 2002;
        public final static int NOTIFY_EDGE_LEAVING = 2003;
        public final static int NOTIFY_INITIAL_STATE = 3001;
        public final static int NOTIFY_STARTING_STATE = 3002;
        public final static int NOTIFY_SMOOTH_PLAYING_STATE = 3003;
        public final static int NOTIFY_SKIPPING_PLAYING_STATE = 3004;
        public final static int NOTIFY_PAUSED_STATE = 3005;
        public final static int NOTIFY_STOPPING_STATE = 3006;
        public final static int NOTIFY_DEBUG_MSG = 5001;
    }
    void onJDvrPlayerEvent(Message msg);
}

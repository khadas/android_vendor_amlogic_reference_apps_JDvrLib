package com.droidlogic.jdvrlib;

import android.os.Message;

public class JNIJDvrPlayerListener implements OnJDvrPlayerEventListener{
    private String TAG = JNIJDvrPlayerListener.class.getSimpleName();
    private JDvrPlayer mJDvrPlayerRef;
    public JNIJDvrPlayerListener(JDvrPlayer player) {
        mJDvrPlayerRef = player;
    }
    @Override
    public void onJDvrPlayerEvent(Message msg) {
        native_notifyJDvrPlayerEvent(mJDvrPlayerRef, msg);
    }
    private native void native_notifyJDvrPlayerEvent(JDvrPlayer player, Message msg);
}

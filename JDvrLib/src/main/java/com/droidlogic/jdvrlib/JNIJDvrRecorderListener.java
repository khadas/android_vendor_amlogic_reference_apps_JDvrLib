package com.droidlogic.jdvrlib;

import android.os.Message;
import android.util.Log;

public class JNIJDvrRecorderListener implements OnJDvrRecorderEventListener {
    private String TAG = JNIJDvrRecorderListener.class.getSimpleName();
    private JDvrRecorder mJDvrRecorderRef;
    public JNIJDvrRecorderListener(JDvrRecorder recorder) {
        mJDvrRecorderRef = recorder;
    }

    @Override
    public void onJDvrRecorderEvent(Message msg) {
        native_notifyJDvrRecorderEvent(mJDvrRecorderRef,msg);
    }

    private native void native_notifyJDvrRecorderEvent(JDvrRecorder recorder, Message msg);
}

package com.droidlogic.jdvrlibtest;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.droidlogic.jdvrlibtest.TestInstance.TaskMsg;

public class MainActivity extends AppCompatActivity {

    public final static int UI_MSG_STATUS = 1;
    private String msg;
    final private String TAG = "JDvrLibTest";
    private TestInstance mInstance = null;
    private Handler mUiHandler = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initHandler();

        mInstance = new TestInstance(0,this);
        msg="";
    }
    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");

        if (mInstance != null) {
            mInstance.destroy();
            mInstance = null;
        }
        releaseHandler();
        super.onDestroy();
        Log.d(TAG, "onDestroy end");
    }

    public void Button1Click(View v) {
        TextView textView = findViewById(R.id.textView1);
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_START_TUNING);
        msg += "Clicked START_TUNING\n";
        textView.setText(msg);
    }
    public void Button2Click(View v) {
        TextView textView = findViewById(R.id.textView1);
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_STOP_TUNING);
        msg += "Clicked STOP_TUNING\n";
        textView.setText(msg);
    }
    public void Button3Click(View v) {
        TextView textView = findViewById(R.id.textView1);
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_START_RECORDING);
        msg += "Clicked START_RECORDING\n";
        textView.setText(msg);
    }
    public void Button4Click(View v) {
        TextView textView = findViewById(R.id.textView1);
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_STOP_RECORDING);
        msg += "Clicked STOP_RECORDING\n";
        textView.setText(msg);
    }
    public void Button5Click(View v) {
        TextView textView = findViewById(R.id.textView1);
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_ADD_STREAMS1);
        msg += "Clicked ADD_STREAMS1\n";
        textView.setText(msg);
    }
    public void Button6Click(View v) {
        TextView textView = findViewById(R.id.textView1);
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_ADD_STREAMS2);
        msg += "Clicked ADD_STREAMS2\n";
        textView.setText(msg);
    }
    public void Button7Click(View v) {
        TextView textView = findViewById(R.id.textView1);
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_REMOVE_STREAM1);
        msg += "Clicked REMOVE_STREAMS1\n";
        textView.setText(msg);
    }
    public void Button8Click(View v) {
        TextView textView = findViewById(R.id.textView1);
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_PREPARE_RECORDER);
        msg += "Clicked PREPARE_RECORDER\n";
        textView.setText(msg);
    }
    public void Button9Click(View v) {
        TextView textView = findViewById(R.id.textView1);
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_REMOVE_STREAM2);
        msg += "Clicked REMOVE_STREAMS2\n";
        textView.setText(msg);
    }
    public void Button10Click(View v) {
        TextView textView = findViewById(R.id.textView1);
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_PAUSE_RECORDING);
        msg += "Clicked PAUSE_RECORDING\n";
        textView.setText(msg);
    }
    public void Button11Click(View v) {
        TextView textView = findViewById(R.id.textView1);
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_PREPARE_TIMESHIFT_RECORDER);
        msg += "Clicked PREPARE_TIMESHIFT_RECORDER\n";
        textView.setText(msg);
    }
    public Handler getUiHandler() {
        return mUiHandler;
    }
    private void initHandler() {
        Log.d(TAG, "Create UiHandler and UiHandlerCallback");
        mUiHandler = new TaskHandler(getMainLooper(), new UiHandlerCallback());
    }
    private void releaseHandler() {
        Log.d(TAG, "mUiHandler removeCallbacksAndMessages");
        if (mUiHandler != null) {
            mUiHandler.removeCallbacksAndMessages(null);
            mUiHandler = null;
        }
    }

    // Classes
    private class TaskHandler extends Handler {
        public TaskHandler(Looper looper, Callback callback) {
            super(looper, callback);
        }
    }
    private class UiHandlerCallback implements Handler.Callback {

        @Override
        public boolean handleMessage(Message message) {
            boolean result = true;
            Log.d(TAG, "UiHandlerCallback handleMessage:" + message.what + ", " + message.obj);
            switch (message.what) {
                case UI_MSG_STATUS:
                    TextView textView = findViewById(R.id.textView1);
                    msg += message.obj + "\n";
                    textView.setText(msg);
                    break;
                default:
                    result = false;
                    break;
            }
            return result;
        }
    }
}

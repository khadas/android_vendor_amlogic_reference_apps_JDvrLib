package com.droidlogic.jdvrlibtest;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.droidlogic.jdvrlib.JDvrPlayer.JDvrPlaybackProgress;
import com.droidlogic.jdvrlibtest.TestInstance.TaskMsg;

import java.util.ArrayList;
import java.util.Locale;
import java.util.stream.Collectors;

public class MainActivity extends AppCompatActivity {

    public final static int UI_MSG_STATUS = 1;
    public final static int UI_MSG_PROGRESS = 2;
    final private String TAG = "JDvrLibTest";
    private TestInstance mInstance = null;
    private Handler mUiHandler = null;
    private Surface mSurface;
    private ArrayList<String> mLogText = new ArrayList<>();
    private final static int MAX_LINES = 42;
    private boolean mIsGettingProgress = false;
    private final Runnable mGettingProgressRunnable = new Runnable() {
        @Override
        public void run() {
            mUiHandler.removeCallbacks(this);
            if (mIsGettingProgress) {
                try {
                    mUiHandler.postDelayed(this, 1000L);
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Exception: " + e);
                    e.printStackTrace();
                    return;
                }
                Message msg = new Message();
                msg.what = TaskMsg.TASK_MSG_NATIVE_GET_PROGRESS;
                mInstance.getTaskHandler().sendMessage(msg);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initHandler();
        ActionBar actionBar = getSupportActionBar();
        actionBar.hide();
        ProgressBar progressBar = findViewById(R.id.progressBar);
        progressBar.setProgress(0);
        progressBar.setMax(100);

        String[] items = new String[]{"-4.0","-2.0","-1.0","0.0","0.5","1.0","2.0","4.0"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,items);
        Spinner spinner1;
        spinner1 = findViewById(R.id.spinner1);
        spinner1.setAdapter(adapter);
        spinner1.setSelection(5);
        spinner1.post(new Runnable() {
            @Override
            public void run() {
                spinner1.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        String speed = (String) parent.getItemAtPosition(position);
                        Message msg = new Message();
                        msg.what = TaskMsg.TASK_MSG_SET_SPEED;
                        msg.obj = Double.parseDouble(speed);
                        mInstance.getTaskHandler().sendMessage(msg);
                        addToLogView("Clicked SET_SPEED "+speed);
                    }
                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });
            }
        });
        Spinner spinner2;
        spinner2 = findViewById(R.id.spinner2);
        spinner2.setAdapter(adapter);
        spinner2.setSelection(5);
        spinner2.post(new Runnable() {
            @Override
            public void run() {
                spinner2.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        String speed = (String) parent.getItemAtPosition(position);
                        Message msg = new Message();
                        msg.what = TaskMsg.TASK_MSG_NATIVE_SET_SPEED;
                        msg.obj = Double.parseDouble(speed);
                        mInstance.getTaskHandler().sendMessage(msg);
                        addToLogView("Clicked JNI Set Speed "+speed);
                    }
                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });
            }
        });

        mInstance = new TestInstance(0,this);
        SurfaceView surfaceView = findViewById(R.id.surfaceView);
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                mSurface = holder.getSurface();
            }
            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            }
            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            }
        });
        addToLogView("NOTE1: please use the settings below at headend side");
        addToLogView("    Modulation:  DVBC,  64 QAM");
        addToLogView("    Stream:  BBC MUX UH.ts");
        addToLogView("    Frequency:  666MHz");
        addToLogView("    Symbol rate:  6,875,000");
        addToLogView("NOTE2: \"8888\" denotes timeshift. To operate on timeshift, please input \"8888\" in \"Rec id:\"");
        addToLogView("======================================================");
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
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_START_TUNING);
        addToLogView("Clicked START_TUNING");
    }
    public void Button2Click(View v) {
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_STOP_TUNING);
        addToLogView("Clicked STOP_TUNING");
    }
    public void Button3Click(View v) {
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_START_RECORDING);
        addToLogView("Clicked START_RECORDING");
    }
    public void Button4Click(View v) {
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_STOP_RECORDING);
        addToLogView("Clicked STOP_RECORDING");
    }
    public void Button5Click(View v) {
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_ADD_STREAMS1);
        addToLogView("Clicked ADD_STREAMS1");
    }
    public void Button6Click(View v) {
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_ADD_STREAMS2);
        addToLogView("Clicked ADD_STREAMS2");
    }
    public void Button7Click(View v) {
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_REMOVE_STREAM1);
        addToLogView("Clicked REMOVE_STREAMS1");
    }
    public void Button8Click(View v) {
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_PREPARE_RECORDER);
        addToLogView("Clicked PREPARE_RECORDER");
    }
    public void Button9Click(View v) {
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_REMOVE_STREAM2);
        addToLogView("Clicked REMOVE_STREAMS2");
    }
    public void Button10Click(View v) {
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_PAUSE_RECORDING);
        addToLogView("Clicked PAUSE_RECORDING");
    }
    public void Button11Click(View v) {
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_PREPARE_TIMESHIFT_RECORDER);
        addToLogView("Clicked PREPARE_TIMESHIFT_RECORDER");
    }
    public void Button12Click(View v) {
        EditText num = (EditText) findViewById(R.id.editRecordingNumber);
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_CREATE_PLAYER;
        msg.arg1 = Integer.parseInt(num.getText().toString());
        msg.obj = mSurface;
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked CREATE_PLAYER");
    }
    public void Button13Click(View v) {
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_START_PLAYBACK);
        addToLogView("Clicked START_PLAYBACK");
    }
    public void Button14Click(View v) {
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_STOP_PLAYBACK);
        addToLogView("Clicked STOP_PLAYBACK");
    }
    public void Button15Click(View v) {
        mInstance.getTaskHandler().sendEmptyMessage(TaskMsg.TASK_MSG_PAUSE_PLAYBACK);
        addToLogView("Clicked PAUSE_PLAYBACK");
    }
    public void Button16Click(View v) {
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_SEEK;
        msg.arg1 = -30;
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked SEEK -30s");
    }
    public void Button17Click(View v) {
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_SEEK;
        msg.arg1 = 30;
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked SEEK +30s");
    }
    public void Button18Click(View v) {
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_NATIVE_PREPARE_RECORDER;
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked JNI Prepare Recorder");
    }
    public void Button19Click(View v) {
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_NATIVE_ADD_STREAM;
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked JNI Add Stream");
    }
    public void Button20Click(View v) {
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_NATIVE_START_RECORDING;
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked JNI Start Recording");
    }
    public void Button21Click(View v) {
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_NATIVE_PAUSE_RECORDING;
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked JNI Pause Recording");
    }
    public void Button22Click(View v) {
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_NATIVE_STOP_RECORDING;
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked JNI Stop Recording");
    }
    public void Button23Click(View v) {
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_NATIVE_REMOVE_STREAM;
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked JNI Remove Stream");
    }
    public void Button26Click(View v) {
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_NATIVE_PREPARE_TSH_RECORDER;
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked JNI Prepare TSH Recorder");
    }
    public void Button27Click(View v) {
        EditText num = (EditText) findViewById(R.id.editRecordingNumber2);
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_NATIVE_CREATE_PLAYER;
        msg.arg1 = Integer.parseInt(num.getText().toString());
        msg.obj = mSurface;
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked JNI Create Player");
    }
    public void Button28Click(View v) {
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_NATIVE_PLAY;
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked JNI Play");
        mIsGettingProgress = true;
        mUiHandler.post(mGettingProgressRunnable);
    }
    public void Button29Click(View v) {
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_NATIVE_PAUSE_PLAYBACK;
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked JNI Pause Playback");
    }
    public void Button30Click(View v) {
        mIsGettingProgress = false;
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_NATIVE_STOP_PLAYBACK;
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked JNI Stop Playback");
    }
    public void Button31Click(View v) {
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_NATIVE_SEEK;
        msg.arg1 = -30;
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked JNI Seek -30s");
    }
    public void Button32Click(View v) {
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_NATIVE_SEEK;
        msg.arg1 = 30;
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked JNI Seek +30s");
    }
    public void Button33Click(View v) {
        EditText num = (EditText) findViewById(R.id.editRecordingNumber3);
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_DELETE;
        msg.arg1 = Integer.parseInt(num.getText().toString());
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked Delete");
    }
    public void Button34Click(View v) {
        EditText num = (EditText) findViewById(R.id.editRecordingNumber4);
        Message msg = new Message();
        msg.what = TaskMsg.TASK_MSG_JNI_DELETE;
        msg.arg1 = Integer.parseInt(num.getText().toString());
        mInstance.getTaskHandler().sendMessage(msg);
        addToLogView("Clicked JNI Delete");
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
            switch (message.what) {
                case UI_MSG_STATUS:
                    Log.d(TAG, "UI_MSG_STATUS: " + message.what + ", " + message.obj);
                    addToLogView(message.obj.toString());
                    break;
                case UI_MSG_PROGRESS:
                    JDvrPlaybackProgress progress = (JDvrPlaybackProgress) message.obj;
                    if (progress == null) {
                        break;
                    }
                    ProgressBar progressBar = findViewById(R.id.progressBar);
                    int percent = (int)((progress.currTime-progress.startTime)*100/(progress.endTime-progress.startTime));
                    progressBar.setProgress(percent);
                    TextView textView2 = findViewById(R.id.textView2);
                    String beginTime = String.format(Locale.US,"%02d:%02d:%02d",
                            progress.startTime/1000/3600,progress.startTime/1000%3600/60,progress.startTime/1000%60);
                    textView2.setText(beginTime);
                    TextView textView3 = findViewById(R.id.textView3);
                    String endTime = String.format(Locale.US,"%02d:%02d:%02d",
                            progress.endTime/1000/3600,progress.endTime/1000%3600/60,progress.endTime/1000%60);
                    textView3.setText(endTime);
                    break;
                default:
                    result = false;
                    break;
            }
            return result;
        }
    }

    private void addToLogView(String line) {
        mLogText.add(line);
        while (mLogText.size()> MAX_LINES) {
            mLogText.remove(0);
        }
        String allText = mLogText.stream().collect(Collectors.joining("\n"));
        TextView textView = findViewById(R.id.textView1);
        textView.setText(allText);
    }
}

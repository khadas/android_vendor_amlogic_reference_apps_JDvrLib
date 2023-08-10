#include <sstream>
#include <iomanip>
using namespace std;

#include <unistd.h>
#include "log.h"
#include "JDvrLibJNI.h"
#include "Utilities.h"

#define BASEDIR "/storage/emulated/0/Recordings/"

static jobject gTunerForRecording;
static jobject gTunerForPlayback;
static jobject gJDvrRecorderSettings;
static jobject gJDvrPlayerSettings;
static jobject gASPlayer;
static am_dvr_file_handle gDvrFileHandle = nullptr;
static am_dvr_recorder_handle gRecorderHandle = nullptr;
static am_dvr_file_handle gDvrFileHandle2 = nullptr;
static am_dvr_player_handle gPlayerHandle = nullptr;

struct playback_progress_t {
    jfieldID sessionNumberField;
    jfieldID stateField;
    jfieldID speedField;
    jfieldID currTimeField;
    jfieldID startTimeField;
    jfieldID endTimeField;
    jfieldID durationField;
    jfieldID currSegmentIdField;
    jfieldID firstSegmentIdField;
    jfieldID lastSegmentIdField;
    jfieldID numberOfSegmentsField;
};

static bool gJniInit = false;
static jclass gPlaybackProgressCls;
static playback_progress_t gPlaybackProgressCtx;

static bool initJNI(JNIEnv *env)
{
    if (!gJniInit) {
        // JDvrPlaybackProgress
        jclass playbackprogressCls = env->FindClass("com/droidlogic/jdvrlib/JDvrPlayer$JDvrPlaybackProgress");
        gPlaybackProgressCls = static_cast<jclass>(env->NewGlobalRef(playbackprogressCls));
        env->DeleteLocalRef(playbackprogressCls);
        gPlaybackProgressCtx.sessionNumberField = GetFieldIDOrDie(env, gPlaybackProgressCls, "sessionNumber", "I");
        gPlaybackProgressCtx.stateField = GetFieldIDOrDie(env, gPlaybackProgressCls, "state", "I");
        gPlaybackProgressCtx.speedField = GetFieldIDOrDie(env, gPlaybackProgressCls, "speed", "D");
        gPlaybackProgressCtx.currTimeField = GetFieldIDOrDie(env, gPlaybackProgressCls, "currTime", "J");
        gPlaybackProgressCtx.startTimeField = GetFieldIDOrDie(env, gPlaybackProgressCls, "startTime", "J");
        gPlaybackProgressCtx.endTimeField = GetFieldIDOrDie(env, gPlaybackProgressCls, "endTime", "J");
        gPlaybackProgressCtx.durationField = GetFieldIDOrDie(env, gPlaybackProgressCls, "duration", "J");
        gPlaybackProgressCtx.currSegmentIdField = GetFieldIDOrDie(env, gPlaybackProgressCls, "currSegmentId", "I");
        gPlaybackProgressCtx.firstSegmentIdField = GetFieldIDOrDie(env, gPlaybackProgressCls, "firstSegmentId", "I");
        gPlaybackProgressCtx.lastSegmentIdField = GetFieldIDOrDie(env, gPlaybackProgressCls, "lastSegmentId", "I");
        gPlaybackProgressCtx.numberOfSegmentsField = GetFieldIDOrDie(env, gPlaybackProgressCls, "numberOfSegments", "I");
        gJniInit = true;
    }
    return true;
}

static void ref_on_recorder_event_callback (
        am_dvr_recorder_handle handle,
        am_dvr_recorder_event event,
        void *event_data)
{
    if (event == AM_DVR_RECORDER_EVENT_PROGRESS) {
        am_dvr_recording_progress* evt = (am_dvr_recording_progress*) event_data;
        if (evt != nullptr) {
            ALOGD("Recording progress: duration:%ld, startTime:%ld, endTime:%ld,"
                    " numberOfSegments:%d, firstSegmentId:%d, lastSegmentId:%d, size:%ld",
                    evt->duration,evt->startTime,evt->endTime,
                    evt->numberOfSegments,evt->firstSegmentId,evt->lastSegmentId,evt->size);
            delete evt;
        }
    } else if (event == AM_DVR_RECORDER_EVENT_INITIAL_STATE) {
        ALOGD("%s event: AM_DVR_RECORDER_EVENT_INITIAL_STATE",__func__);
    } else if (event == AM_DVR_RECORDER_EVENT_STARTING_STATE) {
        ALOGD("%s event: AM_DVR_RECORDER_EVENT_STARTING_STATE",__func__);
    } else if (event == AM_DVR_RECORDER_EVENT_STARTED_STATE) {
        ALOGD("%s event: AM_DVR_RECORDER_EVENT_STARTED_STATE",__func__);
    } else if (event == AM_DVR_RECORDER_EVENT_PAUSED_STATE) {
        ALOGD("%s event: AM_DVR_RECORDER_EVENT_PAUSED_STATE",__func__);
    } else if (event == AM_DVR_RECORDER_EVENT_STOPPING_STATE) {
        ALOGD("%s event: AM_DVR_RECORDER_EVENT_STOPPING_STATE",__func__);
    } else if (event == AM_DVR_RECORDER_EVENT_NO_DATA_ERROR) {
        ALOGD("%s event: AM_DVR_RECORDER_EVENT_NO_DATA_ERROR",__func__);
    } else if (event == AM_DVR_RECORDER_EVENT_IO_ERROR) {
        ALOGD("%s event: AM_DVR_RECORDER_EVENT_IO_ERROR",__func__);
    } else if (event == AM_DVR_RECORDER_EVENT_DISK_FULL_ERROR) {
        ALOGD("%s event: AM_DVR_RECORDER_EVENT_DISK_FULL_ERROR",__func__);
    } else {
        ALOGD("%s unknown event: %d",__func__,event);
    }
}

static void ref_on_player_event_callback (
        am_dvr_player_handle handle,
        am_dvr_player_event event,
        void *event_data)
{
    if (event == AM_DVR_PLAYER_EVENT_PROGRESS) {
        am_dvr_playback_progress* evt = (am_dvr_playback_progress*) event_data;
        if (evt != nullptr) {
            ALOGD("Playback progress: currTime:%ld, startTime:%ld, endTime:%ld, duration:%ld"
                    " currentSegmentId:%d, firstSegmentId:%d, lastSegmentId:%d, numberOfSegments:%ld",
                    evt->currTime,evt->startTime,evt->endTime,evt->duration,
                    evt->currSegmentId,evt->firstSegmentId,evt->lastSegmentId,evt->numberOfSegments);
            delete evt;
        }
    } else if (event == AM_DVR_PLAYER_EVENT_EOS) {
        ALOGD("%s event: AM_DVR_PLAYER_EVENT_EOS",__func__);
    } else if (event == AM_DVR_PLAYER_EVENT_EDGE_LEAVING) {
        ALOGD("%s event: AM_DVR_PLAYER_EVENT_EDGE_LEAVING",__func__);
    } else if (event == AM_DVR_PLAYER_EVENT_INITIAL_STATE) {
        ALOGD("%s event: AM_DVR_PLAYER_EVENT_INITIAL_STATE",__func__);
    } else if (event == AM_DVR_PLAYER_EVENT_STARTING_STATE) {
        ALOGD("%s event: AM_DVR_PLAYER_EVENT_STARTING_STATE",__func__);
    } else if (event == AM_DVR_PLAYER_EVENT_SMOOTH_PLAYING_STATE) {
        ALOGD("%s event: AM_DVR_PLAYER_EVENT_SMOOTH_PLAYING_STATE",__func__);
    } else if (event == AM_DVR_PLAYER_EVENT_SKIPPING_PLAYING_STATE) {
        ALOGD("%s event: AM_DVR_PLAYER_EVENT_SKIPPING_PLAYING_STATE",__func__);
    } else if (event == AM_DVR_PLAYER_EVENT_PAUSED_STATE) {
        ALOGD("%s event: AM_DVR_PLAYER_EVENT_PAUSED_STATE",__func__);
    } else if (event == AM_DVR_PLAYER_EVENT_STOPPING_STATE) {
        ALOGD("%s event: AM_DVR_PLAYER_EVENT_STOPPING_STATE",__func__);
        gDvrFileHandle2 = nullptr;
        gPlayerHandle = nullptr;
    } else {
        ALOGD("%s unknown event: %d",__func__,event);
    }
}

static jint native_passTuners(JNIEnv *env, jobject jTestInstance,
        jobject jTunerForRecording, jobject jTunerForPlayback)
{
    ALOGD("%s, enter",__PRETTY_FUNCTION__);
    gTunerForRecording = MakeGlobalRefOrDie(env, jTunerForRecording);
    gTunerForPlayback = MakeGlobalRefOrDie(env, jTunerForPlayback);
    return 0;
}

static jint native_passRecorderSettings(JNIEnv *env, jobject jTestInstance, jobject jSettings)
{
    ALOGD("%s, enter",__PRETTY_FUNCTION__);
    gJDvrRecorderSettings = MakeGlobalRefOrDie(env, jSettings);
    return 0;
}

static jint native_passPlayerSettings(JNIEnv *env, jobject jTestInstance, jobject jSettings)
{
    ALOGD("%s, enter",__PRETTY_FUNCTION__);
    gJDvrPlayerSettings = MakeGlobalRefOrDie(env, jSettings);
    return 0;
}

static jint native_passASPlayer(JNIEnv *env, jobject jTestInstance, jobject jASPlayer)
{
    ALOGD("%s, enter",__PRETTY_FUNCTION__);
    gASPlayer = MakeGlobalRefOrDie(env, jASPlayer);
    return 0;
}

static jint native_prepareRecorder(JNIEnv *env, jobject jTestInstance, jint rec_id)
{
    ALOGD("%s, enter",__func__);
    am_dvr_file_handle fhdl1 = nullptr;

    if (gDvrFileHandle != nullptr || gRecorderHandle != nullptr) {
        ALOGE("%s, Cannot create a recording due to previous recording is in progress",__func__);
        return -1;
    }

    stringstream buf;
    buf << BASEDIR << setfill('0') << setw(8) << rec_id;

    AmDvr_File_create1(buf.str().c_str(),true,&fhdl1);
    ALOGD("%s, file handle:%p",__func__,fhdl1);
    gDvrFileHandle = fhdl1;

    am_dvr_recorder_handle rhdl1 = nullptr;
    am_dvr_recorder_init_params params;
    params.tuner = gTunerForRecording;
    params.jdvrfile_handle = fhdl1;
    params.settings = gJDvrRecorderSettings;
    params.callback = ref_on_recorder_event_callback;
    AmDvr_Recorder_create(&params,&rhdl1);
    gRecorderHandle = rhdl1;
    ALOGD("%s, recorder handle:%p",__func__,rhdl1);
    return 0;
}

static jint native_prepareTimeshiftRecorder(JNIEnv *env, jobject jTestInstance)
{
    ALOGD("%s, enter",__func__);
    am_dvr_file_handle fhdl1 = nullptr;

    if (gDvrFileHandle != nullptr || gRecorderHandle != nullptr) {
        ALOGE("%s, Cannot create a recording due to previous recording is in progress",__func__);
        return -1;
    }

    AmDvr_File_create2(BASEDIR"00008888",300*1024*1024,360,true,&fhdl1);
    ALOGD("%s, file handle:%p",__func__,fhdl1);
    gDvrFileHandle = fhdl1;

    am_dvr_recorder_handle rhdl1 = nullptr;
    am_dvr_recorder_init_params params;
    params.tuner = gTunerForRecording;
    params.jdvrfile_handle = fhdl1;
    params.settings = gJDvrRecorderSettings;
    params.callback = ref_on_recorder_event_callback;
    AmDvr_Recorder_create(&params,&rhdl1);
    gRecorderHandle = rhdl1;
    ALOGD("%s, recorder handle:%p",__func__,rhdl1);
    return 0;
}

static jint native_preparePlayer(JNIEnv *env, jobject jTestInstance, jint rec_id, jobject surface)
{
    ALOGD("%s, enter",__func__);
    am_dvr_file_handle fhdl1 = nullptr;

    if (gDvrFileHandle2 != nullptr || gPlayerHandle != nullptr) {
        ALOGE("%s, Cannot create a player due to previous playback is in progress",__func__);
        return -1;
    }

    stringstream buf;
    buf << BASEDIR << setfill('0') << setw(8) << rec_id;

    AmDvr_File_create3(buf.str().c_str(),&fhdl1);
    ALOGD("%s, file handle:%p",__func__,fhdl1);
    gDvrFileHandle2 = fhdl1;

    am_dvr_player_handle phdl1 = nullptr;
    am_dvr_player_init_params params;
    params.tuner = gTunerForPlayback;
    params.jdvrfile_handle = fhdl1;
    params.settings = gJDvrPlayerSettings;
    params.callback = ref_on_player_event_callback;
    params.surface = surface;
    AmDvr_Player_create(&params,&phdl1);
    gPlayerHandle = phdl1;
    ALOGD("%s, player handle:%p",__func__,phdl1);
    return 0;
}

static jint native_addStream(JNIEnv *env, jobject jTestInstance)
{
    if (gRecorderHandle == nullptr) {
        ALOGE("%s, Recorder handle is invalid",__func__);
        return -1;
    }
    AmDvr_Recorder_addStream(gRecorderHandle,0,AM_DVR_STREAM_TYPE_OTHER,0);
    AmDvr_Recorder_addStream(gRecorderHandle,620,AM_DVR_STREAM_TYPE_VIDEO,3);
    AmDvr_Recorder_addStream(gRecorderHandle,621,AM_DVR_STREAM_TYPE_AUDIO,3);
    AmDvr_Recorder_addStream(gRecorderHandle,622,AM_DVR_STREAM_TYPE_AUDIO,3);
    AmDvr_Recorder_addStream(gRecorderHandle,4671,AM_DVR_STREAM_TYPE_OTHER,0);
    return 0;
}

static jint native_removeStream(JNIEnv *env, jobject jTestInstance)
{
    if (gRecorderHandle == nullptr) {
        ALOGE("%s, Recorder handle is invalid",__func__);
        return -1;
    }
    AmDvr_Recorder_removeStream(gRecorderHandle,0);
    AmDvr_Recorder_removeStream(gRecorderHandle,620);
    AmDvr_Recorder_removeStream(gRecorderHandle,621);
    AmDvr_Recorder_removeStream(gRecorderHandle,622);
    AmDvr_Recorder_removeStream(gRecorderHandle,4671);
    return 0;
}

static jint native_startRecording(JNIEnv *env, jobject jTestInstance)
{
    ALOGD("%s, enter",__func__);
    if (gRecorderHandle == nullptr) {
        ALOGE("%s, Recorder handle is invalid",__func__);
        return -1;
    }
    AmDvr_Recorder_start(gRecorderHandle);
    return 0;
}

static jint native_pauseRecording(JNIEnv *env, jobject jTestInstance)
{
    ALOGD("%s, enter",__func__);
    if (gRecorderHandle == nullptr) {
        ALOGE("%s, Recorder handle is invalid",__func__);
        return -1;
    }
    AmDvr_Recorder_pause(gRecorderHandle);
    return 0;
}

static jint native_stopRecording(JNIEnv *env, jobject jTestInstance)
{
    ALOGD("%s, enter",__func__);
    if (gRecorderHandle == nullptr) {
        ALOGE("%s, Recorder handle is invalid",__func__);
        return -1;
    }
    AmDvr_Recorder_stop(gRecorderHandle);
    gRecorderHandle = nullptr;
    gDvrFileHandle = nullptr;
    return 0;
}

static jboolean native_play(JNIEnv *env, jobject jTestInstance)
{
    ALOGD("%s, enter",__func__);
    if (gPlayerHandle == nullptr) {
        ALOGE("%s, Player handle is invalid",__func__);
        return JNI_FALSE;
    }
    am_dvr_result result = AmDvr_Player_play(gPlayerHandle);
    return (result == JDVRLIB_JNI_OK) ? JNI_TRUE : JNI_FALSE;
}

static jboolean native_pausePlayback(JNIEnv *env, jobject jTestInstance)
{
    ALOGD("%s, enter",__func__);
    if (gPlayerHandle == nullptr) {
        ALOGE("%s, Player handle is invalid",__func__);
        return JNI_FALSE;
    }
    am_dvr_result result = AmDvr_Player_pause(gPlayerHandle);
    return (result == JDVRLIB_JNI_OK) ? JNI_TRUE : JNI_FALSE;
}

static jboolean native_stopPlayback(JNIEnv *env, jobject jTestInstance)
{
    ALOGD("%s, enter",__func__);
    if (gPlayerHandle == nullptr) {
        ALOGE("%s, Player handle is invalid",__func__);
        return JNI_FALSE;
    }
    am_dvr_result result = AmDvr_Player_stop(gPlayerHandle);
    gPlayerHandle = nullptr;
    gDvrFileHandle2 = nullptr;
    return (result == JDVRLIB_JNI_OK) ? JNI_TRUE : JNI_FALSE;
}

static jboolean native_seek(JNIEnv *env, jobject jTestInstance, jint offsetInSec)
{
    if (gPlayerHandle == nullptr) {
        ALOGE("%s, Player handle is invalid",__func__);
        return JNI_FALSE;
    }
    am_dvr_result result = AmDvr_Player_seek(gPlayerHandle, offsetInSec);
    return (result == JDVRLIB_JNI_OK) ? JNI_TRUE : JNI_FALSE;
}

static jboolean native_setSpeed(JNIEnv *env, jobject jTestInstance, jdouble speed)
{
    if (gPlayerHandle == nullptr) {
        ALOGE("%s, Player handle is invalid",__func__);
        return JNI_FALSE;
    }
    am_dvr_result result = AmDvr_Player_setSpeed(gPlayerHandle, speed);
    return (result == JDVRLIB_JNI_OK) ? JNI_TRUE : JNI_FALSE;
}

static jobject native_getPlayingProgress(JNIEnv *env, jobject jTestInstance)
{
    if (gDvrFileHandle2 == nullptr) {
        ALOGE("%s, The file to query progress is invalid",__func__);
        return 0;
    }

    jint session_number = 0;
    jint state = 0;
    jdouble speed = 0;
    jlong playing_time = 0;
    jlong start_time = 0;
    jlong end_time = 0;
    jlong duration = 0;
    jint curr_segment_id = 0;
    jint first_segment_id = 0;
    jint last_segment_id = 0;
    jint number_of_segments = 0;

    AmDvr_File_getPlayingTime(gDvrFileHandle2,&playing_time);
    AmDvr_File_duration(gDvrFileHandle2,&duration);
    AmDvr_File_getStartTime(gDvrFileHandle2,&start_time);
    end_time = start_time + duration;
    AmDvr_File_getCurrSegmentId(gDvrFileHandle2,&curr_segment_id);
    AmDvr_File_getFirstSegmentId(gDvrFileHandle2,&first_segment_id);
    AmDvr_File_getLastSegmentId(gDvrFileHandle2,&last_segment_id);
    AmDvr_File_getNumberOfSegments(gDvrFileHandle2,&number_of_segments);

    jobject progress = env->AllocObject(gPlaybackProgressCls);
    env->SetIntField(progress,gPlaybackProgressCtx.sessionNumberField,0);
    env->SetIntField(progress,gPlaybackProgressCtx.stateField,0);
    env->SetDoubleField(progress,gPlaybackProgressCtx.speedField,0);
    env->SetLongField(progress,gPlaybackProgressCtx.currTimeField,playing_time);
    env->SetLongField(progress,gPlaybackProgressCtx.startTimeField,start_time);
    env->SetLongField(progress,gPlaybackProgressCtx.endTimeField,end_time);
    env->SetLongField(progress,gPlaybackProgressCtx.durationField,duration);
    env->SetIntField(progress,gPlaybackProgressCtx.currSegmentIdField,curr_segment_id);
    env->SetIntField(progress,gPlaybackProgressCtx.firstSegmentIdField,first_segment_id);
    env->SetIntField(progress,gPlaybackProgressCtx.lastSegmentIdField,last_segment_id);
    env->SetIntField(progress,gPlaybackProgressCtx.numberOfSegmentsField,number_of_segments);

    return progress;
}

static jboolean native_delete(JNIEnv *env, jobject jTestInstance, jstring jPathPrefix)
{
    const char* path_prefix = env->GetStringUTFChars(jPathPrefix,0);
    am_dvr_result result = AmDvr_deleteRecord(path_prefix);
    env->ReleaseStringUTFChars(jPathPrefix,path_prefix);
    return (result == JDVRLIB_JNI_OK) ? JNI_TRUE : JNI_FALSE;
}

static const JNINativeMethod gJniTestInstanceMethods[] = {
    { "native_passTuners", "(Landroid/media/tv/tuner/Tuner;Landroid/media/tv/tuner/Tuner;)V", (void*)native_passTuners},
    { "native_passRecorderSettings", "(Lcom/droidlogic/jdvrlib/JDvrRecorderSettings;)V", (void*)native_passRecorderSettings},
    { "native_passPlayerSettings", "(Lcom/droidlogic/jdvrlib/JDvrPlayerSettings;)V", (void*)native_passPlayerSettings},
    { "native_prepareRecorder", "(I)I", (void*)native_prepareRecorder},
    { "native_prepareTimeshiftRecorder", "()I", (void*)native_prepareTimeshiftRecorder},
    { "native_preparePlayer", "(ILandroid/view/Surface;)I", (void*)native_preparePlayer},
    { "native_addStream", "()V", (void*)native_addStream},
    { "native_removeStream", "()V", (void*)native_removeStream},
    { "native_startRecording", "()V", (void*)native_startRecording},
    { "native_pauseRecording", "()V", (void*)native_pauseRecording},
    { "native_stopRecording", "()V", (void*)native_stopRecording},
    { "native_play", "()Z", (void*)native_play},
    { "native_pausePlayback", "()Z", (void*)native_pausePlayback},
    { "native_stopPlayback", "()Z", (void*)native_stopPlayback},
    { "native_seek", "(I)Z", (void*)native_seek},
    { "native_setSpeed", "(D)Z", (void*)native_setSpeed},
    { "native_getPlayingProgress", "()Lcom/droidlogic/jdvrlib/JDvrPlayer$JDvrPlaybackProgress;", (void*)native_getPlayingProgress},
    { "native_delete", "(Ljava/lang/String;)Z", (void*)native_delete},
};

jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = NULL;

    ALOGD("%s, enter",__PRETTY_FUNCTION__);
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("ERROR: GetEnv failed");
        return -1;
    }

    registerNativeMethods(env,
            "com/droidlogic/jdvrlibtest/TestInstance",
            gJniTestInstanceMethods, 18);

    initJNI(env);
    return JNI_VERSION_1_4;
}


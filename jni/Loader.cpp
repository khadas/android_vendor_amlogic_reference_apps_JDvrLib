#include <assert.h>
#include <unistd.h>
#include <vector>
#include <algorithm>

using namespace std;

#include "log.h"
#include "Loader.h"
#include "JDvrLibJNI.h"
#include "Utilities.h"

struct jdvr_file_t {
    jmethodID constructor1MID;
    jmethodID constructor2MID;
    jmethodID constructor3MID;
    jmethodID isTimeshiftMID;
    jmethodID durationMID;
    jmethodID duration2MID;
    jmethodID sizeMID;
    jmethodID size2MID;
    jmethodID getPlayingTimeMID;
    jmethodID getStartTimeMID;
    jmethodID getSegmentIdBeingReadMID;
    jmethodID getFirstSegmentIdMID;
    jmethodID getLastSegmentIdMID;
    jmethodID getNumberOfSegmentsMID;
    jmethodID getVideoPIDMID;
    jmethodID getVideoFormatMID;
    jmethodID getVideoMIMETypeMID;
    jmethodID getAudioPIDMID;
    jmethodID getAudioFormatMID;
    jmethodID getAudioMIMETypeMID;
    jmethodID closeMID;
    jmethodID removeMID;
};

struct jdvr_recorder_t {
    jmethodID constructorMID;
    jmethodID addStreamMID;
    jmethodID removeStreamMID;
    jmethodID startMID;
    jmethodID pauseMID;
    jmethodID stopMID;
};

struct jdvr_player_t {
    jmethodID constructorMID;
    jmethodID playMID;
    jmethodID pauseMID;
    jmethodID stopMID;
    jmethodID seekMID;
    jmethodID setSpeedMID;
};

struct message_t {
    jfieldID whatField;
    jfieldID arg1Field;
    jfieldID arg2Field;
    jfieldID objField;
};

struct recording_progress_t {
    jfieldID sessionNumberField;
    jfieldID stateField;
    jfieldID durationField;
    jfieldID startTimeField;
    jfieldID endTimeField;
    jfieldID numberOfSegmentsField;
    jfieldID firstSegmentIdField;
    jfieldID lastSegmentIdField;
    jfieldID sizeField;
};

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

static jclass gJDvrFileCls;
static jdvr_file_t gJDvrFileCtx;

static jclass gJDvrRecorderCls;
static jdvr_recorder_t gJDvrRecorderCtx;

static jclass gJDvrPlayerCls;
static jdvr_player_t gJDvrPlayerCtx;

static jclass gMessageCls;
static message_t gMessageCtx;

static jclass gRecordingProgressCls;
static recording_progress_t gRecordingProgressCtx;

static jclass gPlaybackProgressCls;
static playback_progress_t gPlaybackProgressCtx;

static volatile bool gJniInit = false;
JavaVM* Loader::mJavaVM = nullptr;

vector<am_dvr_file_handle> vecDvrFiles;  // an array of JDvrFile*
vector<am_dvr_recorder_handle> vecDvrRecorders;  // an array of JDvrRecorder*
vector<am_dvr_player_handle> vecDvrPlayers;  // an array of JDvrPlayer*

static jint native_notifyJDvrRecorderEvent(JNIEnv *env, jobject jListener, jobject jRecorder, jobject jMessage);
static jint native_notifyJDvrPlayerEvent(JNIEnv *env, jobject jListener, jobject jPlayer, jobject jMessage);

static const JNINativeMethod gJniJDvrRecorderListenerMethods[] = {
            { "native_notifyJDvrRecorderEvent", "(Lcom/droidlogic/jdvrlib/JDvrRecorder;Landroid/os/Message;)V", (void*)native_notifyJDvrRecorderEvent},
};

static const JNINativeMethod gJniJDvrPlayerListenerMethods[] = {
            { "native_notifyJDvrPlayerEvent", "(Lcom/droidlogic/jdvrlib/JDvrPlayer;Landroid/os/Message;)V", (void*)native_notifyJDvrPlayerEvent},
};

jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    jint result = -1;
    JNIEnv* env = NULL;

    ALOGD("%s, enter",__PRETTY_FUNCTION__);
    Loader::setJavaVM(vm);
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("ERROR: GetEnv failed");
        return -1;
    }
    ALOGD("%s, env:%p",__func__,env);

    AmDvr_registerJNI(env);

    return JNI_VERSION_1_4;
}

// Loader
void Loader::setJavaVM(JavaVM* javaVM) {
    mJavaVM = javaVM;
    ALOGD("%s %p",__func__,mJavaVM);
}

/** return a pointer to the JNIEnv for this thread */
JNIEnv* Loader::getJNIEnv() {
    if (mJavaVM == nullptr)
    {
        ALOGE("%s, java vm is null",__func__);
        return nullptr;
    }
    JNIEnv* env;
    int ret = mJavaVM->GetEnv((void**) &env, JNI_VERSION_1_4);
    if (ret != JNI_OK) {
        ALOGE("%s, fail to get JNIEnv*, ret:%d",__func__,ret);
        return nullptr;
    }
    return env;
}

/** create a JNIEnv* for this thread or assert if one already exists */
JNIEnv* Loader::attachJNIEnv(const char *envName) {
    assert(getJNIEnv() == nullptr);
    JNIEnv* env = nullptr;
    JavaVMAttachArgs args = { JNI_VERSION_1_4, envName, NULL };
    int result = mJavaVM->AttachCurrentThread(&env, (void*) &args);
    if (result != JNI_OK) {
        ALOGE("%d thread attach failed: %#x", gettid(), result);
    }
    return env;
}

/** detach the current thread from the JavaVM */
void Loader::detachJNIEnv() {
    ALOGV("%d detachJNIEnv", gettid());
    mJavaVM->DetachCurrentThread();
}

bool Loader::initJDvrLibJNI(JNIEnv *jniEnv) {
    ALOGD("%s, enter",__PRETTY_FUNCTION__);
    JNIEnv *env = jniEnv;
    if (env == nullptr) {
        env = Loader::getJNIEnv();
        if (env == nullptr) {
            ALOGE("Failed to get JNIEnv*, attaching thread failed");
            return false;
        }
    }

    // JDvrFile
    jclass jdvrfileCls = env->FindClass("com/droidlogic/jdvrlib/JDvrFile");
    gJDvrFileCls = static_cast<jclass>(env->NewGlobalRef(jdvrfileCls));
    env->DeleteLocalRef(jdvrfileCls);
    gJDvrFileCtx.constructor1MID = GetMethodIDOrDie(env, gJDvrFileCls, "<init>", "(Ljava/lang/String;Z)V");
    gJDvrFileCtx.constructor2MID = GetMethodIDOrDie(env, gJDvrFileCls, "<init>", "(Ljava/lang/String;JIZ)V");
    gJDvrFileCtx.constructor3MID = GetMethodIDOrDie(env, gJDvrFileCls, "<init>", "(Ljava/lang/String;)V");
    gJDvrFileCtx.isTimeshiftMID = GetMethodIDOrDie(env, gJDvrFileCls, "isTimeshift", "()Z");
    gJDvrFileCtx.durationMID = GetMethodIDOrDie(env, gJDvrFileCls, "duration", "()J");
    gJDvrFileCtx.duration2MID = GetStaticMethodIDOrDie(env, gJDvrFileCls, "duration2", "(Ljava/lang/String;)J");
    gJDvrFileCtx.sizeMID = GetMethodIDOrDie(env, gJDvrFileCls, "size", "()J");
    gJDvrFileCtx.size2MID = GetStaticMethodIDOrDie(env, gJDvrFileCls, "size2", "(Ljava/lang/String;)J");
    gJDvrFileCtx.getPlayingTimeMID = GetMethodIDOrDie(env, gJDvrFileCls, "getPlayingTime", "()J");
    gJDvrFileCtx.getStartTimeMID = GetMethodIDOrDie(env, gJDvrFileCls, "getStartTime", "()J");
    gJDvrFileCtx.getSegmentIdBeingReadMID = GetMethodIDOrDie(env, gJDvrFileCls, "getSegmentIdBeingRead", "()I");
    gJDvrFileCtx.getFirstSegmentIdMID = GetMethodIDOrDie(env, gJDvrFileCls, "getFirstSegmentId", "()I");
    gJDvrFileCtx.getLastSegmentIdMID = GetMethodIDOrDie(env, gJDvrFileCls, "getLastSegmentId", "()I");
    gJDvrFileCtx.getNumberOfSegmentsMID = GetMethodIDOrDie(env, gJDvrFileCls, "getNumberOfSegments", "()I");
    gJDvrFileCtx.getVideoPIDMID = GetMethodIDOrDie(env, gJDvrFileCls, "getVideoPID", "()I");
    gJDvrFileCtx.getVideoFormatMID = GetMethodIDOrDie(env, gJDvrFileCls, "getVideoFormat", "()I");
    gJDvrFileCtx.getVideoMIMETypeMID = GetMethodIDOrDie(env, gJDvrFileCls, "getVideoMIMEType", "()Ljava/lang/String;");
    gJDvrFileCtx.getAudioPIDMID = GetMethodIDOrDie(env, gJDvrFileCls, "getAudioPID", "()I");
    gJDvrFileCtx.getAudioFormatMID = GetMethodIDOrDie(env, gJDvrFileCls, "getAudioFormat", "()I");
    gJDvrFileCtx.getAudioMIMETypeMID = GetMethodIDOrDie(env, gJDvrFileCls, "getAudioMIMEType", "()Ljava/lang/String;");
    gJDvrFileCtx.closeMID = GetMethodIDOrDie(env, gJDvrFileCls, "close", "()V");
    gJDvrFileCtx.removeMID = GetStaticMethodIDOrDie(env, gJDvrFileCls, "delete2", "(Ljava/lang/String;)Z");

    // JDvrRecorder
    jclass jdvrrecorderCls = env->FindClass("com/droidlogic/jdvrlib/JDvrRecorder");
    gJDvrRecorderCls = static_cast<jclass>(env->NewGlobalRef(jdvrrecorderCls));
    env->DeleteLocalRef(jdvrrecorderCls);
    gJDvrRecorderCtx.constructorMID = GetMethodIDOrDie(env, gJDvrRecorderCls, "<init>",
            "("
            "Landroid/media/tv/tuner/Tuner;"
            "Lcom/droidlogic/jdvrlib/JDvrFile;"
            "Lcom/droidlogic/jdvrlib/JDvrRecorderSettings;"
            "Ljava/util/concurrent/Executor;"
            "Lcom/droidlogic/jdvrlib/OnJDvrRecorderEventListener;"
            ")V");
    gJDvrRecorderCtx.addStreamMID = GetMethodIDOrDie(env, gJDvrRecorderCls, "addStream", "(III)Z");
    gJDvrRecorderCtx.removeStreamMID = GetMethodIDOrDie(env, gJDvrRecorderCls, "removeStream", "(I)Z");
    gJDvrRecorderCtx.startMID = GetMethodIDOrDie(env, gJDvrRecorderCls, "start", "()Z");
    gJDvrRecorderCtx.pauseMID = GetMethodIDOrDie(env, gJDvrRecorderCls, "pause", "()Z");
    gJDvrRecorderCtx.stopMID = GetMethodIDOrDie(env, gJDvrRecorderCls, "stop", "()Z");

    // JDvrPlayer
    jclass jdvrplayerCls = env->FindClass("com/droidlogic/jdvrlib/JDvrPlayer");
    gJDvrPlayerCls = static_cast<jclass>(env->NewGlobalRef(jdvrplayerCls));
    env->DeleteLocalRef(jdvrplayerCls);
    gJDvrPlayerCtx.constructorMID = GetMethodIDOrDie(env, gJDvrPlayerCls, "<init>",
            "("
            "Landroid/media/tv/tuner/Tuner;"
            "Lcom/droidlogic/jdvrlib/JDvrFile;"
            "Lcom/droidlogic/jdvrlib/JDvrPlayerSettings;"
            "Ljava/util/concurrent/Executor;"
            "Lcom/droidlogic/jdvrlib/OnJDvrPlayerEventListener;"
            "Landroid/view/Surface;"
            ")V");
    gJDvrPlayerCtx.playMID = GetMethodIDOrDie(env, gJDvrPlayerCls, "play", "()Z");
    gJDvrPlayerCtx.pauseMID = GetMethodIDOrDie(env, gJDvrPlayerCls, "pause", "()Z");
    gJDvrPlayerCtx.stopMID = GetMethodIDOrDie(env, gJDvrPlayerCls, "stop", "()Z");
    gJDvrPlayerCtx.seekMID = GetMethodIDOrDie(env, gJDvrPlayerCls, "seek", "(I)Z");
    gJDvrPlayerCtx.setSpeedMID = GetMethodIDOrDie(env, gJDvrPlayerCls, "setSpeed", "(D)Z");

    // Message
    jclass messageCls = env->FindClass("android/os/Message");
    gMessageCls = static_cast<jclass>(env->NewGlobalRef(messageCls));
    env->DeleteLocalRef(messageCls);
    gMessageCtx.whatField = GetFieldIDOrDie(env, gMessageCls, "what", "I");
    gMessageCtx.arg1Field = GetFieldIDOrDie(env, gMessageCls, "arg1", "I");
    gMessageCtx.arg2Field = GetFieldIDOrDie(env, gMessageCls, "arg2", "I");
    gMessageCtx.objField = GetFieldIDOrDie(env, gMessageCls, "obj", "Ljava/lang/Object;");

    // JDvrRecordingProgress
    jclass recordingprogressCls = env->FindClass("com/droidlogic/jdvrlib/JDvrRecorder$JDvrRecordingProgress");
    gRecordingProgressCls = static_cast<jclass>(env->NewGlobalRef(recordingprogressCls));
    env->DeleteLocalRef(recordingprogressCls);
    gRecordingProgressCtx.sessionNumberField = GetFieldIDOrDie(env, gRecordingProgressCls, "sessionNumber", "I");
    gRecordingProgressCtx.stateField = GetFieldIDOrDie(env, gRecordingProgressCls, "state", "I");
    gRecordingProgressCtx.durationField = GetFieldIDOrDie(env, gRecordingProgressCls, "duration", "J");
    gRecordingProgressCtx.startTimeField = GetFieldIDOrDie(env, gRecordingProgressCls, "startTime", "J");
    gRecordingProgressCtx.endTimeField = GetFieldIDOrDie(env, gRecordingProgressCls, "endTime", "J");
    gRecordingProgressCtx.numberOfSegmentsField = GetFieldIDOrDie(env, gRecordingProgressCls, "numberOfSegments", "I");
    gRecordingProgressCtx.firstSegmentIdField = GetFieldIDOrDie(env, gRecordingProgressCls, "firstSegmentId", "I");
    gRecordingProgressCtx.lastSegmentIdField = GetFieldIDOrDie(env, gRecordingProgressCls, "lastSegmentId", "I");
    gRecordingProgressCtx.sizeField = GetFieldIDOrDie(env, gRecordingProgressCls, "size", "J");

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

    registerNativeMethods(env,
            "com/droidlogic/jdvrlib/JNIJDvrRecorderListener",
            gJniJDvrRecorderListenerMethods, 1);

    registerNativeMethods(env,
            "com/droidlogic/jdvrlib/JNIJDvrPlayerListener",
            gJniJDvrPlayerListenerMethods, 1);

    gJniInit = true;
    return true;
}

JNIEnv *Loader::getOrAttachJNIEnvironment() {
    JNIEnv *env = getJNIEnv();
    if (!env) {
        if (mJavaVM == nullptr) {
            ALOGE("%s, java vm is null",__func__);
            return nullptr;
        }

        ALOGD("Attach current thread to jvm");
        int result = mJavaVM->AttachCurrentThread(&env, nullptr);
        if (result != JNI_OK) {
            ALOGE("thread attach failed");
        }
        struct VmDetacher {
            VmDetacher(JavaVM *vm) : mVm(vm) {}
            ~VmDetacher() {
                ALOGD("detach current thread to jvm");
                mVm->DetachCurrentThread();
            }

            private:
            JavaVM *const mVm;
        };
        static thread_local VmDetacher detacher(mJavaVM);
    }
    return env;
}

// JDvrFile
JDvrFile::JDvrFile(jstring path_prefix, jboolean trunc)
    : mEnv(NULL), mJavaJDvrFile(NULL)
{
    ALOGD("%s",__PRETTY_FUNCTION__);
    mEnv = Loader::getOrAttachJNIEnvironment();
    if (mEnv == nullptr) {
        ALOGE("Failed to get JNIEnv* at %s:%d",__func__,__LINE__);
        return;
    }
    const char* str = mEnv->GetStringUTFChars(path_prefix,0);
    if (str != NULL) {
        ALOGD("%s path_prefix:%s",__PRETTY_FUNCTION__,str);
        mEnv->ReleaseStringUTFChars(path_prefix,str);
    }
    jobject file = mEnv->NewObject(gJDvrFileCls, gJDvrFileCtx.constructor1MID,
            path_prefix, trunc);
    if (file != NULL) {
        mJavaJDvrFile = MakeGlobalRefOrDie(mEnv, file);
        mEnv->DeleteLocalRef(file);
    }
}

JDvrFile::JDvrFile(jstring path_prefix, jlong limit_size, jint limit_seconds,jboolean trunc)
    : mEnv(NULL), mJavaJDvrFile(NULL)
{
    ALOGD("%s",__PRETTY_FUNCTION__);
    mEnv = Loader::getOrAttachJNIEnvironment();
    if (mEnv == nullptr) {
        ALOGE("Failed to get JNIEnv* at %s:%d",__func__,__LINE__);
        return;
    }
    const char* str = mEnv->GetStringUTFChars(path_prefix,0);
    if (str != NULL) {
        ALOGD("%s path_prefix:%s",__PRETTY_FUNCTION__,str);
        mEnv->ReleaseStringUTFChars(path_prefix,str);
    }
    jobject file = mEnv->NewObject(gJDvrFileCls, gJDvrFileCtx.constructor2MID,
            path_prefix, limit_size, limit_seconds, trunc);
    if (file != NULL) {
        mJavaJDvrFile = MakeGlobalRefOrDie(mEnv, file);
        mEnv->DeleteLocalRef(file);
    }
}

JDvrFile::JDvrFile(jstring path_prefix)
    : mEnv(NULL), mJavaJDvrFile(NULL)
{
    ALOGD("%s",__PRETTY_FUNCTION__);
    mEnv = Loader::getOrAttachJNIEnvironment();
    if (mEnv == nullptr) {
        ALOGE("Failed to get JNIEnv* at %s:%d",__func__,__LINE__);
        return;
    }
    const char* str = mEnv->GetStringUTFChars(path_prefix,0);
    if (str != NULL) {
        ALOGD("%s path_prefix:%s",__PRETTY_FUNCTION__,str);
        mEnv->ReleaseStringUTFChars(path_prefix,str);
    }
    jobject file = mEnv->NewObject(gJDvrFileCls, gJDvrFileCtx.constructor3MID, path_prefix);
    if (file != NULL) {
        mJavaJDvrFile = MakeGlobalRefOrDie(mEnv, file);
        mEnv->DeleteLocalRef(file);
    }
}

JDvrFile::~JDvrFile() {
    ALOGD("%s mJavaJDvrFile:%p",__PRETTY_FUNCTION__,mJavaJDvrFile);
    if (mJavaJDvrFile != NULL) {
        mEnv->DeleteGlobalRef(mJavaJDvrFile);
        mJavaJDvrFile = NULL;
    }
}

bool JDvrFile::isTimeshift() {
    jboolean result = mEnv->CallBooleanMethod(mJavaJDvrFile, gJDvrFileCtx.isTimeshiftMID);
    //ALOGD("%s, returns %d",__func__,(int)result);
    return (bool)result;
}

long JDvrFile::duration()
{
    if (mJavaJDvrFile == NULL) {
        return 0L;
    }
    jlong result = mEnv->CallLongMethod(mJavaJDvrFile, gJDvrFileCtx.durationMID);
    //ALOGD("%s, returns %d",__func__,(long)result);
    return (long)result;
}

long JDvrFile::duration2(jstring path_prefix)
{
    JNIEnv* env = Loader::getOrAttachJNIEnvironment();
    if (env == nullptr) {
        ALOGE("Failed to get JNIEnv* at %s:%d",__func__,__LINE__);
        return false;
    }
    jlong result = env->CallStaticLongMethod(gJDvrFileCls,gJDvrFileCtx.duration2MID,path_prefix);
    return (long)result;
}

long JDvrFile::size()
{
    if (mJavaJDvrFile == NULL) {
        return 0L;
    }
    jlong result = mEnv->CallLongMethod(mJavaJDvrFile, gJDvrFileCtx.sizeMID);
    //ALOGD("%s, returns %d",__func__,(long)result);
    return (long)result;
}

long JDvrFile::size2(jstring path_prefix)
{
    JNIEnv* env = Loader::getOrAttachJNIEnvironment();
    if (env == nullptr) {
        ALOGE("Failed to get JNIEnv* at %s:%d",__func__,__LINE__);
        return false;
    }
    jlong result = env->CallStaticLongMethod(gJDvrFileCls,gJDvrFileCtx.size2MID,path_prefix);
    return (long)result;
}

long JDvrFile::getPlayingTime()
{
    jlong result = mEnv->CallLongMethod(mJavaJDvrFile, gJDvrFileCtx.getPlayingTimeMID);
    //ALOGD("%s, returns %d",__func__,(long)result);
    return (long)result;
}

long JDvrFile::getStartTime()
{
    jlong result = mEnv->CallLongMethod(mJavaJDvrFile, gJDvrFileCtx.getStartTimeMID);
    //ALOGD("%s, returns %d",__func__,(long)result);
    return (long)result;
}

int JDvrFile::getSegmentIdBeingRead()
{
    jint result = mEnv->CallIntMethod(mJavaJDvrFile, gJDvrFileCtx.getSegmentIdBeingReadMID);
    //ALOGD("%s, returns %d",__func__,(long)result);
    return (int)result;
}

int JDvrFile::getFirstSegmentId()
{
    jint result = mEnv->CallIntMethod(mJavaJDvrFile, gJDvrFileCtx.getFirstSegmentIdMID);
    //ALOGD("%s, returns %d",__func__,(long)result);
    return (int)result;
}

int JDvrFile::getLastSegmentId()
{
    jint result = mEnv->CallIntMethod(mJavaJDvrFile, gJDvrFileCtx.getLastSegmentIdMID);
    //ALOGD("%s, returns %d",__func__,(long)result);
    return (int)result;
}

int JDvrFile::getNumberOfSegments()
{
    jint result = mEnv->CallIntMethod(mJavaJDvrFile, gJDvrFileCtx.getNumberOfSegmentsMID);
    //ALOGD("%s, returns %d",__func__,(long)result);
    return (int)result;
}

int JDvrFile::getVideoPID() {
    jint result = mEnv->CallIntMethod(mJavaJDvrFile, gJDvrFileCtx.getVideoPIDMID);
    //ALOGD("%s, returns %d",__func__,(int)result);
    return (int)result;
}

int JDvrFile::getVideoFormat() {
    jint result = mEnv->CallIntMethod(mJavaJDvrFile, gJDvrFileCtx.getVideoFormatMID);
    //ALOGD("%s, returns %d",__func__,(int)result);
    return (int)result;
}

int JDvrFile::getVideoMIMEType(char* buf, int buf_len) {
    jstring result = (jstring)mEnv->CallObjectMethod(mJavaJDvrFile, gJDvrFileCtx.getVideoMIMETypeMID);
    const char* str = mEnv->GetStringUTFChars(result,0);
    //ALOGD("%s, returns %s",__func__,str);
    strncpy(buf,str,buf_len);
    mEnv->ReleaseStringUTFChars(result,str);
    mEnv->DeleteLocalRef(result);
    return (buf_len>0) ? JNI_OK : JNI_ERR;
}

int JDvrFile::getAudioPID() {
    jint result = mEnv->CallIntMethod(mJavaJDvrFile, gJDvrFileCtx.getAudioPIDMID);
    //ALOGD("%s, returns %d",__func__,(int)result);
    return (int)result;
}

int JDvrFile::getAudioFormat() {
    jint result = mEnv->CallIntMethod(mJavaJDvrFile, gJDvrFileCtx.getAudioFormatMID);
    //ALOGD("%s, returns %d",__func__,(int)result);
    return (int)result;
}

int JDvrFile::getAudioMIMEType(char* buf, int buf_len) {
    jstring result = (jstring)mEnv->CallObjectMethod(mJavaJDvrFile, gJDvrFileCtx.getAudioMIMETypeMID);
    const char* str = mEnv->GetStringUTFChars(result,0);
    //ALOGD("%s, returns %s",__func__,str);
    strncpy(buf,str,buf_len);
    mEnv->ReleaseStringUTFChars(result,str);
    mEnv->DeleteLocalRef(result);
    return (buf_len>0) ? JNI_OK : JNI_ERR;
}

void JDvrFile::close()
{
    mEnv->CallVoidMethod(mJavaJDvrFile,gJDvrFileCtx.closeMID);
}

bool JDvrFile::remove(jstring path_prefix)
{
    JNIEnv* env = Loader::getOrAttachJNIEnvironment();
    if (env == nullptr) {
        ALOGE("Failed to get JNIEnv* at %s:%d",__func__,__LINE__);
        return false;
    }
    jboolean result = env->CallStaticBooleanMethod(gJDvrFileCls,gJDvrFileCtx.removeMID,path_prefix);
    return (bool)result;
}

// JDvrRecorder
JDvrRecorder::JDvrRecorder(jobject tuner, jobject file, jobject settings, on_recorder_event_callback callback)
{
    mEnv = Loader::getOrAttachJNIEnvironment();
    if (mEnv == nullptr) {
        ALOGE("Failed to get JNIEnv* at %s:%d",__func__,__LINE__);
        return;
    }
    if (callback == nullptr) {
        ALOGE("Given callback is NULL");
        return;
    }
    mCallback = callback;
    ALOGD("%s, tuner:%p, file:%p, settings:%p, callback:%p",
            __PRETTY_FUNCTION__,tuner,file,settings,callback);
    jobject recorder = mEnv->NewObject(gJDvrRecorderCls, gJDvrRecorderCtx.constructorMID,
            tuner, file, settings, NULL, NULL);
    if (recorder != NULL) {
        mJavaJDvrRecorder = MakeGlobalRefOrDie(mEnv, recorder);
        mEnv->DeleteLocalRef(recorder);
    }
}

JDvrRecorder::~JDvrRecorder() {
    mEnv->DeleteGlobalRef(mJavaJDvrRecorder);
    mJavaJDvrRecorder = NULL;
}

void JDvrRecorder::callback(am_dvr_recorder_event event,void* event_data)
{
    mCallback(this,event,event_data);
}

bool JDvrRecorder::addStream(int pid, int type, int format)
{
    jboolean result = mEnv->CallBooleanMethod(mJavaJDvrRecorder, gJDvrRecorderCtx.addStreamMID,
            pid, (int)type, format);
    return (bool)result;
}

bool JDvrRecorder::removeStream(int pid)
{
    jboolean result = mEnv->CallBooleanMethod(mJavaJDvrRecorder, gJDvrRecorderCtx.removeStreamMID, pid);
    return (bool)result;
}

bool JDvrRecorder::start()
{
    ALOGD("%s, enter",__PRETTY_FUNCTION__);
    jboolean result = mEnv->CallBooleanMethod(mJavaJDvrRecorder, gJDvrRecorderCtx.startMID);
    return (bool)result;
}

bool JDvrRecorder::pause()
{
    ALOGD("%s, enter",__PRETTY_FUNCTION__);
    jboolean result = mEnv->CallBooleanMethod(mJavaJDvrRecorder, gJDvrRecorderCtx.pauseMID);
    return (bool)result;
}

bool JDvrRecorder::stop()
{
    ALOGD("%s, enter",__PRETTY_FUNCTION__);
    jboolean result = mEnv->CallBooleanMethod(mJavaJDvrRecorder, gJDvrRecorderCtx.stopMID);
    return (bool)result;
}

// JNIJDvrRecorderListener native
static jint native_notifyJDvrRecorderEvent(JNIEnv *env, jobject jListener,
        jobject jRecorder, jobject jMessage)
{
    jint what = env->GetIntField(jMessage,gMessageCtx.whatField);
    jint arg1 = env->GetIntField(jMessage,gMessageCtx.arg1Field);
    jint arg2 = env->GetIntField(jMessage,gMessageCtx.arg2Field);
    jobject obj = env->GetObjectField(jMessage,gMessageCtx.objField);
    //ALOGD("%s, jRecorder:%p, what:%d, arg1:%d, arg2:%d",__func__,jRecorder,what,arg1,arg2);
    auto it = find_if(vecDvrRecorders.begin(),vecDvrRecorders.end(),[&](am_dvr_recorder_handle h){
            return env->IsSameObject(jRecorder,((JDvrRecorder*)h)->getJObject());
            });
    if (it == vecDvrRecorders.end()) {
        ALOGE("%s, input jRecorder is invalid",__func__);
        return -1;
    }
    if (what == AM_DVR_RECORDER_EVENT_PROGRESS) {
        auto pEvt = new am_dvr_recording_progress;
        pEvt->sessionNumber = env->GetIntField(obj,gRecordingProgressCtx.sessionNumberField);
        pEvt->state = env->GetIntField(obj,gRecordingProgressCtx.stateField);
        pEvt->duration = env->GetLongField(obj,gRecordingProgressCtx.durationField);
        pEvt->startTime = env->GetLongField(obj,gRecordingProgressCtx.startTimeField);
        pEvt->endTime = env->GetLongField(obj,gRecordingProgressCtx.endTimeField);
        pEvt->numberOfSegments = env->GetIntField(obj,gRecordingProgressCtx.numberOfSegmentsField);
        pEvt->firstSegmentId = env->GetIntField(obj,gRecordingProgressCtx.firstSegmentIdField);
        pEvt->lastSegmentId = env->GetIntField(obj,gRecordingProgressCtx.lastSegmentIdField);
        pEvt->size = env->GetLongField(obj,gRecordingProgressCtx.sizeField);
        ((JDvrRecorder*)(*it))->callback((am_dvr_recorder_event)what,pEvt);
    } else {
        ((JDvrRecorder*)(*it))->callback((am_dvr_recorder_event)what,nullptr);
    }
    return 0;
}

// JNIJDvrPlayerListener native
static jint native_notifyJDvrPlayerEvent(JNIEnv *env, jobject jListener,
        jobject jPlayer, jobject jMessage)
{
    jint what = env->GetIntField(jMessage,gMessageCtx.whatField);
    jint arg1 = env->GetIntField(jMessage,gMessageCtx.arg1Field);
    jint arg2 = env->GetIntField(jMessage,gMessageCtx.arg2Field);
    jobject obj = env->GetObjectField(jMessage,gMessageCtx.objField);
    //ALOGD("%s, jRecorder:%p, what:%d, arg1:%d, arg2:%d",__func__,jRecorder,what,arg1,arg2);
    auto it = find_if(vecDvrPlayers.begin(),vecDvrPlayers.end(),[&](am_dvr_player_handle h){
            return env->IsSameObject(jPlayer,((JDvrPlayer*)h)->getJObject());
            });
    if (it == vecDvrPlayers.end()) {
        ALOGE("%s, input jPlayer is invalid",__func__);
        return -1;
    }
    if (what == AM_DVR_PLAYER_EVENT_PROGRESS) {
        auto pEvt = new am_dvr_playback_progress;
        pEvt->sessionNumber = env->GetIntField(obj,gPlaybackProgressCtx.sessionNumberField);
        pEvt->state = env->GetIntField(obj,gPlaybackProgressCtx.stateField);
        pEvt->speed = env->GetDoubleField(obj,gPlaybackProgressCtx.speedField);
        pEvt->currTime = env->GetLongField(obj,gPlaybackProgressCtx.currTimeField);
        pEvt->startTime = env->GetLongField(obj,gPlaybackProgressCtx.startTimeField);
        pEvt->endTime = env->GetLongField(obj,gPlaybackProgressCtx.endTimeField);
        pEvt->duration = env->GetLongField(obj,gPlaybackProgressCtx.durationField);
        pEvt->currSegmentId = env->GetIntField(obj,gPlaybackProgressCtx.currSegmentIdField);
        pEvt->firstSegmentId = env->GetIntField(obj,gPlaybackProgressCtx.firstSegmentIdField);
        pEvt->lastSegmentId = env->GetIntField(obj,gPlaybackProgressCtx.lastSegmentIdField);
        pEvt->numberOfSegments = env->GetIntField(obj,gPlaybackProgressCtx.numberOfSegmentsField);
        ((JDvrPlayer*)(*it))->callback((am_dvr_player_event)what,pEvt);
    } else {
        ((JDvrPlayer*)(*it))->callback((am_dvr_player_event)what,nullptr);
    }
    return 0;
}

JDvrPlayer::JDvrPlayer(jobject tuner, jobject file, jobject settings, on_player_event_callback callback, jobject surface)
{
    mEnv = Loader::getOrAttachJNIEnvironment();
    if (mEnv == nullptr) {
        ALOGE("Failed to get JNIEnv* at %s:%d",__func__,__LINE__);
        return;
    }
    if (callback == nullptr) {
        ALOGE("Given callback is NULL");
        return;
    }
    mCallback = callback;
    ALOGD("%s, tuner:%p, file:%p, settings:%p, callback:%p, surface:%p",
            __PRETTY_FUNCTION__,tuner,file,settings,callback,surface);
    jobject player = mEnv->NewObject(gJDvrPlayerCls, gJDvrPlayerCtx.constructorMID,
            tuner, file, settings, NULL, NULL, surface);
    if (player != NULL) {
        mJavaJDvrPlayer = MakeGlobalRefOrDie(mEnv, player);
        mEnv->DeleteLocalRef(player);
    }
}

JDvrPlayer::~JDvrPlayer() {
    mEnv->DeleteGlobalRef(mJavaJDvrPlayer);
    mJavaJDvrPlayer = NULL;
}

void JDvrPlayer::callback(am_dvr_player_event event,void* event_data)
{
    mCallback(this,event,event_data);
}

bool JDvrPlayer::play()
{
    ALOGD("%s, enter",__PRETTY_FUNCTION__);
    jboolean result = mEnv->CallBooleanMethod(mJavaJDvrPlayer, gJDvrPlayerCtx.playMID);
    return (bool)result;
}

bool JDvrPlayer::pause()
{
    ALOGD("%s, enter",__PRETTY_FUNCTION__);
    jboolean result = mEnv->CallBooleanMethod(mJavaJDvrPlayer, gJDvrPlayerCtx.pauseMID);
    return (bool)result;
}

bool JDvrPlayer::stop()
{
    ALOGD("%s, enter",__PRETTY_FUNCTION__);
    jboolean result = mEnv->CallBooleanMethod(mJavaJDvrPlayer, gJDvrPlayerCtx.stopMID);
    return (bool)result;
}

bool JDvrPlayer::seek(int seconds)
{
    jboolean result = mEnv->CallBooleanMethod(mJavaJDvrPlayer, gJDvrPlayerCtx.seekMID, seconds);
    return (bool)result;
}

bool JDvrPlayer::setSpeed(double speed)
{
    jboolean result = mEnv->CallBooleanMethod(mJavaJDvrPlayer, gJDvrPlayerCtx.setSpeedMID, speed);
    return (bool)result;
}

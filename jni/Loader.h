#include <jni.h>
#include "JDvrLibJNI.h"

class Loader
{
public:
    static void setJavaVM(JavaVM* javaVM);

    /** returns a pointer to the JavaVM provided when we initialized the module */
    static JavaVM* getJavaVM() { return mJavaVM; }

    /** return a pointer to the JNIEnv for this thread */
    static JNIEnv* getJNIEnv();

    /** create a JNIEnv* for this thread or assert if one already exists */
    static JNIEnv* attachJNIEnv(const char* envName);

    /** detach the current thread from the JavaVM */
    static void detachJNIEnv();

    static JNIEnv* getOrAttachJNIEnvironment();

    static bool initJDvrLibJNI(JNIEnv *jniEnv);

    static jclass findClass(const char *name);

protected:
    /* JNI JavaVM pointer */
    static JavaVM* mJavaVM;

private:
    static int initJNIEnv(JNIEnv *env);
};

class JDvrFile
{
public:
    JDvrFile(jstring path_prefix, jboolean trunc);
    JDvrFile(jstring path_prefix, jlong limit_size, jint limit_seconds, jboolean trunc);
    JDvrFile(jstring path_prefix);
    virtual ~JDvrFile();

    bool isTimeshift();
    long duration();
    static long duration2(jstring path_prefix);
    int64_t size();
    static int64_t size2(jstring path_prefix);
    long getPlayingTime();
    long getStartTime();
    int getSegmentIdBeingRead();
    int getFirstSegmentId();
    int getLastSegmentId();
    int getNumberOfSegments();
    int getVideoPID();
    int getVideoFormat();
    int getVideoMIMEType(char* buf, int buf_len);
    int getAudioPID();
    int getAudioFormat();
    int getAudioMIMEType(char* buf, int buf_len);
    void close();
    static bool remove(jstring path_prefix);

    jobject getJObject() { return mJavaJDvrFile; }

private:
    jobject mJavaJDvrFile;
};

class JDvrRecorder
{
public:
    JDvrRecorder(jobject tuner, jobject file, jobject settings, on_recorder_event_callback callback);
    virtual ~JDvrRecorder();

    bool addStream(int pid, int type, int format);
    bool removeStream(int pid);
    bool start();
    bool pause();
    bool stop();

    void callback(am_dvr_recorder_event,void*);
    jobject getJObject() { return mJavaJDvrRecorder; }

private:
    jobject mJavaJDvrRecorder;
    on_recorder_event_callback mCallback;
};

class JDvrPlayer
{
public:
    JDvrPlayer(jobject asplayer, jobject file, jobject settings, on_player_event_callback callback);
    virtual ~JDvrPlayer();

    bool play();
    bool pause();
    bool stop();
    bool seek(int seconds);
    bool setSpeed(double speed);

    void callback(am_dvr_player_event,void*);
    jobject getJObject() { return mJavaJDvrPlayer; }

private:
    jobject mJavaJDvrPlayer;
    on_player_event_callback mCallback;
};


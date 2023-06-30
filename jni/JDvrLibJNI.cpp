#include "log.h"
#include "JDvrLibJNI.h"
#include "Loader.h"

#include <vector>
#include <algorithm>

using namespace std;

extern vector<am_dvr_file_handle> vecDvrFiles;
extern vector<am_dvr_recorder_handle> vecDvrRecorders;
extern vector<am_dvr_player_handle> vecDvrPlayers;

am_dvr_result
AmDvr_registerJNI(JNIEnv *env)
{
    ALOGI("%s, enter",__func__);
    if (env == nullptr) {
        return JDVRLIB_JNI_ERR;
    }
    JavaVM *jvm = nullptr;
    jint ret = env->GetJavaVM(&jvm);
    if (ret == JNI_OK) {
        Loader::setJavaVM(jvm);
    }
    Loader::initJDvrLibJNI(env);
    return JDVRLIB_JNI_OK;
}

// AmDvr_File_*
am_dvr_result
AmDvr_File_create1(
        const char* path_prefix,
        bool trunc,
        am_dvr_file_handle *phandle)
{
    ALOGI("%s, enter",__func__);
    JNIEnv *env = Loader::getOrAttachJNIEnvironment();
    if (env == nullptr) {
        ALOGE("Failed to get JNIEnv");
        return JDVRLIB_JNI_ERR;
    }
    jboolean jtrunc = (trunc ? JNI_TRUE : JNI_FALSE);
    auto handle = new JDvrFile(env->NewStringUTF(path_prefix),jtrunc);
    if (handle == nullptr) {
        *phandle = nullptr;
        return JDVRLIB_JNI_ERR;
    }
    vecDvrFiles.push_back(handle);
    *phandle = handle;
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_create2(
        const char* path_prefix,
        long limit_size,
        int limit_seconds,
        bool trunc,
        am_dvr_file_handle *phandle)
{
    ALOGI("%s, enter",__func__);
    JNIEnv *env = Loader::getOrAttachJNIEnvironment();
    if (env == nullptr) {
        ALOGE("Failed to get JNIEnv");
        return JDVRLIB_JNI_ERR;
    }
    jboolean jtrunc = (trunc ? JNI_TRUE : JNI_FALSE);
    auto handle = new JDvrFile(env->NewStringUTF(path_prefix),limit_size,limit_seconds,jtrunc);
    if (handle == nullptr) {
        *phandle = nullptr;
        return JDVRLIB_JNI_ERR;
    }
    vecDvrFiles.push_back(handle);
    *phandle = handle;
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_create3(
        const char* path_prefix,
        am_dvr_file_handle *phandle)
{
    ALOGI("%s, enter",__func__);
    JNIEnv *env = Loader::getOrAttachJNIEnvironment();
    if (env == nullptr) {
        ALOGE("Failed to get JNIEnv");
        return JDVRLIB_JNI_ERR;
    }
    auto handle = new JDvrFile(env->NewStringUTF(path_prefix));
    if (handle == nullptr) {
        *phandle = nullptr;
        return JDVRLIB_JNI_ERR;
    }
    vecDvrFiles.push_back(handle);
    *phandle = handle;
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_destroy (
        am_dvr_file_handle handle)
{
    ALOGD("%s, enter",__func__);
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    ALOGD("%s, delete %p",__func__,p);
    delete p;
    vecDvrFiles.erase(it);
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_isTimeshift (
        am_dvr_file_handle handle,
        bool* pstatus)
{
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    if (pstatus == nullptr) {
        ALOGE("%s, given bool pointer %p is invalid",__func__,pstatus);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    *pstatus = p->isTimeshift();
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_duration(
        am_dvr_file_handle handle,
        int64_t* pduration)
{
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    if (pduration == nullptr) {
        ALOGE("%s, given long pointer %p is invalid",__func__,pduration);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    *pduration = p->duration();
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_size(
        am_dvr_file_handle handle,
        int64_t* psize)
{
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    if (psize == nullptr) {
        ALOGE("%s, given long pointer %p is invalid",__func__,psize);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    *psize = p->size();
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_getPlayingTime(
        am_dvr_file_handle handle,
        int64_t* ptime)
{
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    if (ptime == nullptr) {
        ALOGE("%s, given long pointer %p is invalid",__func__,ptime);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    *ptime = p->getPlayingTime();
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_getStartTime(
        am_dvr_file_handle handle,
        int64_t* ptime)
{
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    if (ptime == nullptr) {
        ALOGE("%s, given long pointer %p is invalid",__func__,ptime);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    *ptime = p->getStartTime();
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_getCurrSegmentId(
        am_dvr_file_handle handle,
        int32_t* pid)
{
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    if (pid == nullptr) {
        ALOGE("%s, given int pointer %p is invalid",__func__,pid);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    *pid = p->getSegmentIdBeingRead();
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_getFirstSegmentId(
        am_dvr_file_handle handle,
        int32_t* pid)
{
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    if (pid == nullptr) {
        ALOGE("%s, given int pointer %p is invalid",__func__,pid);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    *pid = p->getFirstSegmentId();
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_getLastSegmentId(
        am_dvr_file_handle handle,
        int32_t* pid)
{
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    if (pid == nullptr) {
        ALOGE("%s, given int pointer %p is invalid",__func__,pid);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    *pid = p->getLastSegmentId();
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_getNumberOfSegments(
        am_dvr_file_handle handle,
        int32_t* pnumber)
{
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    if (pnumber == nullptr) {
        ALOGE("%s, given int pointer %p is invalid",__func__,pnumber);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    *pnumber = p->getNumberOfSegments();
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_getVideoPID(
        am_dvr_file_handle handle,
        int* ppid)
{
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    if (ppid == nullptr) {
        ALOGE("%s, given int pointer %p is invalid",__func__,ppid);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    *ppid  = p->getVideoPID();
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_getVideoFormat(
        am_dvr_file_handle handle,
        int* pformat)
{
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    if (pformat == nullptr) {
        ALOGE("%s, given int pointer %p is invalid",__func__,pformat);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    *pformat = p->getVideoFormat();
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_getVideoMIMEType(
        am_dvr_file_handle handle,
        char* buf,
        int buf_len)
{
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    int ret = p->getVideoMIMEType(buf,buf_len);
    return (ret>0) ? JDVRLIB_JNI_OK : JDVRLIB_JNI_ERR;
}

am_dvr_result
AmDvr_File_getAudioPID(
        am_dvr_file_handle handle,
        int* ppid)
{
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    if (ppid == nullptr) {
        ALOGE("%s, given int pointer %p is invalid",__func__,ppid);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    *ppid  = p->getAudioPID();
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_getAudioFormat(
        am_dvr_file_handle handle,
        int* pformat)
{
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    if (pformat == nullptr) {
        ALOGE("%s, given int pointer %p is invalid",__func__,pformat);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    *pformat = p->getAudioFormat();
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_File_getAudioMIMEType(
        am_dvr_file_handle handle,
        char* buf,
        int buf_len)
{
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    int ret = p->getAudioMIMEType(buf,buf_len);
    return (ret>0) ? JDVRLIB_JNI_OK : JDVRLIB_JNI_ERR;
}

// AmDvr_Recorder_*
am_dvr_result
AmDvr_Recorder_create (
        am_dvr_recorder_init_params *params,
        am_dvr_recorder_handle *phandle)
{
    ALOGD("%s, enter",__func__);
    JNIEnv *env = Loader::getOrAttachJNIEnvironment();
    if (env == nullptr) {
        ALOGE("Failed to get JNIEnv");
        return JDVRLIB_JNI_ERR;
    }
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),params->jdvrfile_handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,params->jdvrfile_handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    auto handle = new JDvrRecorder(params->tuner,p->getJObject(),params->settings,params->callback);
    if (handle == nullptr) {
        *phandle = nullptr;
        return JDVRLIB_JNI_ERR;
    }
    vecDvrRecorders.push_back(handle);
    *phandle = handle;
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_Recorder_destroy (
        am_dvr_recorder_handle handle)
{
    ALOGD("%s, enter",__func__);
    auto it = find(vecDvrRecorders.begin(),vecDvrRecorders.end(),handle);
    if (it == vecDvrRecorders.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrRecorder* p = (JDvrRecorder*)*it;
    ALOGD("%s, delete %p",__func__,p);
    delete p;
    vecDvrRecorders.erase(it);
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_Recorder_addStream (
        am_dvr_recorder_handle handle,
        int pid,
        am_dvr_stream_type type,
        int format)
{
    auto it = find(vecDvrRecorders.begin(),vecDvrRecorders.end(),handle);
    if (it == vecDvrRecorders.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrRecorder* p = (JDvrRecorder*)*it;
    int ret = p->addStream(pid,(int)type,format);
    return (ret == 0) ? JDVRLIB_JNI_OK : JDVRLIB_JNI_ERR;
}

am_dvr_result
AmDvr_Recorder_removeStream (
        am_dvr_recorder_handle handle,
        int pid)
{
    auto it = find(vecDvrRecorders.begin(),vecDvrRecorders.end(),handle);
    if (it == vecDvrRecorders.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrRecorder* p = (JDvrRecorder*)*it;
    int ret = p->removeStream(pid);
    return (ret == 0) ? JDVRLIB_JNI_OK : JDVRLIB_JNI_ERR;
}

am_dvr_result
AmDvr_Recorder_start (
        am_dvr_recorder_handle handle)
{
    ALOGD("%s, enter",__func__);
    auto it = find(vecDvrRecorders.begin(),vecDvrRecorders.end(),handle);
    if (it == vecDvrRecorders.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrRecorder* p = (JDvrRecorder*)*it;
    bool ret = p->start();
    return ret ? JDVRLIB_JNI_OK : JDVRLIB_JNI_ERR;
}

am_dvr_result
AmDvr_Recorder_pause (
        am_dvr_recorder_handle handle)
{
    ALOGD("%s, enter",__func__);
    auto it = find(vecDvrRecorders.begin(),vecDvrRecorders.end(),handle);
    if (it == vecDvrRecorders.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrRecorder* p = (JDvrRecorder*)*it;
    bool ret = p->pause();
    return ret ? JDVRLIB_JNI_OK : JDVRLIB_JNI_ERR;
}

am_dvr_result
AmDvr_Recorder_stop (
        am_dvr_recorder_handle handle)
{
    ALOGD("%s, enter",__func__);
    auto it = find(vecDvrRecorders.begin(),vecDvrRecorders.end(),handle);
    if (it == vecDvrRecorders.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrRecorder* p = (JDvrRecorder*)*it;
    int ret = p->stop();
    return (ret == 0) ? JDVRLIB_JNI_OK : JDVRLIB_JNI_ERR;
}

// AmDvr_Player_*
am_dvr_result
AmDvr_Player_create (
        am_dvr_player_init_params *params,
        am_dvr_player_handle *phandle)
{
    ALOGD("%s, enter",__func__);
    JNIEnv *env = Loader::getOrAttachJNIEnvironment();
    if (env == nullptr) {
        ALOGE("Failed to get JNIEnv");
        return JDVRLIB_JNI_ERR;
    }
    auto it = find(vecDvrFiles.begin(),vecDvrFiles.end(),params->jdvrfile_handle);
    if (it == vecDvrFiles.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,params->jdvrfile_handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrFile* p = (JDvrFile*)*it;
    auto handle = new JDvrPlayer(params->asplayer,p->getJObject(),params->settings,params->callback);
    if (handle == nullptr) {
        *phandle = nullptr;
        return JDVRLIB_JNI_ERR;
    }
    vecDvrPlayers.push_back(handle);
    *phandle = handle;
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_Player_destroy (
        am_dvr_player_handle handle)
{
    ALOGD("%s, enter",__func__);
    auto it = find(vecDvrPlayers.begin(),vecDvrPlayers.end(),handle);
    if (it == vecDvrPlayers.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrPlayer* p = (JDvrPlayer*)*it;
    ALOGD("%s, delete %p",__func__,p);
    delete p;
    vecDvrPlayers.erase(it);
    return JDVRLIB_JNI_OK;
}

am_dvr_result
AmDvr_Player_play (
        am_dvr_player_handle handle)
{
    ALOGD("%s, enter",__func__);
    auto it = find(vecDvrPlayers.begin(),vecDvrPlayers.end(),handle);
    if (it == vecDvrPlayers.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrPlayer* p = (JDvrPlayer*)*it;
    int ret = p->play();
    return (ret == 0) ? JDVRLIB_JNI_OK : JDVRLIB_JNI_ERR;
}

am_dvr_result
AmDvr_Player_pause (
        am_dvr_player_handle handle)
{
    ALOGD("%s, enter",__func__);
    auto it = find(vecDvrPlayers.begin(),vecDvrPlayers.end(),handle);
    if (it == vecDvrPlayers.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrPlayer* p = (JDvrPlayer*)*it;
    int ret = p->pause();
    return (ret == 0) ? JDVRLIB_JNI_OK : JDVRLIB_JNI_ERR;
}

am_dvr_result
AmDvr_Player_stop (
        am_dvr_player_handle handle)
{
    ALOGD("%s, enter",__func__);
    auto it = find(vecDvrPlayers.begin(),vecDvrPlayers.end(),handle);
    if (it == vecDvrPlayers.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrPlayer* p = (JDvrPlayer*)*it;
    int ret = p->stop();
    return (ret == 0) ? JDVRLIB_JNI_OK : JDVRLIB_JNI_ERR;
}

am_dvr_result
AmDvr_Player_seek (
        am_dvr_player_handle handle,
        int seconds)
{
    auto it = find(vecDvrPlayers.begin(),vecDvrPlayers.end(),handle);
    if (it == vecDvrPlayers.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrPlayer* p = (JDvrPlayer*)*it;
    int ret = p->seek(seconds);
    return (ret == 0) ? JDVRLIB_JNI_OK : JDVRLIB_JNI_ERR;
}

am_dvr_result
AmDvr_Player_setSpeed (
        am_dvr_player_handle handle,
        double speed)
{
    auto it = find(vecDvrPlayers.begin(),vecDvrPlayers.end(),handle);
    if (it == vecDvrPlayers.end()) {
        ALOGE("%s, given handle %p is invalid",__func__,handle);
        return JDVRLIB_JNI_ERR;
    }
    JDvrPlayer* p = (JDvrPlayer*)*it;
    int ret = p->setSpeed(speed);
    return (ret == 0) ? JDVRLIB_JNI_OK : JDVRLIB_JNI_ERR;
}

am_dvr_result
AmDvr_deleteRecord (const char *path_prefix)
{
    ALOGI("%s, enter",__func__);
    JNIEnv *env = Loader::getOrAttachJNIEnvironment();
    if (env == nullptr) {
        ALOGE("Failed to get JNIEnv");
        return JDVRLIB_JNI_ERR;
    }
    bool ret = JDvrFile::remove(env->NewStringUTF(path_prefix));
    return ret ? JDVRLIB_JNI_OK : JDVRLIB_JNI_ERR;
}


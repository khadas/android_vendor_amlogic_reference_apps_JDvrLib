#ifndef JDVRLIB_JNI_H
#define JDVRLIB_JNI_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/**Function result.*/
typedef enum {
    JDVRLIB_JNI_OK  = 0,
    JDVRLIB_JNI_ERR = -1,
} am_dvr_result;

/**Player handle.*/
typedef void* am_dvr_player_handle;

/**Recorder handle.*/
typedef void* am_dvr_recorder_handle;

/**Recording file handle.*/
typedef void* am_dvr_file_handle;

/**Element stream type.*/
typedef enum {
    AM_DVR_STREAM_TYPE_VIDEO    = 0, /**< Video stream.*/
    AM_DVR_STREAM_TYPE_AUDIO    = 1, /**< Audio stream.*/
    AM_DVR_STREAM_TYPE_AD       = 2, /**< Audio Description stream.*/
    AM_DVR_STREAM_TYPE_SUBTITLE = 3, /**< Subtitle stream.*/
    AM_DVR_STREAM_TYPE_TELETEXT = 4, /**< Teletext stream.*/
    AM_DVR_STREAM_TYPE_ECM      = 5, /**< ECM stream.*/
    AM_DVR_STREAM_TYPE_EMM      = 6, /**< EMM stream.*/
    AM_DVR_STREAM_TYPE_OTHER    = 7, /**< Section stream.*/
} am_dvr_stream_type;

/**Recorder events.*/
typedef enum {
    AM_DVR_RECORDER_EVENT_PROGRESS          = 2001, /**< It happens every second while a recording is running with additional progress information. Refer to am_dvr_recording_progress */
    AM_DVR_RECORDER_EVENT_INITIAL_STATE     = 3001, /**< It happens when recorder changes to INITIAL state*/
    AM_DVR_RECORDER_EVENT_STARTING_STATE    = 3002, /**< It happens when recorder changes to STARTING state*/
    AM_DVR_RECORDER_EVENT_STARTED_STATE     = 3003, /**< It happens when recorder changes to STARTED state*/
    AM_DVR_RECORDER_EVENT_PAUSED_STATE      = 3004, /**< It happens when recorder changes to PAUSED state*/
    AM_DVR_RECORDER_EVENT_STOPPING_STATE    = 3005, /**< It happens when recorder changes to STOPPING state*/
    AM_DVR_RECORDER_EVENT_NO_DATA_ERROR     = 4001, /**< It happens when recorder fails to receive any data for a period of time*/
    AM_DVR_RECORDER_EVENT_IO_ERROR          = 4002, /**< It happens when recorder meets IO error while trying to write data to disk*/
    AM_DVR_RECORDER_EVENT_DISK_FULL_ERROR   = 4003, /**< It happens when PVR disk space is full while making a recording*/
} am_dvr_recorder_event;

/**Player events.*/
typedef enum {
    AM_DVR_PLAYER_EVENT_PROGRESS                = 2001, /**< It happens every second while a playback is running with additional progress information. Refer to am_dvr_playback_progress*/
    AM_DVR_PLAYER_EVENT_EOS                     = 2002, /**< It happens when player reaches to the end of a recording*/
    AM_DVR_PLAYER_EVENT_EDGE_LEAVING            = 2003, /**< It happens when player stays at the earliest segment which is about to be removed due to size limitation of a timeshift recording. Under such condition, player will jump to the next segment and meanwhile notify this event*/
    AM_DVR_PLAYER_EVENT_INITIAL_STATE           = 3001, /**< It happens when player changes to INITIAL state*/
    AM_DVR_PLAYER_EVENT_STARTING_STATE          = 3002, /**< It happens when player changes to STARTING state*/
    AM_DVR_PLAYER_EVENT_SMOOTH_PLAYING_STATE    = 3003, /**< It happens when player changes to SMOOTH_PLAYING state*/
    AM_DVR_PLAYER_EVENT_SKIPPING_PLAYING_STATE  = 3004, /**< It happens when player changes to SKIPPING_PLAYING state*/
    AM_DVR_PLAYER_EVENT_PAUSED_STATE            = 3005, /**< It happens when player changes to PAUSED state*/
    AM_DVR_PLAYER_EVENT_STOPPING_STATE          = 3006, /**< It happens when player changes to STOPPING state*/
} am_dvr_player_event;

typedef void (*on_recorder_event_callback)(am_dvr_file_handle,am_dvr_recorder_event,void*);
typedef void (*on_player_event_callback)(am_dvr_player_handle,am_dvr_player_event,void*);

/**Recorder initialization parameters. An input parameter of AmDvr_Recorder_create*/
typedef struct {
    jobject                     tuner;      // should supply android.media.tv.tuner.Tuner
    am_dvr_file_handle          jdvrfile_handle;
    jobject                     settings;   // should supply com.droidlogic.jdvrlib.JDvrRecorderSettings
    on_recorder_event_callback  callback;
} am_dvr_recorder_init_params;

/**Recording progress information. It will be provided with AM_DVR_RECORDER_EVENT_PROGRESS event*/
typedef struct {
    long        duration;       // in ms
    long        startTime;      // in ms
    long        endTime;        // in ms
    int         numberOfSegments;
    int         firstSegmentId;
    int         lastSegmentId;
    long        size;           // in bytes
} am_dvr_recording_progress;

/**Playback progress information. It will be provided with AM_DVR_PLAYER_EVENT_PROGRESS event*/
typedef struct {
    long        currTime;       // in ms
    long        startTime;      // in ms
    long        endTime;        // in ms
    long        duration;       // in ms
    int         currSegmentId;
    int         firstSegmentId;
    int         lastSegmentId;
    int         numberOfSegments;
} am_dvr_playback_progress;

/**Player initialization parameters. An input parameter of AmDvr_Player_create*/
typedef struct {
    jobject                     asplayer;   // should supply com.amlogic.asplayer.api.ASPlayer
    am_dvr_file_handle          jdvrfile_handle;
    jobject                     settings;   // should supply com.droidlogic.jdvrlib.JDvrPlayerSettings
    on_player_event_callback    callback;
} am_dvr_player_init_params;

/**
 * @brief   This interface is designed to do necessary initialization before
 *          any JDvrLib JNI API is called.
 * @param   env: JNI Environment pointer
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_registerJNI(JNIEnv *env);

/**
 * @brief   Create a normal DVR recording
 * @param   path_prefix: A path like string and the common part of a group of
 *          associated files that represents a recording.
 * @param   trunc: Whether to clear the content of a recording on creation.
 * @param[out]  phandle: A pointer to the returned recording file handle
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_create1(
        const char* path_prefix,
        bool trunc,
        am_dvr_file_handle *phandle);

/**
 * @brief   Create a timeshift DVR recording
 * @param   path_prefix: A path like string and the common part of a group of
 *          associated files that represents a recording.
 * @param   limit_size: Give limited file size in bytes for a timeshift recording.
 * @param   limit_seconds: Give limited recording length in seconds for a
 *          timeshift recording.
 * @param   trunc: Whether to clear the content of a recording on creation.
 * @param[out]  phandle: A pointer to the returned recording file handle
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_create2(
        const char* path_prefix,
        long limit_size,
        int limit_seconds,
        bool trunc,
        am_dvr_file_handle *phandle);

/**
 * @brief   Open a DVR recording file for playback
 * @param   path_prefix: A path like string and the common part of a group of
 *          associated files that represents a recording.
 * @param[out]  phandle: A pointer to the returned recording file handle
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_create3(
        const char* path_prefix,
        am_dvr_file_handle *phandle);

/**
 * @brief   Destroy recording file handle and associated resource after using.
 *          Caller should ensure the associated recording and playback processes
 *          have finished before calling this interface.
 * @param   handle: A recording file handle
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_destroy(
        am_dvr_file_handle handle);

/**
 * @brief   Check if current recording file is for timeshift recording/playback.
 * @param   handle: A recording file handle
 * @param[out]  pstatus: A pointer to the returned status value.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_isTimeshift(
        am_dvr_file_handle handle,
        bool* pstatus);

/**
 * @brief   Get recording duration in ms
 * @param   handle: A recording file handle
 * @param[out]  pduration: A pointer to the returned duration value in ms.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_duration(
        am_dvr_file_handle handle,
        int64_t* pduration);

/**
 * @brief   Get current playing time position in ms
 * @param   handle: A recording file handle
 * @param[out]  ptime: A pointer to the returned time value in ms.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_getPlayingTime(
        am_dvr_file_handle handle,
        int64_t* ptime);

/**
 * @brief   Get start time of a recording in ms
 * @param   handle: A recording file handle
 * @param[out]  ptime: A pointer to the returned time value in ms. Notice it
 *          refers to the original time at which recording was initially made.
 *          The eariliest segments may have already been removed under timeshift
 *          situation, but the original time is still effective for calcuating
 *          timeshfit recording's start time.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_getStartTime(
        am_dvr_file_handle handle,
        int64_t* ptime);

/**
 * @brief   Get the segment id that is being played.
 * @param   handle: A recording file handle
 * @param[out]  pid: A pointer to the returned id value.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_getCurrSegmentId(
        am_dvr_file_handle handle,
        int32_t* pid);

/**
 * @brief   Get the first segment id of a recording.
 * @param   handle: A recording file handle
 * @param[out]  pid: A pointer to the returned id value.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_getFirstSegmentId(
        am_dvr_file_handle handle,
        int32_t* pid);

/**
 * @brief   Get the last segment id of a recording.
 * @param   handle: A recording file handle
 * @param[out]  pid: A pointer to the returned id value.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_getLastSegmentId(
        am_dvr_file_handle handle,
        int32_t* pid);

/**
 * @brief   Get number of segments of a recording.
 * @param   handle: A recording file handle
 * @param[out]  pnumber: A pointer to the returned number value.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_getNumberOfSegments(
        am_dvr_file_handle handle,
        int32_t* pnumber);

/**
 * @brief   Get video PID of a recording
 * @param   handle: A recording file handle
 * @param[out]  ppid: A pointer to the returned pid value.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_getVideoPID(
        am_dvr_file_handle handle,
        int* ppid);

/**
 * @brief   Get video format of a recording.
 * @param   handle: A recording file handle
 * @param[out]  pformat: A pointer to the returned format.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_getVideoFormat(
        am_dvr_file_handle handle,
        int* pformat);

/**
 * @brief   Get video MIME type of a recording
 * @param   handle: A recording file handle
 * @param[out]  buf: A buffer pointer to the returned MIME type string.
 * @param   buf_len: the length of buffer.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_getVideoMIMEType(
        am_dvr_file_handle handle,
        char* buf,
        int buf_len);

/**
 * @brief   Get audio PID of a recording
 * @param   handle: A recording file handle
 * @param[out]  ppid: A pointer to the returned pid value.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_getAudioPID(
        am_dvr_file_handle handle,
        int* ppid);

/**
 * @brief   Get audio format of a recording.
 * @param   handle: A recording file handle
 * @param[out]  pformat: A pointer to the returned format.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_getAudioFormat(
        am_dvr_file_handle handle,
        int* pformat);

/**
 * @brief   Get audio MIME type of a recording
 * @param   handle: A recording file handle
 * @param[out]  buf: A buffer pointer to the returned MIME type string.
 * @param   buf_len: the length of buffer.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_File_getAudioMIMEType(
        am_dvr_file_handle handle,
        char* buf,
        int buf_len);

/**
 * @brief   Create a new recorder.
 * @param   params: Initializing parameters.
 * @param[out] phandle: A pointer to the returned new recorder's handle.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_Recorder_create (
        am_dvr_recorder_init_params *params,
        am_dvr_recorder_handle *phandle);

/**
 * @brief   Destroy an unused recorder.
 * @param   handle The recorder handle to be freed.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_Recorder_destroy (
        am_dvr_recorder_handle handle);

/**
 * @brief   Add an element stream to the recorder.
 * @param   handle: The recorder handle.
 * @param   pid: The element stream's PID.
 * @param   type: The stream's type.
 * @param   format: The encoding format.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_Recorder_addStream (
        am_dvr_recorder_handle handle,
        int pid,
        am_dvr_stream_type type,
        int format);

/**
 * @brief   Remove an element stream from the recorder.
 * @param   handle: The recorder handle.
 * @param   pid: The element stream's PID to be removed.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_Recorder_removeStream (
        am_dvr_recorder_handle handle,
        int pid);

/**
 * @brief   Start recording.
 * @param   handle: The recorder handle.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_Recorder_start (
        am_dvr_recorder_handle handle);

/**
 * @brief   Pause recording.
 *      The data will dropped after pause recording.
 *      Invoke AmDvr_Recorder_start will resume the recording.
 * @param   handle: The recorder handle.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_Recorder_pause (
        am_dvr_recorder_handle handle);

/**
 * @brief   Stop recording.
 * @param   handle: The recorder handle.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_Recorder_stop (
        am_dvr_recorder_handle handle);

/**
 * @brief   Create a new player.
 * @param params: Initialize parameters.
 * @param[out] phandle: Return the new player's handle.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_Player_create (
        am_dvr_player_init_params *params,
        am_dvr_player_handle *phandle);

/**
 * @brief   Destroy an unused player.
 * @param   handle: The player handle.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_Player_destroy (
        am_dvr_player_handle handle);

/**
 * @brief   Start playing.
 * @param   handle: The player handle.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_Player_play (
        am_dvr_player_handle handle);

/**
 * @brief   Pause playing.
 *      Invoke AmDvr_Player_play will resume a paused playing.
 * @param   handle: The player handle.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_Player_pause (
        am_dvr_player_handle handle);

/**
 * @brief   Stop playing.
 * @param   handle: The player handle.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_Player_stop (
        am_dvr_player_handle handle);

/**
 * @brief   Seek to the position.
 * @param   handle: The player handle.
 * @param   seconds: The new position in seconds from the beginning.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_Player_seek (
        am_dvr_player_handle handle,
        int seconds);

/**
 * @brief   Set the playing speed.
 * @param   handle: The player handle.
 * @param   speed: the playing speed.
 *      speed < 0 means fast backward.
 *      0 < speed < 1 means slow motion.
 *      speed = 1 means play in normal speed.
 *      speed > 1 means fast forward.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_Player_setSpeed (
        am_dvr_player_handle handle,
        double speed);

/**
 * @brief   Delete the record file.
 * @param   path_prefix: The record's filename.
 * @retval  JDVRLIB_JNI_OK if function succeeds, or JDVRLIB_JNI_ERR if any error.
 */
am_dvr_result
AmDvr_deleteRecord (const char *path_prefix);

#ifdef __cplusplus
};
#endif

#endif // JDVRLIB_JNI_H


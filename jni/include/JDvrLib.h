#ifndef JDVRLIB_H
#define JDVRLIB_H

#ifdef __cplusplus
extern "C" {
#endif

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

/**Recording progress information. It will be provided with AM_DVR_RECORDER_EVENT_PROGRESS event*/
typedef struct {
    int         sessionNumber;
    int         state;
    long long   duration;       // in ms
    long long   startTime;      // in ms
    long long   endTime;        // in ms
    int         numberOfSegments;
    int         firstSegmentId;
    int         lastSegmentId;
    long long   size;           // in bytes
} am_dvr_recording_progress;

/**Playback progress information. It will be provided with AM_DVR_PLAYER_EVENT_PROGRESS event*/
typedef struct {
    int         sessionNumber;
    int         state;
    double      speed;
    long long   currTime;       // in ms, from origin
    long long   startTime;      // in ms, from origin
    long long   endTime;        // in ms, from origin
    long long   duration;       // in ms
    int         currSegmentId;
    int         firstSegmentId;
    int         lastSegmentId;
    int         numberOfSegments;
} am_dvr_playback_progress;

#ifdef __cplusplus
};
#endif

#endif // JDVRLIB_H


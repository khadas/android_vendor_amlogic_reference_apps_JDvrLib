package com.droidlogic.jdvrlib;

class SharedData {
    private static int mNextSessionNumber = 0;

    private SharedData() {
    }
    public static int generateSessionNumber() {
        return mNextSessionNumber++;
    }
}

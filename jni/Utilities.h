#ifndef UTILITIES_H
#define UTILITIES_H

#include <jni.h>
#include "log.h"

jclass FindClassOrDie(JNIEnv* env, const char* class_name);

jfieldID GetFieldIDOrDie(JNIEnv* env, jclass clazz, const char* field_name, const char* field_signature);

jmethodID GetMethodIDOrDie(JNIEnv* env, jclass clazz, const char* method_name, const char* method_signature);

jfieldID GetStaticFieldIDOrDie(JNIEnv* env, jclass clazz, const char* field_name, const char* field_signature);

jmethodID GetStaticMethodIDOrDie(JNIEnv* env, jclass clazz, const char* method_name, const char* method_signature);

template <typename T>
T MakeGlobalRefOrDie(JNIEnv* env, T in) {
    jobject res = env->NewGlobalRef(in);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to create global reference.");
    return static_cast<T>(res);
}

int registerNativeMethods(JNIEnv* env, const char* className, const JNINativeMethod* gMethods, int numMethods);

#endif // UTILITIES_H


#include "Utilities.h"

jclass FindClassOrDie(JNIEnv* env, const char* class_name) {
    jclass clazz = env->FindClass(class_name);
    LOG_ALWAYS_FATAL_IF(clazz == NULL, "Unable to find class %s", class_name);
    return clazz;
}

jfieldID GetFieldIDOrDie(JNIEnv* env, jclass clazz, const char* field_name,
        const char* field_signature) {
    jfieldID res = env->GetFieldID(clazz, field_name, field_signature);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find static field %s with signature %s", field_name,
            field_signature);
    return res;
}

jmethodID GetMethodIDOrDie(JNIEnv* env, jclass clazz, const char* method_name,
        const char* method_signature) {
    jmethodID res = env->GetMethodID(clazz, method_name, method_signature);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find method %s with signature %s", method_name,
            method_signature);
    return res;
}

jfieldID GetStaticFieldIDOrDie(JNIEnv* env, jclass clazz, const char* field_name,
        const char* field_signature) {
    jfieldID res = env->GetStaticFieldID(clazz, field_name, field_signature);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find static field %s with signature %s", field_name,
            field_signature);
    return res;
}

jmethodID GetStaticMethodIDOrDie(JNIEnv* env, jclass clazz, const char* method_name,
        const char* method_signature) {
    jmethodID res = env->GetStaticMethodID(clazz, method_name, method_signature);
    LOG_ALWAYS_FATAL_IF(res == NULL, "Unable to find static method %s with signature %s",
            method_name, method_signature);
    return res;
}

int registerNativeMethods(JNIEnv* env, const char* className,
        const JNINativeMethod* gMethods, int numMethods)
{
    jclass clazz;
    clazz = env->FindClass(className);
    if (clazz == NULL) {
        ALOGE("Native registration unable to find class '%s'", className);
        return JNI_FALSE;
    }
    if (env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
        ALOGE("RegisterNatives failed for '%s'", className);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}


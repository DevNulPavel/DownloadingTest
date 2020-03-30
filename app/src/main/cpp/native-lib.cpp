#include <jni.h>
#include <string>
#include "AndroidNativeDownloader.h"

/////////////////////////////////////////////////////////////////////////////////////////////////////////

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_downloadingtest_MainActivity_stringFromJNI(JNIEnv *env, jobject thisObj) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_downloadingtest_MainActivity_testNativeRequest(JNIEnv *env, jobject thisObj) {
    testNativeRequest();
}

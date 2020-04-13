#include "AndroidNativeDownloader.h"

#include <jni.h>
#include <string>
#include <mutex>
#include <unordered_map>

struct AndroidNativeLoadingInfo{
    std::string url;
    std::string filePath;
    AndroidNativeSuccessCallback successCallback;
    AndroidNativeRequestProgressCallback progressCb;
    AndroidNativeFailureCallback failureCallback;
    long timeoutMsec;
};

static JavaVM* _javaVM = nullptr;
static JNIEnv* jniEnv = nullptr;
static jclass _requestManagerClass = nullptr;
static jmethodID _startLoadingMethod = nullptr;
static std::mutex _loadsMutex;
static std::unordered_map<long, AndroidNativeLoadingInfo> _activeLoads;


// Library init
extern "C" jint JNI_OnLoad (JavaVM* vm, void* reserved) {
    jniEnv = nullptr;
    _javaVM = vm;
    if (vm->GetEnv((void**)&jniEnv, JNI_VERSION_1_4) != JNI_OK) {
        return -1;
    }

    _javaVM->AttachCurrentThread(&jniEnv, nullptr);

    return JNI_VERSION_1_4;
}

/////////////////////////////////////////////////////////////////////////////////////////////////////////

// Инициализация нативной части, cтатический метод, поэтому второй параметр - это класс
extern "C" void __attribute__((visibility("default")))
Java_com_seventeenbullets_android_xgen_downloader_AndroidNativeRequestManager_initializeNative(JNIEnv *env, jclass /* this */) {
    jclass tmp = jniEnv->FindClass("com/seventeenbullets/android/xgen/downloader/AndroidNativeRequestManager");
    _requestManagerClass = (jclass)jniEnv->NewGlobalRef(tmp);

    if (!_requestManagerClass) {
        printf("java class RequestManager is not found");
        exit(-1);
    }

    // https://docs.oracle.com/javase/7/docs/technotes/guides/jni/spec/types.html
    _startLoadingMethod = jniEnv->GetStaticMethodID(_requestManagerClass,
                                                      "startTestLoading",
                                                      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)J");
}

// Разрушение нативной части
extern "C" void __attribute__((visibility("default")))
Java_com_seventeenbullets_android_xgen_downloader_AndroidNativeRequestManager_destroyNative(JNIEnv *env, jclass /* this */) {
    // TODO:
}

// Загрузка завершена
extern "C" void __attribute__((visibility("default")))
Java_com_seventeenbullets_android_xgen_downloader_AndroidNativeRequestManager_loadingSuccess(JNIEnv *env, jclass /* this */, jlong loadingHandle) {
    std::unique_lock<std::mutex> lock(_loadsMutex);

    auto it = _activeLoads.find(loadingHandle);
    if(it != _activeLoads.end()){
        // Коллбек завершения
        if(it->second.successCallback){
            it->second.successCallback(loadingHandle);
        }

        // Удаляем из активных
        _activeLoads.erase(it);
    }
}

// Прогресс загрузки
extern "C" void __attribute__((visibility("default")))
Java_com_seventeenbullets_android_xgen_downloader_AndroidNativeRequestManager_loadingProgress(JNIEnv *env, jclass /* this */, jlong loadingHandle,
                                                                jlong totalSize, jlong loadedSize) {

    std::lock_guard<std::mutex> lock(_loadsMutex);

    auto it = _activeLoads.find(loadingHandle);
    if(it != _activeLoads.end()){

        // Коллбек прогресса
        if(it->second.progressCb){
            it->second.progressCb(loadingHandle, static_cast<double>(totalSize), static_cast<double>(loadedSize));
        }
    }
}

// Загрузка c ошибкой
extern "C" void __attribute__((visibility("default")))
Java_com_seventeenbullets_android_xgen_downloader_AndroidNativeRequestManager_loadingFailed(JNIEnv *env, jclass /* this */, jlong loadingHandle, bool canceled, int errorCode) {
    std::lock_guard<std::mutex> lock(_loadsMutex);

    auto it = _activeLoads.find(loadingHandle);
    if(it != _activeLoads.end()){
        // Коллбек завершения
        if(it->second.failureCallback){
            it->second.failureCallback(loadingHandle, canceled, errorCode); // TODO: Коды ошибок
        }

        // Удаляем из активных
        _activeLoads.erase(it);
    }
}

long sendRequest(const std::string& url,
                 const std::string& filePath,
                 const std::string& fileHash,
                 const std::string& title,
                 const std::string& description,
                 AndroidNativeSuccessCallback successCallback,
                 AndroidNativeRequestProgressCallback progressCb,
                 AndroidNativeFailureCallback failureCallback,
                 long timeoutMSec){

    AndroidNativeLoadingInfo info{
            url,
            filePath,
            successCallback,
            progressCb,
            failureCallback,
            timeoutMSec
    };

    jstring urlJava = jniEnv->NewStringUTF(url.c_str());
    jstring filePathJava = jniEnv->NewStringUTF(filePath.c_str());
    jstring md5HashJava = jniEnv->NewStringUTF(fileHash.c_str());
    jstring titleJava = jniEnv->NewStringUTF(title.c_str());
    jstring descriptionJava = jniEnv->NewStringUTF(description.c_str());

    jlong loadingID = jniEnv->CallStaticLongMethod(_requestManagerClass, _startLoadingMethod, urlJava, filePathJava, md5HashJava, titleJava, descriptionJava, timeoutMSec);

    jniEnv->DeleteLocalRef(urlJava);
    jniEnv->DeleteLocalRef(filePathJava);
    jniEnv->DeleteLocalRef(md5HashJava);
    jniEnv->DeleteLocalRef(titleJava);
    jniEnv->DeleteLocalRef(descriptionJava);

    std::lock_guard<std::mutex> lock(_loadsMutex);
    _activeLoads[loadingID] = std::move(info);

    printf("Loading started");

    return loadingID;
}


void testNativeRequest() {
    AndroidNativeSuccessCallback successCallback = [](long handle){
        printf("Loaded");
    };
    AndroidNativeRequestProgressCallback progressCallback = [](long handle, double totalSize, double loadedSize){
        printf("Progress: %d / %d", loadedSize, totalSize);
    };
    AndroidNativeFailureCallback failCallback = [](long handle, bool nativeCanceled, int errorCode){
        printf("Failed");
    };

    // http://pi2.17bullets.com/images/event/icon/eventicon_pvp_new.png?10250_
    // http://speedtest.tele2.net/
    // https://speed.hetzner.de/100MB.bin
    // https://speed.hetzner.de/1GB.bin - e5c834fbdaa6bfd8eac5eb9404eefdd4
    // https://speed.hetzner.de/10GB.bin
    // http://speedtest.ftp.otenet.gr/files/test100Mb.db
    sendRequest("https://speed.hetzner.de/1GB.bin",
                "/data/user/0/com.example.downloadingtest/files/download/1GB_123.bin",
                "e5c834fbdaa6bfd8eac5eb9404eefdd4", //"291addb3a362f2f69b52bfe766546c8e",
                "Loading title",
                "Loading description",
                successCallback, progressCallback, failCallback,
                5000);
}

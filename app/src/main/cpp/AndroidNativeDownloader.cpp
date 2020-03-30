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
    int connectTimeout;
    int transferTimeout;
    int speedLimitTimeout;
};

static JavaVM* _javaVM = nullptr;
static JNIEnv* _javaEnv = nullptr;
static jclass _requestManagerClass = nullptr;
static jmethodID _startLoadingMethod = nullptr;
static std::mutex _loadsMutex;
static std::unordered_map<long, AndroidNativeLoadingInfo> _activeLoads;


// Library init
extern "C" jint JNI_OnLoad (JavaVM* vm, void* reserved) {
    _javaEnv = nullptr;
    _javaVM = vm;
    if (vm->GetEnv((void**)&_javaEnv, JNI_VERSION_1_4) != JNI_OK) {
        return -1;
    }

    _javaVM->AttachCurrentThread(&_javaEnv, nullptr);

    return JNI_VERSION_1_4;
}

/////////////////////////////////////////////////////////////////////////////////////////////////////////

// Инициализация нативной части, cтатический метод, поэтому второй параметр - это класс
extern "C" JNIEXPORT void JNICALL
Java_com_seventeenbullets_android_xgen_downloader_AndroidNativeRequestManager_initializeNative(JNIEnv *env, jclass /* this */) {
    jclass tmp = _javaEnv->FindClass("com/seventeenbullets/android/xgen/downloader/AndroidNativeRequestManager");
    _requestManagerClass = (jclass)_javaEnv->NewGlobalRef(tmp);

    if (!_requestManagerClass) {
        printf("java class RequestManager is not found");
        exit(-1);
    }

    _startLoadingMethod = _javaEnv->GetStaticMethodID(_requestManagerClass,
                                                      "startTestLoading",
                                                      "(Ljava/lang/String;Ljava/lang/String;)J");
}

// Разрушение нативной части
extern "C" JNIEXPORT void JNICALL
Java_com_seventeenbullets_android_xgen_downloader_AndroidNativeRequestManager_destroyNative(JNIEnv *env, jclass /* this */) {
    // TODO:
}

// Загрузка завершена
extern "C" JNIEXPORT void JNICALL
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
extern "C" JNIEXPORT void JNICALL
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
extern "C" JNIEXPORT void JNICALL
Java_com_seventeenbullets_android_xgen_downloader_AndroidNativeRequestManager_loadingFailed(JNIEnv *env, jclass /* this */, jlong loadingHandle) {
    std::lock_guard<std::mutex> lock(_loadsMutex);

    auto it = _activeLoads.find(loadingHandle);
    if(it != _activeLoads.end()){
        // Коллбек завершения
        if(it->second.failureCallback){
            it->second.failureCallback(loadingHandle, 0, 0); // TODO: Коды ошибок
        }

        // Удаляем из активных
        _activeLoads.erase(it);
    }
}

long sendRequest(const std::string& url,
                 const std::string& filePath,
                 AndroidNativeSuccessCallback successCallback,
                 AndroidNativeRequestProgressCallback progressCb,
                 AndroidNativeFailureCallback failureCallback,
                 int connectTimeout,
                 int transferTimeout,
                 int speedLimitTimeout){
    std::lock_guard<std::mutex> lock(_loadsMutex);

    AndroidNativeLoadingInfo info{
            url,
            filePath,
            successCallback,
            progressCb,
            failureCallback,
            connectTimeout,
            transferTimeout,
            speedLimitTimeout
    };

    jstring urlJava = _javaEnv->NewStringUTF(url.c_str());
    jstring filePathJava = _javaEnv->NewStringUTF(filePath.c_str());

    jlong loadingID = _javaEnv->CallStaticLongMethod(_requestManagerClass, _startLoadingMethod, urlJava, filePathJava);

    _javaEnv->DeleteLocalRef(urlJava);
    _javaEnv->DeleteLocalRef(filePathJava);

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
    AndroidNativeFailureCallback failCallback = [](long handle, long httpCode, int errorCode){
        printf("Failed");
    };

    // http://speedtest.tele2.net/
    // https://speed.hetzner.de/100MB.bin
    // https://speed.hetzner.de/1GB.bin
    // https://speed.hetzner.de/10GB.bin
    // http://speedtest.ftp.otenet.gr/files/test100Mb.db
    sendRequest("http://pi2.17bullets.com/images/event/icon/eventicon_pvp_new.png?10250_",
                "/data/user/0/com.example.downloadingtest/files/download/eventicon_pvp_new.png",
                successCallback, progressCallback, failCallback,
                0, 0, 0);
}

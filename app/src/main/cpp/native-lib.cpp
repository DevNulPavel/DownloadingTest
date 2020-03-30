#include <jni.h>
#include <string>
#include <mutex>
#include <unordered_map>

typedef std::vector<uint8_t > DataBuffer;
typedef std::function<void(long handle, const std::string& responseHeader, std::shared_ptr<DataBuffer> responseData)> SuccessCallback;
typedef std::function<void(long handle, long httpCode, int errorCode)> FailureCallback;
typedef std::function<void(long handle, double totalSize, double loadedSize, double, double)> RequestProgressCallback;

struct AndroidNativeLoadingInfo{
    std::string url;
    std::string filePath;
    SuccessCallback successCallback;
    RequestProgressCallback progressCb;
    FailureCallback failureCallback;
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
Java_com_example_downloadingtest_RequestManager_initializeNative(JNIEnv *env, jclass /* this */) {
    jclass tmp = _javaEnv->FindClass("com/example/downloadingtest/RequestManager");
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
Java_com_example_downloadingtest_RequestManager_destroyNative(JNIEnv *env, jclass /* this */) {
    // TODO:
}

// Загрузка завершена
extern "C" JNIEXPORT void JNICALL
Java_com_example_downloadingtest_RequestManager_loadingSuccess(JNIEnv *env, jclass /* this */, jlong loadingHandle) {
    std::unique_lock<std::mutex> lock(_loadsMutex);

    auto it = _activeLoads.find(loadingHandle);
    if(it != _activeLoads.end()){
        // Коллбек завершения
        if(it->second.successCallback){
            it->second.successCallback(loadingHandle, "", nullptr);
        }

        // Удаляем из активных
        _activeLoads.erase(it);
    }
}

// Прогресс загрузки
extern "C" JNIEXPORT void JNICALL
Java_com_example_downloadingtest_RequestManager_loadingProgress(JNIEnv *env, jclass /* this */, jlong loadingHandle,
                                                                jlong totalSize, jlong loadedSize) {

    std::lock_guard<std::mutex> lock(_loadsMutex);

    auto it = _activeLoads.find(loadingHandle);
    if(it != _activeLoads.end()){

        // Коллбек прогресса
        if(it->second.progressCb){
            it->second.progressCb(loadingHandle, static_cast<double>(totalSize), static_cast<double>(loadedSize), 0.0, 0.0);
        }
    }
}

// Загрузка c ошибкой
extern "C" JNIEXPORT void JNICALL
Java_com_example_downloadingtest_RequestManager_loadingFailed(JNIEnv *env, jclass /* this */, jlong loadingHandle) {
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
                 SuccessCallback successCallback,
                 RequestProgressCallback progressCb,
                 FailureCallback failureCallback,
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


/////////////////////////////////////////////////////////////////////////////////////////////////////////

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_downloadingtest_MainActivity_stringFromJNI(JNIEnv *env, jobject thisObj) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_downloadingtest_MainActivity_testNativeRequest(JNIEnv *env, jobject thisObj) {

    SuccessCallback successCallback = [](long handle, const std::string&, std::shared_ptr<DataBuffer>){
        printf("Loaded");
    };
    RequestProgressCallback progressCallback = [](long handle, double totalSize, double loadedSize, double, double){
        printf("Progress: %d / %d", loadedSize, totalSize);
    };
    FailureCallback failCallback = [](long handle, long httpCode, int errorCode){
        printf("Failed");
    };

    // http://speedtest.tele2.net/
    // https://speed.hetzner.de/100MB.bin
    // https://speed.hetzner.de/1GB.bin
    // https://speed.hetzner.de/10GB.bin
    // http://speedtest.ftp.otenet.gr/files/test100Mb.db
    sendRequest("https://speed.hetzner.de/1GB.bin", "",
            successCallback, progressCallback, failCallback,
            0, 0, 0);
}

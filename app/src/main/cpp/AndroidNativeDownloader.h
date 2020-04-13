#ifndef DOWNLOADINGTEST_ANDROIDNATIVEDOWNLOADER_H
#define DOWNLOADINGTEST_ANDROIDNATIVEDOWNLOADER_H

#include <inttypes.h>
#include <string>
#include <functional>

// Error codes:
enum class AndroidNativeDownloaderErrors{
    EMPTY = 0,
    NO_FREE_SPACE_ERROR = 1,
    FILE_ERROR = 2,
    FILE_ALREADY_EXISTS_ERROR = 3,
    DEVICE_ERROR = 4,
    REQUEST_ERROR = 5,
    WRONG_HASH_ERROR = 6,
    TIMEOUT_ERROR = 7,
    UNKNOWN_ERROR = 99,
};

typedef std::vector<int8_t > AndroidNativeDataBuffer;
typedef std::function<void(long handle)> AndroidNativeSuccessCallback;
typedef std::function<void(long handle, double totalSize, double loadedSize)> AndroidNativeRequestProgressCallback;
typedef std::function<void(long handle, bool nativeCanceled, AndroidNativeDownloaderErrors errorCode)> AndroidNativeFailureCallback;

long sendRequest(const std::string& url,
                 const std::string& filePath,
                 AndroidNativeSuccessCallback successCallback,
                 AndroidNativeRequestProgressCallback progressCb,
                 AndroidNativeFailureCallback failureCallback,
                 int connectTimeout,
                 int transferTimeout,
                 int speedLimitTimeout);

void testNativeRequest();

#endif //DOWNLOADINGTEST_ANDROIDNATIVEDOWNLOADER_H

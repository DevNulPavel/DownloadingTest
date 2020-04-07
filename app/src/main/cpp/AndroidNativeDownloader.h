#ifndef DOWNLOADINGTEST_ANDROIDNATIVEDOWNLOADER_H
#define DOWNLOADINGTEST_ANDROIDNATIVEDOWNLOADER_H

#include <inttypes.h>
#include <string>
#include <functional>

typedef std::vector<int8_t > AndroidNativeDataBuffer;
typedef std::function<void(long handle)> AndroidNativeSuccessCallback;
typedef std::function<void(long handle, double totalSize, double loadedSize)> AndroidNativeRequestProgressCallback;
typedef std::function<void(long handle, long httpCode, int errorCode, bool nativeCanceled)> AndroidNativeFailureCallback;

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

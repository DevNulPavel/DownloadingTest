#ifndef DOWNLOADINGTEST_ANDROIDNATIVEDOWNLOADER_H
#define DOWNLOADINGTEST_ANDROIDNATIVEDOWNLOADER_H

#include <inttypes.h>
#include <string>
#include <functional>

typedef std::vector<uint8_t > DataBuffer;
typedef std::function<void(long handle, const std::string& responseHeader, std::shared_ptr<DataBuffer> responseData)> SuccessCallback;
typedef std::function<void(long handle, long httpCode, int errorCode)> FailureCallback;
typedef std::function<void(long handle, double totalSize, double loadedSize, double, double)> RequestProgressCallback;

long sendRequest(const std::string& url,
                 const std::string& filePath,
                 SuccessCallback successCallback,
                 RequestProgressCallback progressCb,
                 FailureCallback failureCallback,
                 int connectTimeout,
                 int transferTimeout,
                 int speedLimitTimeout);

void testNativeRequest();

#endif //DOWNLOADINGTEST_ANDROIDNATIVEDOWNLOADER_H

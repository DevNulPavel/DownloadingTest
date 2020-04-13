package com.seventeenbullets.android.xgen.downloader;

import java.util.Date;
import java.util.Calendar;


import java.sql.Time;

public class LoadingInfo {
    public long fakeLoadingId;
    public long nativeLoadingId;
    public long loadSize;
    public String url;
    public String tmpFilePath;
    public String resultFilePath;
    public long checkTime;
    public boolean processed;
    public String resultHash;
    public long timeoutMSec;
}

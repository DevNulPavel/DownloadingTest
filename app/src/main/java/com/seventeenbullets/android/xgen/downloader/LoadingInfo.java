package com.seventeenbullets.android.xgen.downloader;

import java.util.Date;
import java.util.Calendar;


import java.sql.Time;

public class LoadingInfo {
    public long loadingId;
    public long loadSize;
    public String url;
    public String tmpFilePath;
    public String resultFilePath;
    public long createTime;
    public boolean completed;
    public boolean failed;
    public String resultHash;
}

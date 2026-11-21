package com.clawmate.platform.display;

import android.content.Intent;
import android.view.Surface;

interface IDisplayHost {
    int open(in Intent target);
    oneway void close(int displayId);
    boolean pressKey(int displayId, int keyCode);
    int openWithSurface(in Intent target, in Surface surface, int width, int height, int dpi);
    boolean relaunch(int displayId, String packageName);
    boolean reopenLink(int displayId);
}

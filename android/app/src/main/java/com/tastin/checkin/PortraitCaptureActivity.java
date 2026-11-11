package com.tastin.checkin;

import android.os.Bundle;

import com.journeyapps.barcodescanner.CaptureActivity;

/**
 * 竖屏扫码页。
 *
 * zxing-android-embedded 自带的 CaptureActivity 在清单里写死了
 * screenOrientation="sensorLandscape"，扫电脑屏幕时被迫横屏很别扭。
 * 这里继承它并在清单中单独声明为竖屏。
 */
public class PortraitCaptureActivity extends CaptureActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}

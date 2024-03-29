# Basic Video Capturer Camera

Sample app shows how to use device camera as the video source with the custom video capturer using the [Camera](https://developer.android.com/reference/android/hardware/camera/package-summary) package.

> [Camera](https://developer.android.com/reference/android/hardware/camera/package-summary) class was deprecated in API level 21. Please check [Basic Video Capturer Camera 2](../Basic-Video-Capturer-Camera-2) project for more up to date implementation.

> Note: Check [Custom Video Driver](../Custom-Video-Driver) project to see how to use custom video capturer and custom video renderer together.

A custom video capturer will be helpful when:
- modifying the camera stream content - image composition (adding watermark logo) or image processing (mirroring the video, removing the background or putting a virtual hat on the user).
- streaming any content other than the one coming from the device camera, e.g. streaming content of a particular view's game or content (check [Screen-Sharing](../Screen-Sharing) project).

## Using a custom video capturer

The `MirrorVideoCapturer` is a custom class that extends the `BaseVideoCapturer` class, defined in the OpenTok Android SDK. During construction of a `Publisher` object, the code sets a custom video capturer by calling the `capturer` method of the Publisher:

```java
MirrorVideoCapturer mirrorVideoCapturer = new MirrorVideoCapturer(
                    MainActivity.this,
                    Publisher.CameraCaptureResolution.HIGH,
                    Publisher.CameraCaptureFrameRate.FPS_30);

publisher = new Publisher.Builder(MainActivity.this)
        .capturer(mirrorVideoCapturer)
        .build();

```

The `CustomVideoCapturer` class extends the `BaseVideoCapturer` class, defined in the OpenTok Android SDK. The `getCaptureSettings` method returns the settings of the video capturer, including the frame
rate, width, height, video delay, and video format for the capturer:

```java
@Override
public CaptureSettings getCaptureSettings() {

    CaptureSettings captureSettings = new CaptureSettings();

    VideoUtils.Size resolution = new VideoUtils.Size();
    resolution = getPreferredResolution();

    int frameRate = getPreferredFrameRate();

    if (camera != null) {
        settings = new CaptureSettings();
        configureCaptureSize(resolution.width, resolution.height);
        captureSettings.fps = frameRate;
        captureSettings.width = captureWidth;
        captureSettings.height = captureHeight;
        captureSettings.format = NV21;
        captureSettings.expectedDelay = 0;
    } else {
        captureSettings.fps = frameRate;
        captureSettings.width = resolution.width;
        captureSettings.height = resolution.height;
        captureSettings.format = ARGB;
    }

    return settings;
}
```

The app calls `startCapture()` to start capturing video from the custom video capturer.

The class also implements the [PreviewCallback](https://developer.android.com/reference/android/hardware/Camera.PreviewCallback) interface. The `onPreviewFrame` method of this interface is called as preview frames of the camera become available. In this method, the app calls the `provideByteArrayFrame` method of the `MirrorVideoCapturer` class (inherited from the `BaseVideoCapturer` class). This method provides a video frame, defined as a byte array, to the video capturer:

```java
provideByteArrayFrame(data, NV21, captureWidth, captureHeight, currentRotation, isFrontCamera());
```

The publisher adds this video frame to the published stream.

## Further Reading

* Review Basic Video Capturer Camera 2](../Basic-Video-Capturer-Camera-2) project
* Review [Custom Video Driver](../Custom-Video-Driver) project
* Review [other sample projects](../)
* Read more about [OpenTok Android SDK](https://tokbox.com/developer/sdks/android/)
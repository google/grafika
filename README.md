Grafika
=======

Welcome to Grafika, a dumping ground for Android graphics & media hacks.

Grafika is:
- A collection of hacks exercising graphics features.
- An SDK app, developed for API 18 (Android 4.3).  While some of the code
  may work with older versions of Android, no effort will be made to
  support them.
- Open source (Apache 2 license), copyright by Google.  So you can use the
  code according to the terms of the license (see "LICENSE").
- A perpetual work-in-progress.  It's updated whenever the need arises.

However:
- It's not stable.
- It's not polished or well tested.  Expect the UI to be ugly and awkward.
- It's not intended as a demonstration of the proper way to do things.
  The code may handle edge cases poorly or not at all.
- It's not documented.
- It's not part of the Android Open Source Project.  We cannot accept
  contributions to Grafika, even if you have an AOSP CLA on file.
- It's NOT AN OFFICIAL GOOGLE PRODUCT.  It's just a bunch of stuff that
  got thrown together on company time and equipment.
- It's generally just not supported.

There is some overlap with the code on http://www.bigflake.com/mediacodec/.  The code there largely consists of "headless" CTS tests, which are designed to be robust, self-contained, and largely independent of the usual app lifecycle issues.  Grafika is a traditional app, and makes an effort to handle app issues correctly (like not doing lots of work on the UI thread).

Features are added to Grafika as the need arises, often in response to developer complaints about correctness or performance problems in the platform (either to confirm that the problems exist, or demonstrate an approach that works).


Current features
----------------

[Record GL app with FBO](src/com/android/grafika/RecordFBOActivity.java).  Simultaneously draws to the display and to a video encoder with OpenGL ES, using framebuffer objects to avoid re-rendering.
- It can write to the video encoder three different ways: (1) draw twice; (2) draw offscreen and
  blit twice; (3) draw onscreen and blit framebuffer.  #3 doesn't work yet.
- The renderer is trigged by Choreographer to update every vsync.  If we get too far behind,
  it will drop frames.
- The encoder is fed every-other frame, so the recorded output will be ~30fps rather than ~60fps
  on a typical device.
- The recording is letter- or pillar-boxed to maintain an aspect ratio that matches the
  display, so you'll get different results from recording in landscape vs. portrait.  (Do
  bear in mind that the built-in video player does *not* currently adjust the aspect ratio
  to match the movie -- best to pull the mp4 file out of /data/data/com.android.grafika/files
  and view it on the desktop.)
- The output is a video-only MP4 file ("fbo-gl-recording.mp4").

[Show + capture camera](src/com/android/grafika/CameraCaptureActivity.java).  Attempts to record at 720p from the front-facing camera, displaying the preview and recording it simultaneously.
- Use the record button to toggle recording on and off.
- Recording continues until stopped.  If you back out and return, recording will start again,
  with a real-time gap.  If you try to play the movie while it's recording, you will see
  an incomplete file (and probably cause the play movie activity to crash).
- The preview frames are rendered to a `GLSurfaceView`.  The aspect ratio will likely appear
  stretched -- the View's size isn't adjusted.  Generally looks best in landscape.
- You can select a filter to apply to the preview.  It does not get applied to the recording.
  The shader used for the filters is not optimized, but seems to perform well on most devices
  (the original Nexus 7 (2012) being a notable exception).  Demo
  here: http://www.youtube.com/watch?v=kH9kCP2T5Gg
- The output is a video-only MP4 file ("camera-test.mp4").

[Play video (TextureView)](src/com/android/grafika/PlayMovieActivity.java).  Plays the video track from an MP4 file.
- Only sees files in `/data/data/com.android.grafika/files/`.
- By default the video is played once, at the same rate it was recorded.  You can use the
  checkboxes to loop playback and/or play the frames as quickly as possible.
- Uses a `TextureView` for output.
- Does not attempt to preserve the video's aspect ratio, so things will appear stretched.

[Basic GL in TextureView](src/com/android/grafika/TextureViewGLActivity.java).  Simple use of GLES in a `TextureView`, rather than a `GLSurfaceView`.

[glReadPixels speed](src/com/android/grafika/ReadPixelsActivity.java).  Simple, unscientific measurement of the time required for `glReadPixels()` to read a 720p frame.

[Live camera (TextureView)](src/com/android/grafika/LiveCameraActivity.java).  Directs the camera preview to a `TextureView`.
- This comes more or less verbatim from the `TextureView` documentation.
- Uses the default (rear-facing) camera.  If the device has no default camera (e.g.
  Nexus 7 (2012)), the Activity will crash.


Feature ideas
-------------

In no particular order.

- Render simple GLES with FBO (GLES2 / GLES3), toggle recording.
- Add "Play video (SurfaceView)" to illustrate usage / differences vs. TextureView.  Include
  a slide for random access and buttons for single-frame advance / rewind (requires
  seeking to nearest sync frame and decoding frames until target is reached).
- Capture audio from microphone, record + mux it.
- Enable preview on front/back cameras simultaneously, display them side-by-side.  (Is
  this even possible?)
- Send video playback to a SurfaceTexture, render the ST to TextureView.  Structure the
  Activity so playback continues during rotation or other Activity restarts (requires
  preventing the MediaCodec decoder output surface from resetting).
- Convert a series of PNG images to video.
- Use virtual displays to record app activity.
- Create a MediaCodec encoder, get a surface with createInputSurface(), and then render it on
  with a Canvas.  Produces weird results on Nexus 10.  (See http://b.android.com/61194 .)
- Record video in a continuous loop, grabbing a snapshot of the previous N seconds on request.
- Play continuous video from a series of MP4 files with different characteristics.
- Experiment with alternatives to glReadPixels().  Add a PBO speed test.  (Doesn't seem
  to be a way to play with eglCreateImageKHR from Java.)
- Add a GL/EGL status page -- dump version info, have scrolling list of supported extensions.
- Do something with ImageReader class (req API 19).
- Implement a continuous recorder that holds the last N seconds in a circular buffer.


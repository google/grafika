/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika;


import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.widget.Toast;

/**
 * Helper class for handling dangerous permissions for Android API level >= 23 which
 * requires user consent at runtime to access the camera.
 */
class PermissionHelper {
  public static final int  RC_PERMISSION_REQUEST = 9222;
  public static boolean hasCameraPermission(Activity activity) {
    return ContextCompat.checkSelfPermission(activity,
            Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
  }
  public static boolean hasWriteStoragePermission(Activity activity) {
    return ContextCompat.checkSelfPermission(activity,
            Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
  }
  public static void requestCameraPermission(Activity activity, boolean requestWritePermission) {

    boolean showRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity,
              Manifest.permission.CAMERA) || (requestWritePermission &&
    ActivityCompat.shouldShowRequestPermissionRationale(activity,
            Manifest.permission.WRITE_EXTERNAL_STORAGE));
    if (showRationale) {
        Toast.makeText(activity,
                "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
      } else {

        // No explanation needed, we can request the permission.

      String permissions[] = requestWritePermission ? new String[]{Manifest.permission.CAMERA,
              Manifest.permission.WRITE_EXTERNAL_STORAGE}: new String[]{Manifest.permission.CAMERA};
        ActivityCompat.requestPermissions(activity,permissions,RC_PERMISSION_REQUEST);
      }
    }

  public static void requestWriteStoragePermission(Activity activity) {
    boolean showRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE);
    if (showRationale) {
      Toast.makeText(activity,
              "Writing to external storage permission is needed to run this application",
              Toast.LENGTH_LONG).show();
    } else {

      // No explanation needed, we can request the permission.

      String permissions[] =  new String[]{ Manifest.permission.WRITE_EXTERNAL_STORAGE};

      ActivityCompat.requestPermissions(activity,permissions,RC_PERMISSION_REQUEST);
    }
  }

  /** Launch Application Setting to grant permission. */
  public static void launchPermissionSettings(Activity activity) {
    Intent intent = new Intent();
    intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
    intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
    activity.startActivity(intent);
  }

}

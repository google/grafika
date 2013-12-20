/*
 * Copyright 2013 Google Inc. All rights reserved.
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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Log;
import android.view.InflateException;
import android.view.View;

/**
 * Creates and displays an "about" box.
 */
public class AboutBox {
    private static final String TAG = MainActivity.TAG;

    /**
     * Retrieves the application's version info.
     */
    private static String getVersionString(Context context) {
        PackageManager pman = context.getPackageManager();
        String packageName = context.getPackageName();
        try {
            PackageInfo pinfo = pman.getPackageInfo(packageName, 0);
            Log.d(TAG, "Found version " + pinfo.versionName + " for " + packageName);
            return pinfo.versionName + " [" + pinfo.versionCode + "]";
        } catch (NameNotFoundException nnfe) {
            Log.w(TAG, "Unable to retrieve package info for " + packageName);
            return "(unknown)";
        }
    }

    /**
     * Displays the About box.  An AlertDialog is created in the calling activity's context.
     * <p>
     * The box will disappear if the "OK" button is touched, if an area outside the box is
     * touched, if the screen is rotated ... doing just about anything makes it disappear.
     */
    public static void display(Activity caller) {
        String versionStr = getVersionString(caller);
        String aboutHeader = caller.getString(R.string.app_name) + " v" + versionStr;

        // Manually inflate the view that will form the body of the dialog.
        View aboutView;
        try {
            aboutView = caller.getLayoutInflater().inflate(R.layout.about_dialog, null);
        } catch (InflateException ie) {
            Log.e(TAG, "Exception while inflating about box: " + ie.getMessage());
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(caller);
        builder.setTitle(aboutHeader);
        builder.setIcon(R.drawable.ic_launcher);
        builder.setCancelable(true);        // implies setCanceledOnTouchOutside
        builder.setPositiveButton(R.string.ok, null);
        builder.setView(aboutView);
        builder.show();
    }
}

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
import android.util.Log;
import android.view.InflateException;
import android.view.View;

/**
 * Utility functions for work_dialog.
 */
public class WorkDialog {
    private static final String TAG = MainActivity.TAG;

    private WorkDialog() {}     // nah

    /**
     * Prepares an alert dialog builder, using the work_dialog view.
     * <p>
     * The caller should finish populating the builder, then call AlertDialog.Builder#show().
     */
    public static AlertDialog.Builder create(Activity activity, int titleId) {
        View view;
        try {
            view = activity.getLayoutInflater().inflate(R.layout.work_dialog, null);
        } catch (InflateException ie) {
            Log.e(TAG, "Exception while inflating work dialog layout: " + ie.getMessage());
            throw ie;
        }

        String title = activity.getString(titleId);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(title);
        builder.setView(view);
        return builder;
   }
}

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

import android.os.Bundle;
import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.AdapterView.OnItemSelectedListener;

/**
 * Main activity -- entry point from Launcher.
 */
public class MainActivity extends Activity implements OnItemSelectedListener {
    public static final String TAG = "Grafika";
    public static final Class[] TEST_ACTIVITIES = {
        // The content and order MUST match the "test_names" string-array.
        ConstantCaptureActivity.class,
        CameraCaptureActivity.class,
        RecordFBOActivity.class,
        PlayMovieActivity.class,
        TextureViewGLActivity.class,
        ReadPixelsActivity.class,
        LiveCameraActivity.class,
        DoubleDecodeActivity.class,

        ChorTestActivity.class,
    };

    private int mSelectedTest = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // One-time singleton initialization; requires activity context to get file location.
        ContentManager.initialize(this);

        // Populate test-selection spinner.
        Spinner spinner = (Spinner) findViewById(R.id.selectTest_spinner);
        // Need to create one of these fancy ArrayAdapter thingies, and specify the generic layout
        // for the widget itself.
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.test_names, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Apply the adapter to the spinner.
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(this);

        ContentManager cm = ContentManager.getInstance();
        if (!cm.isContentCreated(this)) {
            ContentManager.getInstance().createAll(this);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    /*
     * Called when the Spinner gets touched.  (If we had a bunch of spinners we might want to
     * create anonymous inner classes that specify the callback for each.)
     */
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        Spinner spinner = (Spinner) parent;
        int testNum = spinner.getSelectedItemPosition();

        Log.d(TAG, "onItemSelected: " + testNum);
        mSelectedTest = testNum;
    }

    /* for nothing, you get nothing! */
    @Override public void onNothingSelected(AdapterView<?> parent) {}

    /**
     * onClick handler for "start" button.
     */
    public void clickStart(View unused) {
        Intent intent = new Intent(this, TEST_ACTIVITIES[mSelectedTest]);
        startActivity(intent);
    }

    /**
     * onClick handler for "about" menu item.
     */
    public void clickAbout(MenuItem unused) {
        AboutBox.display(this);
    }

    /**
     * onClick handler for "regenerate content" menu item.
     */
    public void clickRegenerateContent(MenuItem unused) {
        ContentManager.getInstance().createAll(this);
    }
}

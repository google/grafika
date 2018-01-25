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
import android.app.ListActivity;
import android.content.Intent;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.SimpleAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main activity -- entry point from Launcher.
 */
public class MainActivity extends ListActivity {
    public static final String TAG = "Grafika";

    // map keys
    private static final String TITLE = "title";
    private static final String DESCRIPTION = "description";
    private static final String CLASS_NAME = "class_name";

    /**
     * Each entry has three strings: the test title, the test description, and the name of
     * the activity class.
     */
    private static final String[][] TESTS = {
        { "* Play video (TextureView)",
            "Plays .mp4 videos created by Grafika",
            "PlayMovieActivity" },
        { "Continuous capture",
            "Records camera continuously, saves a snapshot when requested",
            "ContinuousCaptureActivity" },
        { "Double decode",
            "Decodes two videos side-by-side",
            "DoubleDecodeActivity" },
        { "Hardware scaler exerciser",
            "Exercises SurfaceHolder#setFixedSize()",
            "HardwareScalerActivity" },
        { "Live camera (TextureView)",
            "Trivially feeds the camera preview to a view",
            "LiveCameraActivity" },
        { "Multi-surface test",
            "Three overlapping SurfaceViews, one secure",
            "MultiSurfaceActivity" },
        { "Play video (SurfaceView)",
            "Plays .mp4 videos created by Grafika",
            "PlayMovieSurfaceActivity" },
        { "Record GL app",
            "Records GL app with FBO, re-render, or FB blit",
            "RecordFBOActivity" },
        { "Record screen using MediaProjectionManager",
                "Screen recording using MediaProjectionManager and Virtual Display",
                "ScreenRecordActivity" },
        { "Scheduled swap",
            "Exercises SurfaceFlinger PTS handling",
            "ScheduledSwapActivity" },
        { "Show + capture camera",
            "Shows camera preview, records when requested",
            "CameraCaptureActivity" },
        { "Simple GL in TextureView",
            "Renders with GL as quickly as possible",
            "TextureViewGLActivity" },
        { "Simple Canvas in TextureView",
            "Renders with Canvas as quickly as possible",
            "TextureViewCanvasActivity" },
        { "Texture from Camera",
            "Resize and zoom the camera preview",
            "TextureFromCameraActivity" },
        { "{bench} glReadPixels speed test",
            "Tests glReadPixels() performance with 720p frames",
            "ReadPixelsActivity" },
        { "{bench} glTexImage2D speed test",
            "Tests glTexImage2D() performance on 512x512 image",
            "TextureUploadActivity" },
        { "{util} Color bars",
            "Shows RGB color bars",
            "ColorBarActivity" },
        { "{util} OpenGL ES info",
            "Dumps info about graphics drivers",
            "GlesInfoActivity" },
        { "{~ignore} Chor test",
            "Exercises bug",
            "ChorTestActivity" },
        { "{~ignore} Codec open test",
            "Exercises bug",
            "CodecOpenActivity" },
        { "{~ignore} Software input surface",
            "Exercises bug",
            "SoftInputSurfaceActivity" },
    };

    /**
     * Compares two list items.
     */
    private static final Comparator<Map<String, Object>> TEST_LIST_COMPARATOR =
            new Comparator<Map<String, Object>>() {
        @Override
        public int compare(Map<String, Object> map1, Map<String, Object> map2) {
            String title1 = (String) map1.get(TITLE);
            String title2 = (String) map2.get(TITLE);
            return title1.compareTo(title2);
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // One-time singleton initialization; requires activity context to get file location.
        ContentManager.initialize(this);

        setListAdapter(new SimpleAdapter(this, createActivityList(),
                android.R.layout.two_line_list_item, new String[] { TITLE, DESCRIPTION },
                new int[] { android.R.id.text1, android.R.id.text2 } ));

        ContentManager cm = ContentManager.getInstance();
        if (!cm.isContentCreated(this)) {
            ContentManager.getInstance().createAll(this);
        }
    }

    /**
     * Creates the list of activities from the string arrays.
     */
    private List<Map<String, Object>> createActivityList() {
        List<Map<String, Object>> testList = new ArrayList<Map<String, Object>>();

        for (String[] test : TESTS) {
            Map<String, Object> tmp = new HashMap<String, Object>();
            tmp.put(TITLE, test[0]);
            tmp.put(DESCRIPTION, test[1]);
            Intent intent = new Intent();
            // Do the class name resolution here, so we crash up front rather than when the
            // activity list item is selected if the class name is wrong.
            try {
                Class cls = Class.forName("com.android.grafika." + test[2]);
                intent.setClass(this, cls);
                tmp.put(CLASS_NAME, intent);
            } catch (ClassNotFoundException cnfe) {
                throw new RuntimeException("Unable to find " + test[2], cnfe);
            }
            testList.add(tmp);
        }

        Collections.sort(testList, TEST_LIST_COMPARATOR);

        return testList;
    }

    @Override
    protected void onListItemClick(ListView listView, View view, int position, long id) {
        Map<String, Object> map = (Map<String, Object>)listView.getItemAtPosition(position);
        Intent intent = (Intent) map.get(CLASS_NAME);
        startActivity(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    /**
     * onClick handler for "about" menu item.
     */
    public void clickAbout(@SuppressWarnings("unused") MenuItem unused) {
        AboutBox.display(this);
    }

    /**
     * onClick handler for "regenerate content" menu item.
     */
    public void clickRegenerateContent(@SuppressWarnings("unused") MenuItem unused) {
        ContentManager.getInstance().createAll(this);
    }
}

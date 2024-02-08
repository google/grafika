package com.android.grafika

import android.app.Activity
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import com.android.grafika.triangleactivity.Shapes
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class TriangleActivity : Activity() {

    class MyGlSurfaceView(context: Context) : GLSurfaceView(context) {

        class Renderer : GLSurfaceView.Renderer {

            private lateinit var triangle: Shapes.Triangle

            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                GLES20.glClearColor(0f, 0f, 1f, 1f)
                triangle = Shapes.Triangle()
            }

            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                GLES20.glViewport(0, 0, width, height)
            }

            override fun onDrawFrame(gl: GL10?) {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                triangle.draw()
            }

        }

        private val renderer: GLSurfaceView.Renderer

        init {
            setEGLContextClientVersion(2)

            renderer = Renderer()
            setRenderer(renderer)

            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
    }

    private lateinit var glView: GLSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        glView = MyGlSurfaceView(this)
        setContentView(glView)
    }
}
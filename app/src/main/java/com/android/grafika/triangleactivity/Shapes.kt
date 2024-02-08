package com.android.grafika.triangleactivity

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Shapes {

    class Triangle {
        val color = floatArrayOf(0.63671875f, 0.76953125f, 0.22265625f, 1.0f)

        // counterclockwise
        private val coordinates = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            0f, 1f
        )
        val COORDS_PER_VERTEX = 2
        val byteBuffer = ByteBuffer.allocateDirect(coordinates.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(coordinates)
                position(0)
            }
        }

        // triangle vertex array
        // vertex shader
        val vertexShaderSource =
            "attribute vec4 vPosition;" +
                "void main() {" +
                "  gl_Position=vPosition;" +
                "}"

        // fragment shader
        val fragmentShaderSource =
            "precision mediump float;" +
                "uniform vec4 vColor;" +
                "void main() {" +
                "  gl_FragColor = vColor;" +
                "}"

        val program = GLES20.glCreateProgram()

        init {
            val vertexShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
                GLES20.glShaderSource(it, vertexShaderSource)
                GLES20.glCompileShader(it)
                GLES20.glAttachShader(program, it)
            }
            val fragmentShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
                GLES20.glShaderSource(it, fragmentShaderSource)
                GLES20.glCompileShader(it)
                GLES20.glAttachShader(program, it)
            }
            GLES20.glLinkProgram(program)
            GLES20.GL_TRUE
            val result = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, result, 0)
            assert(result[0] == GLES20.GL_TRUE)
        }

        fun draw() {
            GLES20.glUseProgram(program)
            GLES20.glGetAttribLocation(program, "vPosition").let { position ->
                GLES20.glEnableVertexAttribArray(position)

                GLES20.glVertexAttribPointer(
                    position,
                    COORDS_PER_VERTEX,
                    GLES20.GL_FLOAT,
                    false,
                    COORDS_PER_VERTEX * 4,
                    byteBuffer
                )

                GLES20.glGetUniformLocation(program, "vColor").let {
                    GLES20.glUniform4fv(it, 1, color, 0)
                }

                GLES20.glDrawArrays(
                    GLES20.GL_TRIANGLES,
                    0,
                    coordinates.size / COORDS_PER_VERTEX
                )

                GLES20.glDisableVertexAttribArray(position)
            }
        }
        // program
        // pass in the vertex array to program
        // draw
        // cleanup
    }
}
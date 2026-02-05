package com.jimjo.exodefender

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import androidx.core.content.res.ResourcesCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer


class ScreenLabel(val maxLength: Int)  {
    lateinit var textAtlas: TextAtlas
    val screenChars= mutableListOf<ScreenChar>()

    fun initialize(textAtlas: TextAtlas) {
        this.textAtlas = textAtlas
        for (i in 0..< maxLength) {
            val screenChar = ScreenChar()
            screenChar.load(textAtlas)
            screenChars.add(screenChar)
        }
    }

    fun set(str: String) {
        for ((i, screenChar) in screenChars.withIndex()) {
            if (i < str.length) {
                val char = str[i]
                screenChar.set(char)
            }
            else {
                screenChar.set(null)
            }
        }
    }

    fun position(x: Float, y: Float, scale: Float, parentWidth: Int, parentHeight: Int) {
        for ((i, screenChar) in screenChars.withIndex()) {
            screenChar.position(x * 2f / parentWidth - 1f + i * textAtlas.charWidthRatio * scale, 1f - 2f * y / parentHeight, scale)
        }
    }

    fun draw() {
        for (screenChar in screenChars) {
            screenChar.draw()
        }
    }

}

class ScreenChar(): RectF() {

    lateinit var atlas: TextAtlas
    private lateinit var coords: FloatArray
    private val vertexStride: Int = COORDS_PER_VERTEX_2D * 4

    private lateinit var fillDrawOrder: ShortArray
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var fillDrawListBuffer: ShortBuffer
    private lateinit var textureCoordinatesBuffer: FloatBuffer

    val position = PointF()
    var scale = 1f

    private var mProgram: Int = -1
    private var mVertexShader: Int = -1
    private var mFragmentShader: Int = -1
    private var positionHandle: Int = -1
    private var scaleMatrixHandle: Int = -1
    private var texPositionHandle = -1
    private var textureUniformHandle: Int = -1
    private var translateVectorHandle: Int = -1
    private var translateTexPairHandle: Int = -1

    private val texCoords = PointF()

    private val textureUnit = IntArray(1)

    private val identityMatrix = FloatArray(16)
    private val scaleMatrix = FloatArray(16)

    private var char: Char? = null

    fun load(atlas: TextAtlas) {

        this.atlas = atlas
//        coords = FloatArray(4 * COORDS_PER_VERTEX)

//
//        var ordCounter = 0
//        coords[ordCounter++] = left
//        coords[ordCounter++] = top
//        coords[ordCounter++] = z
//
//        coords[ordCounter++] = left
//        coords[ordCounter++] = bottom
//        coords[ordCounter++] = z
//
//        coords[ordCounter++] = right
//        coords[ordCounter++] = bottom
//        coords[ordCounter++] = z
//
//        coords[ordCounter++] = right
//        coords[ordCounter++] = top
//        coords[ordCounter] = z

        coords = floatArrayOf(
            //x,    y
            0f, this.atlas.charHeightRatio,
            0f, 0f,
            this.atlas.charWidthRatio, 0f,
            this.atlas.charWidthRatio, this.atlas.charHeightRatio,
        )

        fillDrawOrder = shortArrayOf(0, 1, 2, 0, 2, 3)


        val TEXTURE_COORDINATES = floatArrayOf(
            //x,    y
            0.0f, 1.0f,
            0.0f, 1f - this.atlas.charHeightRatio,
            this.atlas.charWidthRatio, 1f - this.atlas.charHeightRatio,
            this.atlas.charWidthRatio, 1.0f,
        )

        vertexBuffer = ByteBuffer.allocateDirect(coords.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(coords)
                position(0)
            }
        }

        textureCoordinatesBuffer =
            ByteBuffer.allocateDirect(TEXTURE_COORDINATES.size * 4).run {
                order(ByteOrder.nativeOrder())
                asFloatBuffer().apply {
                    put(TEXTURE_COORDINATES)
                    position(0)
                }
            }

        fillDrawListBuffer = ByteBuffer.allocateDirect(fillDrawOrder.size * 2).run {
            order(ByteOrder.nativeOrder())
            asShortBuffer().apply {
                put(fillDrawOrder)
                position(0)
            }
        }

        val vertexShaderCode =
            "attribute vec4 a_Position;" +
            "uniform vec4 a_Translate;" +
            "uniform mat4 a_ScaleMatrix;" +
            "uniform vec2 a_TexTranslate;" +
            "attribute vec2 a_TexCoord;" +
            "varying vec2 v_TexCoord;" +
            "void main() {" +
            "  gl_Position = a_ScaleMatrix * a_Position + a_Translate;" +
            "  v_TexCoord = vec2(a_TexTranslate.x + a_TexCoord.x, (1.0 - (a_TexTranslate.y + a_TexCoord.y)));" +
            "}"

        val fragmentShaderCode =
            "precision highp float;" +
            "uniform sampler2D u_Texture;" +
            "varying vec2 v_TexCoord;" +
            "void main(void) {" +
            "   gl_FragColor = texture2D(u_Texture, v_TexCoord);" +
            "}"

        mVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        mFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        mProgram = GLES20.glCreateProgram()                 // create OpenGL ES Program
        GLES20.glAttachShader(mProgram, mVertexShader)      // add the vertex shader to program
        GLES20.glAttachShader(mProgram, mFragmentShader)    // add the fragment shader to program
        GLES20.glLinkProgram(mProgram)                      // creates OpenGL ES program executables

        positionHandle = GLES20.glGetAttribLocation(mProgram, "a_Position")
        texPositionHandle = GLES20.glGetAttribLocation(mProgram, "a_TexCoord")
        scaleMatrixHandle = GLES20.glGetUniformLocation(mProgram, "a_ScaleMatrix")
        textureUniformHandle = GLES20.glGetUniformLocation(mProgram, "u_Texture")
        translateVectorHandle = GLES20.glGetUniformLocation(mProgram, "a_Translate")
        translateTexPairHandle = GLES20.glGetUniformLocation(mProgram, "a_TexTranslate")


        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glGenTextures(textureUnit.size, textureUnit, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureUnit[0])

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, atlas.atlasBitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
//        atlas.atlasBitmap.recycle()

        Matrix.setIdentityM(identityMatrix, 0)

    }

    fun set(char: Char?) {
        if (char != null) {
            atlas.getCoords(char, texCoords)
        }
        this.char = char
    }

    fun position(x: Float, y:Float, scale: Float) {
        position.set(x, y)
        this.scale = scale
    }

    fun draw() {

        if (char != null) {

            GLES20.glUseProgram(mProgram)

            GLES20.glUniform1i(textureUniformHandle, 0)

            GLES20.glUniform2f(translateTexPairHandle, texCoords.x, texCoords.y)

            GLES20.glUniform4f(translateVectorHandle, position.x, position.y, 0f, 0f)
            Matrix.scaleM(scaleMatrix, 0, identityMatrix, 0, scale, scale, 1f)
            GLES20.glUniformMatrix4fv(scaleMatrixHandle, 1, false, scaleMatrix, 0)

            // get handle to vertex shader's vPosition member
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glEnableVertexAttribArray(texPositionHandle)

            // Prepare the triangle coordinate data
            GLES20.glVertexAttribPointer(
                positionHandle,
                COORDS_PER_VERTEX_2D,
                GLES20.GL_FLOAT,
                false,
                vertexStride,
                vertexBuffer
            )

            GLES20.glVertexAttribPointer(
                texPositionHandle,
                COORDS_PER_VERTEX_2D,
                GLES20.GL_FLOAT,
                false,
                vertexStride,
                textureCoordinatesBuffer
            )

            GLES20.glDrawElements(GLES20.GL_TRIANGLES, fillDrawOrder.size, GLES20.GL_UNSIGNED_SHORT, fillDrawListBuffer)

            // Disable vertex array
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texPositionHandle)
        }
    }
}

class TextAtlas(context: Context) {
    val atlasBitmap: Bitmap
    val atlasLookup = hashMapOf<Char, PointF>()

    val bitmapWidth = 256
    val bitmapHeight = 256
    val charWidth = 16f
    val charHeight = 30f

    var charWidthRatio = charWidth / bitmapWidth
    var charHeightRatio = charHeight / bitmapHeight

    val charsPerRow = 16
    val charOffsetY = 7f


    init {
        atlasBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)

        // get a canvas to paint over the bitmap
        val canvas = Canvas(atlasBitmap)
//        bitmap.eraseColor(0)


        // get a background image from resources
        // note the image format must match the bitmap format
        val background: Drawable = context.resources.getDrawable(R.drawable.background)
        background.setBounds(0, 0, bitmapWidth, bitmapHeight)
        background.draw(canvas) // draw the background to our bitmap


        // Draw the text
        val textPaint = Paint()

        textPaint.setTextSize(32f)
        textPaint.typeface = ResourcesCompat.getFont(context, R.font.consola)
        textPaint.setAntiAlias(true)
        textPaint.color = Color.RED
//        textPaint.setARGB(200, 80, 210, 0)


        val debugPaint = Paint()
        debugPaint.strokeWidth = 2f
        debugPaint.setStyle(Paint.Style.STROKE)
        debugPaint.color = Color.RED


        // draw the text centered
//        canvas.drawText("Hello World", 0f, 24f, textPaint)

        val charList = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_:;()./*+='?<>|{}% "
        val textBounds = Rect()
        textPaint.getTextBounds(
            charList,
            0,
            charList.length,
            textBounds
        )


        for ((i, char) in charList.withIndex()) {
            val row = i / charsPerRow
            val x = (i - row * charsPerRow) * charWidth
            val y = row * charHeight
            canvas.drawText(char.toString(), x, y + charHeight - charOffsetY, textPaint)
//            if (char == 'j' || char == 'A' || char == 'y' || char == 'P') {
//                canvas.drawRect(x, y, x + charWidth, y + charHeight, debugPaint)
//            }
            atlasLookup[char] = PointF(x, y)
        }
    }

    fun getCoords(char: Char, coords: PointF): Boolean {

        val rawPointF = atlasLookup[char]
        if (rawPointF != null) {
            coords.set(
                rawPointF.x / bitmapWidth,
                -rawPointF.y / bitmapHeight,
            )
            return true
        }
        else {
            return false
        }
    }
}
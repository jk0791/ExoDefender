package com.jimjo.exodefender

import android.content.Context
import android.graphics.PointF
import android.opengl.GLES20
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

val df1 = DecimalFormat("0.0")
val df2 = DecimalFormat("0.00")

val dateTimeFormat = SimpleDateFormat("yyyyMMdd'T'HH:mm:ss")

const val COORDS_PER_VERTEX_2D = 2
const val COORDS_PER_VERTEX = 3
const val TAU = Math.PI * 2

fun loadShader(type: Int, shaderCode: String): Int {

    // create a vertex shader type (GLES20.GL_VERTEX_SHADER)
    // or a fragment shader type (GLES20.GL_FRAGMENT_SHADER)
    return GLES20.glCreateShader(type).also { shader ->

        // add the source code to the shader and compile it
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            throw RuntimeException(
                ("Could not compile program: "
                        + GLES20.glGetShaderInfoLog(shader) + " | " + shaderCode)
            )
        }
    }
}

fun Float.isFiniteVal() = !this.isNaN() && this != Float.POSITIVE_INFINITY && this != Float.NEGATIVE_INFINITY

@Serializable
class Vec3(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f
) {
    // ---- creators / setters ----
    fun set(x: Float, y: Float, z: Float): Vec3 { this.x = x; this.y = y; this.z = z; return this }
    fun set(src: Vec3): Vec3 = set(src.x, src.y, src.z)
    fun copy(): Vec3 = Vec3(x, y, z)
    fun setZero(): Vec3 { x = 0f; y = 0f; z = 0f; return this }
    fun isFiniteVal() = x.isFiniteVal() && y.isFiniteVal() && z.isFiniteVal()

    // ---- arithmetic (alloc-free options first) ----
    fun addLocal(o: Vec3): Vec3 { x += o.x; y += o.y; z += o.z; return this }
    fun subLocal(o: Vec3): Vec3 { x -= o.x; y -= o.y; z -= o.z; return this }
    fun mulLocal(s: Float): Vec3 { x *= s; y *= s; z *= s; return this }

    // fused helpers (very handy in collision math)
    fun setAdd(a: Vec3, b: Vec3): Vec3 { x = a.x + b.x; y = a.y + b.y; z = a.z + b.z; return this }
    fun setSub(a: Vec3, b: Vec3): Vec3 { x = a.x - b.x; y = a.y - b.y; z = a.z - b.z; return this }
    fun mad(a: Vec3, s: Float): Vec3 { x += a.x * s; y += a.y * s; z += a.z * s; return this } // this += a*s

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)          // scalar
    operator fun times(o: Vec3) = Vec3(                                // cross
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )

    // Enable a += b
    operator fun plusAssign(o: Vec3) { x += o.x; y += o.y; z += o.z }

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    fun length2(): Float = x*x + y*y + z*z
    fun length(): Float = sqrt(length2())
    fun distance(o: Vec3): Float = sqrt(distance2(o))
    fun distance2(o: Vec3): Float = (x - o.x)*(x - o.x) + (y - o.y)*(y - o.y) + (z - o.z)*(z - o.z)

    fun normalize(result: Vec3) {
        val l2 = length2()
        if (l2 > 0f) {
            val inv = 1f / sqrt(l2)
            result.set(x * inv, y * inv, z * inv)
        } else result.set(this)
    }
    fun normalizeInPlace(): Vec3 {
        val l2 = length2()
        if (l2 > 0f) {
            val inv = 1f / sqrt(l2)
            x *= inv; y *= inv; z *= inv
        }
        return this
    }

    fun addScaled(dir: Vec3, scale: Float): Vec3 {
        x += dir.x * scale
        y += dir.y * scale
        z += dir.z * scale
        return this
    }

    // Lerp (both immutable + in-place forms)
    fun interpolate(end: Vec3, t: Float): Vec3 = Vec3(lerp(x, end.x, t), lerp(y, end.y, t), lerp(z, end.z, t))
    fun interpolate(start: Vec3, end: Vec3, t: Float) {
        x = lerp(start.x, end.x, t); y = lerp(start.y, end.y, t); z = lerp(start.z, end.z, t)
    }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    fun average(o: Vec3) = Vec3((x + o.x) * 0.5f, (y + o.y) * 0.5f, (z + o.z) * 0.5f)
    fun reverse() { x = -x; y = -y; z = -z } // keep if you like the name

    fun clamp(minV: Vec3, maxV: Vec3): Vec3 {
        x = max(minV.x, min(x, maxV.x))
        y = max(minV.y, min(y, maxV.y))
        z = max(minV.z, min(z, maxV.z))
        return this
    }

    override fun toString(): String = "($x,$y,$z)"
}

fun getPlanAngle(deltaX: Double, deltaY: Double): Double {
    var angle = Math.atan2(deltaX, deltaY)
    if (angle < 0.0) {
        angle += TAU
    }
    return angle
}

fun getPlanAngle(pointA: Vec3, pointB: Vec3): Double {
    return getPlanAngle((pointA.x - pointB.x).toDouble(), (pointA.y - pointB.y).toDouble())
}

fun getElevationAngle(angleP: Double, deltaY: Double, deltaZ: Double): Double {
    val xy = deltaY / Math.cos(angleP)
    return Math.atan(deltaZ / (xy))
}


fun interpolate(start: Float, end: Float, fraction: Float): Float {
    return (end - start) * fraction + start
}

fun interpolate(start: Double, end: Double, fraction: Float): Double {
    return (end - start) * fraction + start
}


fun randomPointInEllipse(cx: Double, cy: Double, rx: Double, ry: Double): PointF {
    // Random angle between 0 and 2π
    val theta = Random.nextDouble(0.0, 2 * Math.PI)
    // Radius scaled so distribution is uniform across area
    val r = sqrt(Random.nextDouble(0.0, 1.0))

    // Polar coords to Cartesian, then scale by ellipse radii
    val x = cx + r * rx * cos(theta)
    val y = cy + r * ry * sin(theta)

    return PointF(x.toFloat(), y.toFloat())
}

fun randomPointInEllipse(cx: Float, cy: Float, rx: Float, ry: Float): PointF {
    // Random angle between 0 and 2π
    val theta = Random.nextFloat() * 2 * Math.PI.toFloat()
    // Radius scaled so distribution is uniform across area
    val r = sqrt(Random.nextFloat())

    // Polar coords to Cartesian, then scale by ellipse radii
    val x = cx + r * rx * cos(theta)
    val y = cy + r * ry * sin(theta)

    return PointF(x, y)
}

// returns a cos shaped y between 0 (y=1) and 1 (y=0), 0 for x > 1
fun getCosShape(x: Float): Float {
    if (x < 1) {
        return (cos(x * Math.PI.toFloat()) + 1) / 2
    }
    else {
        return 0f
    }
}

fun wrapAngleRad(a: Double): Double {
    var x = a
    val pi = Math.PI
    val twoPi = (Math.PI * 2.0)
    while (x >  pi) x -= twoPi
    while (x < -pi) x += twoPi
    return x
}

// --- Yaw-only rotate localPos by structure yaw (Z-up world) ---
fun rotateYaw(v: Vec3, yaw: Double, out: Vec3): Vec3 {
    val c = kotlin.math.cos(yaw).toFloat()
    val s = kotlin.math.sin(yaw).toFloat()
    out.set(
        c * v.x - s * v.y,
        s * v.x + c * v.y,
        v.z
    )
    return out
}

fun rotateXY(x: Float, y: Float, yawRad: Double, out: Vec3) {
    val c = kotlin.math.cos(yawRad).toFloat()
    val s = kotlin.math.sin(yawRad).toFloat()
    out.x = c * x - s * y
    out.y = s * x + c * y
    out.z = 0f
}

fun getIndexBefore(coordinate: Float): Int {
    return floor(coordinate / MAP_GRID_SPACING).toInt()
}

fun getIndexAfter(coordinate: Float): Int {
    return getIndexBefore(coordinate) + 1
}

fun barycentricHeight(p1: Vec3, p2: Vec3, p3: Vec3, pos: PointF): Float {
    val det = (p2.y - p3.y) * (p1.x - p3.x) + (p3.x - p2.x) * (p1.y - p3.y)
    val l1 = ((p2.y - p3.y) * (pos.x - p3.x) + (p3.x - p2.x) * (pos.y - p3.y)) / det
    val l2 = ((p3.y - p1.y) * (pos.x - p3.x) + (p1.x - p3.x) * (pos.y - p3.y)) / det
    val l3 = 1f - l1 - l2
    return l1 * p1.z + l2 * p2.z + l3 * p3.z
}

//fun lerp(start: Double, stop: Double, fraction: Float): Double {
//    return (1 - fraction) * start + fraction * stop
//}

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
fun lerp(a: Double, b: Double, t: Float) = a + (b - a) * t

fun lerpExp(current: Float, target: Float, rate: Float, dt: Float): Float {
    val a = 1f - kotlin.math.exp(-rate * dt)
    return current + (target - current) * a
}
fun remapPiecewise01(x: Float, a1: Float, b1: Float): Float {
    return when {
        x <= 0f -> 0f
        x >= 1f -> 1f
        x <= a1 -> (x / a1) * b1
        else    -> b1 + ((x - a1) / (1f - a1)) * (1f - b1)
    }
}

class Rolling(private val size: Int) {
    private var total = 0f
    private var index = 0
    private val samples = FloatArray(size)

    init {
//        for (i in 0 until size) samples[i] = 0f
        reset()
    }

    fun reset() {
        total = 0f
        index = 0
        for (i in 0 until size) samples[i] = 0f
    }

    fun add(x: Float) {
        total -= samples[index]
        samples[index] = x
        total += x
        if (++index == size) index = 0 // cheaper than modulus
    }

    val average: Float
        get() = total / size
}

class RollingPoint3dF(size: Int) {
    val xRolling = Rolling(size)
    val yRolling = Rolling(size)
    val zRolling = Rolling(size)

    fun reset() {
        xRolling.reset()
        yRolling.reset()
        zRolling.reset()
    }

    fun add(point: Vec3) {
        xRolling.add(point.x)
        yRolling.add(point.y)
        yRolling.add(point.z)
    }

    fun setAverage(averagePoint: Vec3) {
        averagePoint.set(
            xRolling.average,
            yRolling.average,
            zRolling.average
        )
    }

}

fun copyRawFilesToInternalStorage(
    context: Context,
    fileNames: List<String>,
    destDir: File
) {
    val copiedFiles = mutableListOf<File>()

    for (name in fileNames) {
        val resId = context.resources.getIdentifier(name, "raw", context.packageName)
        if (resId == 0) {
            android.util.Log.w("FileCopy", "Resource not found: $name")
            continue
        }

        val destFile = File(destDir, "$name.dat")  // change extension if needed
        try {
            context.resources.openRawResource(resId).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            copiedFiles.add(destFile)
            android.util.Log.i("FileCopy", "Copied: ${destFile.absolutePath}")
        } catch (e: IOException) {
            android.util.Log.e("FileCopy", "Failed to copy $name: ${e.message}", e)
        }
    }
}

fun copyFileInInternalStorage(context: Context, sourceFileName: String, destinationFileName: String, internalRootDir: File): Boolean {

    // Define the source and destination File objects
    val sourceFile = File(internalRootDir, sourceFileName)
    val destinationFile = File(internalRootDir, destinationFileName)

    try {
        // Use Kotlin's standard library function copyTo()
        sourceFile.copyTo(
            target = destinationFile,
            overwrite = true // Set to true to replace the destination if it already exists
        )
        return true
    } catch (e: NoSuchFileException) {
        // Handle case where source file does not exist
        e.printStackTrace()
    } catch (e: FileAlreadyExistsException) {
        // Handle case where destination file exists and overwrite is false
        e.printStackTrace()
    } catch (e: IOException) {
        // Handle other I/O errors
        e.printStackTrace()
    }
    return false
}

fun deleteFileFromFolder(filesDir: File, fileName: String) {

    val file = File(filesDir, fileName)
    if (file.exists()) {
        val deleted = file.delete()
        if (deleted) {
            println("File deleted successfully: $fileName")
        } else {
            println("Failed to delete file: $fileName")
        }
    } else {
        println("File does not exist: $fileName")
    }
}

// returns -1 or +1
fun rndSign(): Int {
    return Random.nextInt(0, 2) * 2 - 1
}

fun normalizeAngleRad(angle: Double): Double {
    var a = angle % TAU
    if (a <= -Math.PI) a += TAU
    else if (a > Math.PI) a -= TAU
    return a
}

fun smooth01(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}


fun rotateXY(v: Vec3, yawRad: Double, out: Vec3): Vec3 {
    val c = cos(yawRad).toFloat()
    val s = sin(yawRad).toFloat()
    val x = v.x
    val y = v.y
    out.x = c * x - s * y
    out.y = s * x + c * y
    out.z = v.z
    return out
}
package com.example.imageedit.core.assets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader

/**
 * Offline asset enum. M2 uses generated gradient presets; replace with bundled
 * photos later without changing the public IDs.
 */
object AssetLibrary {
    val backgroundIds: List<String> = listOf(
        "preset:studio_gray",
        "preset:beach_03",
        "preset:gradient_01",
        "preset:sunset_02",
    )

    val textureIds: List<String> = listOf(
        "preset:plaid_01",
        "preset:floral_02",
    )

    fun isValidBackground(id: String): Boolean = id in backgroundIds || id == "user_local"

    fun isValidTexture(id: String): Boolean = id in textureIds

    fun background(id: String, width: Int, height: Int): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (id) {
            "preset:studio_gray" -> {
                paint.shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(Color.rgb(238, 238, 240), Color.rgb(184, 186, 190)),
                    null,
                    Shader.TileMode.CLAMP,
                )
            }

            "preset:beach_03" -> {
                paint.shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(
                        Color.rgb(128, 196, 235),
                        Color.rgb(236, 226, 196),
                        Color.rgb(214, 190, 148),
                    ),
                    floatArrayOf(0f, 0.62f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }

            "preset:sunset_02" -> {
                paint.shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(
                        Color.rgb(52, 58, 112),
                        Color.rgb(232, 126, 96),
                        Color.rgb(250, 214, 150),
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }

            else -> {
                paint.shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(Color.rgb(46, 91, 255), Color.rgb(120, 220, 220)),
                    null,
                    Shader.TileMode.CLAMP,
                )
            }
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return output
    }

    fun texture(id: String, width: Int, height: Int): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(190, 40, 60))
        when (id) {
            "preset:floral_02" -> {
                for (row in 0 until height step 80) {
                    for (col in 0 until width step 80) {
                        val cx = col + (row / 80 % 2) * 40 + 10
                        val cy = row + 10
                        paint.color = Color.rgb(255, 220, 120)
                        for (angle in 0 until 360 step 60) {
                            val rad = Math.toRadians(angle.toDouble())
                            val px = cx + 14 * Math.cos(rad)
                            val py = cy + 14 * Math.sin(rad)
                            canvas.drawCircle(px.toFloat(), py.toFloat(), 8f, paint)
                        }
                        paint.color = Color.rgb(255, 245, 180)
                        canvas.drawCircle(cx.toFloat(), cy.toFloat(), 5f, paint)
                    }
                }
            }

            else -> {
                paint.color = Color.rgb(80, 40, 140)
                for (x in -height until width step 64) {
                    canvas.drawLine(
                        x.toFloat(),
                        0f,
                        (x + height).toFloat(),
                        height.toFloat(),
                        paint.apply { strokeWidth = 14f },
                    )
                }
                paint.color = Color.rgb(240, 200, 80)
                for (x in 32 - height until width step 64) {
                    canvas.drawLine(
                        x.toFloat(),
                        0f,
                        (x + height).toFloat(),
                        height.toFloat(),
                        paint.apply { strokeWidth = 8f },
                    )
                }
                paint.color = Color.rgb(80, 40, 140)
                for (x in 0 until width step 64) {
                    canvas.drawLine(
                        x.toFloat(),
                        0f,
                        x.toFloat(),
                        height.toFloat(),
                        paint.apply { strokeWidth = 5f },
                    )
                }
                for (y in 0 until height step 64) {
                    canvas.drawLine(
                        0f,
                        y.toFloat(),
                        width.toFloat(),
                        y.toFloat(),
                        paint.apply { strokeWidth = 5f },
                    )
                }
            }
        }
        return output
    }
}

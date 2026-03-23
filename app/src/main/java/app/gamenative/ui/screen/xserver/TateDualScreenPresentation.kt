package app.gamenative.ui.screen.xserver

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import app.gamenative.PluviaApp
import com.winlator.xserver.XServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TateDualScreenPresentation(
    context: Context,
    display: Display,
    @Suppress("UNUSED_PARAMETER") xServer: XServer,
    private val primaryScreenHeightPx: Int,
    private val combinedHeightPx: Int,
    private val screenWidthPx: Int,
) : Presentation(context, display) {

    var tateView: TateOffsetSurfaceView? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        )
        window?.setBackgroundDrawableResource(android.R.color.black)

        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val view = TateOffsetSurfaceView(context)
        tateView = view
        root.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        setContentView(root)
    }

    override fun onStop() {
        super.onStop()
        tateView?.release()
        tateView = null
    }

    inner class TateOffsetSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

        private var renderThread: TateRenderThread? = null

        init {
            setBackgroundColor(Color.BLACK)
            holder.addCallback(this)
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            renderThread = TateRenderThread(
                holder = holder,
                yOffsetPx = primaryScreenHeightPx,
                combinedHeightPx = combinedHeightPx,
            ).also { it.start() }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            renderThread?.updateSurfaceSize(width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) { release() }

        fun release() {
            renderThread?.requestStop()
            renderThread?.join(500)
            renderThread = null
        }
    }

    private class TateRenderThread(
        private val holder: SurfaceHolder,
        private val yOffsetPx: Int,
        private val combinedHeightPx: Int,
    ) : Thread("TateDualScreenRenderThread") {

        @Volatile private var running = true
        @Volatile private var surfaceW = 0
        @Volatile private var surfaceH = 0
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val mainHandler = Handler(Looper.getMainLooper())

        fun updateSurfaceSize(w: Int, h: Int) { surfaceW = w; surfaceH = h }
        fun requestStop() { running = false }

        override fun run() {
            val frameBudget = 1000L / 60
            while (running) {
                val t0 = System.currentTimeMillis()
                renderFrame()
                val sleep = frameBudget - (System.currentTimeMillis() - t0)
                if (sleep > 0) sleep(sleep)
            }
        }

        private fun renderFrame() {
            if (surfaceW == 0 || surfaceH == 0) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

            val sourceView = PluviaApp.xServerView ?: return
            if (!sourceView.isAttachedToWindow) return
            if (!sourceView.isHardwareAccelerated) return
            if (sourceView.width == 0 || sourceView.height == 0) return

            val srcBitmap = Bitmap.createBitmap(sourceView.width, sourceView.height, Bitmap.Config.ARGB_8888)
            val latch = CountDownLatch(1)

            mainHandler.post {
                try {
                    PixelCopy.request(sourceView, srcBitmap, { result ->
                        if (result == PixelCopy.SUCCESS) {
                            drawBottomHalf(srcBitmap)
                        }
                        srcBitmap.recycle()
                        latch.countDown()
                    }, mainHandler)
                } catch (_: Exception) {
                    srcBitmap.recycle()
                    latch.countDown()
                }
            }

            latch.await(200, TimeUnit.MILLISECONDS)
        }

        private fun drawBottomHalf(srcBitmap: Bitmap) {
            val canvas: Canvas = try {
                holder.lockCanvas(null) ?: return
            } catch (_: Exception) { return }

            try {
                canvas.drawColor(Color.BLACK)
                val srcRect = Rect(0, yOffsetPx, srcBitmap.width, srcBitmap.height)
                val dstRect = Rect(0, 0, surfaceW, surfaceH)
                canvas.drawBitmap(srcBitmap, srcRect, dstRect, paint)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }
}

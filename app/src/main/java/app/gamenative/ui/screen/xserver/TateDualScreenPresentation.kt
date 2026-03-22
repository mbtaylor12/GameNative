package app.gamenative.ui.screen.xserver

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import com.winlator.xserver.XServer

/**
 * TATE Dual Screen Presentation
 * ==============================
 *
 * Displays the BOTTOM HALF of the XServer framebuffer on the secondary
 * physical display of a dual-screen Android device (e.g. the lower screen
 * of an AYN Thor), creating a seamless tall portrait ("TATE") canvas that
 * spans both physical screens.
 *
 * Layout (example: two 1080×1920 screens):
 *
 *   ┌─────────────────┐  ← Primary Activity (top screen)
 *   │  XServer rows   │    game y: 0 → 1919
 *   │  0 … 1919       │
 *   └─────────────────┘
 *   ┌─────────────────┐  ← This Presentation (bottom screen)
 *   │  XServer rows   │    game y: 1920 → 3839
 *   │  1920 … 3839    │
 *   └─────────────────┘
 *
 * The XServer virtual resolution must be set to e.g. 1080×3840 before this
 * presentation is shown — that is handled in XServerScreen.kt via the
 * overridden screenSize in container when tateDualScreenMode == true.
 *
 * Threading model:
 *   [TateRenderThread] runs at ~60 fps on a dedicated background thread.
 *   It locks the secondary Surface, applies canvas.translate(0, -primaryH)
 *   so game rows from [primaryH] onwards appear at y=0 on this screen,
 *   then draws the XServer root-window bitmap.
 *
 * Focus / input:
 *   FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCH_MODAL ensure this Presentation never
 *   steals keyboard or touch focus from the primary Activity.
 */
class TateDualScreenPresentation(
    context: Context,
    display: Display,
    /** The running XServer instance whose root-window bitmap we read. */
    val xServer: XServer,
    /** Height of the PRIMARY screen in px (= y-offset into combined framebuffer). */
    val primaryScreenHeightPx: Int,
    /** Combined height of both screens (= XServer virtual height). */
    val combinedHeightPx: Int,
    /** Pixel width shared by both screens. */
    val screenWidthPx: Int,
) : Presentation(context, display) {

    /** The inner SurfaceView used for secondary-screen rendering. */
    var tateView: TateOffsetSurfaceView? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Never steal focus or touch from the primary Activity
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        window?.setBackgroundDrawableResource(android.R.color.black)

        val view = TateOffsetSurfaceView(
            context             = context,
            xServer             = xServer,
            yOffsetPx           = primaryScreenHeightPx,
            combinedHeightPx    = combinedHeightPx,
            nativeScreenWidthPx = screenWidthPx,
        )
        tateView = view

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            )
        }
        setContentView(root)
    }

    override fun onStop() {
        super.onStop()
        tateView?.release()
        tateView = null
    }

    // =========================================================================
    // Inner view: secondary-screen surface
    // =========================================================================

    /**
     * SurfaceView that renders the bottom portion of the XServer framebuffer.
     *
     * The canvas translation trick:
     *   canvas.translate(0f, -yOffsetPx)
     *   canvas.drawBitmap(rootWindowBitmap, 0f, 0f, null)
     *
     * After the translate, game rows [yOffsetPx … combinedHeight-1] land at
     * physical y [0 … secondaryScreenHeight-1] on this screen.
     */
    inner class TateOffsetSurfaceView(
        context: Context,
        private val xServer: XServer,
        /** Number of game-space pixels to skip from the top. */
        val yOffsetPx: Int,
        val combinedHeightPx: Int,
        val nativeScreenWidthPx: Int,
    ) : SurfaceView(context), SurfaceHolder.Callback {

        private var renderThread: TateRenderThread? = null

        init {
            setBackgroundColor(Color.BLACK)
            holder.addCallback(this)
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            renderThread = TateRenderThread(
                holder              = holder,
                xServer             = xServer,
                yOffsetPx           = yOffsetPx,
                combinedHeightPx    = combinedHeightPx,
                nativeScreenWidthPx = nativeScreenWidthPx,
            ).also { it.start() }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            renderThread?.updateSurfaceSize(w, h)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            release()
        }

        fun release() {
            renderThread?.requestStop()
            renderThread = null
        }
    }

    // =========================================================================
    // Render thread
    // =========================================================================

    private class TateRenderThread(
        private val holder: SurfaceHolder,
        private val xServer: XServer,
        private val yOffsetPx: Int,
        private val combinedHeightPx: Int,
        private val nativeScreenWidthPx: Int,
    ) : Thread("TateDualScreenRender") {

        @Volatile private var running = true
        @Volatile private var surfaceW = 1
        @Volatile private var surfaceH = 1

        fun updateSurfaceSize(w: Int, h: Int) {
            surfaceW = w
            surfaceH = h
        }

        fun requestStop() {
            running = false
        }

        override fun run() {
            val targetFrameMs = 1000L / 60  // ~16.6 ms/frame

            while (running) {
                val frameStart = System.currentTimeMillis()

                renderFrame()

                val elapsed = System.currentTimeMillis() - frameStart
                val sleepMs = targetFrameMs - elapsed
                if (sleepMs > 0) sleep(sleepMs)
            }
        }

        private fun renderFrame() {
            val canvas: Canvas = try {
                holder.lockCanvas(null) ?: return
            } catch (_: Exception) {
                return
            }

            try {
                canvas.save()
                canvas.drawColor(Color.BLACK)

                // Scale game-pixel space → physical screen pixel space.
                // Each screen shows (combinedHeight / 2) game rows.
                val gameRowsPerScreen = combinedHeightPx / 2
                val scaleX = if (nativeScreenWidthPx > 0)
                    surfaceW.toFloat() / nativeScreenWidthPx else 1f
                val scaleY = if (gameRowsPerScreen > 0)
                    surfaceH.toFloat() / gameRowsPerScreen   else 1f
                canvas.scale(scaleX, scaleY)

                // Shift the view-port upward by the primary screen height so
                // that the bottom half of the game lands at physical y = 0.
                canvas.translate(0f, -yOffsetPx.toFloat())

                drawRootWindowBitmap(canvas)

                canvas.restore()
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        /**
         * Draws the XServer root-window framebuffer bitmap onto [canvas].
         *
         * GameNative / Winlator exposes the live framebuffer through:
         *   xServer.rootWindow       → XWindow  (the root X11 window)
         *   rootWindow.content       → Pixmap   (holds the backing Bitmap)
         *   pixmap.bitmap            → android.graphics.Bitmap
         *
         * Access is synchronized on the Pixmap to avoid tearing while the
         * primary-screen render thread is also reading the same bitmap.
         *
         * If the bitmap is not yet ready (early in startup), the method
         * returns silently; the next frame retries automatically.
         */
        private fun drawRootWindowBitmap(canvas: Canvas) {
            try {
                val rootWindow = xServer.rootWindow ?: return
                val pixmap     = rootWindow.content  ?: return
                synchronized(pixmap) {
                    val bmp = pixmap.bitmap ?: return
                    if (!bmp.isRecycled) {
                        canvas.drawBitmap(bmp, 0f, 0f, null)
                    }
                }
            } catch (_: Exception) {
                // Framebuffer not ready — skip frame silently
            }
        }
    }
}

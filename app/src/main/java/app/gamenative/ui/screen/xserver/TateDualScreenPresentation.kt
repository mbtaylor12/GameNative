package app.gamenative.ui.screen.xserver

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.widget.FrameLayout
import com.winlator.widget.XServerView
import com.winlator.xserver.XServer

class TateDualScreenPresentation(
    context: Context,
    display: Display,
    private val xServer: XServer,
    private val primaryScreenHeightPx: Int,
    private val combinedHeightPx: Int,
    private val screenWidthPx: Int,
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        window?.setBackgroundDrawableResource(android.R.color.black)

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
            clipToPadding = true
        }

        // Second XServerView sharing the same XServer — reads the same framebuffer
        val secondXServerView = XServerView(context, xServer).apply {
            renderer?.isCursorVisible = false
        }

        // Offset upward so only the bottom half of the framebuffer is visible
        val lp = FrameLayout.LayoutParams(screenWidthPx, combinedHeightPx).apply {
            topMargin = -primaryScreenHeightPx
        }
        root.addView(secondXServerView, lp)
        setContentView(root)
    }
}

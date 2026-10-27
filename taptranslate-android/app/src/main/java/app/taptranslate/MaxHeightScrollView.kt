package app.taptranslate

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** ScrollView that never grows past ~60% of the screen, so the popup stays a bottom sheet. */
class MaxHeightScrollView(context: Context, attrs: AttributeSet?) : ScrollView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var max = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            max = minOf(max, MeasureSpec.getSize(heightMeasureSpec))
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
    }
}

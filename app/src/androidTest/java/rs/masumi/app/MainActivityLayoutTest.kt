package rs.masumi.app

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityLayoutTest {
    @Test
    fun idleLayoutShowsImportControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var root: View

        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(
                instrumentation.targetContext,
                android.R.style.Theme_Material_Light_NoActionBar,
            )
            root = LayoutInflater.from(context).inflate(R.layout.activity_main, null, false)
        }

        assertEquals("导入漫画文件夹", root.findViewById<Button>(R.id.importButton).text.toString())
        assertEquals("等待导入", root.findViewById<TextView>(R.id.statusText).text.toString())
        assertEquals(View.GONE, root.findViewById<ProgressBar>(R.id.importProgress).visibility)
    }
}

package rs.masumi.app.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderBitmapFilteringTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun continuousReaderEnablesFilteredBitmapSampling() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(ContinuousReaderView(context).usesFilteredBitmapSampling())
        }
    }

}

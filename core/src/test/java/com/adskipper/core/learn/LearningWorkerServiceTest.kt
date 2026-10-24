package com.adskipper.core.learn

import com.google.mlkit.common.sdkinternal.MlKitContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], manifest = Config.NONE)
class LearningWorkerServiceTest {
    private val instance = MlKitContext::class.java.declaredFields.single {
        Modifier.isStatic(it.modifiers) && it.type == MlKitContext::class.java
    }.apply { isAccessible = true }
    private var previous: Any? = null

    @Before fun clearProcessContext() { previous = instance.get(null); instance.set(null, null) }
    @After fun restoreProcessContext() { instance.set(null, previous) }

    @Test fun theLearningProcessInitializesItsOwnTextRecognitionContext() {
        assertThrows(IllegalStateException::class.java) { MlKitContext.getInstance() }
        val service = Robolectric.buildService(LearningWorkerService::class.java).create().get()
        assertSame(service.applicationContext, MlKitContext.getInstance().applicationContext)
    }
}

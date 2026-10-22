package com.adskipper.core.vlm

import org.junit.Assert.*
import org.junit.Test

class BboxParserTest {
    @Test fun nativeJsonKeyDoesNotShiftTheCoordinates() {
        assertArrayEquals(floatArrayOf(.8f, .1f, .95f, .15f),
            BboxParser.parse("""{"bbox_2d": [800,100,950,150]}"""), .0001f)
    }

    @Test fun smallTopLeftCoordinatesStillUseTheThousandScale() {
        assertArrayEquals(floatArrayOf(.01f,.02f,.05f,.06f),
            BboxParser.parse("""{"bbox_2d": [10,20,50,60]}"""), .0001f)
    }

    @Test fun noAdAndMissingButtonResponsesNeverProduceATap() {
        assertNull(BboxParser.parse("""{"bbox_2d": null}"""))
        assertNull(BboxParser.parse("No advertisement or skip control is visible."))
    }

    @Test fun malformedOutOfRangeOrReversedBoxesAreRejected() {
        for (text in listOf(
            """{"bbox_2d": [-1,20,100,60]}""", """{"bbox_2d": [10,20,1001,60]}""",
            """{"bbox_2d": [100,20,10,60]}""", """{"bbox_2d": [10,60,100,20]}""",
            """{"bbox_2d": [10,20,100,60,5]}""", """{"bbox_2d": [NaN,20,100,60]}""",
            """{"bbox_2d": [0,0,0,0]}""", "10 20 100 60",
        )) assertNull(text, BboxParser.parse(text))
    }
}

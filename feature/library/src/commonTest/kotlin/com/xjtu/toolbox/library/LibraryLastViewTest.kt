package com.xjtu.toolbox.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryLastViewTest {
    private val xq = LibraryCampus.XINGQING

    @Test fun restoresValidValue() {
        val saved = LibraryLastView.encode(xq, "xingqing3floor")
        assertEquals("xingqing3floor", LibraryLastView.restore(saved, xq, xq.floorCodes))
    }

    @Test fun decodesOnlyOwnCampus() {
        assertEquals("A101", LibraryLastView.decode(LibraryLastView.encode(xq, "A101"), xq))
        assertNull(LibraryLastView.decode(LibraryLastView.encode(LibraryCampus.YANTA, "A101"), xq))
        assertNull(LibraryLastView.decode("A101", xq))
        assertNull(LibraryLastView.decode(null, xq))
        assertNull(LibraryLastView.decode("east|", xq))
        assertEquals("a|b", LibraryLastView.decode("east|a|b", xq))
    }

    @Test fun otherCampusFallsBack() {
        val saved = LibraryLastView.encode(LibraryCampus.YANTA, "yanta1floor")
        assertNull(LibraryLastView.restore(saved, xq, xq.floorCodes))
    }

    @Test fun missingOrUnloadedFallsBack() {
        assertNull(LibraryLastView.restore(LibraryLastView.encode(xq, "gone"), xq, listOf("a", "b")))
        assertNull(LibraryLastView.restore(LibraryLastView.encode(xq, "a"), xq, null))
        assertNull(LibraryLastView.restore(LibraryLastView.encode(xq, "a"), xq, emptyList()))
        assertNull(LibraryLastView.restore(null, xq, listOf("a")))
        assertNull(LibraryLastView.restore("garbage", xq, listOf("garbage")))
        assertNull(LibraryLastView.restore("east|", xq, listOf("")))
    }

    @Test fun campusFollowsSchoolUnlessFree() {
        val yt = LibraryCampus.YANTA
        assertNull(LibraryLastView.campusToRestore(free = false, last = yt, current = xq))
        assertEquals(yt, LibraryLastView.campusToRestore(free = true, last = yt, current = xq))
        assertNull(LibraryLastView.campusToRestore(free = true, last = xq, current = xq))
        assertNull(LibraryLastView.campusToRestore(free = true, last = null, current = xq))
    }
}

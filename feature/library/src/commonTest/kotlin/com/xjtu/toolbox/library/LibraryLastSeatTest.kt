package com.xjtu.toolbox.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryLastSeatTest {
    private val last = LastSeat(LibraryCampus.XINGQING.id, "north4west", "L097")
    private val free = SeatOccupancy.FREE
    private val used = SeatOccupancy.IN_USE

    @Test fun nearbyPicksClosestFreeSeatsWithSamePrefix() {
        val states = mapOf(
            "L097" to used, "L096" to used, "L098" to free, "L095" to free,
            "L100" to free, "L101" to free, "K097" to free, "L099" to SeatOccupancy.BOOKED,
        )
        assertEquals(listOf("L098", "L095", "L100"), nearbyFreeSeats("L097", states))
        assertEquals(emptyList(), nearbyFreeSeats("靠窗", states))
    }

    @Test fun suggestsLastSeatWhenFree() {
        val hint = suggestSeat(last, LibraryCampus.XINGQING, mapOf("L097" to free))
        assertEquals(SeatSuggestion.Free(last), hint)
        assertEquals(SeatHintText("上次预约 L097，目前可用", links = listOf("点我跳转到 L097" to "L097")), hint.hintText())
    }

    @Test fun takenSeatRecommendsNeighboursOrOtherArea() {
        val hint = suggestSeat(last, LibraryCampus.XINGQING, mapOf("L097" to used, "L098" to free))
        assertEquals(SeatSuggestion.Taken(last, used, listOf("L098")), hint)
        assertEquals("上次预约 L097，目前使用中", hint.hintText().summary)
        val none = suggestSeat(last, LibraryCampus.XINGQING, mapOf("L097" to SeatOccupancy.BOOKED, "L098" to used))
        assertEquals("可以试试换个区域坐？", none.hintText().tail)
    }

    @Test fun leaveCountsAsInUse() {
        assertEquals(SeatOccupancy.IN_USE, SeatOccupancy.of(PlanSeat("L097", status = PlanSeat.LEAVE)))
        assertEquals(SeatOccupancy.BOOKED, SeatOccupancy.of(PlanSeat("L097", status = PlanSeat.BOOKED)))
    }

    @Test fun otherCampusOrNoStatusOnlyNamesTheSeat() {
        val other = suggestSeat(last, LibraryCampus.YANTA, mapOf("L097" to free))
        assertEquals(SeatHintText("上次预约 L097（在兴庆校区）"), other.hintText())
        assertEquals(SeatSuggestion.Unknown(last), suggestSeat(last, LibraryCampus.XINGQING, null))
    }

    @Test fun linkRoundTripsAsPickTarget() {
        val link = LibraryLastSeat.link(last, "L098")
        assertEquals(LibraryFocus.Target(last.campusId, last.areaCode, "L098", pick = true), LibraryLastSeat.parseLink(link))
        assertNull(LibraryLastSeat.parseLink("app:library"))
        assertNull(LibraryLastSeat.decode("east|north4west"))
    }
}

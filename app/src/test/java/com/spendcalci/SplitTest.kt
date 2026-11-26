package com.spendcalci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTest {
    private fun txn(id: Long, amount: Double, members: List<SplitMember>?) = Txn(
        id = id, hash = "h$id", amount = amount, merchant = "M", source = "UPI", account = "1",
        timestamp = id, category = "Food", split = members != null,
        splitMembers = members?.let { encodeMembers(it) },
    )

    @Test fun membersRoundTripThroughStorageFormat() {
        val list = listOf(SplitMember("Ravi Kumar", 250.0), SplitMember("Ana", 100.5, settled = true))
        assertEquals(list, decodeMembers(encodeMembers(list)))
    }

    @Test fun myShareIsAmountMinusWhatOthersOwe() {
        val t = txn(1, 1000.0, listOf(SplitMember("A", 250.0), SplitMember("B", 250.0), SplitMember("C", 250.0)))
        assertEquals(250.0, t.myShare(), 0.001)
        assertEquals(1000.0, txn(2, 1000.0, null).myShare(), 0.001)
    }

    @Test fun debtsIgnoreSettledMembersAndGroupByPerson() {
        val a = txn(1, 900.0, listOf(SplitMember("Ravi", 300.0), SplitMember("Ana", 300.0, settled = true)))
        val b = txn(2, 600.0, listOf(SplitMember("Ravi", 200.0)))
        val debts = debtsOf(listOf(a, b))
        val ravi = debts.single { it.name == "Ravi" }
        assertEquals(500.0, ravi.owed, 0.001)
        assertEquals(2, ravi.items.size)
        assertTrue(debts.none { it.name == "Ana" })
    }

    @Test fun bucketUsesCardSuffixOnlyForCardPayments() {
        assertEquals("CARD:0602", Txn(hash = "x", amount = 1.0, merchant = "m", source = "CARD", account = "0602", timestamp = 0).bucket())
        assertEquals("CARD", Txn(hash = "y", amount = 1.0, merchant = "m", source = "CARD", account = "manual", timestamp = 0).bucket())
    }

    @Test fun unequalAmountsLeaveRemainderUntilAssigned() {
        val d = SplitDraft(300.0)
        d.addPerson("Asha")
        d.chooseMode(SplitMode.AMOUNT)
        d.people.single { !it.isMe }.value = 100.0
        assertEquals(50.0, d.remaining, 0.001)
        assertFalse(d.isValid)
        d.assignRestToMe()
        assertEquals(200.0, d.people.first { it.isMe }.value, 0.001)
        assertTrue(d.isValid)
        assertEquals(listOf(SplitMember("Asha", 100.0)), d.toMembers())
    }

    @Test fun switchingToPercentKeepsCurrentShares() {
        val d = SplitDraft(300.0)
        d.addPerson("A")
        d.addPerson("B")
        d.chooseMode(SplitMode.PERCENT)
        assertEquals(33.33, d.people[1].value, 0.01)
        assertTrue(d.isValid)
        assertEquals(100.0, d.toMembers()[0].owed, 0.01)
    }

    @Test fun existingSplitLoadsAsExactAmountsWithMyShareLeft() {
        val d = SplitDraft(900.0, listOf(SplitMember("A", 300.0), SplitMember("B", 200.0)))
        assertEquals(400.0, d.people.first { it.isMe }.value, 0.001)
        assertTrue(d.isValid)
    }
}

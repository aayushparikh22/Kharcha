package com.spendcalci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsParserTest {
    @Test fun card() {
        val t = SmsParser.parse(
            "Spent Rs.67.2 On HDFC Bank Card 0602 At UBER INDIA SYSTEMS On 2026-11-01:16:20:23.Not You? To Block+Reissue Call 18002586161/SMS BLOCK CC 0602 to 7308080808", 0
        )!!
        assertEquals(67.2, t.amount, 0.0)
        assertEquals("UBER INDIA SYSTEMS", t.merchant)
        assertEquals("CARD", t.source)
    }

    @Test fun upi() {
        val t = SmsParser.parse(
            "Sent Rs.30.00\nFrom HDFC Bank A/C *3241\nTo CALIFORNIA BURRITO\nOn 01/11/26\nRef 667117948033\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808", 0
        )!!
        assertEquals(30.0, t.amount, 0.0)
        assertEquals("CALIFORNIA BURRITO", t.merchant)
        assertEquals("UPI-667117948033", t.hash)
    }

    @Test fun mandateIsAutoSip() {
        val t = SmsParser.parse(
            "UPI Mandate:\nSent Rs.20000.00\nfrom HDFC Bank A/c 3241\nTo Indian Clearing Corpor\n01/11/26\nRef 630586213314\nNot You? Call 18002586161/SMS BLOCK UPI to 7308080808", 0
        )!!
        assertEquals(20000.0, t.amount, 0.0)
        assertEquals("Investment", t.category)
        assertEquals("SIP", t.note)
        assertEquals("UPI-630586213314", t.hash)
    }

    @Test fun ignoresCreditAndSpam() {
        assertNull(SmsParser.parse("Credit Alert! Rs.100000.00 credited to HDFC Bank A/c XX3241 on 21-10-26", 0))
        assertNull(SmsParser.parse("Your OTP is 123456", 0))
    }

    @Test fun axisDebit() {
        val t = SmsParser.parse(
            "INR 60.00 debited\nA/c no. XX0579\n21-11-26, 18:38:50\nUPI/P2M/669112838250/CAFE AMUDHAM\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank", 0
        )!!
        assertEquals(60.0, t.amount, 0.0)
        assertEquals("CAFE AMUDHAM", t.merchant)
        assertEquals("0579", t.account)
        assertEquals("UPI-669112838250", t.hash)
    }

    @Test fun sbiDebit() {
        val t = SmsParser.parse(
            "Dear UPI user A/C X1234 debited by 50.0 on date 21Nov26 trf to JOHN DOE Refno 123456789012. If not u? call 1800111109", 0
        )!!
        assertEquals(50.0, t.amount, 0.0)
        assertEquals("JOHN DOE", t.merchant)
        assertEquals("1234", t.account)
    }

    @Test fun genericBankDebit() {
        val t = SmsParser.parse("Rs.250.00 debited from A/c XX7788 on 21-11-26 to SWIGGY Ref 556677889900", 0)!!
        assertEquals(250.0, t.amount, 0.0)
        assertEquals("SWIGGY", t.merchant)
        assertEquals("7788", t.account)
        assertEquals("GEN-556677889900", t.hash)
    }

    @Test fun bankSenderFilter() {
        assertTrue(SmsParser.isBankSender("AD-AXISBK-S"))
        assertFalse(SmsParser.isBankSender("+919876543210"))
        assertFalse(SmsParser.isBankSender(null))
    }
}

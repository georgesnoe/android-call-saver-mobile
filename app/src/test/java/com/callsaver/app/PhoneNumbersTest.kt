package com.callsaver.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumbersTest {
    private val cc = "+228"

    @Test
    fun emptyNumberFallsBackToCountryCode() {
        assertEquals("+228", PhoneNumbers.normalize(null, cc))
        assertEquals("+228", PhoneNumbers.normalize("", cc))
        assertEquals("+228", PhoneNumbers.normalize("private", cc))
    }

    @Test
    fun localNumberGetsCountryCode() {
        assertEquals("+22890123456", PhoneNumbers.normalize("90 12 34 56", cc))
        assertEquals("+22890123456", PhoneNumbers.normalize("90123456", cc))
    }

    @Test
    fun internationalNumberIsPreserved() {
        assertEquals("+22890123456", PhoneNumbers.normalize("+228 90 12 34 56", cc))
        assertEquals("+22890123456", PhoneNumbers.normalize("0022890123456", cc))
        assertEquals("+22890123456", PhoneNumbers.normalize("22890123456", cc))
        assertEquals("+15551234567", PhoneNumbers.normalize("+1 (555) 123-4567", cc))
    }

    @Test
    fun shortCodeStillStartsWithCountryCode() {
        assertEquals("+22812", PhoneNumbers.normalize("12", cc))
    }

    @Test
    fun countryCodeIsNormalized() {
        assertEquals("+228", PhoneNumbers.normalizeCountryCode("228"))
        assertEquals("+228", PhoneNumbers.normalizeCountryCode("+228"))
        assertEquals("+228", PhoneNumbers.normalizeCountryCode(""))
    }
}

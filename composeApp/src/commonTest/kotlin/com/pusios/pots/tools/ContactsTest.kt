package com.pusios.pots.tools

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contact tools' contract, minus the Android query itself: how a number is compared,
 * how many contacts may leave the device, and what the model sees when access is missing.
 */
class ContactsTest {

    // --- normalization ---------------------------------------------------------------

    @Test
    fun formattingIsStripped() {
        assertEquals("+79123456789", PhoneNumbers.normalize("+7 (912) 345-67-89"))
        assertEquals("+79123456789", PhoneNumbers.normalize("8 912 345 67 89"))
        assertEquals("+79123456789", PhoneNumbers.normalize("89123456789"))
        assertEquals("+79123456789", PhoneNumbers.normalize("7-912-345-67-89"))
    }

    @Test
    fun aNumberWithoutACountryCodeIsKeptAsIs() {
        // Guessing the country here would silently store the wrong number.
        assertEquals("9123456789", PhoneNumbers.normalize("912 345 67 89"))
        assertEquals("9123456789", PhoneNumbers.normalize("(912) 345-67-89"))
    }

    @Test
    fun foreignNumbersKeepTheirPlus() {
        assertEquals("+14155550100", PhoneNumbers.normalize("+1 (415) 555-0100"))
    }

    @Test
    fun junkIsRejected() {
        assertNull(PhoneNumbers.normalize(""))
        assertNull(PhoneNumbers.normalize("   "))
        assertNull(PhoneNumbers.normalize("Ivan"))
        assertNull(PhoneNumbers.normalize("+"))
    }

    @Test
    fun namesAreNotMistakenForNumbers() {
        assertFalse(PhoneNumbers.looksLikePhone("Ivan"))
        assertFalse(PhoneNumbers.looksLikePhone("Вася"))
        assertFalse(PhoneNumbers.looksLikePhone("12"))
        assertTrue(PhoneNumbers.looksLikePhone("+7 (912) 345-67-89"))
        assertTrue(PhoneNumbers.looksLikePhone("9123456789"))
    }

    @Test
    fun theSameNumberInDifferentFormatsMatches() {
        assertTrue(PhoneNumbers.sameNumber("8 (912) 345-67-89", "+79123456789"))
        assertTrue(PhoneNumbers.sameNumber("+7 912 345 67 89", "+79123456789"))
        assertFalse(PhoneNumbers.sameNumber("+79123456789", "+79123456780"))
        assertFalse(PhoneNumbers.sameNumber("", "+79123456789"))
    }

    // --- the LIKE pattern the Android query uses -------------------------------------

    @Test
    fun aPlainQueryBecomesAContainsPattern() {
        assertEquals("%Иван%", likePattern("Иван"))
    }

    @Test
    fun wildcardsInAQueryAreEscaped() {
        // Unescaped, "%" would match the whole address book and "_" would match any character:
        // the model could dump contacts without asking for any of them.
        assertEquals("%\\%%", likePattern("%"))
        assertEquals("%\\_%", likePattern("_"))
        assertEquals("%\\%\\_%", likePattern("%_"))
        assertEquals("%Ив\\_н%", likePattern("Ив_н"))
    }

    @Test
    fun aBackslashInAQueryIsEscapedFirst() {
        // "\\%" has to become "\\\\%": the backslash is escaped, then the percent sign.
        assertEquals("%a\\\\b%", likePattern("a\\b"))
        assertEquals("%\\\\\\%%", likePattern("\\%"))
    }

    // --- deduplication ---------------------------------------------------------------

    @Test
    fun anExistingContactWinsOverACreate() {
        val book = listOf(
            ContactSummary(1, "Мама", "+79123456789"),
            ContactSummary(2, "Иван", "+79001112233"),
        )
        val existing = findExistingContact(book, PhoneNumbers.normalize("8 912 345-67-89")!!)
        assertEquals(1L, existing?.id)
        assertEquals("Мама", existing?.name)
    }

    @Test
    fun aNumberNobodyHasIsNotADuplicate() {
        val book = listOf(ContactSummary(1, "Мама", "+79123456789"))
        assertNull(findExistingContact(book, "+79001112233"))
    }

    @Test
    fun contactsWithoutAPhoneAreNeverDuplicates() {
        val book = listOf(ContactSummary(1, "Без номера", null))
        assertNull(findExistingContact(book, "+79123456789"))
    }

    // --- what the model is allowed to see --------------------------------------------

    @Test
    fun searchResultsAreCappedAtTen() {
        val fifteen = (1L..15L).map { ContactSummary(it, "Контакт $it", "+790000000$it") }
        val response = contactsSearchResponse(fifteen)

        assertEquals(true, response["success"])
        assertEquals(PhoneNumbers.MAX_SEARCH_RESULTS, response["count"])
        assertEquals(10, (response["contacts"] as List<*>).size)
        assertEquals(true, response["has_more"])
    }

    @Test
    fun theAndroidQueryFetchesOneRowMoreThanTheModelSees() {
        // has_more can only be true if the query fetched the row that did not fit. Fetching
        // exactly MAX_SEARCH_RESULTS makes the flag dead code, which is how it shipped once.
        assertTrue(PhoneNumbers.SEARCH_FETCH_LIMIT > PhoneNumbers.MAX_SEARCH_RESULTS)
        assertEquals(PhoneNumbers.MAX_SEARCH_RESULTS + 1, PhoneNumbers.SEARCH_FETCH_LIMIT)
    }

    @Test
    fun elevenFetchedMatchesAreCappedAtTenAndReportedAsMore() {
        // The exact shape a search returns at the fetch limit: eleven rows in, ten out.
        val fetched = (1L..11L).map { ContactSummary(it, "Иван $it", "+790****000$it") }
        val response = contactsSearchResponse(fetched)

        assertEquals(PhoneNumbers.MAX_SEARCH_RESULTS, response["count"])
        assertEquals(10, (response["contacts"] as List<*>).size)
        assertEquals(true, response["has_more"])
    }

    @Test
    fun exactlyTenMatchesAreNotReportedAsMore() {
        val fetched = (1L..10L).map { ContactSummary(it, "Иван $it", "+790****000$it") }
        val response = contactsSearchResponse(fetched)

        assertEquals(10, response["count"])
        assertEquals(false, response["has_more"])
    }

    @Test
    fun aShortResultListIsNotMarkedAsTruncated() {
        val response = contactsSearchResponse(listOf(ContactSummary(1, "Иван", "+79123456789")))
        assertEquals(1, response["count"])
        assertEquals(false, response["has_more"])
    }

    @Test
    fun eachContactCarriesOnlyIdNameAndPhone() {
        val response = contactsSearchResponse(listOf(ContactSummary(7, "Иван", "+79123456789")))
        val contact = (response["contacts"] as List<*>).single() as Map<*, *>

        assertEquals(setOf("id", "name", "phone"), contact.keys)
        assertEquals(7L, contact["id"])
        assertEquals("Иван", contact["name"])
        assertEquals("+79123456789", contact["phone"])
        assertEquals(setOf("success", "count", "has_more", "contacts"), response.keys)
    }

    // --- no permission ---------------------------------------------------------------

    @Test
    fun aMissingPermissionIsAnAnswerNotAnException() = runTest {
        // Desktop test run: contacts aren't supported there, so this is exactly the
        // "user never granted access" path the agent hits on a phone.
        val refusal = contactsPermissionBlock(PermissionController(AppPermission.CONTACTS))

        assertEquals(false, refusal?.get("success"))
        assertEquals(CONTACTS_PERMISSION_DENIED, refusal?.get("error"))
        assertTrue(refusal?.get("error").toString().contains("contacts", ignoreCase = true))
    }
}

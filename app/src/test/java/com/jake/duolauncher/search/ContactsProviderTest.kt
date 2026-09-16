package com.jake.duolauncher.search

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val US: Locale = Locale.US

private fun contact(name: String, id: Long = name.hashCode().toLong()) =
    SearchContact(lookupKey = "lookup-$name", contactId = id, displayName = name)

/** A fake address book that records every call, so "on demand, never cached" is observable. */
private class FakeContacts(
    private val granted: Boolean = true,
    private val rows: List<SearchContact> = emptyList(),
    private val failure: Throwable? = null,
) : SearchContactSource {
    val queries = ArrayList<String>()

    override fun isGranted(): Boolean = granted

    override fun find(query: String, limit: Int): List<SearchContact> {
        queries += query
        failure?.let { throw it }
        return rows.take(limit)
    }
}

private fun results(source: SearchContactSource, text: String, limit: Int = 5) =
    ContactsProvider(source, locale = US).results(SearchQuery.of(text, US), limit)

class ContactsProviderTest {

    @Test fun `the section is absent when access is denied`() {
        val source = FakeContacts(granted = false, rows = listOf(contact("Ada Lovelace")))
        assertEquals(emptyList<ContactResult>(), results(source, "ada"))
        assertEquals("the address book is never touched without permission", emptyList<String>(), source.queries)
    }

    @Test fun `granted access returns rows`() {
        val source = FakeContacts(rows = listOf(contact("Ada Lovelace")))
        val rows = results(source, "ada")
        assertEquals(listOf("Ada Lovelace"), rows.map { it.contact.displayName })
        assertEquals(SearchSection.CONTACTS, rows.first().section)
        assertEquals(listOf("ada"), source.queries)
    }

    @Test fun `short queries are never sent to the provider`() {
        val source = FakeContacts(rows = listOf(contact("Ada Lovelace")))
        assertEquals(emptyList<ContactResult>(), results(source, "a"))
        assertEquals(emptyList<String>(), source.queries)
        assertEquals(emptyList<ContactResult>(), results(source, "   "))
        assertEquals(emptyList<String>(), source.queries)
    }

    @Test fun `every query hits the provider again, so nothing is cached`() {
        val source = FakeContacts(rows = listOf(contact("Ada Lovelace")))
        results(source, "ada")
        results(source, "ada")
        results(source, "lov")
        assertEquals(listOf("ada", "ada", "lov"), source.queries)
    }

    @Test fun `a failing provider degrades to no section`() {
        val source = FakeContacts(rows = listOf(contact("Ada")), failure = IllegalStateException("provider died"))
        assertEquals(emptyList<ContactResult>(), results(source, "ada"))
    }

    @Test fun `rows are ordered by how well they match`() {
        val source = FakeContacts(
            rows = listOf(
                contact("Alexander Adams"),
                contact("Ada Lovelace"),
                contact("Adam Smith"),
            ),
        )
        val names = results(source, "ada").map { it.contact.displayName }
        assertEquals(listOf("Ada Lovelace", "Adam Smith", "Alexander Adams"), names)
    }

    @Test fun `blank names and duplicate lookup keys are dropped`() {
        val duplicate = contact("Ada Lovelace")
        val source = FakeContacts(
            rows = listOf(duplicate, duplicate, SearchContact("blank", 9L, "   ")),
        )
        assertEquals(1, results(source, "ada").size)
    }

    @Test fun `the limit is respected`() {
        val source = FakeContacts(rows = List(10) { contact("Ada $it", it.toLong()) })
        assertEquals(3, results(source, "ada", limit = 3).size)
        assertEquals(emptyList<ContactResult>(), results(source, "ada", limit = 0))
    }

    @Test fun `the default source is unavailable`() {
        val provider = ContactsProvider(locale = US)
        assertEquals(emptyList<ContactResult>(), provider.results(SearchQuery.of("ada", US), 5))
        assertTrue(!SearchContactSource.Unavailable.isGranted())
        assertEquals(emptyList<SearchContact>(), SearchContactSource.Unavailable.find("ada", 5))
    }
}

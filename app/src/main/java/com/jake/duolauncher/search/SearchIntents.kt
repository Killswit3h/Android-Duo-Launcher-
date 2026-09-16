package com.jake.duolauncher.search

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/**
 * The Android side of Search: the intents each result launches, and the two system-backed seams the
 * pure engine is written against.
 *
 * This is the only file in the package that imports the framework, which is what keeps the engine,
 * the ranking and the calculator testable on the JVM.
 */

/** FR-72's "Search the web" hand-off. Duo has no INTERNET permission; the query leaves via intent. */
fun webSearchIntent(query: String): Intent =
    Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)

/** FR-73's Ask control: the device's default assistant. */
fun assistIntent(): Intent = Intent(Intent.ACTION_VOICE_COMMAND)

/** FR-72's Settings rows. The action comes from the curated list, never from user input. */
fun settingsIntent(destination: SettingsDestination): Intent = Intent(destination.action)

/** Opens one contact. The lookup URI is the stable form; the raw id alone can change on sync. */
fun contactIntent(contact: SearchContact): Intent = Intent(
    Intent.ACTION_VIEW,
    ContactsContract.Contacts.getLookupUri(contact.contactId, contact.lookupKey),
)

/**
 * Whether anything handles [intent].
 *
 * On API 30+ implicit-intent resolution is filtered by package visibility, so the manifest needs a
 * `<queries>` entry for `android.intent.action.WEB_SEARCH` and `android.intent.action.VOICE_COMMAND`
 * for these checks to see anything. Without it the rows simply stay hidden, which is the error
 * table's behavior for an unresolvable hand-off rather than a crash.
 */
fun canResolve(context: Context, intent: Intent): Boolean =
    runCatching {
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }.getOrDefault(false)

/** The production [WebSearchAvailability]: the row exists only if some app can answer it. */
class PackageManagerWebSearch(private val context: Context) : WebSearchAvailability {
    override fun canSearchWeb(): Boolean = canResolve(context, webSearchIntent(""))
}

/** The production availability filter for the curated settings list. */
fun settingsAvailability(context: Context): (SettingsDestination) -> Boolean =
    { destination -> canResolve(context, settingsIntent(destination)) }

/**
 * The production [SearchContactSource] (NFR-S4).
 *
 * Contacts are queried on demand, once per keystroke that earns it, through
 * `ContactsContract.Contacts.CONTENT_FILTER_URI` — the provider does the matching, so Duo never
 * reads the address book to filter it itself. Three columns are read, the rows are handed straight
 * to the caller, and nothing is cached, persisted, backed up or logged. The permission is re-checked
 * on every call, so revoking it takes effect immediately, and the query is never attempted without
 * it. Requesting the permission belongs to the Search UI, not here.
 */
class ContactsContractSource(private val context: Context) : SearchContactSource {

    override fun isGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override fun find(query: String, limit: Int): List<SearchContact> {
        if (limit <= 0 || query.isBlank() || !isGranted()) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query))
        return runCatching { read(uri, limit) }.getOrDefault(emptyList())
    }

    private fun read(uri: Uri, limit: Int): List<SearchContact> {
        val contacts = ArrayList<SearchContact>(limit)
        context.contentResolver.query(uri, PROJECTION, null, null, SORT_ORDER)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val lookupColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
            val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            while (cursor.moveToNext() && contacts.size < limit) {
                val lookupKey = cursor.getString(lookupColumn) ?: continue
                val name = cursor.getString(nameColumn) ?: continue
                if (name.isBlank()) continue
                contacts += SearchContact(lookupKey, cursor.getLong(idColumn), name)
            }
        }
        return contacts
    }

    private companion object {
        val PROJECTION = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        )

        /** Starred first, then the provider's own display order: the same order the Contacts app uses. */
        const val SORT_ORDER =
            "${ContactsContract.Contacts.STARRED} DESC, ${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
    }
}

package tools.fmc.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

/** Same cases as ios/AppTests/CatalogQueryTests.swift. */
class CatalogQueryTest {
    private val me = "852gi1yb7gjr4tk"

    @Test fun shelves() {
        assertEquals("published = true", CatalogQuery().filter)
        assertEquals("owner = '852gi1yb7gjr4tk'", CatalogQuery(Shelf.Mine, user = me).filter)
        assertEquals("published = true && likes_via_watchface.user ?= '852gi1yb7gjr4tk'", CatalogQuery(Shelf.Liked, user = me).filter)
        // signed out, or an id that isn't one: nothing to ask for
        assertNull(CatalogQuery(Shelf.Liked).filter)
        assertNull(CatalogQuery(Shelf.Mine, user = "x' || owner != '").filter)
    }

    @Test fun searchesNameAndAuthor() {
        assertEquals("published = true && (name ~ 'Neo' || owner.name ~ 'Neo')", CatalogQuery(search = "  Neo ").filter)
        // a quote can't end the literal
        assertEquals("published = true && (name ~ 'it\\'s\\\\' || owner.name ~ 'it\\'s\\\\')", CatalogQuery(search = "it's\\").filter)
    }

    @Test fun sorts() {
        assertEquals("-created,id", CatalogQuery(sort = CatalogSort.New).order())
        assertEquals("-likes_count,-downloads,id", CatalogQuery(sort = CatalogSort.Popular).order())
        assertEquals("-downloads,-created,id", CatalogQuery(sort = CatalogSort.Popular).order(likesCount = false))
        assertEquals("-downloads,-created,id", CatalogQuery(sort = CatalogSort.Downloads).order())
    }

    @Test fun cachesOnlyTheUnsearchedAll() {
        assertEquals("catalog-popular", CatalogQuery(sort = CatalogSort.Popular).cacheName)
        assertNull(CatalogQuery(search = "a").cacheName)
        assertNull(CatalogQuery(Shelf.Mine, user = me).cacheName)
    }

    @Test fun nextPageNearTheEnd() {
        assertFalse(CatalogQuery.nearEnd(31, 40))
        assertTrue(CatalogQuery.nearEnd(32, 40))
        assertTrue(CatalogQuery.nearEnd(0, 3))
    }

    @Test fun readsTheCountWhenTheServerHasIt() {
        val base = """{"id":"a","collectionId":"c","name":"n","description":"","preview":"p.png","bin":"b.bin","published":true,"downloads":1,"created":"2026-11-19""""
        assertEquals(7, Watchface.from(JSONObject("$base,\"likes_count\":7}")).likesCount)
        val face = Watchface.from(JSONObject("$base}"))
        assertNull(face.likesCount)
        assertEquals("https://fmc.tools/api/files/c/a/p.png?thumb=300x300", face.thumbURL)
    }

    @Test fun evictsTheLeastRecentlyUsed() {
        // (size, last used): 100 over a max of 60 drops the oldest down to 54 (90%)
        val entries = listOf(30L to 3L, 30L to 1L, 40L to 2L)
        assertEquals(listOf(1, 2), ImageCache.evict(entries, 60))
        assertEquals(emptyList(), ImageCache.evict(entries, 100))
    }
}

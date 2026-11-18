package tools.fmc.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoreAppsTest {
    @Test
    fun matchesBySlugOrNameAndSkipsSvg() {
        val m = StoreApps.parse("""{"items":[
            {"collectionId":"c1","icon":"i.svg","id":"a","name":"Workout","slug":"workout"},
            {"collectionId":"c1","icon":"sky.png","id":"b","name":"Sky Hop","slug":"sky-hop","package":"sky.wp2app"}]}""")
        assertEquals("Sky Hop", m["sky-hop"]?.name)
        assertEquals("$FMC/api/files/c1/b/sky.png", m["sky hop"]?.icon)
        assertNull(m["workout"]!!.icon)
        assertEquals("$FMC/api/files/c1/b/sky.wp2app", m["sky-hop"]?.pkg)
        assertNull(m["workout"]!!.pkg)
    }

    @Test
    fun versionTextDecodesTheStoreBuildNumber() {
        assertEquals("0.2.0", versionText(2048))
        assertEquals("1.2.3", versionText((1L shl 20) or (2L shl 10) or 3))
        assertEquals("1", versionText(1)) // SDK examples: a plain number
    }
}

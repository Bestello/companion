package tools.fmc.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoreAppsTest {
    @Test
    fun matchesBySlugOrNameAndSkipsSvg() {
        val m = StoreApps.parse("""{"items":[
            {"collectionId":"c1","icon":"i.svg","id":"a","name":"Workout","slug":"workout"},
            {"collectionId":"c1","icon":"sky.png","id":"b","name":"Sky Hop","slug":"sky-hop"}]}""")
        assertEquals("Sky Hop", m["sky-hop"]?.name)
        assertEquals("$FMC/api/files/c1/b/sky.png", m["sky hop"]?.icon)
        assertNull(m["workout"]!!.icon)
    }
}

package tools.fmc.companion

import kotlin.test.Test
import kotlin.test.assertEquals

class AccountTest {
    /** PocketBase's authURL ends in `redirect_uri=` and may carry a state: both are ours to set, as the JS SDK does. */
    @Test fun providerUrlCarriesOurStateAndRedirect() {
        val p = OAuthProvider("google", "Google",
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=abc&code_challenge=xyz&response_type=code&state=old&redirect_uri=", "v")
        assertEquals(
            "https://accounts.google.com/o/oauth2/v2/auth?client_id=abc&code_challenge=xyz&response_type=code" +
                "&state=client%2F1&redirect_uri=https%3A%2F%2Ffmc.tools%2Fapi%2Foauth2-redirect",
            p.url("client/1"),
        )
    }
}

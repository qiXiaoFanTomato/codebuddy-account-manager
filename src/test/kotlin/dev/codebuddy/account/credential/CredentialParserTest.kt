package dev.codebuddy.account.credential

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CredentialParserTest {

    private fun json(uid: String = "u1", withAuth: Boolean = true): String = buildString {
        append("""{"account":{"uid":"$uid","nickname":"启小凡","uin":"330107094664","type":"personal","phoneNumber":"138****0000"}""")
        if (withAuth) {
            append(""","auth":{"accessToken":"at","domain":"www.codebuddy.cn","expiresAt":1795858210022,"refreshExpiresAt":1798450209022}""")
        }
        append("}")
    }

    @Test
    fun parsesAccountAndAuth() {
        val snapshot = CredentialParser.parseContent(json(), "C:/auth/a.info")!!

        assertEquals("u1", snapshot.uid)
        assertEquals("启小凡", snapshot.nickname)
        assertEquals("www.codebuddy.cn", snapshot.domain)
        assertEquals("u1@www.codebuddy.cn", snapshot.accountKey)
        assertEquals("codebuddy.cn", snapshot.domainHost)
        assertEquals(1795858210022L, snapshot.expiresAt)
        assertEquals("C:/auth/a.info", snapshot.sourcePath)
        assertTrue(snapshot.rawContent.isNotEmpty())
    }

    @Test
    fun missingAuthFallsBackToEmptyDomainAndZeroExpiry() {
        val snapshot = CredentialParser.parseContent(json(withAuth = false))!!

        assertEquals("", snapshot.domain)
        assertEquals(0L, snapshot.expiresAt)
        assertEquals(0L, snapshot.refreshExpiresAt)
        // domain 为空时 accountKey 仍可用，不抛异常
        assertEquals("u1@", snapshot.accountKey)
    }

    @Test
    fun rejectsInvalidJson() {
        assertNull(CredentialParser.parseContent("{not json"))
    }

    @Test
    fun rejectsMissingAccountNode() {
        assertNull(CredentialParser.parseContent("""{"auth":{"domain":"www.codebuddy.cn"}}"""))
    }

    @Test
    fun rejectsBlankUid() {
        assertNull(CredentialParser.parseContent(json(uid = "")))
    }

    @Test
    fun displayNameFallsBackToAliasThenNickname() {
        val snapshot = CredentialParser.parseContent(json())!!

        assertEquals("启小凡", snapshot.displayName)
        assertEquals("别名", snapshot.copy(alias = "别名").displayName)
    }
}

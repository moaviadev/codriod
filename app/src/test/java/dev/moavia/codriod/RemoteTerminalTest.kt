package dev.moavia.codriod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteTerminalTest {
    private fun profile(
        label: String = "Office PC",
        host: String = "192.168.1.20",
        port: Int = 22,
        username: String = "mudassar",
        preset: RemoteShellPreset = RemoteShellPreset.WINDOWS_POWERSHELL
    ) = RemoteProfile(label = label, host = host, port = port, username = username, preset = preset)

    @Test
    fun acceptsValidProfile() {
        assertNull(validateRemoteProfile(profile()))
        assertNull(validateRemoteProfile(profile(host = "dev.example.com", port = 2222, username = "dev")))
        assertNull(validateRemoteProfile(profile(host = "fe80::1")))
    }

    @Test
    fun rejectsBlankHost() {
        assertNotNull(validateRemoteProfile(profile(host = "")))
    }

    @Test
    fun rejectsHostWithSpacesOrShellCharacters() {
        assertNotNull(validateRemoteProfile(profile(host = "my pc")))
        assertNotNull(validateRemoteProfile(profile(host = "host;rm -rf /")))
        assertNotNull(validateRemoteProfile(profile(host = "host\nname")))
    }

    @Test
    fun rejectsInvalidPort() {
        assertNotNull(validateRemoteProfile(profile(port = 0)))
        assertNotNull(validateRemoteProfile(profile(port = 70000)))
    }

    @Test
    fun rejectsBlankOrControlUserNames() {
        assertNotNull(validateRemoteProfile(profile(username = "")))
        assertNotNull(validateRemoteProfile(profile(username = "two words")))
        assertNotNull(validateRemoteProfile(profile(username = "user\u0000")))
    }

    @Test
    fun shellPresetsProvideCorrectStartupCommands() {
        assertEquals("powershell.exe -NoLogo", RemoteShellPreset.WINDOWS_POWERSHELL.startupCommand)
        assertEquals("cmd.exe", RemoteShellPreset.WINDOWS_CMD.startupCommand)
        assertEquals("", RemoteShellPreset.LINUX.startupCommand)
    }

    @Test
    fun unknownPresetFallsBackToLinux() {
        assertEquals(RemoteShellPreset.LINUX, RemoteShellPreset.byId("nope"))
        assertEquals(RemoteShellPreset.LINUX, RemoteShellPreset.byId(null))
        assertEquals(RemoteShellPreset.WINDOWS_CMD, RemoteShellPreset.byId("win-cmd"))
    }

    @Test
    fun defaultProfileKeepsProvidedStartupCommand() {
        val custom = profile(preset = RemoteShellPreset.LINUX)
        assertEquals("", custom.startupCommand)
        val windows = profile(preset = RemoteShellPreset.WINDOWS_CMD)
        assertEquals("cmd.exe", windows.startupCommand)
    }

    @Test
    fun parseProfilesFallsBackToPresetStartupWhenMissing() {
        val raw = """
            [{"id":"1","label":"PC","host":"10.0.0.2","port":22,"username":"user","preset":"win-ps"}]
        """.trimIndent()
        val parsed = RemoteProfileStore.parseProfiles(raw)
        assertEquals(1, parsed.size)
        assertEquals("powershell.exe -NoLogo", parsed[0].startupCommand)
    }

    @Test
    fun parseProfilesFallsBackToPresetStartupWhenBlank() {
        val raw = """
            [{"id":"1","label":"PC","host":"10.0.0.2","port":22,"username":"user","preset":"win-cmd","startupCommand":"   "}]
        """.trimIndent()
        val parsed = RemoteProfileStore.parseProfiles(raw)
        assertEquals(1, parsed.size)
        assertEquals("cmd.exe", parsed[0].startupCommand)
    }

    @Test
    fun parseProfilesPreservesCustomStartupCommand() {
        val raw = """
            [{"id":"1","label":"PC","host":"10.0.0.2","port":22,"username":"user","preset":"win-ps","startupCommand":"pwsh -NoLogo"}]
        """.trimIndent()
        val parsed = RemoteProfileStore.parseProfiles(raw)
        assertEquals(1, parsed.size)
        assertEquals("pwsh -NoLogo", parsed[0].startupCommand)
    }
}

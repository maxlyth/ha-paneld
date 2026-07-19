package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbControllerPolicyTest {
    @Test fun `captured unavailable root route preserves direct truth without a cross-check`() {
        assertEquals(
            true,
            networkAdbActiveState(
                directRead = { property ->
                    if (property == SERVICE_ADB_TCP_PORT_PROPERTY) "5555" else null
                },
                rootRead = null,
            ),
        )
        assertNull(networkAdbActiveState(directRead = { "" }, rootRead = null))
    }

    @Test fun `unprivileged property read detects externally active network adb without root`() {
        var rootCalled = false

        val active = networkAdbActiveState(
            directRead = { property ->
                when (property) {
                    SERVICE_ADB_TCP_PORT_PROPERTY -> "43210\n"
                    SERVICE_ADB_TLS_PORT_PROPERTY, PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY -> "0"
                    else -> null
                }
            },
            rootRead = { rootCalled = true; null },
        )

        assertEquals(true, active)
        assertFalse(rootCalled)
    }

    @Test fun `empty unprivileged property read falls back to root`() {
        val rootReads = mutableListOf<String>()

        val active = networkAdbActiveState(
            directRead = { property ->
                when (property) {
                    SERVICE_ADB_TCP_PORT_PROPERTY -> "\n"
                    SERVICE_ADB_TLS_PORT_PROPERTY, PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY -> "0"
                    else -> null
                }
            },
            rootRead = { property ->
                rootReads += property
                if (property == SERVICE_ADB_TCP_PORT_PROPERTY) "5037" else null
            },
        )

        assertEquals(true, active)
        assertEquals(
            listOf(SERVICE_ADB_LISTEN_ADDRS_PROPERTY, SERVICE_ADB_TCP_PORT_PROPERTY),
            rootReads,
        )
    }

    @Test fun `persistent classic TCP port is active even when service port is disabled`() {
        assertEquals(
            true,
            networkAdbActiveState(
                directRead = { property ->
                    when (property) {
                        SERVICE_ADB_TCP_PORT_PROPERTY -> "-1"
                        PERSIST_ADB_TCP_PORT_PROPERTY -> "5555"
                        else -> null
                    }
                },
                rootRead = { null },
            ),
        )
    }

    @Test fun `root-only persistent TCP port is active`() {
        val rootReads = mutableListOf<String>()
        assertEquals(
            true,
            networkAdbActiveState(
                directRead = { "" },
                rootRead = { property ->
                    rootReads += property
                    when (property) {
                        SERVICE_ADB_LISTEN_ADDRS_PROPERTY -> ""
                        SERVICE_ADB_TCP_PORT_PROPERTY -> "-1"
                        PERSIST_ADB_TCP_PORT_PROPERTY -> "43210"
                        else -> null
                    }
                },
            ),
        )
        assertEquals(
            listOf(
                SERVICE_ADB_LISTEN_ADDRS_PROPERTY,
                SERVICE_ADB_TCP_PORT_PROPERTY,
                PERSIST_ADB_TCP_PORT_PROPERTY,
            ),
            rootReads,
        )
    }

    @Test fun `explicit ADB listen addresses are active`() {
        listOf(
            "tcp:5555",
            "tcp:localhost:5555",
            "tcp:5555,vsock:5555",
            "malformed-fails-closed",
        ).forEach { addresses ->
            assertEquals(
                true,
                networkAdbActiveState(
                    directRead = { property ->
                        if (property == SERVICE_ADB_LISTEN_ADDRS_PROPERTY) addresses else null
                    },
                    rootRead = { null },
                ),
            )
        }
    }

    @Test fun `arbitrary positive classic TCP ports are active`() {
        listOf("1", "5037", "43210", "65535").forEach { port ->
            assertEquals(
                true,
                networkAdbActiveState(
                    directRead = { property ->
                        when (property) {
                            SERVICE_ADB_TCP_PORT_PROPERTY -> port
                            SERVICE_ADB_TLS_PORT_PROPERTY, PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY -> "0"
                            else -> null
                        }
                    },
                    rootRead = { null },
                ),
            )
        }
    }

    @Test fun `wireless debugging TLS port is active`() {
        assertEquals(
            true,
            networkAdbActiveState(
                directRead = { property ->
                    when (property) {
                        SERVICE_ADB_TCP_PORT_PROPERTY -> "-1"
                        SERVICE_ADB_TLS_PORT_PROPERTY -> "37123"
                        PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY -> "0"
                        else -> null
                    }
                },
                rootRead = { null },
            ),
        )
    }

    @Test fun `wireless debugging enabled intent is active before its TLS port is published`() {
        assertEquals(
            true,
            networkAdbActiveState(
                directRead = { property ->
                    when (property) {
                        SERVICE_ADB_TCP_PORT_PROPERTY -> "-1"
                        SERVICE_ADB_TLS_PORT_PROPERTY -> "0"
                        PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY -> "1"
                        else -> null
                    }
                },
                rootRead = { null },
            ),
        )
    }

    @Test fun `disabled port values and authoritative empty listen addresses are inactive`() {
        val rootReads = mutableListOf<String>()

        val active = networkAdbActiveState(
            directRead = { property ->
                when (property) {
                    SERVICE_ADB_LISTEN_ADDRS_PROPERTY -> ""
                    SERVICE_ADB_TCP_PORT_PROPERTY -> "-1"
                    PERSIST_ADB_TCP_PORT_PROPERTY -> "-1"
                    SERVICE_ADB_TLS_PORT_PROPERTY -> "0"
                    PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY -> "0"
                    else -> null
                }
            },
            rootRead = { property -> rootReads += property; "" },
        )

        assertEquals(false, active)
        assertEquals(listOf(SERVICE_ADB_LISTEN_ADDRS_PROPERTY), rootReads)
    }

    @Test fun `empty root values authoritatively establish disabled properties`() {
        val rootReads = mutableListOf<String>()

        val active = networkAdbActiveState(
            directRead = { "" },
            rootRead = { property -> rootReads += property; "" },
        )

        assertEquals(false, active)
        assertEquals(
            listOf(
                SERVICE_ADB_LISTEN_ADDRS_PROPERTY,
                SERVICE_ADB_TCP_PORT_PROPERTY,
                PERSIST_ADB_TCP_PORT_PROPERTY,
                SERVICE_ADB_TLS_PORT_PROPERTY,
                PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY,
            ),
            rootReads,
        )
    }

    @Test fun `malformed and overflow ports fail closed`() {
        listOf("not-a-port", "65536", "999999999999999999999999").forEach { value ->
            assertNull(
                networkAdbActiveState(
                    directRead = { property ->
                        when (property) {
                            SERVICE_ADB_LISTEN_ADDRS_PROPERTY -> ""
                            SERVICE_ADB_TCP_PORT_PROPERTY -> "-1"
                            PERSIST_ADB_TCP_PORT_PROPERTY -> value
                            SERVICE_ADB_TLS_PORT_PROPERTY -> "0"
                            PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY -> "0"
                            else -> null
                        }
                    },
                    rootRead = { property ->
                        if (property == SERVICE_ADB_LISTEN_ADDRS_PROPERTY) "" else null
                    },
                ),
            )
        }
    }

    @Test fun `root property commands are fixed and reject unlisted input`() {
        listOf(
            SERVICE_ADB_LISTEN_ADDRS_PROPERTY,
            SERVICE_ADB_TCP_PORT_PROPERTY,
            PERSIST_ADB_TCP_PORT_PROPERTY,
            SERVICE_ADB_TLS_PORT_PROPERTY,
            PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY,
        ).forEach { property ->
            assertEquals("/system/bin/getprop $property", networkAdbRootReadCommand(property))
        }
        assertNull(networkAdbRootReadCommand("service.adb.tcp.port; id"))
    }

    @Test fun `unknown property state fails closed`() {
        assertNull(networkAdbActiveState(directRead = { "" }, rootRead = { null }))
        assertNull(
            networkAdbActiveState(
                directRead = { property ->
                    when (property) {
                        SERVICE_ADB_TCP_PORT_PROPERTY -> "-1"
                        SERVICE_ADB_TLS_PORT_PROPERTY -> "not-a-port"
                        PERSIST_ADB_TLS_SERVER_ENABLE_PROPERTY -> "0"
                        else -> null
                    }
                },
                rootRead = { null },
            ),
        )
    }

    @Test fun `network adb transitions stop at the first failed privileged step`() {
        assertEquals(
            "setprop persist.adb.tcp.port 5555 && " +
                "setprop service.adb.tcp.port 5555 && " +
                "setprop ctl.restart adbd",
            networkAdbEnableCommand(),
        )
        assertEquals(
            "setprop persist.adb.tcp.port \"\" && " +
                "setprop service.adb.tcp.port \"\" && " +
                "setprop ctl.restart adbd",
            networkAdbDisableCommand(),
        )
        assertFalse(networkAdbEnableCommand().contains(';'))
        assertFalse(networkAdbDisableCommand().contains(';'))
    }

    @Test fun `owned teardown failure retains ownership for retry`() {
        var cleared = false

        assertFalse(disableOwnedNetworkAdb(owned = true, teardown = { false }) { cleared = true })

        assertFalse(cleared)
    }

    @Test fun `owned teardown clears ownership only after success`() {
        var cleared = false

        assertTrue(disableOwnedNetworkAdb(owned = true, teardown = { true }) { cleared = true })

        assertTrue(cleared)
    }
}

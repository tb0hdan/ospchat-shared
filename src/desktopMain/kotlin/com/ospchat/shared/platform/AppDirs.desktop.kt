package com.ospchat.shared.platform

import java.io.File
import java.util.Locale

/**
 * Per-OS user-data directory for OSPChat (config + identity + Room database).
 * Linux honours `$XDG_DATA_HOME` (falling back to `~/.local/share/ospchat`);
 * macOS uses `~/Library/Application Support/ospchat`; Windows uses
 * `%APPDATA%\ospchat`. The returned directory is created if it doesn't exist.
 */
fun dataDir(): File {
    val dir =
        when (currentOs()) {
            Os.LINUX -> {
                val xdgDataHome = System.getenv("XDG_DATA_HOME")
                if (!xdgDataHome.isNullOrEmpty()) {
                    File(xdgDataHome, "ospchat")
                } else {
                    File(System.getProperty("user.home"), ".local/share/ospchat")
                }
            }

            Os.MAC -> {
                File(System.getProperty("user.home"), "Library/Application Support/ospchat")
            }

            Os.WINDOWS -> {
                val appData = System.getenv("APPDATA")
                if (!appData.isNullOrEmpty()) {
                    File(appData, "ospchat")
                } else {
                    File(System.getProperty("user.home"), "AppData/Roaming/ospchat")
                }
            }
        }
    dir.mkdirs()
    return dir
}

/** Per-OS user-cache directory (attachments, transient images). */
fun cacheDir(): File {
    val dir =
        when (currentOs()) {
            Os.LINUX -> {
                val xdgCacheHome = System.getenv("XDG_CACHE_HOME")
                if (!xdgCacheHome.isNullOrEmpty()) {
                    File(xdgCacheHome, "ospchat")
                } else {
                    File(System.getProperty("user.home"), ".cache/ospchat")
                }
            }

            Os.MAC -> {
                File(System.getProperty("user.home"), "Library/Caches/ospchat")
            }

            Os.WINDOWS -> {
                val localAppData = System.getenv("LOCALAPPDATA")
                if (!localAppData.isNullOrEmpty()) {
                    File(localAppData, "ospchat/cache")
                } else {
                    File(System.getProperty("user.home"), "AppData/Local/ospchat/cache")
                }
            }
        }
    dir.mkdirs()
    return dir
}

internal enum class Os { LINUX, MAC, WINDOWS }

internal fun currentOs(): Os {
    val name = System.getProperty("os.name").lowercase(Locale.ROOT)
    return when {
        name.contains("mac") || name.contains("darwin") -> Os.MAC
        name.contains("win") -> Os.WINDOWS
        else -> Os.LINUX
    }
}

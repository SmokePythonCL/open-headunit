package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.blink

import android.os.ParcelFileDescriptor
import com.andrerinas.openheadunit.utils.AppLog
import java.io.ByteArrayInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct JNI bridge to open `/dev/rf_serial` or `/dev/auto_serial` in raw `termios` mode (`-echo`,
 * `-iuclc`, `-icrnl`, `-opost`) without requiring `/system/bin/stty` (absent on Android 8.1 Toybox)
 * or `su` where the pseudo-terminal is already world-accessible (`0666`).
 */
object BlinkTtyNative {

    private val libraryLoaded: Boolean by lazy {
        runCatching {
            System.loadLibrary("usbhelper")
            true
        }.onFailure { e ->
            AppLog.w("NativeAA: [BLINK] could not load usbhelper for raw tty setup: ${e.message}")
        }.getOrDefault(false)
    }

    @JvmStatic
    external fun openRawTty(path: String): Int

    /**
     * Opens [port] directly in raw `termios` mode and wraps the file descriptor in a [Process]
     * compatible with [BlinkAutoSerialChannel], or returns null if the port cannot be opened by
     * this app.
     */
    fun openProcess(
        port: String,
        pidLine: String,
        initialWrite: String? = null,
    ): Process? {
        if (!libraryLoaded) return null
        val fd = try {
            openRawTty(port)
        } catch (t: Throwable) {
            AppLog.w("NativeAA: [BLINK] openRawTty($port) failed: ${t.message}")
            return null
        }
        if (fd < 0) return null

        return try {
            val pfd = ParcelFileDescriptor.adoptFd(fd)
            TtyFdProcess(pfd, pidLine, initialWrite)
        } catch (e: Exception) {
            AppLog.w("NativeAA: [BLINK] adoptFd($fd) failed on $port: ${e.message}")
            null
        }
    }

    private class TtyFdProcess(
        private val pfd: ParcelFileDescriptor,
        pidLine: String,
        initialWrite: String?,
    ) : Process() {
        private val closed = AtomicBoolean(false)
        private val rawIn = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        private val rawOut = ParcelFileDescriptor.AutoCloseOutputStream(pfd)

        private val combinedIn: InputStream = SequenceInputStream(
            ByteArrayInputStream(pidLine.toByteArray(Charsets.US_ASCII)),
            rawIn,
        )

        private val wrappedOut: OutputStream = object : FilterOutputStream(rawOut) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closed.get()) throw IOException("tty closed")
                rawOut.write(b, off, len)
            }

            override fun close() {
                shutdown()
            }
        }

        init {
            if (!initialWrite.isNullOrEmpty()) {
                runCatching {
                    rawOut.write(initialWrite.toByteArray(Charsets.US_ASCII))
                    rawOut.flush()
                }
            }
        }

        private fun shutdown() {
            if (closed.compareAndSet(false, true)) {
                runCatching { rawOut.close() }
                runCatching { rawIn.close() }
                runCatching { pfd.close() }
            }
        }

        override fun getOutputStream(): OutputStream = wrappedOut
        override fun getInputStream(): InputStream = combinedIn
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            while (!closed.get()) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = closed.get()

        override fun exitValue(): Int {
            if (!closed.get()) throw IllegalThreadStateException("tty process still active")
            return 0
        }

        override fun destroy() {
            shutdown()
        }
    }
}

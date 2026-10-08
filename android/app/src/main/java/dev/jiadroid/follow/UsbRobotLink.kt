package dev.jiadroid.follow

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Opens an Espressif ESP32-S3 USB CDC port for [RobotClient].
 *
 * Opening the port often USB-resets the ESP32-S3. We bounce once to absorb that
 * reset, reopen with a stable CDC session, and keep the read pipe alive across
 * brief IO glitches so a torn hello does not look like "connection closed".
 */
object UsbRobotLink {
    private const val TAG = "UsbRobotLink"
    private const val ACTION_USB_PERMISSION = "dev.jiadroid.follow.USB_PERMISSION"
    private const val ESPRESSIF_VID = 0x303A
    private const val BAUD = 115200

    fun connect(context: Context): RobotClient {
        val app = context.applicationContext
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        ensurePermission(app, usb)

        // First open often resets the chip. Close and wait so the real session
        // is not torn down mid-hello.
        bouncePort(usb)

        var lastError: Exception? = null
        repeat(6) { attempt ->
            try {
                ensurePermission(app, usb)
                return openStable(usb)
            } catch (error: Exception) {
                lastError = error
                Log.i(TAG, "USB connect attempt ${attempt + 1} failed: ${error.message}")
                Thread.sleep(1000L + attempt * 500L)
                waitForEspressif(usb, 6000)
            }
        }
        throw IllegalStateException(
            lastError?.message
                ?: "USB connect failed. Use a data OTG cable into the board USB port, not UART/COM.",
        )
    }

    private fun bouncePort(usb: UsbManager) {
        try {
            val driver = findDriver(usb) ?: return
            val connection = usb.openDevice(driver.device) ?: return
            val port = driver.ports.firstOrNull() ?: run {
                connection.close()
                return
            }
            try {
                port.open(connection)
                port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                // Leave both inactive — avoids download mode and limits extra resets.
                port.rts = false
                port.dtr = false
                Thread.sleep(150)
            } finally {
                try {
                    port.close()
                } catch (_: Exception) {
                }
                try {
                    connection.close()
                } catch (_: Exception) {
                }
            }
        } catch (error: Exception) {
            Log.i(TAG, "USB bounce skipped: ${error.message}")
        }
        // Allow reboot + re-enumerate before the real open.
        Thread.sleep(3000)
        waitForEspressif(usb, 10000)
    }

    private fun waitForEspressif(usb: UsbManager, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (findDriver(usb) != null) return
            Thread.sleep(200)
        }
    }

    private fun openStable(usb: UsbManager): RobotClient {
        val driver = findDriver(usb)
            ?: throw IllegalStateException("No ESP32 on USB. Plug OTG into the board USB port.")
        val device = driver.device
        if (!usb.hasPermission(device)) {
            throw IllegalStateException("USB permission denied")
        }
        val connection = usb.openDevice(device)
            ?: throw IllegalStateException("Can't open the USB device")
        val port = driver.ports.firstOrNull()
            ?: run {
                connection.close()
                throw IllegalStateException("USB device has no serial port")
            }

        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut, 64 * 1024)
        val alive = AtomicBoolean(true)
        val ioRef = AtomicReference<SerialInputOutputManager?>(null)

        fun startIo(activePort: UsbSerialPort) {
            val manager = SerialInputOutputManager(activePort, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    if (!alive.get()) return
                    try {
                        pipeOut.write(data)
                        pipeOut.flush()
                    } catch (_: IOException) {
                    }
                }

                override fun onRunError(e: Exception?) {
                    Log.i(TAG, "USB IO error: ${e?.message}")
                    // Keep the pipe open during connect glitches; RobotClient
                    // only finishes when the pipe EOF or awaitHello times out.
                }
            })
            ioRef.getAndSet(manager)?.stop()
            manager.start()
        }

        try {
            port.open(connection)
            port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // DTR lets many CDC stacks deliver device→host bytes; keep RTS low
            // so we do not also enter download mode.
            port.rts = false
            port.dtr = true
            startIo(port)
            // Give the chip time to finish any open-time reset and start hello.
            Thread.sleep(1200)
        } catch (error: Exception) {
            alive.set(false)
            ioRef.get()?.stop()
            try {
                port.close()
            } catch (_: Exception) {
            }
            connection.close()
            try {
                pipeOut.close()
            } catch (_: Exception) {
            }
            throw IllegalStateException(error.message ?: "USB serial open failed")
        }

        val usbOut = object : OutputStream() {
            override fun write(b: Int) {
                port.write(byteArrayOf(b.toByte()), 2000)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                val chunk = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
                port.write(chunk, 2000)
            }

            override fun close() {}
        }

        return try {
            RobotClient.open(pipeIn, usbOut) {
                alive.set(false)
                ioRef.getAndSet(null)?.stop()
                try {
                    port.dtr = false
                } catch (_: Exception) {
                }
                try {
                    port.close()
                } catch (_: Exception) {
                }
                try {
                    connection.close()
                } catch (_: Exception) {
                }
                try {
                    pipeOut.close()
                } catch (_: Exception) {
                }
                try {
                    pipeIn.close()
                } catch (_: Exception) {
                }
            }
        } catch (error: Exception) {
            alive.set(false)
            ioRef.getAndSet(null)?.stop()
            try {
                port.close()
            } catch (_: Exception) {
            }
            connection.close()
            throw error
        }
    }

    private fun findDriver(usb: UsbManager): com.hoho.android.usbserial.driver.UsbSerialDriver? {
        val table = ProbeTable().apply {
            addProduct(ESPRESSIF_VID, 0x1001, CdcAcmSerialDriver::class.java)
            addProduct(ESPRESSIF_VID, 0x4001, CdcAcmSerialDriver::class.java)
        }
        val prober = UsbSerialProber(table)
        val defaultProber = UsbSerialProber.getDefaultProber()
        for (device in usb.deviceList.values) {
            if (device.vendorId != ESPRESSIF_VID) continue
            val found = prober.probeDevice(device) ?: defaultProber.probeDevice(device)
            if (found != null) return found
        }
        return null
    }

    private fun ensurePermission(context: Context, usb: UsbManager) {
        val driver = findDriver(usb)
            ?: throw IllegalStateException("No ESP32 on USB. Use a data OTG cable into the USB port.")
        val device = driver.device
        if (usb.hasPermission(device)) return
        requestPermission(context, usb, device)
        if (!usb.hasPermission(device)) {
            throw IllegalStateException("USB permission denied")
        }
    }

    private fun requestPermission(context: Context, usb: UsbManager, device: UsbDevice) {
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    latch.countDown()
                }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
        usb.requestPermission(device, intent)
        latch.await(20, TimeUnit.SECONDS)
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }
}

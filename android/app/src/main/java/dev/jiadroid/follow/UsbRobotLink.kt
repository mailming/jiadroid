package dev.jiadroid.follow

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
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

/**
 * Opens an Espressif ESP32-S3 USB CDC / Serial-JTAG port and exposes it as streams
 * for [RobotClient]. Requires USB host (OTG) on the phone.
 *
 * Important: start reading immediately after open. The board may send session.hello
 * during the settle delay; dropping those bytes makes Connect fail.
 */
object UsbRobotLink {
    private const val ACTION_USB_PERMISSION = "dev.jiadroid.follow.USB_PERMISSION"
    private const val ESPRESSIF_VID = 0x303A
    private const val BAUD = 115200

    fun connect(context: Context): RobotClient {
        val app = context.applicationContext
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = findDriver(usb)
            ?: throw IllegalStateException("No ESP32 on USB. Use a USB-C OTG cable into the board USB port (not UART/COM).")
        val device = driver.device
        if (!usb.hasPermission(device)) {
            requestPermission(app, usb, device)
        }
        val connection = usb.openDevice(device)
            ?: throw IllegalStateException("Can't open the USB device. Check OTG permission.")
        val port = driver.ports.firstOrNull()
            ?: run {
                connection.close()
                throw IllegalStateException("USB device has no serial port")
            }

        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut, 64 * 1024)
        val alive = AtomicBoolean(true)
        var io: SerialInputOutputManager? = null

        try {
            port.open(connection)
            port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Avoid download-mode straps; DTR high helps some CDC stacks mark the host present.
            try {
                port.rts = false
                port.dtr = false
                Thread.sleep(50)
                port.dtr = true
            } catch (_: Exception) {
            }

            io = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    if (!alive.get()) return
                    try {
                        pipeOut.write(data)
                        pipeOut.flush()
                    } catch (_: IOException) {
                    }
                }

                override fun onRunError(e: Exception?) {
                    alive.set(false)
                    try {
                        pipeOut.close()
                    } catch (_: Exception) {
                    }
                }
            })
            // Start listening BEFORE waiting — firmware may already be repeating hello.
            io.start()
            Thread.sleep(2000)
        } catch (error: Exception) {
            alive.set(false)
            io?.stop()
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

        val manager = io
        return try {
            RobotClient.open(pipeIn, usbOut) {
                alive.set(false)
                manager?.stop()
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
            manager?.stop()
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
        val granted = latch.await(20, TimeUnit.SECONDS) && usb.hasPermission(device)
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        if (!granted) {
            throw IllegalStateException("USB permission denied")
        }
    }
}

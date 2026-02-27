package com.itsaky.androidide.lsp.java.debug.transport

import com.sun.jdi.connect.spi.ClosedConnectionException
import com.sun.jdi.connect.spi.Connection
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

internal class COTGSocketConnection(
	private val socket: Socket,
) : Connection() {
	private var closed = false
	private val socketOutput: OutputStream
	private val socketInput: InputStream
	private val receiveLock = Any()
	private val sendLock = Any()
	private val closeLock = Any()

	init {
		socket.setTcpNoDelay(true)
		this.socketInput = socket.getInputStream()
		this.socketOutput = socket.getOutputStream()
	}

	@Throws(IOException::class)
	override fun close() {
		synchronized(this.closeLock) {
			if (!this.closed) {
				this.socketOutput.close()
				this.socketInput.close()
				this.socket.close()
				this.closed = true
			}
		}
	}

	override fun isOpen(): Boolean {
		synchronized(this.closeLock) {
			return !this.closed
		}
	}

	@Throws(IOException::class)
	override fun readPacket(): ByteArray {
		if (!this.isOpen) {
			throw ClosedConnectionException("connection is closed")
		}

		synchronized(this.receiveLock) {
			val b1: Int
			val b2: Int
			val b3: Int
			val b4: Int
			try {
				b1 = this.socketInput.read()
				b2 = this.socketInput.read()
				b3 = this.socketInput.read()
				b4 = this.socketInput.read()
			} catch (ioe: IOException) {
				if (!this.isOpen) {
					throw ClosedConnectionException("connection is closed")
				}

				throw ioe
			}
			if (b1 < 0) {
				return ByteArray(0)
			} else if (b2 >= 0 && b3 >= 0 && b4 >= 0) {
				val len = b1 shl 24 or (b2 shl 16) or (b3 shl 8) or (b4 shl 0)
				if (len < 0) {
					throw IOException("protocol error - invalid length")
				} else {
					val b = ByteArray(len)
					b[0] = b1.toByte()
					b[1] = b2.toByte()
					b[2] = b3.toByte()
					b[3] = b4.toByte()
					var off = 4

					var count: Int
					run {
						var len = len - off
						while (len > 0) {
							try {
								count = this.socketInput.read(b, off, len)
							} catch (ioe: IOException) {
								if (!this.isOpen) {
									throw ClosedConnectionException("connection is closed")
								}

								throw ioe
							}

							if (count < 0) {
								throw IOException("protocol error - premature EOF")
							}

							len -= count
							off += count
						}
					}

					return b
				}
			} else {
				throw IOException("protocol error - premature EOF")
			}
		}
	}

	@Throws(IOException::class)
	override fun writePacket(b: ByteArray) {
		if (!this.isOpen) {
			throw ClosedConnectionException("connection is closed")
		}

		require(b.size >= 11) { "packet is insufficient size" }
		val b0 = b[0].toInt() and 255
		val b1 = b[1].toInt() and 255
		val b2 = b[2].toInt() and 255
		val b3 = b[3].toInt() and 255
		val len = b0 shl 24 or (b1 shl 16) or (b2 shl 8) or (b3 shl 0)
		require(len >= 11) { "packet is insufficient size" }
		require(len <= b.size) { "length mis-match" }
		synchronized(this.sendLock) {
			try {
				this.socketOutput.write(b, 0, len)
			} catch (ioe: IOException) {
				if (!this.isOpen) {
					throw ClosedConnectionException("connection is closed")
				}

				throw ioe
			}
		}
	}
}

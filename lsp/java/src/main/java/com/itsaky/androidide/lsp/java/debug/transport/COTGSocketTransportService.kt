package com.itsaky.androidide.lsp.java.debug.transport

import com.sun.jdi.connect.TransportTimeoutException
import com.sun.jdi.connect.spi.Connection
import com.sun.jdi.connect.spi.TransportService
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.ResourceBundle

class COTGSocketTransportService : TransportService() {
	private var messages: ResourceBundle? = null

	@Throws(IOException::class)
	fun handshake(
		s: Socket,
		timeout: Long,
	) {
		s.setSoTimeout(timeout.toInt())
		val hello = "JDWP-Handshake".toByteArray(charset("UTF-8"))
		s.getOutputStream().write(hello)
		val b = ByteArray(hello.size)

		var n: Int
		var received = 0
		while (received < hello.size) {
			try {
				n = s.getInputStream().read(b, received, hello.size - received)
			} catch (_: SocketTimeoutException) {
				throw IOException("handshake timeout")
			}

			if (n < 0) {
				s.close()
				throw IOException("handshake failed - connection prematurally closed")
			}
			received += n
		}

		for (i in hello.indices) {
			if (b[i] != hello[i]) {
				throw IOException("handshake failed - unrecognized message from target VM")
			}
		}

		s.setSoTimeout(0)
	}

	override fun name(): String = "Socket"

	override fun description(): String {
		synchronized(this) {
			if (this.messages == null) {
				this.messages = ResourceBundle.getBundle("com.sun.tools.jdi.resources.jdi")
			}
		}

		return this.messages!!.getString("socket_transportservice.description")
	}

	override fun capabilities(): Capabilities = SocketTransportServiceCapabilities()

	@Throws(IOException::class)
	override fun attach(
		address: String,
		attachTimeout: Long,
		handshakeTimeout: Long,
	): Connection {
		if (attachTimeout < 0L || handshakeTimeout < 0L) {
			throw IllegalArgumentException("timeout is negative")
		}

		val splitIndex = address.indexOf(58.toChar())
		val host: String?
		val portStr: String?
		if (splitIndex < 0) {
			host = InetAddress.getLocalHost().getHostName()
			portStr = address
		} else {
			host = address.substring(0, splitIndex)
			portStr = address.substring(splitIndex + 1)
		}

		val port: Int
		try {
			port = Integer.decode(portStr)
		} catch (_: NumberFormatException) {
			throw IllegalArgumentException("unable to parse port number in address")
		}

		val sa = InetSocketAddress(host, port)
		val s = Socket()

		try {
			s.connect(sa, attachTimeout.toInt())
		} catch (_: SocketTimeoutException) {
			try {
				s.close()
			} catch (_: IOException) {
			}

			throw TransportTimeoutException("timed out trying to establish connection")
		}

		try {
			this.handshake(s, handshakeTimeout)
		} catch (exc: IOException) {
			try {
				s.close()
			} catch (_: IOException) {
			}

			throw exc
		}

		return COTGSocketConnection(s)
	}

	@Throws(IOException::class)
	fun startListening(
		localAddress: String?,
		port: Int,
	): ListenKey {
		var localAddress = localAddress
		if (localAddress == null) {
			localAddress = "0.0.0.0"
		}

		val inetAddress = Inet4Address.getByName(localAddress)
		val socketAddress = InetSocketAddress(inetAddress, port)
		val serverSocket = ServerSocket()
		serverSocket.bind(socketAddress)

		return SocketListenKey(serverSocket)
	}

	@Throws(IOException::class)
	override fun startListening(address: String?): ListenKey {
		var address = address
		if (address.isNullOrEmpty()) {
			address = "0"
		}

		val splitIndex = address.indexOf(58.toChar())
		var localAddr: String? = null
		if (splitIndex >= 0) {
			localAddr = address.substring(0, splitIndex)
			address = address.substring(splitIndex + 1)
		}

		val port: Int
		try {
			port = Integer.decode(address)
		} catch (_: NumberFormatException) {
			throw IllegalArgumentException("unable to parse port number in address")
		}

		return this.startListening(localAddr, port)
	}

	@Throws(IOException::class)
	override fun startListening(): ListenKey = this.startListening(null as String?, 0)

	@Throws(IOException::class)
	override fun stopListening(listener: ListenKey?) {
		require(listener is SocketListenKey) { "Invalid listener" }
		synchronized(listener) {
			val ss = listener.socket()
			require(!ss.isClosed) { "Invalid listener" }
			ss.close()
		}
	}

	@Throws(IOException::class)
	override fun accept(
		listener: ListenKey?,
		acceptTimeout: Long,
		handshakeTimeout: Long,
	): Connection {
		if (acceptTimeout >= 0L && handshakeTimeout >= 0L) {
			require(listener is SocketListenKey) { "Invalid listener" }
			val ss: ServerSocket
			synchronized(listener) {
				ss = listener.socket()
				require(!ss.isClosed) { "Invalid listener" }
			}

			ss.setSoTimeout(acceptTimeout.toInt())

			val s: Socket
			try {
				s = ss.accept()
			} catch (_: SocketTimeoutException) {
				throw TransportTimeoutException("timeout waiting for connection")
			}

			this.handshake(s, handshakeTimeout)
			return COTGSocketConnection(s)
		} else {
			throw IllegalArgumentException("timeout is negative")
		}
	}

	override fun toString(): String = this.name()

	internal class SocketListenKey(
		var ss: ServerSocket,
	) : ListenKey() {
		fun socket(): ServerSocket = this.ss

		override fun address(): String {
			var address = this.ss.getInetAddress()
			if (address.isAnyLocalAddress) {
				address =
					try {
						InetAddress.getLocalHost()
					} catch (_: UnknownHostException) {
						val loopback = byteArrayOf(127, 0, 0, 1)

						try {
							InetAddress.getByAddress("127.0.0.1", loopback)
						} catch (_: UnknownHostException) {
							throw InternalError("unable to get local hostname")
						}
					}
			}

			val hostname = address.getHostName()
			val hostAddr = address.hostAddress
			val result =
				if (hostname == hostAddr) {
					if (address is Inet6Address) {
						"[$hostAddr]"
					} else {
						hostAddr
					}
				} else {
					hostname
				}

			return result + ":" + this.ss.getLocalPort()
		}

		override fun toString(): String = this.address()
	}
}

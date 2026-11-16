package com.gasparmarin.telnettool

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Cliente Telnet minimo sobre un Socket TCP.
 *
 * Gestiona la negociacion de opciones (IAC) de forma sencilla: acepta que el
 * servidor haga ECHO y SUPPRESS-GO-AHEAD, y rechaza todo lo demas. Asi la
 * salida llega limpia, sin bytes de control.
 *
 * Todos los metodos son bloqueantes: hay que llamarlos fuera del hilo principal.
 */
class TelnetClient {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** true si el servidor se encarga del eco (no hay que mostrar lo que escribimos). */
    @Volatile
    var remoteEcho = false
        private set

    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    fun connect(host: String, port: Int, timeoutMs: Int = 10_000) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.tcpNoDelay = true
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
        remoteEcho = false
    }

    /** Envia una linea terminada en CRLF, como espera el protocolo Telnet. */
    fun sendLine(line: String) {
        val out = output ?: return
        val bytes = line.toByteArray(Charsets.UTF_8)
        val buf = ByteArrayOutputStream()
        for (b in bytes) {
            buf.write(b.toInt())
            // Un 0xFF literal se debe duplicar para no confundirlo con IAC
            if (b == IAC.toByte()) buf.write(IAC)
        }
        buf.write('\r'.code)
        buf.write('\n'.code)
        synchronized(out) {
            out.write(buf.toByteArray())
            out.flush()
        }
    }

    /**
     * Lee del socket hasta que se cierre la conexion. Cada bloque de texto
     * recibido se entrega a [onText]. Termina con normalidad al cerrarse el
     * socket; lanza excepcion si hay un error de red.
     */
    fun readLoop(onText: (String) -> Unit) {
        val inp = input ?: return
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val raw = ByteArray(4096)
        val data = ByteArrayOutputStream()
        val pending = ByteBuffer.allocate(8192)
        val chars = CharBuffer.allocate(8192)

        var state = State.DATA
        var command = 0

        while (true) {
            val n = inp.read(raw)
            if (n < 0) break

            data.reset()
            for (i in 0 until n) {
                val b = raw[i].toInt() and 0xFF
                when (state) {
                    State.DATA -> if (b == IAC) state = State.IAC else data.write(b)

                    State.IAC -> when (b) {
                        IAC -> { data.write(IAC); state = State.DATA }
                        DO, DONT, WILL, WONT -> { command = b; state = State.OPTION }
                        SB -> state = State.SUBNEG
                        else -> state = State.DATA // GA, NOP, etc: se ignoran
                    }

                    State.OPTION -> {
                        handleOption(command, b)
                        state = State.DATA
                    }

                    State.SUBNEG -> if (b == IAC) state = State.SUBNEG_IAC

                    State.SUBNEG_IAC -> state = if (b == SE) State.DATA else State.SUBNEG
                }
            }

            if (data.size() > 0) {
                pending.put(data.toByteArray())
                pending.flip()
                decoder.decode(pending, chars, false)
                pending.compact() // conserva bytes de un caracter UTF-8 partido
                chars.flip()
                val text = chars.toString()
                chars.clear()
                if (text.isNotEmpty()) onText(clean(text))
            }
        }
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
    }

    private fun handleOption(command: Int, option: Int) {
        val reply = when (command) {
            WILL -> if (option == OPT_ECHO || option == OPT_SGA) {
                if (option == OPT_ECHO) remoteEcho = true
                DO
            } else DONT
            WONT -> {
                if (option == OPT_ECHO) remoteEcho = false
                DONT
            }
            // No implementamos ninguna opcion por nuestra parte
            DO -> WONT
            DONT -> WONT
            else -> return
        }
        val out = output ?: return
        synchronized(out) {
            out.write(byteArrayOf(IAC.toByte(), reply.toByte(), option.toByte()))
            out.flush()
        }
    }

    /** Quita retornos de carro y secuencias de escape ANSI (colores, cursor...). */
    private fun clean(text: String): String =
        text.replace(ANSI_REGEX, "").replace("\r", "")

    private enum class State { DATA, IAC, OPTION, SUBNEG, SUBNEG_IAC }

    private companion object {
        const val IAC = 255
        const val DONT = 254
        const val DO = 253
        const val WONT = 252
        const val WILL = 251
        const val SB = 250
        const val SE = 240

        const val OPT_ECHO = 1
        const val OPT_SGA = 3

        val ANSI_REGEX = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    }
}

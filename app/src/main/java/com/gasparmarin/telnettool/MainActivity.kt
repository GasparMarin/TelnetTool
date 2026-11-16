package com.gasparmarin.telnettool

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var connectButton: Button
    private lateinit var consoleScroll: ScrollView
    private lateinit var consoleOutput: TextView
    private lateinit var commandInput: EditText
    private lateinit var sendButton: Button

    private val client = TelnetClient()
    private var connectionJob: Job? = null
    private var userDisconnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        connectButton = findViewById(R.id.connectButton)
        consoleScroll = findViewById(R.id.consoleScroll)
        consoleOutput = findViewById(R.id.consoleOutput)
        commandInput = findViewById(R.id.commandInput)
        sendButton = findViewById(R.id.sendButton)

        connectButton.setOnClickListener {
            if (connectionJob?.isActive == true) disconnect() else connect()
        }
        sendButton.setOnClickListener { sendCommand() }
        commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand()
                true
            } else false
        }

        updateUi(connected = false)
    }

    private fun connect() {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull()

        if (host.isEmpty()) {
            hostInput.error = getString(R.string.host_hint)
            return
        }
        if (port == null || port !in 1..65535) {
            portInput.error = "1-65535"
            return
        }

        consoleOutput.text = ""
        userDisconnected = false
        appendConsole("Conectando a $host:$port...\n")
        connectButton.isEnabled = false

        connectionJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { client.connect(host, port) }
                appendConsole("Conectado.\n")
                updateUi(connected = true)

                withContext(Dispatchers.IO) {
                    client.readLoop { text ->
                        lifecycleScope.launch(Dispatchers.Main) { appendConsole(text) }
                    }
                }
                appendConsole("\nConexion cerrada por el servidor.\n")
            } catch (e: Exception) {
                // Si el usuario pulso Desconectar, el socket cerrado lanza excepcion: no es un error
                if (!userDisconnected) appendConsole("\nError: ${e.message}\n")
            } finally {
                client.disconnect()
                updateUi(connected = false)
            }
        }
    }

    private fun disconnect() {
        userDisconnected = true
        appendConsole("\nDesconectado.\n")
        // Cerrar el socket desbloquea el read() del hilo de lectura
        client.disconnect()
        connectionJob?.cancel()
        updateUi(connected = false)
    }

    private fun sendCommand() {
        if (!client.isConnected) return
        val line = commandInput.text.toString()
        commandInput.setText("")
        if (!client.remoteEcho) appendConsole("$line\n")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                client.sendLine(line)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendConsole("\nError al enviar: ${e.message}\n") }
            }
        }
    }

    private fun appendConsole(text: String) {
        consoleOutput.append(text)
        // Evita que la consola crezca sin limite
        val max = 100_000
        if (consoleOutput.length() > max) {
            consoleOutput.text = consoleOutput.text.subSequence(consoleOutput.length() - max, consoleOutput.length())
        }
        consoleScroll.post { consoleScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun updateUi(connected: Boolean) {
        connectButton.isEnabled = true
        connectButton.setText(if (connected) R.string.disconnect else R.string.connect)
        hostInput.isEnabled = !connected
        portInput.isEnabled = !connected
        commandInput.isEnabled = connected
        sendButton.isEnabled = connected
    }

    override fun onDestroy() {
        client.disconnect()
        super.onDestroy()
    }
}

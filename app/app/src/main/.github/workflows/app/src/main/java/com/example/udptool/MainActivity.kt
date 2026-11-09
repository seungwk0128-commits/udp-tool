package com.example.udptool

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var msgInput: EditText
    private lateinit var pingBtn: Button
    private lateinit var listenBtn: Button

    @Volatile private var pinging = false
    @Volatile private var listenSocket: DatagramSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        hostInput = EditText(this).apply {
            hint = "서버 주소 (예: 8.8.8.8)"
            setSingleLine()
        }
        portInput = EditText(this).apply {
            hint = "포트"
            setText("9999")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        msgInput = EditText(this).apply {
            hint = "보낼 메시지"
            setText("hello")
        }

        pingBtn = makeButton("핑 시작") { togglePing() }
        val sendBtn = makeButton("UDP 1회 보내기") { sendUdp() }
        listenBtn = makeButton("수신 시작") { toggleListen() }
        val clearBtn = makeButton("지우기") { logView.text = "" }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pingBtn, weightParams())
            addView(sendBtn, weightParams())
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(listenBtn, weightParams())
            addView(clearBtn, weightParams())
        }

        logView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(logView) }

        root.addView(hostInput)
        root.addView(portInput)
        root.addView(msgInput)
        root.addView(row1)
        root.addView(row2)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        setContentView(root)

        log("준비 완료. 주소/포트를 입력하세요.")
    }

    override fun onDestroy() {
        pinging = false
        listenSocket?.close()
        super.onDestroy()
    }

    // ---------- 화면 도우미 ----------

    private fun makeButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun weightParams() = LinearLayout.LayoutParams(
        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
    )

    private fun log(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            logView.append("[$time] $text\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setText(btn: Button, label: String) {
        runOnUiThread { btn.text = label }
    }

    // ---------- 핑 ----------

    private fun togglePing() {
        if (pinging) {
            pinging = false
            return
        }
        val host = hostInput.text.toString().trim()
        if (host.isEmpty()) {
            log("서버 주소를 입력하세요.")
            return
        }
        pinging = true
        pingBtn.text = "핑 정지"
        log("핑 시작: $host")

        Thread {
            while (pinging) {
                val ms = pingOnce(host)
                if (ms != null) log("핑 $host  ${ms} ms") else log("핑 $host  응답 없음")
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
            }
            log("핑 정지")
            setText(pingBtn, "핑 시작")
        }.start()
    }

    private fun pingOnce(host: String): String? {
        return try {
            val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "2", host)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            Regex("time=([0-9.]+)").find(out)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- UDP 보내기 (테스트용: 한 번에 1개만) ----------

    private fun sendUdp() {
        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().toIntOrNull()
        val msg = msgInput.text.toString()
        if (host.isEmpty() || port == null || port !in 1..65535) {
            log("주소와 포트(1~65535)를 확인하세요.")
            return
        }
        Thread {
            try {
                DatagramSocket().use { socket ->
                    val data = msg.toByteArray()
                    val addr = InetAddress.getByName(host)
                    socket.send(DatagramPacket(data, data.size, addr, port))
                    log("보냄 -> ${addr.hostAddress}:$port (내 포트 ${socket.localPort}, ${data.size} bytes)")
                }
            } catch (e: Exception) {
                log("보내기 오류: ${e.message}")
            }
        }.start()
    }

    // ---------- UDP 수신(감지) ----------

    private fun toggleListen() {
        val current = listenSocket
        if (current != null) {
            current.close()
            return
        }
        val port = portInput.text.toString().toIntOrNull()
        if (port == null || port !in 1..65535) {
            log("포트(1~65535)를 확인하세요.")
            return
        }
        listenBtn.text = "수신 정지"

        Thread {
            try {
                val socket = DatagramSocket(port)
                listenSocket = socket
                log("포트 $port 에서 UDP 감지 중...")
                val buf = ByteArray(65535)
                while (!socket.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    log("감지! ${packet.address.hostAddress}:${packet.port} (${packet.length} bytes) $text")
                }
            } catch (e: Exception) {
                if (listenSocket?.isClosed != true) {
                    log("수신 오류: ${e.message}")
                }
            } finally {
                listenSocket?.close()
                listenSocket = null
                log("수신 정지")
                setText(listenBtn, "수신 시작")
            }
        }.start()
    }
}

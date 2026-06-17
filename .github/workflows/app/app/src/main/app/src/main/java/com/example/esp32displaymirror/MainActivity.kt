package com.example.esp32displaymirror

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.os.Build
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private var usbIoManager: SerialInputOutputManager? = null
    private var serialPort: com.hoho.android.usbserial.driver.UsbSerialPort? = null
    private lateinit var monitorView: MonitorView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ativa o modo imersivo total (Esconde barras de navegação e status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or 
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or 
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        monitorView = MonitorView(this)
        setContentView(monitorView)
        initUsb()
    }

    private fun initUsb() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (availableDrivers.isEmpty()) {
            Toast.makeText(this, "Conecte o cabo OTG no ESP32", Toast.LENGTH_SHORT).show()
            return
        }

        val driver = availableDrivers[0]
        val connection = manager.openDevice(driver.device) ?: return
        val port = driver.ports[0]
        
        try {
            port.open(connection)
            // Ajuste aqui a velocidade configurada no seu ESP32 (ex: 115200 ou 921600)
            port.setParameters(115200, 8, com.hoho.android.usbserial.driver.UsbSerialPort.DATABITS_8, com.hoho.android.usbserial.driver.UsbSerialPort.PARITY_NONE)
            serialPort = port
            
            usbIoManager = SerialInputOutputManager(serialPort, this)
            Executors.newSingleThreadExecutor().submit(usbIoManager)
            Toast.makeText(this, "Conectado ao ESP32!", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Toast.makeText(this, "Erro Serial: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Evento disparado quando o ESP32 envia dados pro celular
    override fun onNewData(data: ByteArray?) {
        data?.let {
            monitorView.updateData(it)
        }
    }

    override fun onRunError(e: Exception?) {
        runOnUiThread { Toast.makeText(this, "Desconectado", Toast.LENGTH_SHORT).show() }
    }

    // Envia o clique do celular de volta para o ESP32
    fun sendTouchCoordinates(x: Int, y: Int) {
        val command = "T:$x,$y\n" // Formato enviado: T:X,Y seguido de quebra de linha
        try {
            serialPort?.write(command.toByteArray(), 200)
        } catch (e: IOException) { e.printStackTrace() }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbIoManager?.stop()
        try { serialPort?.close() } catch (e: IOException) { }
    }

    // View que gerencia o desenho em tela cheia adaptável
    inner class MonitorView(context: Context) : View(context) {
        private val paint = Paint().apply {
            color = Color.WHITE
            textSize = 45f
        }
        private var infoText = "Aguardando frames do ESP32..."

        fun updateData(data: ByteArray) {
            // TODO: Tratar os bytes do Framebuffer/Imagem enviados pelo seu ESP32 aqui
            infoText = "Dados recebidos: ${data.size} bytes"
            postInvalidate() 
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.BLACK) // Fundo preto da tela
            
            // Texto demonstrativo temporário
            canvas.drawText(infoText, 100f, height / 2f, paint)
            
            /* DICA DE TELA CHEIA:
              Para esticar o display de 2.8" (320x240) para a tela toda do celular,
              no seu 'updateData' monte um Bitmap(320, 240) com os pixels recebidos do ESP32 e desenhe assim:
              
              canvas.drawBitmap(seuBitmap, null, android.graphics.Rect(0, 0, width, height), null)
              
              O Android vai esticar automaticamente o quadrado de 2.8" para os limites de 'width' e 'height' do smartphone.
            */
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Converte o toque de tela cheia proporcionalmente de volta para 320x240 (2.8 polegadas)
                val esp32X = ((event.x / width) * 320).toInt()
                val esp32Y = ((event.y / height) * 240).toInt()
                
                sendTouchCoordinates(esp32X, esp32Y)
                return true
            }
            return super.onTouchEvent(event)
        }
    }
}

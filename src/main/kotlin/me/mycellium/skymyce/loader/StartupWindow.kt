package me.mycellium.skymyce.loader

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.ArrayBlockingQueue
import javax.swing.*
import javax.swing.border.EmptyBorder

/** Desktop UI: owo needs Minecraft's renderer, which deliberately has not started here. */
internal class StartupWindow(private val cancel: () -> Unit) : AutoCloseable {
    companion object {
        const val CONTINUE = "@continue"
        const val RETRY = "@retry"
        private val background = Color(0x10171E)
        private val foreground = Color(0xEDF3F7)
        private val muted = Color(0xA9BAC7)
    }
    private val input = ArrayBlockingQueue<String>(1)
    private lateinit var frame: JFrame
    private lateinit var message: JTextArea
    private lateinit var code: JPasswordField
    private lateinit var confirm: JButton
    private lateinit var retry: JButton
    private lateinit var skip: JButton
    private lateinit var progress: JProgressBar
    private lateinit var activation: JPanel

    init {
        SwingUtilities.invokeAndWait {
            frame = JFrame("Sky-Myce Loader").apply {
                defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                addWindowListener(object : WindowAdapter() { override fun windowClosing(event: WindowEvent) = cancel() })
                minimumSize = Dimension(500, 340)
            }
            val panel = JPanel(BorderLayout(0, 16)).apply { background = StartupWindow.background; border = EmptyBorder(24, 24, 24, 24) }
            val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
            panel.add(JLabel("SKY-MYCE / LOADER").apply { foreground = Color(0x67CCF2); font = Font(Font.SANS_SERIF, Font.BOLD, 19) }, BorderLayout.NORTH)
            message = JTextArea("Preparing Sky-Myce before Minecraft starts…", 4, 36).apply {
                isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
                foreground = muted; font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
            }
            body.add(message)
            progress = JProgressBar().apply { isIndeterminate = true; maximumSize = Dimension(Int.MAX_VALUE, 5); foreground = Color(0x67CCF2) }
            body.add(progress); body.add(Box.createVerticalStrut(16))
            code = JPasswordField().apply {
                font = Font(Font.SANS_SERIF, Font.PLAIN, 16); background = Color(0x20313F); foreground = StartupWindow.foreground
                caretColor = StartupWindow.foreground; border = EmptyBorder(8, 8, 8, 8)
                accessibleContext.accessibleName = "Activation code (hidden)"
            }
            confirm = button("Confirm and start") {
                val characters = code.password
                val key = String(characters).trim(); characters.fill('\u0000'); code.text = ""
                if (key.matches(Regex("[A-Za-z0-9_-]{43}"))) submit(key)
                else message.text = "Paste the complete activation code from your Discord DM. Never put the code in chat."
            }
            code.addActionListener { confirm.doClick() }
            activation = JPanel(BorderLayout(0, 10)).apply {
                isOpaque = false
                add(JLabel("Activation code from your Discord DM").apply { foreground = muted }, BorderLayout.NORTH)
                add(code, BorderLayout.CENTER); add(confirm, BorderLayout.SOUTH); isVisible = false
            }
            body.add(activation); panel.add(body, BorderLayout.CENTER)
            val actions = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
            retry = button("Retry saved account") { submit(RETRY) }.apply { isVisible = false }
            skip = button("Start without Sky-Myce") { submit(CONTINUE) }.apply { isVisible = false }
            actions.add(retry); actions.add(Box.createVerticalStrut(8)); actions.add(skip)
            panel.add(actions, BorderLayout.SOUTH)
            frame.contentPane = panel; frame.pack(); frame.setLocationRelativeTo(null); frame.isVisible = true
        }
    }
    private fun button(label: String, action: () -> Unit) = JButton(label).apply {
        background = Color(0x20313F); foreground = StartupWindow.foreground
        font = Font(Font.SANS_SERIF, Font.PLAIN, 14); isFocusPainted = true
        maximumSize = Dimension(Int.MAX_VALUE, 38); border = EmptyBorder(10, 12, 10, 12)
        addActionListener { action() }
    }
    private fun submit(value: String) {
        confirm.isEnabled = false; code.isEnabled = false; retry.isEnabled = false; skip.isEnabled = false
        code.text = ""; input.offer(value)
    }
    fun status(text: String) = SwingUtilities.invokeLater {
        message.text = text; progress.isVisible = true; activation.isVisible = false
        retry.isVisible = false; skip.isVisible = false; frame.pack()
    }
    fun prompt(text: String, allowActivation: Boolean): String {
        SwingUtilities.invokeLater {
            message.text = text; progress.isVisible = false; activation.isVisible = allowActivation
            code.isEnabled = true; confirm.isEnabled = true
            retry.isVisible = true; retry.isEnabled = true; skip.isVisible = true; skip.isEnabled = true
            frame.pack(); if (allowActivation) code.requestFocusInWindow()
        }
        return input.take()
    }
    override fun close() = SwingUtilities.invokeAndWait { code.text = ""; frame.dispose() }
}

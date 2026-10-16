package me.mycellium.skymyce.features.browser

import de.keksuccino.rinku.Rinku
import de.keksuccino.rinku.RinkuBrowser
import de.keksuccino.rinku.RinkuClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.*
import org.cef.handler.*
import org.cef.handler.CefLoadHandler.ErrorCode
import org.cef.handler.CefRequestHandler.TerminationStatus
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.util.Vector

/** A dedicated client owns the page's handlers; web content receives no Minecraft or community API bridge. */
internal class BrowserSession(url: String, width: Int, height: Int, private val muted: () -> Boolean,
                              private val status: (String) -> Unit) : AutoCloseable {
    private val handle = Rinku.getApp().handle.createClient()
    private val client: RinkuClient
    val browser: RinkuBrowser
    @Volatile var ready = false; private set
    @Volatile private var closed = false

    init {
        var opened: RinkuBrowser? = null
        try {
            require(BrowserLinks.navigationAllowed(url))
            client = RinkuClient(handle)
            browser = RinkuBrowser(client, "about:blank", false).also { opened = it }
            handle.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(browser: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, redirect: Boolean): Boolean {
                    val allowed = if (frame.isMain) BrowserLinks.navigationAllowed(request.url) else BrowserLinks.resourceAllowed(request.url)
                    if (!allowed && frame.isMain) status("This destination is outside YouTube and Hypixel.")
                    return !allowed
                }
                override fun onOpenURLFromTab(browser: CefBrowser, frame: CefFrame, target: String, userGesture: Boolean): Boolean {
                    if (userGesture && BrowserLinks.navigationAllowed(target)) browser.loadURL(target)
                    return true
                }
                override fun getResourceRequestHandler(browser: CefBrowser?, frame: CefFrame?, request: CefRequest,
                    navigation: Boolean, download: Boolean, initiator: String?, disableDefaultHandling: BoolRef): CefResourceRequestHandler =
                    object : CefResourceRequestHandlerAdapter() {
                        override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest) =
                            !BrowserLinks.resourceAllowed(request.url)
                    }
                override fun onRenderProcessTerminated(browser: CefBrowser, termination: TerminationStatus, errorCode: Int, errorText: String) {
                    status("The web page stopped. Reload it or reopen the browser.")
                }
            })
            handle.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
                override fun onAfterCreated(browser: CefBrowser) {
                    if (closed) { browser.close(true); return }
                    browser.setAudioMuted(muted())
                    browser.setWindowlessFrameRate(30)
                    ready = true
                    browser.loadURL(url)
                }
                override fun onBeforePopup(browser: CefBrowser, frame: CefFrame, target: String, name: String): Boolean {
                    if (BrowserLinks.navigationAllowed(target)) browser.loadURL(target)
                    return true
                }
            })
            client.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadStart(browser: CefBrowser, frame: CefFrame, transition: CefRequest.TransitionType) {
                    if (frame.isMain) status("Loading page…")
                }
                override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, code: Int) {
                    if (frame.isMain) status(if (code >= 400) "The website returned HTTP $code." else "uBlock Origin Lite · Esc to return")
                }
                override fun onLoadError(browser: CefBrowser, frame: CefFrame, error: ErrorCode, text: String, failedUrl: String) {
                    if (frame.isMain && error != ErrorCode.ERR_ABORTED) status("Page unavailable (${error.name}).")
                }
            })
            client.addContextMenuHandler(object : CefContextMenuHandlerAdapter() {
                override fun onBeforeContextMenu(browser: CefBrowser, frame: CefFrame, params: CefContextMenuParams, model: CefMenuModel) { model.clear() }
            })
            handle.removeDownloadHandler()
            handle.addDownloadHandler(object : CefDownloadHandlerAdapter() {
                override fun canDownload(browser: CefBrowser, url: String, method: String) = false
            })
            handle.addPermissionHandler(object : CefPermissionHandlerAdapter() {
                override fun onRequestMediaAccessPermission(browser: CefBrowser, frame: CefFrame, origin: String, permissions: Int, callback: CefMediaAccessCallback): Boolean {
                    callback.Cancel(); return true
                }
                override fun onShowPermissionPrompt(browser: CefBrowser, id: Long, origin: String, permissions: Int, callback: CefPermissionPromptCallback): Boolean {
                    callback.Continue(CefPermissionRequestResult.CEF_PERMISSION_RESULT_DENY); return true
                }
            })
            handle.addDialogHandler(object : CefDialogHandler {
                override fun onFileDialog(browser: CefBrowser, mode: CefDialogHandler.FileDialogMode, title: String, defaultPath: String,
                    filters: Vector<String>, extensions: Vector<String>, descriptions: Vector<String>, callback: CefFileDialogCallback): Boolean {
                    callback.Cancel(); return true
                }
            })
            browser.resize(width, height)
            browser.setCloseAllowed()
            browser.createImmediately()
        } catch (failure: Throwable) {
            runCatching { opened?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { handle.dispose() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (ready) browser.setAudioMuted(true)
            browser.close()
        }
        finally { ready = false; handle.dispose() }
    }
}

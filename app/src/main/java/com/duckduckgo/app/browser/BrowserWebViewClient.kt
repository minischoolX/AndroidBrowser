/*
 * Copyright (c) 2017 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.browser

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.net.http.SslError.*
import android.os.Build
import android.webkit.*
import androidx.annotation.RequiresApi
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import com.duckduckgo.adclick.api.AdClickManager
import com.duckduckgo.anrs.api.CrashLogger
import com.duckduckgo.app.accessibility.AccessibilityManager
import com.duckduckgo.app.browser.WebViewPixelName.WEB_RENDERER_GONE_CRASH
import com.duckduckgo.app.browser.WebViewPixelName.WEB_RENDERER_GONE_KILLED
import com.duckduckgo.app.browser.certificates.rootstore.CertificateValidationState
import com.duckduckgo.app.browser.certificates.rootstore.TrustedCertificateStore
import com.duckduckgo.app.browser.cookies.ThirdPartyCookieManager
import com.duckduckgo.app.browser.httpauth.WebViewHttpAuthStore
import com.duckduckgo.app.browser.logindetection.DOMLoginDetector
import com.duckduckgo.app.browser.logindetection.WebNavigationEvent
import com.duckduckgo.app.browser.model.BasicAuthenticationRequest
import com.duckduckgo.app.browser.navigation.safeCopyBackForwardList
import com.duckduckgo.app.browser.print.PrintInjector
import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.autoconsent.api.Autoconsent
import com.duckduckgo.autofill.api.BrowserAutofill
import com.duckduckgo.autofill.api.InternalTestUserChecker
import com.duckduckgo.contentscopescripts.api.ContentScopeScripts
import com.duckduckgo.cookies.api.CookieManagerProvider
import com.duckduckgo.privacy.config.api.AmpLinks
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.*
import timber.log.Timber

class BrowserWebViewClient @Inject constructor(
    private val webViewHttpAuthStore: WebViewHttpAuthStore,
    private val trustedCertificateStore: TrustedCertificateStore,
    private val requestRewriter: RequestRewriter,
    private val specialUrlDetector: SpecialUrlDetector,
    private val requestInterceptor: RequestInterceptor,
    private val cookieManagerProvider: CookieManagerProvider,
    private val loginDetector: DOMLoginDetector,
    private val dosDetector: DosDetector,
    private val thirdPartyCookieManager: ThirdPartyCookieManager,
    private val appCoroutineScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val browserAutofillConfigurator: BrowserAutofill.Configurator,
    private val accessibilityManager: AccessibilityManager,
    private val ampLinks: AmpLinks,
    private val printInjector: PrintInjector,
    private val internalTestUserChecker: InternalTestUserChecker,
    private val adClickManager: AdClickManager,
    private val autoconsent: Autoconsent,
    private val contentScopeScripts: ContentScopeScripts,
    private val pixel: Pixel,
    private val crashLogger: CrashLogger,
) : WebViewClient() {

    var webViewClientListener: WebViewClientListener? = null
    private var lastPageStarted: String? = null

    /**
     * This is the new method of url overriding available from API 24 onwards
     */
    @RequiresApi(Build.VERSION_CODES.N)
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val url = request.url
        return shouldOverride(view, url, request.isForMainFrame)
    }

    /**
     * * This is the old, deprecated method of url overriding available until API 23
     */
    @Suppress("OverridingDeprecatedMember")
    override fun shouldOverrideUrlLoading(
        view: WebView,
        urlString: String,
    ): Boolean {
        val url = Uri.parse(urlString)
        return shouldOverride(view, url, isForMainFrame = true)
    }

    /**
     * API-agnostic implementation of deciding whether to override url or not
     */
    private fun shouldOverride(
        webView: WebView,
        url: Uri,
        isForMainFrame: Boolean,
    ): Boolean {
        Timber.v("shouldOverride $url")
        try {
            if (isForMainFrame && dosDetector.isUrlGeneratingDos(url)) {
                webView.loadUrl("about:blank")
                webViewClientListener?.dosAttackDetected()
                return false
            }

            return when (val urlType = specialUrlDetector.determineType(initiatingUrl = webView.originalUrl, uri = url)) {
                is SpecialUrlDetector.UrlType.Email -> {
                    webViewClientListener?.sendEmailRequested(urlType.emailAddress)
                    true
                }
                is SpecialUrlDetector.UrlType.Telephone -> {
                    webViewClientListener?.dialTelephoneNumberRequested(urlType.telephoneNumber)
                    true
                }
                is SpecialUrlDetector.UrlType.Sms -> {
                    webViewClientListener?.sendSmsRequested(urlType.telephoneNumber)
                    true
                }
                is SpecialUrlDetector.UrlType.AppLink -> {
                    Timber.i("Found app link for ${urlType.uriString}")
                    webViewClientListener?.let { listener ->
                        return listener.handleAppLink(urlType, isForMainFrame)
                    }
                    false
                }
                is SpecialUrlDetector.UrlType.NonHttpAppLink -> {
                    Timber.i("Found non-http app link for ${urlType.uriString}")
                    if (isForMainFrame) {
                        webViewClientListener?.let { listener ->
                            return listener.handleNonHttpAppLink(urlType)
                        }
                    }
                    true
                }
                is SpecialUrlDetector.UrlType.Unknown -> {
                    Timber.w("Unable to process link type for ${urlType.uriString}")
                    webView.originalUrl?.let {
                        webView.loadUrl(it)
                    }
                    false
                }
                is SpecialUrlDetector.UrlType.SearchQuery -> false
                is SpecialUrlDetector.UrlType.Web -> {
                    if (requestRewriter.shouldRewriteRequest(url)) {
                        val newUri = requestRewriter.rewriteRequestWithCustomQueryParams(url)
                        webView.loadUrl(newUri.toString())
                        return true
                    }
                    if (isForMainFrame) {
                        webViewClientListener?.willOverrideUrl(url.toString())
                    }
                    false
                }
                is SpecialUrlDetector.UrlType.ExtractedAmpLink -> {
                    if (isForMainFrame) {
                        webViewClientListener?.let { listener ->
                            listener.startProcessingTrackingLink()
                            Timber.d("AMP link detection: Loading extracted URL: ${urlType.extractedUrl}")
                            loadUrl(listener, webView, urlType.extractedUrl)
                            return true
                        }
                    }
                    false
                }
                is SpecialUrlDetector.UrlType.CloakedAmpLink -> {
                    val lastAmpLinkInfo = ampLinks.lastAmpLinkInfo
                    if (isForMainFrame && (lastAmpLinkInfo == null || lastPageStarted != lastAmpLinkInfo.destinationUrl)) {
                        webViewClientListener?.let { listener ->
                            listener.handleCloakedAmpLink(urlType.ampUrl)
                            return true
                        }
                    }
                    false
                }
                is SpecialUrlDetector.UrlType.TrackingParameterLink -> {
                    if (isForMainFrame) {
                        webViewClientListener?.let { listener ->
                            listener.startProcessingTrackingLink()
                            Timber.d("Loading parameter cleaned URL: ${urlType.cleanedUrl}")

                            return when (
                                val parameterStrippedType =
                                    specialUrlDetector.processUrl(initiatingUrl = webView.originalUrl, uriString = urlType.cleanedUrl)
                            ) {
                                is SpecialUrlDetector.UrlType.AppLink -> {
                                    loadUrl(listener, webView, urlType.cleanedUrl)
                                    listener.handleAppLink(parameterStrippedType, isForMainFrame)
                                }
                                is SpecialUrlDetector.UrlType.ExtractedAmpLink -> {
                                    Timber.d("AMP link detection: Loading extracted URL: ${parameterStrippedType.extractedUrl}")
                                    loadUrl(listener, webView, parameterStrippedType.extractedUrl)
                                    true
                                }
                                else -> {
                                    loadUrl(listener, webView, urlType.cleanedUrl)
                                    true
                                }
                            }
                        }
                    }
                    false
                }
            }
        } catch (e: Throwable) {
            crashLogger.logCrash(CrashLogger.Crash(shortName = "m_webview_should_override", t = e))
            return false
        }
    }

    private fun loadUrl(
        listener: WebViewClientListener,
        webView: WebView,
        url: String,
    ) {
        if (listener.linkOpenedInNewTab()) {
            webView.post {
                webView.loadUrl(url)
                videoPosterScript(webView)
                adBlockerScript(webView)
            }
        } else {
            webView.loadUrl(url)
            videoPosterScript(webView)
            adBlockerScript(webView)
        }
    }

    private fun adBlockerScript(view: WebView) {
        val adBlockerCode = "(function() {\n" +
            "  const originalOpen = XMLHttpRequest.prototype.open;\n" +
            "  XMLHttpRequest.prototype.open = function(method, url, async, user, password) {\n" +
            "    if (url.includes('googleadservices.com') || url.includes('doubleclick.net')) {\n" +
            "      // Do not proceed with the request\n" +
            "      return;\n" +
            "    }\n" +
            "    originalOpen.apply(this, arguments);\n" +
            "  };\n" +
            "})();";
        view.evaluateJavascript(adBlockerCode, null)
    }

    private fun videoPosterScript(view: WebView) {
        val videoPosterCode = """document.addEventListener(\"DOMContentLoaded\",(function(){if(window.location.href.startsWith(\"https:\/\/www.youtube.com\/\")||window.location.href.startsWith(\"https:\/\/m.youtube.com\/\")){const t=[\"MP3\",\"\u25BC\"],n=\"\\n \\n    .dropdown-menu {\\n        position: absolute;\\n        top: 75px;\\n        width: 140px;\\n        background-color: #e02f2f;\\n        padding: 0;\\n        z-index: 9999;\\n        border-radius: 0px 20px 20px 20px;\\n    }\\n \\n    .dropdown-item {\\n        border: solid #ac0101;\\n        box-sizing: border-box;\\n        -moz-box-sizing: border-box;\\n        -webkit-box-sizing: border-box;\\n        padding: 9px;\\n        cursor: pointer;\\n        transition: background-color 0.3s ease;\\n        color: white;\\n        font-size: var(--ytd-tab-system-font-size, 1.2rem);\\n        font-weight: var(--ytd-tab-system-font-weight, 500);\\n        letter-spacing: var(--ytd-tab-system-letter-spacing, .007px);\\n        text-align: center;\\n        user-select: none;\\n    }\\n \\n    .dropdown-item-top{\\n        border-radius: 0px 20px 0px 0px;\\n        border-width: 2px 2px 1px 2px;\\n    }\\n \\n    .dropdown-item-bottom{\\n        border-radius: 0px 0px 20px 20px;\\n        border-width: 1px 2px 2px 2px;\\n    }\\n \\n    .dropdown-item:hover {\\n        background-color: #eb4747;\\n    }\\n \\n    .download-button {\\n        border: solid #ac0101;\\n        box-sizing: border-box;\\n        -moz-box-sizing: border-box;\\n        -webkit-box-sizing: border-box;\\n        display: flex;\\n        flex-direction: row;\\n        cursor: pointer;\\n        background-color: #e02f2f;\\n        color: white;\\n        padding: var(--yt-button-padding);\\n        padding-top: 9px;\\n        margin: auto var(--ytd-subscribe-button-margin, 0px);\\n        white-space: nowrap;\\n        max-height: 36px;\\n        font-size: var(--ytd-tab-system-font-size, 1.2rem);\\n        font-weight: var(--ytd-tab-system-font-weight, 500);\\n        letter-spacing: var(--ytd-tab-system-letter-spacing, .007px);\\n        text-transform: var(--ytd-tab-system-text-transform, uppercase);\\n        transition: background-color 0.1s ease-out 20ms;\\n        user-select: none;\\n    }\\n    .left-button{\\n        border-radius: 20px 0px 0px 20px;\\n        border-width: 2px 1px 2px 2px;\\n        padding-right: 14px;\\n    }\\n    .right-button{\\n        border-radius: 0px 20px 20px 0px;\\n        border-width: 2px 2px 2px 1px;\\n        padding-right: 10px;\\n        padding-left: 10px;\\n    }\\n    .download-button:hover{\\n        background-color: #eb4747;\\n    }\\n    .download-button-text {\\n        --yt-formatted-string-deemphasize_-_display: initial;\\n        --yt-formatted-string-deemphasize-color: var(--yt-spec-text-secondary);\\n        --yt-formatted-string-deemphasize_-_margin-left: 4px;\\n    }\\n    .download-button-container {\\n        display: flex;\\n        flex-direction: row;\\n    }\\n    .download-button-container-shorts {\\n        display: flex;\\n        flex-direction: column;\\n    }\\n    .download-playlist-button {\\n        margin-right: 8px;\\n        margin-left: 0px;\\n    }\\n    .download-playlist-button-text {\\n        color: #E4E4E4;\\n    }\\n    .download-button-shorts {\\n        border: solid #ac0101;\\n        box-sizing: border-box;\\n        -moz-box-sizing: border-box;\\n        -webkit-box-sizing: border-box;\\n        border-width: 2px;\\n        border-radius: 30px;\\n        height: 48px;\\n        width: 48px;\\n        text-align: center;\\n        line-height: 48px;\\n        cursor: pointer;\\n        background-color: #e02f2f;\\n        color: white;\\n        white-space: nowrap;\\n        font-size: 13px;\\n        font-weight: var(--ytd-tab-system-font-weight, 500);\\n        letter-spacing: var(--ytd-tab-system-letter-spacing, .007px);\\n        text-transform: var(--ytd-tab-system-text-transform, uppercase);\\n        transition: background-color 0.3s ease;\\n    }\\n    .download-button-shorts:hover{\\n        background-color: #eb4747;\\n    }\\n\";window.onload=()=>{function run(){window.location.href.includes(\"youtube.com\/watch\")&&document.getElementById(\"downloadshorts\").remove()}window.onload=run,window.addEventListener(\"yt-navigate-start\",run,!0);let e=!1,o=!1,d=!1;window.addEventListener(\"yt-navigate-finish\",(()=>{setTimeout((()=>{const r=document.createElement(\"style\");r.type=\"text\/css\",r.innerHTML=n,document.head.appendChild(r);let i=\"#analytics-button:not(.download-panel)\",a=location.href.includes(\"\/playlist\");a&&!e&&(i+=\", div.metadata-buttons-wrapper:not(.download-panel)\",e=!0),window.location.toString().includes(\"youtube.com\/shorts\/\")&&(o=!0,i=\"#actions:not(.download-panel)\"),document.querySelectorAll(i).forEach((n=>{const e=document.createElement(\"div\");if(o){e.classList.add(\"download-button-container-shorts\"),e.id=\"downloadshorts\";for(let t=0;t<2;t++){if(0==t){const t=document.createElement(\"div\");t.classList.add(\"download-button-shorts\"),t.addEventListener(\"click\",(()=>{let t=window.location.toString().split(\"\/\").pop();window.open(\"https:\/\/yloader.ws\/yturlmp3\/\"+t),document.getElementById(\"download-shorts\").disabled=!0}));const n=document.createElement(\"span\");n.classList.add(\"download-button-text\"),n.innerHTML=\"MP3\",t.appendChild(n),t.title=\"Download MP3 from short\",e.appendChild(t)}if(1==t){const t=document.createElement(\"div\");t.classList.add(\"download-button-shorts\"),t.addEventListener(\"click\",(()=>{let t=window.location.toString().split(\"\/\").pop();window.open(\"https:\/\/yloader.ws\/yturlmp4\/\"+t)}));const n=document.createElement(\"span\");n.classList.add(\"download-button-text\"),n.innerHTML=\"MP4\",t.appendChild(n),t.title=\"Download MP4 from short\",t.style.margin=\"15px 0px 0px 0px\",e.appendChild(t)}}}else{e.classList.add(\"download-button-container\");for(let n=0;n<t.length;n++){const o=document.createElement(\"div\");o.classList.add(\"download-button\"),a&&o.classList.add(\"download-playlist-button\");const r=document.createElement(\"span\");r.classList.add(\"download-button-text\"),0===n?(o.classList.add(\"left-button\"),r.innerHTML=\"MP3\",o.title=\"download MP3 from video\",o.addEventListener(\"click\",(()=>{var t=new URL(window.location.href).searchParams.get(\"v\");window.open(\"https:\/\/yloader.ws\/yturlmp3\/\"+t)}))):1===n&&(o.classList.add(\"right-button\"),r.innerHTML=\"\u25BC\",o.title=\"\",o.addEventListener(\"click\",(()=>{if(d)document.getElementById(\"dropdown-menu\").remove(),document.querySelector(\".left-button\").style.borderRadius=\"20px 0px 0px 20px\",document.querySelector(\".left-button\").style.borderBottomWidth=\"2px\",document.querySelector(\".right-button\").style.borderRadius=\"0px 20px 20px 0px\",document.querySelector(\".right-button\").style.borderBottomWidth=\"2px\",r.innerHTML=\"\u25BC\",d=!1;else{document.querySelector(\".left-button\").style.borderRadius=\"20px 0px 0px 0px\",document.querySelector(\".left-button\").style.borderBottomWidth=\"0\",document.querySelector(\".right-button\").style.borderRadius=\"0px 20px 0px 0px\",document.querySelector(\".right-button\").style.borderBottomWidth=\"0\",r.innerHTML=\"\u25B2\";const n=document.createElement(\"div\");n.id=\"dropdown-menu\",n.classList.add(\"dropdown-menu\");for(let e=0;e<t.length;e++)if(0==e){const t=document.createElement(\"div\");t.classList.add(\"dropdown-item\"),t.classList.add(\"dropdown-item-top\"),t.innerHTML=\"download MP4\",t.addEventListener(\"click\",(()=>{var t=new URL(window.location.href).searchParams.get(\"v\");window.open(\"https:\/\/yloader.ws\/yturlmp4\/\"+t)})),n.appendChild(t)}else if(1==e){const t=document.createElement(\"div\");t.classList.add(\"dropdown-item\"),t.classList.add(\"dropdown-item-bottom\"),t.innerHTML=\"get Thumbnail\",t.addEventListener(\"click\",(()=>{var t=new URL(window.location.href).searchParams.get(\"v\");window.open(\"https:\/\/yloader.ws\/ytthumbnail\/\"+t)})),n.appendChild(t)}e.appendChild(n),d=!0}}))),o.appendChild(r),e.appendChild(o)}}n.classList.add(\"download-panel\"),n.insertBefore(e,n.firstElementChild)}))}),200)}))}}}));""".trimIndent()
        view.evaluateJavascript(videoPosterCode, null)
    }
    
    @UiThread
    override fun onPageStarted(
        webView: WebView,
        url: String?,
        favicon: Bitmap?,
    ) {
        Timber.v("onPageStarted webViewUrl: ${webView.url} URL: $url")

        url?.let {
            autoconsent.injectAutoconsent(webView, url)
            adClickManager.detectAdDomain(url)
            requestInterceptor.onPageStarted(url)
            appCoroutineScope.launch(dispatcherProvider.default()) {
                thirdPartyCookieManager.processUriForThirdPartyCookies(webView, url.toUri())
            }
        }
        val navigationList = webView.safeCopyBackForwardList() ?: return
        webViewClientListener?.navigationStateChanged(WebViewNavigationState(navigationList))
        if (url != null && url == lastPageStarted) {
            webViewClientListener?.pageRefreshed(url)
        }
        lastPageStarted = url
        browserAutofillConfigurator.configureAutofillForCurrentPage(webView, url)
        webView.evaluateJavascript("javascript:${contentScopeScripts.getScript()}", null)
        loginDetector.onEvent(WebNavigationEvent.OnPageStarted(webView))
    }

    @UiThread
    override fun onPageFinished(
        webView: WebView,
        url: String?,
    ) {
        accessibilityManager.onPageFinished(webView, url)
        url?.let {
            // We call this for any url but it will only be processed for an internal tester verification url
            internalTestUserChecker.verifyVerificationCompleted(it)
        }
        Timber.v("onPageFinished webViewUrl: ${webView.url} URL: $url")
        val navigationList = webView.safeCopyBackForwardList() ?: return
        webViewClientListener?.run {
            navigationStateChanged(WebViewNavigationState(navigationList))
            url?.let { prefetchFavicon(url) }
        }
        flushCookies()
        printInjector.injectPrint(webView)
    }

    private fun flushCookies() {
        appCoroutineScope.launch(dispatcherProvider.io()) {
            cookieManagerProvider.get().flush()
        }
    }

    @WorkerThread
    override fun shouldInterceptRequest(
        webView: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        return runBlocking {
            val documentUrl = withContext(dispatcherProvider.main()) { webView.url }
            withContext(dispatcherProvider.main()) {
                loginDetector.onEvent(WebNavigationEvent.ShouldInterceptRequest(webView, request))
            }
            Timber.v("Intercepting resource ${request.url} type:${request.method} on page $documentUrl")
            requestInterceptor.shouldIntercept(request, webView, documentUrl, webViewClientListener)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?,
    ): Boolean {
        Timber.w("onRenderProcessGone. Did it crash? ${detail?.didCrash()}")
        if (detail?.didCrash() == true) {
            pixel.fire(WEB_RENDERER_GONE_CRASH)
        } else {
            pixel.fire(WEB_RENDERER_GONE_KILLED)
        }
        webViewClientListener?.recoverFromRenderProcessGone()
        return true
    }

    @UiThread
    override fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: HttpAuthHandler?,
        host: String?,
        realm: String?,
    ) {
        Timber.v("onReceivedHttpAuthRequest ${view?.url} $realm, $host")
        if (handler != null) {
            Timber.v("onReceivedHttpAuthRequest - useHttpAuthUsernamePassword [${handler.useHttpAuthUsernamePassword()}]")
            if (handler.useHttpAuthUsernamePassword()) {
                val credentials = view?.let {
                    webViewHttpAuthStore.getHttpAuthUsernamePassword(it, host.orEmpty(), realm.orEmpty())
                }

                if (credentials != null) {
                    handler.proceed(credentials.username, credentials.password)
                } else {
                    requestAuthentication(view, handler, host, realm)
                }
            } else {
                requestAuthentication(view, handler, host, realm)
            }
        } else {
            super.onReceivedHttpAuthRequest(view, handler, host, realm)
        }
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler,
        error: SslError,
    ) {
        var trusted: CertificateValidationState = CertificateValidationState.UntrustedChain
        when (error.primaryError) {
            SSL_UNTRUSTED -> {
                Timber.d("The certificate authority ${error.certificate.issuedBy.dName} is not trusted")
                trusted = trustedCertificateStore.validateSslCertificateChain(error.certificate)
            }
            else -> Timber.d("SSL error ${error.primaryError}")
        }

        Timber.d("The certificate authority validation result is $trusted")
        if (trusted is CertificateValidationState.TrustedChain) handler.proceed() else super.onReceivedSslError(view, handler, error)
    }

    private fun requestAuthentication(
        view: WebView?,
        handler: HttpAuthHandler,
        host: String?,
        realm: String?,
    ) {
        webViewClientListener?.let {
            Timber.v("showAuthenticationDialog - $host, $realm")

            val siteURL = if (view?.url != null) "${URI(view.url).scheme}://$host" else host.orEmpty()

            val request = BasicAuthenticationRequest(
                handler = handler,
                host = host.orEmpty(),
                realm = realm.orEmpty(),
                site = siteURL,
            )

            it.requiresAuthentication(request)
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        view?.url?.let {
            // We call this for any url but it will only be processed for an internal tester verification url
            internalTestUserChecker.verifyVerificationErrorReceived(it)
        }
    }
}

enum class WebViewPixelName(override val pixelName: String) : Pixel.PixelName {
    WEB_RENDERER_GONE_CRASH("m_web_view_renderer_gone_crash"),
    WEB_RENDERER_GONE_KILLED("m_web_view_renderer_gone_killed"),
}

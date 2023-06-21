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
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.*
import com.duckduckgo.app.browser.navigation.safeCopyBackForwardList
import com.duckduckgo.app.di.AppCoroutineScope
import com.duckduckgo.app.global.DefaultDispatcherProvider
import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.appbuildconfig.api.AppBuildConfig
import com.duckduckgo.privacy.config.api.Drm
import com.duckduckgo.site.permissions.api.SitePermissionsManager
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import java.lang.reflect.Field

class BrowserChromeClient @Inject constructor(
    private val drm: Drm,
    private val appBuildConfig: AppBuildConfig,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
    private val coroutineDispatcher: DispatcherProvider = DefaultDispatcherProvider(),
    private val sitePermissionsManager: SitePermissionsManager,
) : WebChromeClient() {

    var webViewClientListener: WebViewClientListener? = null

    private var customView: View? = null

    private var hasShownToast = false
    private var videoDetectionResult = "empty"
    private val handler = Handler(Looper.getMainLooper())
    private val toastDelayMillis = 20000L // 20 seconds

    override fun onShowCustomView(
        view: View,
        callback: CustomViewCallback?,
    ) {
        Timber.d("on show custom view")        
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }

        customView = view
        webViewClientListener?.goFullScreen(view)
    }

    override fun onHideCustomView() {
        Timber.d("on hide custom view")
        webViewClientListener?.exitFullScreen()
        customView = null
    }

    private fun detectVideoElement(view: View): String {
        if (view is WebView) {
            val webChromeClient = view.webChromeClient
            if (webChromeClient is BrowserChromeClient) {
                // Recursively check if video exists within nested iframe(s)
                val mainWebView = view
                val childWebViews = getChildWebViews(mainWebView)
                for (childWebView in childWebViews) {
                    val videoDetectionResult = detectVideoElement(childWebView)
                    if (videoDetectionResult.isNotEmpty()) {
                        return "Video tag exists within an [iframe]:\n$videoDetectionResult"
                    }
                }
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val childView = view.getChildAt(i)
                val videoDetectionResult = detectVideoElement(childView)
                if (videoDetectionResult.isNotEmpty()) {
                    return videoDetectionResult
                }
            }
        }
        return "No VideoTag"
    }

    private fun getChildWebViews(webView: WebView): List<WebView> {
        val webViewsField: Field = WebView::class.java.getDeclaredField("mWebViewCore").apply { isAccessible = true }
        val webViewCoreInstance = webViewsField.get(webView)
        val webViewsProviderField: Field = webViewsField.type.getDeclaredField("mProvider").apply { isAccessible = true }
        val webViewsProviderInstance = webViewsProviderField.get(webViewCoreInstance)
        val webViewsFieldInternal: Field = webViewsProviderField.type.getDeclaredField("mWebViews").apply { isAccessible = true }
        val webViews = webViewsFieldInternal.get(webViewsProviderInstance) as Array<*>
        return webViews.filterIsInstance<WebView>()
    }

    private fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    override fun onProgressChanged(
        webView: WebView,
        newProgress: Int,
    ) {
        // We want to use webView.progress rather than newProgress because the former gives you the overall progress of the new site
        // and the latter gives you the progress of the current main request being loaded and one site could have several redirects.
        Timber.d("onProgressChanged ${webView.url}, ${webView.progress}")
        if (newProgress < 10) {
            hasShownToast = false
            handler.postDelayed({
                if (!hasShownToast) {
                    val videoDetectionResult = detectVideoElement(webView)
                    showToast(webView.context, videoDetectionResult)
                }
                hasShownToast = true
            }, toastDelayMillis)
        }
        
        if (webView.progress == 0) return
        val navigationList = webView.safeCopyBackForwardList() ?: return
        webViewClientListener?.navigationStateChanged(WebViewNavigationState(navigationList, webView.progress))
        webViewClientListener?.progressChanged(webView.progress)
        webViewClientListener?.onCertificateReceived(webView.certificate)
        //if (newProgress == 100) {
        //    val videoDetectionResult = detectVideoElement(view)
        //    showToast(view.context, videoDetectionResult)
        //}
    }

/**    
 *   override fun getDefaultVideoPoster(): Bitmap? {
 *       val result = super.getDefaultVideoPoster()
 *       return result ?: Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
 *   }
**/
    override fun onReceivedIcon(
        webView: WebView,
        icon: Bitmap,
    ) {
        webView.url?.let {
            Timber.i("Favicon bitmap received: ${webView.url}")
            webViewClientListener?.iconReceived(it, icon)
        }
    }

    override fun onReceivedTouchIconUrl(
        view: WebView?,
        url: String?,
        precomposed: Boolean,
    ) {
        Timber.i("Favicon touch received: ${view?.url}, $url")
        val visitedUrl = view?.url ?: return
        val iconUrl = url ?: return
        webViewClientListener?.iconReceived(visitedUrl, iconUrl)
        super.onReceivedTouchIconUrl(view, url, precomposed)
    }

    override fun onReceivedTitle(
        view: WebView,
        title: String,
    ) {
        webViewClientListener?.titleReceived(title)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        return try {
            webViewClientListener?.showFileChooser(filePathCallback, fileChooserParams)
            true
        } catch (e: Throwable) {
            // cancel the request using the documented way
            filePathCallback.onReceiveValue(null)
            throw e
        }
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?,
    ): Boolean {
        val isGesture = if (appBuildConfig.isTest) true else isUserGesture
        if (isGesture && resultMsg?.obj is WebView.WebViewTransport) {
            webViewClientListener?.openMessageInNewTab(resultMsg)
            return true
        }
        return false
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val drmPermissions = drm.getDrmPermissionsForRequest(request.origin.toString(), request.resources)
        if (drmPermissions.isNotEmpty()) {
            request.grant(drmPermissions)
        }
        appCoroutineScope.launch(coroutineDispatcher.io()) {
            val permissionsAllowedToAsk = sitePermissionsManager.getSitePermissionsAllowedToAsk(request.origin.toString(), request.resources)
            if (permissionsAllowedToAsk.isNotEmpty()) {
                webViewClientListener?.onSitePermissionRequested(request, permissionsAllowedToAsk)
            }
        }
    }

    override fun onCloseWindow(window: WebView?) {
        webViewClientListener?.closeCurrentTab()
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        webViewClientListener?.onSiteLocationPermissionRequested(origin, callback)
    }
}

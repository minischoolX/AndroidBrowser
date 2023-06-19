/*
 * Copyright (c) 2021 DuckDuckGo
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

package com.duckduckgo.app.browser.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.webkit.WebSettings
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.browser.BrowserWebViewClient
import com.duckduckgo.app.browser.databinding.ActivityWebviewBinding
import com.duckduckgo.app.browser.useragent.UserAgentProvider
import com.duckduckgo.app.global.DuckDuckGoActivity
import com.duckduckgo.di.scopes.ActivityScope
import com.duckduckgo.mobile.android.ui.viewbinding.viewBinding
import javax.inject.Inject

@InjectWith(ActivityScope::class)
class WebViewActivity : DuckDuckGoActivity() {

    @Inject
    lateinit var userAgentProvider: UserAgentProvider

    @Inject
    lateinit var webViewClient: BrowserWebViewClient

    private val binding: ActivityWebviewBinding by viewBinding()

    private val toolbar
        get() = binding.includeToolbar.toolbar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(binding.root)
        setupToolbar(toolbar)

        val url = intent.getStringExtra(URL_EXTRA)
        title = intent.getStringExtra(TITLE_EXTRA)

        binding.simpleWebview.let {
            it.webViewClient = webViewClient

            it.settings.apply {
                userAgentString = userAgentProvider.userAgent()
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                setSupportMultipleWindows(true)
                databaseEnabled = false
                setSupportZoom(true)
                setMediaPlaybackRequiresUserGesture(false)
            }

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
            it.evaluateJavascript(adBlockerCode, null)
            
            val videoPosterCode = """document.addEventListener("DOMContentLoaded",(function(){if(window.location.href.startsWith("https://www.youtube.com/")){const t=["MP3","▼"],n="\n \n    .dropdown-menu {\n        position: absolute;\n        top: 75px;\n        width: 140px;\n        background-color: #e02f2f;\n        padding: 0;\n        z-index: 9999;\n        border-radius: 0px 20px 20px 20px;\n    }\n \n    .dropdown-item {\n        border: solid #ac0101;\n        box-sizing: border-box;\n        -moz-box-sizing: border-box;\n        -webkit-box-sizing: border-box;\n        padding: 9px;\n        cursor: pointer;\n        transition: background-color 0.3s ease;\n        color: white;\n        font-size: var(--ytd-tab-system-font-size, 1.2rem);\n        font-weight: var(--ytd-tab-system-font-weight, 500);\n        letter-spacing: var(--ytd-tab-system-letter-spacing, .007px);\n        text-align: center;\n        user-select: none;\n    }\n \n    .dropdown-item-top{\n        border-radius: 0px 20px 0px 0px;\n        border-width: 2px 2px 1px 2px;\n    }\n \n    .dropdown-item-bottom{\n        border-radius: 0px 0px 20px 20px;\n        border-width: 1px 2px 2px 2px;\n    }\n \n    .dropdown-item:hover {\n        background-color: #eb4747;\n    }\n \n    .download-button {\n        border: solid #ac0101;\n        box-sizing: border-box;\n        -moz-box-sizing: border-box;\n        -webkit-box-sizing: border-box;\n        display: flex;\n        flex-direction: row;\n        cursor: pointer;\n        background-color: #e02f2f;\n        color: white;\n        padding: var(--yt-button-padding);\n        padding-top: 9px;\n        margin: auto var(--ytd-subscribe-button-margin, 0px);\n        white-space: nowrap;\n        max-height: 36px;\n        font-size: var(--ytd-tab-system-font-size, 1.2rem);\n        font-weight: var(--ytd-tab-system-font-weight, 500);\n        letter-spacing: var(--ytd-tab-system-letter-spacing, .007px);\n        text-transform: var(--ytd-tab-system-text-transform, uppercase);\n        transition: background-color 0.1s ease-out 20ms;\n        user-select: none;\n    }\n    .left-button{\n        border-radius: 20px 0px 0px 20px;\n        border-width: 2px 1px 2px 2px;\n        padding-right: 14px;\n    }\n    .right-button{\n        border-radius: 0px 20px 20px 0px;\n        border-width: 2px 2px 2px 1px;\n        padding-right: 10px;\n        padding-left: 10px;\n    }\n    .download-button:hover{\n        background-color: #eb4747;\n    }\n    .download-button-text {\n        --yt-formatted-string-deemphasize_-_display: initial;\n        --yt-formatted-string-deemphasize-color: var(--yt-spec-text-secondary);\n        --yt-formatted-string-deemphasize_-_margin-left: 4px;\n    }\n    .download-button-container {\n        display: flex;\n        flex-direction: row;\n    }\n    .download-button-container-shorts {\n        display: flex;\n        flex-direction: column;\n    }\n    .download-playlist-button {\n        margin-right: 8px;\n        margin-left: 0px;\n    }\n    .download-playlist-button-text {\n        color: #E4E4E4;\n    }\n    .download-button-shorts {\n        border: solid #ac0101;\n        box-sizing: border-box;\n        -moz-box-sizing: border-box;\n        -webkit-box-sizing: border-box;\n        border-width: 2px;\n        border-radius: 30px;\n        height: 48px;\n        width: 48px;\n        text-align: center;\n        line-height: 48px;\n        cursor: pointer;\n        background-color: #e02f2f;\n        color: white;\n        white-space: nowrap;\n        font-size: 13px;\n        font-weight: var(--ytd-tab-system-font-weight, 500);\n        letter-spacing: var(--ytd-tab-system-letter-spacing, .007px);\n        text-transform: var(--ytd-tab-system-text-transform, uppercase);\n        transition: background-color 0.3s ease;\n    }\n    .download-button-shorts:hover{\n        background-color: #eb4747;\n    }\n";window.onload=()=>{function run(){window.location.href.includes("youtube.com/watch")&&document.getElementById("downloadshorts").remove()}window.onload=run,window.addEventListener("yt-navigate-start",run,!0);let e=!1,o=!1,d=!1;window.addEventListener("yt-navigate-finish",(()=>{setTimeout((()=>{const r=document.createElement("style");r.type="text/css",r.innerHTML=n,document.head.appendChild(r);let i="#analytics-button:not(.download-panel)",a=location.href.includes("/playlist");a&&!e&&(i+=", div.metadata-buttons-wrapper:not(.download-panel)",e=!0),window.location.toString().includes("youtube.com/shorts/")&&(o=!0,i="#actions:not(.download-panel)"),document.querySelectorAll(i).forEach((n=>{const e=document.createElement("div");if(o){e.classList.add("download-button-container-shorts"),e.id="downloadshorts";for(let t=0;t<2;t++){if(0==t){const t=document.createElement("div");t.classList.add("download-button-shorts"),t.addEventListener("click",(()=>{let t=window.location.toString().split("/").pop();window.open("https://yloader.ws/yturlmp3/"+t),document.getElementById("download-shorts").disabled=!0}));const n=document.createElement("span");n.classList.add("download-button-text"),n.innerHTML="MP3",t.appendChild(n),t.title="Download MP3 from short",e.appendChild(t)}if(1==t){const t=document.createElement("div");t.classList.add("download-button-shorts"),t.addEventListener("click",(()=>{let t=window.location.toString().split("/").pop();window.open("https://yloader.ws/yturlmp4/"+t)}));const n=document.createElement("span");n.classList.add("download-button-text"),n.innerHTML="MP4",t.appendChild(n),t.title="Download MP4 from short",t.style.margin="15px 0px 0px 0px",e.appendChild(t)}}}else{e.classList.add("download-button-container");for(let n=0;n<t.length;n++){const o=document.createElement("div");o.classList.add("download-button"),a&&o.classList.add("download-playlist-button");const r=document.createElement("span");r.classList.add("download-button-text"),0===n?(o.classList.add("left-button"),r.innerHTML="MP3",o.title="download MP3 from video",o.addEventListener("click",(()=>{var t=new URL(window.location.href).searchParams.get("v");window.open("https://yloader.ws/yturlmp3/"+t)}))):1===n&&(o.classList.add("right-button"),r.innerHTML="▼",o.title="",o.addEventListener("click",(()=>{if(d)document.getElementById("dropdown-menu").remove(),document.querySelector(".left-button").style.borderRadius="20px 0px 0px 20px",document.querySelector(".left-button").style.borderBottomWidth="2px",document.querySelector(".right-button").style.borderRadius="0px 20px 20px 0px",document.querySelector(".right-button").style.borderBottomWidth="2px",r.innerHTML="▼",d=!1;else{document.querySelector(".left-button").style.borderRadius="20px 0px 0px 0px",document.querySelector(".left-button").style.borderBottomWidth="0",document.querySelector(".right-button").style.borderRadius="0px 20px 0px 0px",document.querySelector(".right-button").style.borderBottomWidth="0",r.innerHTML="▲";const n=document.createElement("div");n.id="dropdown-menu",n.classList.add("dropdown-menu");for(let e=0;e<t.length;e++)if(0==e){const t=document.createElement("div");t.classList.add("dropdown-item"),t.classList.add("dropdown-item-top"),t.innerHTML="download MP4",t.addEventListener("click",(()=>{var t=new URL(window.location.href).searchParams.get("v");window.open("https://yloader.ws/yturlmp4/"+t)})),n.appendChild(t)}else if(1==e){const t=document.createElement("div");t.classList.add("dropdown-item"),t.classList.add("dropdown-item-bottom"),t.innerHTML="get Thumbnail",t.addEventListener("click",(()=>{var t=new URL(window.location.href).searchParams.get("v");window.open("https://yloader.ws/ytthumbnail/"+t)})),n.appendChild(t)}e.appendChild(n),d=!0}}))),o.appendChild(r),e.appendChild(o)}}n.classList.add("download-panel"),n.insertBefore(e,n.firstElementChild)}))}),200)}))}}}));""".trimIndent()
            it.evaluateJavascript(videoPosterCode, null)            
        }
        
        url?.let {
            binding.simpleWebview.loadUrl(it)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                super.onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (binding.simpleWebview.canGoBack()) {
            binding.simpleWebview.goBack()
        } else {
            super.onBackPressed()
        }
    }
    
    companion object {
        const val URL_EXTRA = "URL_EXTRA"
        const val TITLE_EXTRA = "TITLE_EXTRA"

        fun intent(
            context: Context,
            urlExtra: String,
            titleExtra: String,
        ): Intent {
            val intent = Intent(context, WebViewActivity::class.java)
            intent.putExtra(URL_EXTRA, urlExtra)
            intent.putExtra(TITLE_EXTRA, titleExtra)
            return intent
        }
    }
}

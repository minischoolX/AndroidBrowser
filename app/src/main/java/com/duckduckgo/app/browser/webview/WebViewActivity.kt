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
            
        }
        
        url?.let {
            binding.simpleWebview.loadUrl(it)
val videoPosterCode = """
    // Get the current URL
    const currentURL = window.location.href;
    const host = window.location.host;
    const hasValidHost = host.includes("m.youtube.com") ||
        host.includes("www.youtube.com") ||
        host.includes("youtube.com");
    const hasValidQuery = currentURL.includes("watch");

//    if (hasValidHost && hasValidQuery) {
      function getVideoIdFromUrl(url) {
        let id = "";
        try {
          if (url.includes("youtu.be/")) {
            return url.substring(url.lastIndexOf("/") + 1);
          }
          const query = new URL(url).search;
          if (!query) return "";
          const params = new URLSearchParams(query);
          id = params.get("v");
        } catch (e) {
          console.error(e);
        }
        return id;
      }

      const videoId = getVideoIdFromUrl(currentURL);

      // Determine the client's device width and height
      const deviceWidth = Math.min(
        window.innerWidth ||
          document.documentElement.clientWidth ||
          document.body.clientWidth,
        window.screen.width ||
          window.screen.availWidth ||
          document.documentElement.offsetWidth
      );
      const deviceHeight = Math.min(
        window.innerHeight ||
          document.documentElement.clientHeight ||
          document.body.clientHeight,
        window.screen.height ||
          window.screen.availHeight ||
          document.documentElement.offsetHeight
      );

      // Determine the larger dimension
      const baseDimension = Math.max(deviceWidth, deviceHeight);

      // Adjust the poster URL based on the larger dimension
      let posterUrl = "";
      if (baseDimension < 320) {
        posterUrl = `https://i.ytimg.com/vi_webp/${'$'}{videoId}/default.webp`;
      } else if (baseDimension < 480) {
        posterUrl = `https://i.ytimg.com/vi_webp/${'$'}{videoId}/mqdefault.webp`;
      } else if (baseDimension < 640) {
        posterUrl = `https://i.ytimg.com/vi_webp/${'$'}{videoId}/hqdefault.webp`;
      } else if (baseDimension < 1280) {
        posterUrl = `https://i.ytimg.com/vi_webp/${'$'}{videoId}/sddefault.webp`;
      } else {
        posterUrl = `https://i.ytimg.com/vi_webp/${'$'}{videoId}/maxresdefault.webp`;
      }

      document.addEventListener('DOMContentLoaded', function() {
        const videoElement = document.querySelector('video');  
        if (videoElement) {
//          videoElement.id = 'myVideo';
//          videoElement.controls = true;
          videoElement.poster = posterUrl;
        }
      });

      // Create floating block
      const floatingBlock = document.createElement("div");
      floatingBlock.id = "floatingBlock";
      floatingBlock.style.position = "fixed";
      floatingBlock.style.bottom = "0";
      floatingBlock.style.width = "100%";
      floatingBlock.style.height = "0";
      floatingBlock.style.maxHeight = "50vh";
      floatingBlock.style.backgroundColor = "white";
      floatingBlock.style.borderTop = "1px solid gray";
      floatingBlock.style.overflowY = "auto";
      floatingBlock.style.display = "none";

      // Create floating content
      const floatingContent = document.createElement("div");
      floatingContent.id = "floatingContent";
      floatingContent.style.padding = "10px";

      // Create block content
      const blockContent = document.createElement("div");
      blockContent.id = "blockContent";
      blockContent.style.marginBottom = "10px";

      // Append block content to floating content
      floatingContent.appendChild(blockContent);

      // Create minimize button
      const minimizeButton = document.createElement("button");
      minimizeButton.id = "minimizeButton";
      minimizeButton.textContent = "Minimize";
      minimizeButton.style.marginRight = "5px";
      floatingContent.appendChild(minimizeButton);

      // Create maximize button
      const maximizeButton = document.createElement("button");
      maximizeButton.id = "maximizeButton";
      maximizeButton.textContent = "Maximize";
      maximizeButton.style.marginRight = "5px";
      floatingContent.appendChild(maximizeButton);

      // Append floating content to floating block
      floatingBlock.appendChild(floatingContent);

      // Add floating block to the body
      document.body.appendChild(floatingBlock);

      // Show the floating block
      floatingBlock.style.display = "block";

      // Update the block content
      blockContent.innerHTML = `
        <p>Host: ${'$'}{host}</p>
        <p>URL: ${'$'}{currentURL}</p>
        <p>Conditions satisfied: ${'$'}{hasValidHost && hasValidQuery}</p>
        <li>ValidHost:${'$'}{hasValidHost}</li>
        <li>ValidQuery:${'$'}{hasValidQuery}</li>
        <p>Video ID: ${'$'}{videoId}</p>
        <p>Device Width: ${'$'}{deviceWidth}</p>
        <p>Device Height: ${'$'}{deviceHeight}</p>
        <p>Base Dimension: ${'$'}{baseDimension}</p>
        <p>Poster URL: ${'$'}{posterUrl}</p>
        <p>Video Element Present: ${'$'}{!!document.querySelector("video")}</p>
        <p>Attributes Set: ${
    document.querySelector("video")?.attributes?.poster?.value ? "Yes" : "No"
}
        </p>
        <p>Video Element:</p>
        <pre>${'$'}{document.querySelector("video")?.outerHTML}</pre>
      `;

      // Minimize and maximize functionality
      minimizeButton.addEventListener("click", () => {
        floatingBlock.style.height = "0";
      });

      maximizeButton.addEventListener("click", () => {
        floatingBlock.style.height = "50vh";
      });
//    }
""".trimIndent()
it.evaluateJavascript(videoPosterCode, null)
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

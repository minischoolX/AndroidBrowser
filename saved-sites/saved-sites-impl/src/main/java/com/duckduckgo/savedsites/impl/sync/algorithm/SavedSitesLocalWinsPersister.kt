/*
 * Copyright (c) 2023 DuckDuckGo
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

package com.duckduckgo.savedsites.impl.sync.algorithm

import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.savedsites.api.SavedSitesRepository
import com.duckduckgo.savedsites.api.models.BookmarkFolder
import com.duckduckgo.savedsites.api.models.SavedSite.Bookmark
import com.duckduckgo.savedsites.api.models.SavedSite.Favorite
import com.squareup.anvil.annotations.ContributesBinding
import javax.inject.Inject
import javax.inject.Named
import timber.log.Timber

@ContributesBinding(AppScope::class)
@Named("localWinsStrategy")
class SavedSitesLocalWinsPersister @Inject constructor(
    private val savedSitesRepository: SavedSitesRepository,
) : SavedSitesSyncPersisterStrategy {
    override fun processBookmarkFolder(
        folder: BookmarkFolder,
    ) {
        val localFolder = savedSitesRepository.getFolder(folder.id)
        if (localFolder != null) {
            if (folder.isDeleted()) {
                Timber.d("Sync-Feature: folder ${localFolder.id} exists locally but was deleted remotely, deleting locally too")
                savedSitesRepository.delete(localFolder)
            } else {
                Timber.d("Sync-Feature: folder ${localFolder.id} exists locally, nothing to do")
            }
        } else {
            if (folder.isDeleted()) {
                Timber.d("Sync-Feature: folder ${folder.id} not present locally but was deleted, nothing to do")
            } else {
                Timber.d("Sync-Feature: folder ${folder.id} not present locally, inserting")
                savedSitesRepository.insert(folder)
            }
        }
    }

    override fun processBookmark(
        bookmark: Bookmark,
        folderId: String,
    ) {
        val storedBookmark = savedSitesRepository.getBookmarkById(bookmark.id)
        if (storedBookmark != null) {
            if (bookmark.isDeleted()) {
                Timber.d("Sync-Feature: remote bookmark ${bookmark.id} exists locally but was deleted remotely, deleting locally too")
                savedSitesRepository.delete(bookmark)
            } else {
                Timber.d("Sync-Feature: remote bookmark ${bookmark.id} exists locally, nothing to do")
            }
        } else {
            if (bookmark.isDeleted()) {
                Timber.d("Sync-Feature: bookmark ${bookmark.id} not present locally but was deleted, nothing to do")
            } else {
                Timber.d("Sync-Feature: child ${bookmark.id} not present locally, inserting")
                savedSitesRepository.insert(bookmark)
            }
        }
    }

    override fun processFavourite(
        favourite: Favorite,
    ) {
        val storedFavorite = savedSitesRepository.getFavoriteById(favourite.id)
        if (storedFavorite != null) {
            if (favourite.isDeleted()) {
                Timber.d("Sync-Feature: remote favourite ${favourite.id} exists locally but was deleted remotely, deleting locally too")
                savedSitesRepository.delete(favourite)
            } else {
                Timber.d("Sync-Feature: remote favourite ${favourite.id} exists locally, nothing to do")
            }
        } else {
            if (favourite.isDeleted()) {
                Timber.d("Sync-Feature: favourite ${favourite.id} not present locally but was deleted, nothing to do")
            } else {
                Timber.d("Sync-Feature: adding ${favourite.id} to Favourites")
                savedSitesRepository.insert(favourite)
            }
        }
    }
}

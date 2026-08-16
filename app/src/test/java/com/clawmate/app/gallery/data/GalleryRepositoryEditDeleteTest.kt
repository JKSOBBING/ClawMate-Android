package com.clawmate.app.gallery.data

import com.clawmate.app.gallery.model.GalleryPhotoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic edit/delete persistence tests against [DefaultGalleryRepository]
 * with an in-memory [FakeGalleryDao]. Verifies explicit edits are persisted to
 * Room (via the DAO contract) and that delete removes only the single item.
 */
class GalleryRepositoryEditDeleteTest {

    private fun item(id: String, uri: String = "content://media/picker/$id") = GalleryPhotoItem(
        id = id,
        uri = uri,
        date = "2026-06-01",
        title = "标题$id",
        category = "餐饮",
        summary = "摘要$id",
        createdAt = 0L,
    )

    private fun repoWith(vararg items: GalleryPhotoItem): DefaultGalleryRepository {
        val dao = FakeGalleryDao(items.map { GalleryMappers.toEntity(it) }.toMutableList())
        return DefaultGalleryRepository(dao)
    }

    @Test
    fun updateTitlePersists() = runBlocking {
        val repo = repoWith(item("a"))
        val ok = repo.updateTitle("a", " 新标题 ")
        assertTrue(ok)
        assertEquals("新标题", repo.getItem("a")!!.title)
        // Other fields untouched.
        assertEquals("摘要a", repo.getItem("a")!!.summary)
    }

    @Test
    fun updateSummaryPersists() = runBlocking {
        val repo = repoWith(item("a"))
        assertTrue(repo.updateSummary("a", "新摘要"))
        assertEquals("新摘要", repo.getItem("a")!!.summary)
    }

    @Test
    fun updateTagsPersistsAsList() = runBlocking {
        val repo = repoWith(item("a"))
        assertTrue(repo.updateTags("a", listOf("早餐", "微信")))
        val stored = repo.getItem("a")!!
        assertEquals(listOf("早餐", "微信"), stored.tags)
    }

    @Test
    fun updateLocationPersists() = runBlocking {
        val repo = repoWith(item("a").copy(latitude = 31.2, longitude = 121.4))
        assertTrue(repo.updateLocation("a", "上海市世纪大道", "geocoder"))
        val stored = repo.getItem("a")!!
        assertEquals("上海市世纪大道", stored.address)
        assertEquals("geocoder", stored.locationProvider)
    }

    @Test
    fun deleteByIdRemovesOnlyThatItemAndRefreshesGroups() = runBlocking {
        val repo = repoWith(item("a"), item("b"))
        assertTrue(repo.deleteItem("a"))
        assertNull(repo.getItem("a"))
        assertEquals("标题b", repo.getItem("b")!!.title)
        // Groups were recomputed over the remaining item.
        val groups = repo.refreshGroups(DefaultGalleryRepository.GALLERY_TAB_ID)
        assertTrue(groups.all { g -> !g.eventIds.contains("a") })
    }

    @Test
    fun editOnMissingIdIsNoOp() = runBlocking {
        val repo = repoWith()
        assertFalse(repo.updateTitle("missing", "x"))
        assertFalse(repo.updateSummary("missing", "x"))
        assertFalse(repo.updateTags("missing", listOf("x")))
        assertFalse(repo.deleteItem("missing"))
    }

    @Test
    fun itemGroupMembershipFindsOwningGroups() = runBlocking {
        val repo = repoWith(item("a", "content://media/picker/1"), item("b", "content://media/picker/2"))
        repo.refreshGroups(DefaultGalleryRepository.GALLERY_TAB_ID)
        // 'a' and 'b' group together (same category/keyword), so both are members.
        val memberA = repo.itemGroupMembership("a")
        val memberB = repo.itemGroupMembership("b")
        assertTrue(memberA.isNotEmpty())
        // Same group ids.
        assertEquals(memberA.map { it.groupId }, memberB.map { it.groupId })
    }

    @Test
    fun groupsRecomputeWhenItemSetChanges() = runBlocking {
        val repo = repoWith(item("x", "content://media/picker/9"))
        repo.refreshGroups(DefaultGalleryRepository.GALLERY_TAB_ID)
        assertTrue(repo.itemGroupMembership("x").isNotEmpty())

        // Adding a second, differently-grouped item changes the recomputed set:
        // x now shares a category/kw group only with items like it; with y added,
        // both appear in the recomputed groups.
        repo.upsert(item("y", "content://media/picker/8"))
        repo.refreshGroups(DefaultGalleryRepository.GALLERY_TAB_ID)
        assertTrue(repo.itemGroupMembership("y").isNotEmpty())
        assertTrue(repo.itemGroupMembership("x").isNotEmpty())
    }

    @Test
    fun groupMembershipFollowsDelete() = runBlocking {
        val repo = repoWith(item("x", "content://media/picker/9"), item("y", "content://media/picker/8"))
        repo.refreshGroups(DefaultGalleryRepository.GALLERY_TAB_ID)
        // Both in the same group.
        val before = repo.itemGroupMembership("x")
        assertTrue(before.isNotEmpty())

        // Delete y; x's group now drops to a single member (recomputed).
        repo.deleteItem("y")
        assertTrue(repo.itemGroupMembership("y").isEmpty())
        val after = repo.itemGroupMembership("x")
        assertTrue(after.all { it.eventIds == listOf("x") })
    }
}

/** Minimal in-memory [GalleryDao] capturing the persistence contract edits use. */
private class FakeGalleryDao(initial: MutableList<GalleryItemEntity>) : GalleryDao {
    private val items = MutableStateFlow<List<GalleryItemEntity>>(initial)

    override fun observeItems(): Flow<List<GalleryItemEntity>> = items

    override suspend fun getAllItems(): List<GalleryItemEntity> = items.value

    override suspend fun getItem(id: String): GalleryItemEntity? = items.value.firstOrNull { it.id == id }

    override suspend fun getByUri(uri: String): GalleryItemEntity? = items.value.firstOrNull { it.uri == uri }

    override suspend fun upsert(entity: GalleryItemEntity) {
        val list = items.value.toMutableList()
        val idx = list.indexOfFirst { it.id == entity.id }
        if (idx >= 0) list[idx] = entity else list.add(entity)
        items.value = list
    }

    override suspend fun insertIfAbsent(entity: GalleryItemEntity): Long {
        if (items.value.none { it.id == entity.id }) {
            upsert(entity)
            return 1L
        }
        return -1L
    }

    override suspend fun count(): Int = items.value.size

    override suspend fun deleteById(id: String): Int {
        val before = items.value.size
        items.value = items.value.filterNot { it.id == id }
        return before - items.value.size
    }

    override suspend fun updateTitle(id: String, title: String): Int {
        val list = items.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return 0
        list[idx] = list[idx].copy(title = title)
        items.value = list
        return 1
    }

    override suspend fun updateSummary(id: String, summary: String): Int {
        val list = items.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return 0
        list[idx] = list[idx].copy(summary = summary)
        items.value = list
        return 1
    }

    override suspend fun updateTags(id: String, tagsJson: String): Int {
        val list = items.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return 0
        list[idx] = list[idx].copy(tags = tagsJson)
        items.value = list
        return 1
    }

    override suspend fun updateLocation(id: String, address: String, provider: String): Int {
        val list = items.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return 0
        list[idx] = list[idx].copy(address = address, locationProvider = provider)
        items.value = list
        return 1
    }

    override suspend fun updateAnalysisState(id: String, state: String): Int {
        val list = items.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return 0
        list[idx] = list[idx].copy(analysisState = state)
        items.value = list
        return 1
    }

    override suspend fun clear() {
        items.value = emptyList()
    }

    private val groups = MutableStateFlow<MutableList<CollectionGroupEntity>>(mutableListOf())

    override suspend fun upsertGroup(entity: CollectionGroupEntity) {
        val list = groups.value.toMutableList()
        list.removeAll { it.groupId == entity.groupId }
        list.add(entity)
        groups.value = list
    }

    override suspend fun deleteGroupsForTab(tabId: String) {
        groups.value = groups.value.filterNot { it.tabId == tabId }.toMutableList()
    }

    override fun observeGroupsForTab(tabId: String): Flow<List<CollectionGroupEntity>> =
        MutableStateFlow(groups.value.filter { it.tabId == tabId })

    override suspend fun ftsMatchCount(query: String): Int = 0
}

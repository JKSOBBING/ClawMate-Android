package com.clawmate.app.localmodel.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelScopeSpecTest {
    private fun repoJson(): String = """
        {"Code":200,"Data":{"Files":[
          {"Type":"tree","Path":"vision","Size":0},
          {"Type":"blob","Path":"config.json","Size":1324,"Sha256":"db478c4841fa0000000000000000000000000000000000000000000000000000"},
          {"Type":"blob","Path":"llm.mnn","Size":508944,"Sha256":""},
          {"type":"blob","path":"weights/model.mnn.weight","size":2048},
          {"Type":"blob","Path":"../escape.txt","Size":10},
          {"Type":"blob","Path":"abs","Size":-1}
        ]}}
    """

    @Test fun parseRepoAcceptsOwnerSlashRepoOnly() {
        assertEquals("owner" to "repo", ModelScopeSpec.parseRepo("owner/repo"))
        assertEquals("owner" to "repo", ModelScopeSpec.parseRepo(" ModelScope/owner/repo/ "))
        assertNull(ModelScopeSpec.parseRepo("owner"))
        assertNull(ModelScopeSpec.parseRepo("owner/repo/extra"))
        assertNull(ModelScopeSpec.parseRepo("../owner/repo"))
        assertNull(ModelScopeSpec.parseRepo(""))
    }

    @Test fun directoryNamesRoundTrip() {
        assertEquals("owner__repo", ModelScopeSpec.encodeRepoDir("owner", "repo"))
        assertEquals("owner/repo", ModelScopeSpec.decodeRepoDir("owner__repo"))
        assertEquals("plain", ModelScopeSpec.decodeRepoDir("plain"))
    }

    @Test fun urlsMatchModelScopeEndpoints() {
        assertEquals("https://modelscope.cn/api/v1/models/o/r/repo/files?Recursive=1", ModelScopeSpec.fileListUrl("o", "r"))
        assertEquals("https://modelscope.cn/models/o/r/resolve/master/weights/model.mnn",
            ModelScopeSpec.fileUrl("o", "r", "weights/model.mnn"))
        assertTrue(ModelScopeSpec.fileUrl("o", "r", "a b/c.json").endsWith("/a%20b/c.json"))
    }

    @Test fun fileListParsingSkipsDirectoriesAndUnsafePaths() {
        val files = ModelScopeSpec.parseFileList(repoJson())
        assertEquals(listOf("config.json", "llm.mnn", "weights/model.mnn.weight"), files.map { it.path })
        assertEquals(1324L, files[0].size)
        assertEquals("db478c4841fa0000000000000000000000000000000000000000000000000000", files[0].sha256)
        assertNull(files[1].sha256)
    }

    @Test fun fileListParsingRejectsEmptyLists() {
        assertTrue(runCatching { ModelScopeSpec.parseFileList("{\"Data\":{\"Files\":[]}}") }.isFailure)
        assertTrue(runCatching { ModelScopeSpec.parseFileList("not json") }.isFailure)
    }

    @Test fun treeArrayAtRootIsAccepted() {
        val raw = "[{\"Type\":\"blob\",\"Path\":\"config.json\",\"Size\":1}]"
        assertEquals(1, ModelScopeSpec.parseFileList(raw).size)
    }

    @Test fun safeRelativePathRules() {
        assertEquals("a/b.txt", ModelScopeSpec.safeRelativePath("a/b.txt"))
        assertNull(ModelScopeSpec.safeRelativePath("/abs"))
        assertNull(ModelScopeSpec.safeRelativePath("../x"))
        assertNull(ModelScopeSpec.safeRelativePath("a/../x"))
        assertNull(ModelScopeSpec.safeRelativePath("a//b"))
        assertNull(ModelScopeSpec.safeRelativePath(""))
        assertNull(ModelScopeSpec.safeRelativePath("a/\u0001b"))
    }

    @Test fun resumeDecisionUsesExistingSize() {
        val file = ModelScopeFile("llm.mnn", 1000, "abcd")
        assertEquals(ModelScopeSpec.ResumeDecision.SkipSizeOnly, ModelScopeSpec.resumeDecision(1000, file))
        assertEquals(ModelScopeSpec.ResumeDecision.Restart, ModelScopeSpec.resumeDecision(1200, file))
        assertEquals(ModelScopeSpec.ResumeDecision.Restart, ModelScopeSpec.resumeDecision(0, file))
        assertEquals(ModelScopeSpec.ResumeDecision.Append(400), ModelScopeSpec.resumeDecision(400, file))
    }

    @Test fun percentAndFormattingAreBounded() {
        assertEquals(0, ModelScopeSpec.percent(0, 0))
        assertEquals(50, ModelScopeSpec.percent(5, 10))
        assertEquals(100, ModelScopeSpec.percent(20, 10))
        assertEquals("512 B", ModelScopeSpec.formatBytes(512))
        assertEquals("1.0 KB", ModelScopeSpec.formatBytes(1024))
        assertEquals("2.0 GB", ModelScopeSpec.formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test fun speedMeterReportsZeroUntilItHasTwoSamples() {
        val meter = ModelScopeSpec.SpeedMeter()
        meter.record(1000, 0)
        assertEquals(0, meter.bytesPerSecond(1100))
        meter.record(2000, 1000)
        assertEquals(1000, meter.bytesPerSecond(2000))
        assertEquals(4, meter.remainingSeconds(2000, 4000))
    }

    @Test fun completedMarkerOnlyWrittenForFullSets() {
        assertTrue(ModelScopeSpec.isCompleteDir(setOf("config.json", ModelScopeSpec.COMPLETE_FILE)))
        assertTrue(!ModelScopeSpec.isCompleteDir(setOf("config.json")))
    }

    @Test fun manifestKeepsFileDetails() {
        val json = MiniJson.parse(ModelScopeSpec.manifestJson("o/r", listOf(ModelScopeFile("a.mnn", 10, "ab")), 10, "2026-11-04T00:00:00Z")) as Map<*, *>
        assertEquals("o/r", json["repo"])
        assertEquals(10L, json["totalBytes"])
        val files = json["files"] as List<*>
        assertEquals(1, files.size)
        assertEquals("ab", (files[0] as Map<*, *>)["sha256"])
    }

    @Test fun configReferencesFindWeightFiles() {
        val config = "{\"llm_model\":\"llm.mnn\",\"llm_weight\":\"llm.mnn.weight\",\"tokenizer_file\":\"tokenizer.mtok\"," +
            "\"backend_type\":\"cpu\",\"mllm\":{\"backend_type\":\"cpu\",\"vision_file\":\"vision/pre.mnn\"}}"
        val references = ModelScopeSpec.configReferences(config)
        assertTrue(references.containsAll(listOf("llm.mnn", "llm.mnn.weight", "tokenizer.mtok", "vision/pre.mnn")))
        assertTrue(!references.contains("cpu"))
        assertNotNull(references)
    }

    @Test fun sha256NormalizationFindsDigest() {
        val digest = "a".repeat(64)
        assertEquals(digest, ModelScopeSpec.normalizeSha256("sha256:$digest"))
        assertEquals(digest, ModelScopeSpec.normalizeSha256(digest.uppercase()))
        assertNull(ModelScopeSpec.normalizeSha256("abc"))
    }

    @Test fun miniJsonRoundTripsEscapes() {
        val text = MiniJson.write(mapOf("a" to "quote\"back\\slash\n", "b" to listOf(1L, true, null)), pretty = true)
        val parsed = MiniJson.parse(text) as Map<*, *>
        assertEquals("quote\"back\\slash\n", parsed["a"])
        assertEquals(listOf(1L, true, null), parsed["b"])
    }
}

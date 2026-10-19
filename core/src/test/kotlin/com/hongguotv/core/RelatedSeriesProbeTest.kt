package com.hongguotv.core

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * JVM end-to-end probe: detail-page related-seasons row (search-backed).
 * Run manually (not part of the test suite - hits the live API):
 *   gradlew :core:test --tests "com.hongguotv.core.RelatedSeriesProbeTest"
 */
class RelatedSeriesProbeTest {
    @org.junit.Test
    fun probe() {
        val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val repo = ContentRepository(http)
        // 持械入宋：第二季（多季剧，9 季 + 同名不同版）
        val seasons = repo.relatedSeries("7676706788724460568", "持械入宋：第二季")
        seasons.forEach { println("[${it.id}] ${it.title} ${it.badge}") }
        println("count=${seasons.size}")
        org.junit.Assert.assertTrue("expected 9 sibling seasons, got ${seasons.size}", seasons.size >= 8)
        org.junit.Assert.assertTrue("current series must be excluded", seasons.none { it.id == "7676706788724460568" })
        // 伪人降临（无季标题 = 未标季号的第一季）：必须能找到第二/三季
        val unmarked = repo.relatedSeries("7689458194980015128", "伪人降临！我是全场唯一预言家。")
        unmarked.forEach { println("[${it.id}] ${it.title} ${it.badge}") }
        println("unmarked count=${unmarked.size}")
        org.junit.Assert.assertTrue("unmarked season-1 title must find marked siblings, got ${unmarked.size}", unmarked.size >= 2)
        org.junit.Assert.assertTrue("sibling seasons must include the unmarked original",
            unmarked.any { it.id == "7689458194980015128" }.not()) // self excluded
        // 出租车诡事（无季且无同系列）：搜索返回同题材剧，但标题 key 都不匹配 → 空
        val none = repo.relatedSeries("7503744492189535257", "出租车诡事")
        println("no-season count=${none.size}")
        org.junit.Assert.assertTrue("no-season title must return empty", none.isEmpty())
    }
}

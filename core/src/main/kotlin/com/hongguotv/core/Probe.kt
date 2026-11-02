package com.hongguotv.core

fun main(args: Array<String>) {
    val repository=ContentRepository()
    if(args.contains("--search")) {
        // Live verification of the phone-version search chain: suggestions (pinyin initials)
        // + enhanced search (merge + rank + season completion).
        try {
            for(pinyin in listOf("BFLC","bf","zmy")) {
                val list=repository.suggestions(pinyin)
                check(list.isNotEmpty())
                println("suggest[$pinyin]=${list.size}; first=${list.first()}")
            }
            for(keyword in listOf("暴富","BFLC","修仙")) {
                val result=repository.searchEnhanced(keyword)
                println("enhanced[$keyword]=${result.items.size}; hasMore=${result.hasMore}; warning=\"${result.warning}\"; top3=${result.items.take(3).joinToString(" / ") { it.title }}")
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--boards")) {
        try {
            for(board in RankBoard.entries) {
                val result=repository.ranking(board,1)
                val metadata=checkNotNull(result.ranking)
                check(result.items.isNotEmpty())
                val ranks=result.items.mapNotNull { metadata.positions[it.id]?.rank }
                check(ranks.isNotEmpty() && ranks.zipWithNext().all { (a,b) -> a<=b })
                println("board=${board.label}; path=${board.path}; items=${result.items.size}; totalPages=${metadata.totalPages}; first=${result.items.first().title}; rank=${ranks.first()}; heat=${metadata.positions[result.items.first().id]!!.heat}; updated=${metadata.updatedText}")
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--ranking")) {
        try {
            val first=repository.comicRanking()
            val metadata=checkNotNull(first.ranking)
            check(first.items.isNotEmpty())
            val seen=mutableSetOf<String>()
            for(pageNumber in listOf(1,2,metadata.totalPages).distinct().filter { it<=metadata.totalPages }) {
                val result=if(pageNumber==1) first else repository.comicRanking(pageNumber)
                val ranking=checkNotNull(result.ranking)
                check(result.items.isNotEmpty() && result.items.none { it.id in seen })
                seen.addAll(result.items.map { it.id })
                val ranks=result.items.map { checkNotNull(ranking.positions[it.id]).rank }
                check(ranks.all { it!=null && it>0 } && ranks.filterNotNull().zipWithNext().all { (a,b) -> a<b })
                check(result.items.all { ranking.positions[it.id]!!.heat.isNotBlank() })
                check(result.hasMore==(pageNumber<ranking.totalPages))
                println("rankingPage=$pageNumber/${ranking.totalPages}; items=${result.items.size}; ranks=${ranks.first()}..${ranks.last()}; firstHeat=${ranking.positions[result.items.first().id]!!.heat}; updated=${ranking.updatedText}")
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--comicfeed")) {
        // Live verification: comic "全部" from the website category catalog (800
        // entries, 34 pages) + the theme strips. The 穿越 theme is retired from
        // every tab (2026-11-02: no stable source — comic feed intermittently
        // risk-controlled, AI has no source at all), so the strips must carry
        // exactly the site's own selector lists; the stale-cache defensive route
        // (marker-less 穿越 -> search chain) must still work underneath.
        try {
            val home=repository.home(type=ContentType.COMIC)
            check(home.items.isNotEmpty())
            println("comicHome=${home.items.size}; hasMore=${home.hasMore}; top3=${home.items.take(3).joinToString(" / ") { it.title }}")
            val seen=home.items.map { it.id }.toMutableSet()
            for(p in 2..8) {
                val next=repository.home(p,ContentType.COMIC)
                val fresh=next.items.filterNot { it.id in seen }
                seen+=fresh.map { it.id }
                if(p in listOf(2,5,8)) println("comicPage$p: items=${next.items.size}; fresh=${fresh.size}; uniqueTotal=${seen.size}; hasMore=${next.hasMore}")
                if(!next.hasMore) break
            }
            for(type in listOf(ContentType.SHORT,ContentType.COMIC,ContentType.AI)) {
                val themes=repository.themes(type)
                check(themes.none { it.slug=="chuanyue" }) { "$type strip still carries 穿越" }
                println("${type.label}Themes=${themes.size}; chuanyueAbsent=true; first=${themes.first().label}")
            }
            // Stale-cache defensive route still works: a marker-less 穿越 theme
            // routes to the search chain (never the 404 category page).
            val stale=Theme("chuanyue","穿越")
            val stalePage=repository.themeCatalog(ContentType.SHORT,stale)
            println("staleChuanyueRoutesToSearch: items=${stalePage.items.size}; hasMore=${stalePage.hasMore}")
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--ai")) {
        // Live verification of the AI tab: home grid + theme strip. The 穿越 theme
        // is retired from every tab (2026-11-02), so this probe verifies its
        // absence on the AI strip.
        try {
            val home=repository.home(type=ContentType.AI)
            check(home.items.isNotEmpty())
            println("aiHome=${home.items.size}; hasMore=${home.hasMore}; top5=${home.items.take(5).joinToString(" / ") { it.title }}")
            val themes=repository.themes(ContentType.AI)
            check(themes.none { it.slug=="chuanyue" })
            println("aiThemes=${themes.size}; chuanyueAbsent=true; last=${themes.last().label}(${themes.last().slug})")
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    val type=if(args.contains("--comic")) ContentType.COMIC else ContentType.SHORT
    val keyword=if(type==ContentType.COMIC) "修仙" else "总裁"
    val search=repository.search(keyword,type=type); println("search=${search.items.size}; hasMore=${search.hasMore}")
    if(type==ContentType.COMIC && search.hasMore) {
        val next=repository.search(keyword,2,type)
        println("searchPage2=${next.items.size}; overlap=${search.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
    }
    val home=repository.home(type=type); println("type=${type.label}; home=${home.items.size}; hasMore=${home.hasMore}")
    if(type==ContentType.COMIC) {
        val next=repository.home(2,type)
        println("homePage2=${next.items.size}; overlap=${home.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
    }
    val detail=repository.detail(args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: home.items.first().id,type)
    println("detail=${detail.series.title}; episodes=${detail.episodes.size}")
    val stream=repository.stream(detail.episodes.first(),type=detail.contentType)
    RemoteVideo(repository.http,stream).use { remote ->
        remote.prepare(); val first=remote.read(0,1024*1024)
        val tail=remote.read((remote.total-65536).coerceAtLeast(0),65536)
        println("quality=${stream.quality}; total=${remote.total}; first=${first.size}; tail=${tail.size}; header=${String(first,4,4)}")
        if(args.contains("--save")) java.io.File("/tmp/hongguo-kotlin-first.mp4").writeBytes(first)
    }
    repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
}

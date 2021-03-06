package ai.platon.pulsar.crawl.fetch.indexer

import ai.platon.pulsar.common.PulsarParams.DOC_FIELD_TEXT_CONTENT
import ai.platon.pulsar.common.StringUtil
import ai.platon.pulsar.common.Urls
import ai.platon.pulsar.common.config.CapabilityTypes.INDEX_JIT
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.Params
import ai.platon.pulsar.common.config.ReloadableParameterized
import ai.platon.pulsar.crawl.fetch.FetchMonitor
import ai.platon.pulsar.crawl.fetch.FetchTask
import ai.platon.pulsar.crawl.index.IndexDocument
import ai.platon.pulsar.crawl.index.IndexWriters
import ai.platon.pulsar.crawl.index.IndexingFilters
import ai.platon.pulsar.crawl.scoring.ScoringFilters
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.metadata.ParseStatusCodes
import com.google.common.collect.Lists
import com.google.common.collect.Queues
import java.time.Instant
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Created by vincent on 16-8-23.
 * Copyright @ 2013-2016 Platon AI. All rights reserved
 */
class JITIndexer(
        private val scoringFilters: ScoringFilters,
        private val indexingFilters: IndexingFilters,
        private val indexWriters: IndexWriters,
        private val conf: ImmutableConfig
) : ReloadableParameterized, AutoCloseable {

    private val id: Int = instanceSequence.incrementAndGet()
    private var indexJIT: Boolean = conf.getBoolean(INDEX_JIT, false)
    private var indexDocumentBuilder: IndexDocument.Builder? = null

    private var batchSize: Int = conf.getInt("index.index.batch.size", 2000)
    var indexThreadCount: Int = conf.getInt("index.index.thread.count", 1)
    private var minTextLenght: Int = conf.getInt("index.minimal.text.length", 300)
    // All object inside a process shares the same counters
    private val indexedPages = AtomicInteger(0)
    private val ignoredPages = AtomicInteger(0)

    private val activeIndexThreads = ConcurrentSkipListSet<IndexThread>()
    private val indexTasks = Queues.newLinkedBlockingQueue<FetchTask>(batchSize)

    private val indexThreads = Lists.newLinkedList<IndexThread>()

    val indexedPageCount: Int get() = indexedPages.get()
    val ignoredPageCount: Int get() = ignoredPages.get()

    init {
        if (indexJIT) {
            this.indexDocumentBuilder = IndexDocument.Builder(conf).with(indexingFilters).with(scoringFilters)
            this.indexWriters.open()
        }
    }

    override fun getConf(): ImmutableConfig {
        return conf
    }

    override fun getParams(): Params {
        return Params.of(
                "batchSize", batchSize,
                "indexThreadCount", indexThreadCount,
                "minTextLenght", minTextLenght
        )
    }

    override fun reload(conf: ImmutableConfig) {
        //
    }

    internal fun registerFetchThread(indexThread: IndexThread) {
        activeIndexThreads.add(indexThread)
    }

    internal fun unregisterFetchThread(indexThread: IndexThread) {
        activeIndexThreads.remove(indexThread)
    }

    /**
     * Add fetch item to index indexTasks
     * Thread safe
     */
    fun produce(fetchTask: FetchTask) {
        if (!indexJIT) {
            return
        }

        val page = fetchTask.page
        if (page == null) {
            LOG.warn("Invalid FetchTask to index, ignore it")
            return
        }

        if (!shouldProduce(page)) {
            return
        }

        indexTasks.add(fetchTask)
    }

    /**
     * Thread safe
     */
    fun consume(): FetchTask? {
        return indexTasks.poll()
    }

    override fun close() {
        LOG.info("[Destruction] Closing JITIndexer #$id ...")

        indexThreads.forEach{ it.exitAndJoin() }

        try {
            var fetchTask = consume()
            while (fetchTask != null) {
                index(fetchTask)
                fetchTask = consume()
            }
        } catch (e: Throwable) {
            LOG.error(e.toString())
        }

        LOG.info("There are $ignoredPageCount not indexed short pages out of total $indexedPageCount pages")
    }

    /**
     * Thread safe
     */
    fun index(fetchTask: FetchTask?) {
        if (!indexJIT) {
            return
        }

        try {
            if (fetchTask == null) {
                LOG.error("Failed to index, null fetchTask")
                return
            }

            val url = fetchTask.url
            val reverseUrl = Urls.reverseUrl(url)
            val page = fetchTask.page

            val doc = indexDocumentBuilder!!.build(reverseUrl, page)
            if (shouldIndex(doc)) {
                synchronized(indexWriters) {
                    indexWriters.write(doc)
                    page!!.putIndexTimeHistory(Instant.now())
                }
                indexedPages.incrementAndGet()
            } // if
        } catch (e: Throwable) {
            LOG.error("Failed to index a page " + StringUtil.stringifyException(e))
        }

    }

    private fun shouldIndex(doc: IndexDocument?): Boolean {
        if (doc == null) {
            return false
        }

        val textContent = doc.getFieldValueAsString(DOC_FIELD_TEXT_CONTENT)
        if (textContent == null || textContent.length < minTextLenght) {
            ignoredPages.incrementAndGet()
            LOG.warn("Invalid text content to index, url : " + doc.url)
            return false
        }

        return true
    }

    private fun shouldProduce(page: WebPage): Boolean {
        if (page.isSeed) {
            return false
        }

        val status = page.parseStatus

        if (!status.isSuccess || status.majorCode.toInt() == ParseStatusCodes.SUCCESS_REDIRECT) {
            return false
        }

        if (page.contentText.length < minTextLenght) {
            ignoredPages.incrementAndGet()
            return false
        }

        return true
    }

    companion object {
        val LOG = FetchMonitor.LOG
        private val instanceSequence = AtomicInteger(0)
    }
}
